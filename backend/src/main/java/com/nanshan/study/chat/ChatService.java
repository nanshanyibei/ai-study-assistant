package com.nanshan.study.chat;

import com.nanshan.study.config.LlmProperties;
import com.nanshan.study.llm.CallResult;
import com.nanshan.study.llm.CancellationToken;
import com.nanshan.study.llm.ChatTurn;
import com.nanshan.study.llm.LlmClient;
import com.nanshan.study.llm.StreamSink;
import com.nanshan.study.llm.UsageMeter;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 对话编排：会话上下文 + 流式转发 + 取消 + 双模型分工。
 * <p>
 * 主链路（用户等待的部分）：query 改写（可选）→ 取上下文 → 主流模型流式回答。
 * 旁路（用户不看也不等）：首轮标题生成、超限上下文摘要压缩，均走快模型并异步执行。
 * <p>
 * 阶段二演进点（先留好扩展位置）：
 * 1. 会话存取从内存搬到 Redis（多实例共享）
 * 2. 前置 RAG 检索，把命中片段拼进 system / user 上下文
 * 3. 后置落库（会话、消息、token 消耗）
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final LlmClient llm;
    private final LlmProperties props;
    private final FastTaskService fastTask;
    private final UsageMeter meter;

    private final Map<String, Deque<ChatTurn>> sessions = new ConcurrentHashMap<>();
    private final Map<String, CancellationToken> inflight = new ConcurrentHashMap<>();
    /** 被挤出上下文窗口的旧对话，等下一轮异步压缩成摘要 */
    private final Map<String, List<ChatTurn>> evicted = new ConcurrentHashMap<>();
    /** 会话摘要：替代「直接丢掉旧对话」的做法，保住用户早期说过的约束 */
    private final Map<String, String> summaries = new ConcurrentHashMap<>();
    private final Map<String, String> titles = new ConcurrentHashMap<>();

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ChatService(LlmClient llm, LlmProperties props, FastTaskService fastTask, UsageMeter meter) {
        this.llm = llm;
        this.props = props;
        this.fastTask = fastTask;
        this.meter = meter;
    }

    public String stream(String sessionId, String userText, SseEmitter emitter) {
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        CancellationToken token = new CancellationToken();
        inflight.put(requestId, token);

        appendTurn(sessionId, ChatTurn.user(userText));

        // 客户端断开 / 超时 / 异常时立刻取消，避免继续读流烧 token
        emitter.onCompletion(() -> cancelIfInflight(requestId, "client-completed"));
        emitter.onTimeout(() -> cancelIfInflight(requestId, "sse-timeout"));
        emitter.onError(e -> cancelIfInflight(requestId, "client-error"));

        executor.submit(() -> {
            StringBuilder answer = new StringBuilder();
            StringBuilder thinking = new StringBuilder();
            CallResult call = null;
            boolean appended = false;
            try {
                send(emitter, "meta", Map.of(
                        "requestId", requestId,
                        "sessionId", sessionId,
                        "model", llm.modelName(),
                        "fastModel", fastTask.fastModelName()
                ), token);

                List<ChatTurn> history = history(sessionId);
                boolean firstRound = history.size() == 1;

                // ① 多轮追问改写：喂给模型的是「独立问句」，存进历史的仍是用户原话
                String rewritten = rewriteIfNeeded(sessionId, history, userText, emitter, token);

                List<ChatTurn> context = buildContext(sessionId, history, rewritten);
                call = llm.stream(context, new StreamSink() {
                    @Override
                    public void content(String text) {
                        answer.append(text);
                        send(emitter, "delta", Map.of("text", text), token);
                    }

                    @Override
                    public void reasoning(String text) {
                        // 思考过程只推给前端展示，不入上下文：推理模型的 reasoning
                        // 不能作为 history 回传，否则会被当成普通 assistant 内容污染下一轮
                        thinking.append(text);
                        send(emitter, "thinking", Map.of("text", text), token);
                    }
                }, token);
                meter.record("chat", call, false);

                if (token.isCancelled()) {
                    send(emitter, "cancelled", Map.of("reason", String.valueOf(token.reason()),
                            "partialLength", answer.length()), token);
                } else {
                    // 先把回答写回上下文，再做标题——标题依赖完整答案
                    if (!answer.isEmpty()) {
                        appendTurn(sessionId, ChatTurn.assistant(answer.toString()));
                        appended = true;
                    }
                    String title = firstRound ? generateTitle(sessionId, userText, answer.toString()) : null;
                    send(emitter, "done", buildDonePayload(answer, thinking, call, title), token);
                }
            } catch (Exception e) {
                log.error("流式对话失败 requestId={}", requestId, e);
                send(emitter, "error", Map.of("message", String.valueOf(e.getMessage())), token);
            } finally {
                // 被中断的残缺回答也入库：用户看到的内容与模型认知保持一致
                if (!appended && !answer.isEmpty()) {
                    appendTurn(sessionId, ChatTurn.assistant(answer.toString()));
                }
                compressOverflowAsync(sessionId);
                inflight.remove(requestId);
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // 客户端已断开时 complete 可能抛异常，忽略
                }
            }
        });

        return requestId;
    }

    /** 首轮生成会话标题，失败不影响主链路 */
    private String generateTitle(String sessionId, String question, String answer) {
        String title = fastTask.generateTitle(question, answer);
        if (title != null && !title.isBlank()) {
            titles.put(sessionId, title);
            return title;
        }
        return null;
    }

    /**
     * 追问改写：只有「存在历史 + 本地规则判定需要改写」才会真的发一次快模型调用。
     * 目的是给后续 RAG 检索提供可独立理解的问句——向量检索看不到上下文，看到「它怎么算」必然召回偏。
     */
    private String rewriteIfNeeded(String sessionId, List<ChatTurn> history, String userText,
                                   SseEmitter emitter, CancellationToken token) {
        if (history.size() <= 1 || !FastTaskService.needsRewrite(userText)) {
            return null;
        }
        List<ChatTurn> prior = history.subList(0, history.size() - 1);
        String rewritten = fastTask.rewriteQuery(prior, userText);
        if (rewritten != null && !rewritten.equals(userText.trim())) {
            send(emitter, "rewrite", Map.of("question", rewritten), token);
            log.debug("session={} 问句改写：{} -> {}", sessionId, userText, rewritten);
        }
        return rewritten;
    }

    private Map<String, Object> buildDonePayload(StringBuilder answer, StringBuilder thinking,
                                                 CallResult call, String title) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("length", answer.length());
        payload.put("thinkingLength", thinking.length());
        if (call != null) {
            payload.put("promptTokens", call.promptTokens());
            payload.put("completionTokens", call.completionTokens());
            payload.put("reasoningTokens", call.reasoningTokens());
            payload.put("costCny", round(meter.costOf(call, false)));
        }
        if (title != null) {
            payload.put("title", title);
        }
        return payload;
    }

    public boolean cancel(String requestId) {
        CancellationToken token = inflight.get(requestId);
        if (token == null) {
            return false;
        }
        token.cancel("user-stop");
        return true;
    }

    public List<ChatTurn> history(String sessionId) {
        Deque<ChatTurn> deque = sessions.get(sessionId);
        if (deque == null) {
            return List.of();
        }
        synchronized (deque) {
            return List.copyOf(deque);
        }
    }

    public String summary(String sessionId) {
        return summaries.get(sessionId);
    }

    public String title(String sessionId) {
        return titles.get(sessionId);
    }

    public void clear(String sessionId) {
        sessions.remove(sessionId);
        summaries.remove(sessionId);
        titles.remove(sessionId);
        evicted.remove(sessionId);
    }

    private void appendTurn(String sessionId, ChatTurn turn) {
        Deque<ChatTurn> deque = sessions.computeIfAbsent(sessionId, k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(turn);
            int limit = props.maxHistoryOrDefault();
            // 溢出不再直接丢弃：暂存起来，由快模型压缩成摘要后接回上下文
            while (deque.size() > limit) {
                evicted.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(deque.removeFirst());
            }
        }
    }

    /**
     * @param rewritten 改写后的问句；为 null 表示用用户原话
     */
    private List<ChatTurn> buildContext(String sessionId, List<ChatTurn> history, String rewritten) {
        List<ChatTurn> context = new ArrayList<>(history.size() + 2);

        if (props.systemPrompt() != null && !props.systemPrompt().isBlank()) {
            context.add(ChatTurn.system(props.systemPrompt()));
        }
        String summary = summaries.get(sessionId);
        if (summary != null && !summary.isBlank()) {
            context.add(ChatTurn.system("以下是更早对话的摘要，用于保持上下文连续：\n" + summary));
        }

        for (int i = 0; i < history.size(); i++) {
            ChatTurn turn = history.get(i);
            boolean isLatestUser = rewritten != null && i == history.size() - 1 && "user".equals(turn.role());
            context.add(isLatestUser ? ChatTurn.user(rewritten) : turn);
        }
        return context;
    }

    /** 溢出摘要走旁路线程：用户此刻正在看答案，等他下一次提问时摘要已就绪 */
    private void compressOverflowAsync(String sessionId) {
        List<ChatTurn> pending = evicted.remove(sessionId);
        if (pending == null || pending.isEmpty()) {
            return;
        }
        executor.submit(() -> {
            String summary = fastTask.summarize(summaries.get(sessionId), pending);
            if (summary != null && !summary.isBlank()) {
                summaries.put(sessionId, summary);
                log.debug("session={} 上下文摘要已更新，{} 条旧对话被压缩", sessionId, pending.size());
            }
        });
    }

    private void cancelIfInflight(String requestId, String reason) {
        CancellationToken token = inflight.get(requestId);
        if (token != null) {
            token.cancel(reason);
        }
    }

    private double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /** SSE 发送失败通常意味着客户端已断开，此时取消上游读取 */
    private void send(SseEmitter emitter, String event, Object data, CancellationToken token) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (Exception e) {
            token.cancel("write-failed");
            throw new IllegalStateException("SSE 发送失败", e);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
