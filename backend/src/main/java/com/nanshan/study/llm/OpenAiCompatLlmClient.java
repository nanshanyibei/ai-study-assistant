package com.nanshan.study.llm;

import com.nanshan.study.config.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OpenAI 兼容协议的客户端（DeepSeek / 通义 / Kimi / 硅基流动 均可）。
 * 自行解析 SSE，不引入额外 SDK：协议简单，且能精确控制取消与错误处理。
 */
public class OpenAiCompatLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatLlmClient.class);
    private static final String DONE = "[DONE]";

    /**
     * 上游空闲超时：超过这个时长没有任何一行数据到达，视为 hang，强制断开。
     * HttpRequest.timeout 只覆盖到响应头，流式 body 阶段没有超时，
     * 必须自己兜底，否则一条挂死的连接会永久占用线程和 SSE 通道。
     */
    private static final long IDLE_TIMEOUT_SECONDS = 180;

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final LlmProperties props;
    private final String endpoint;

    private final ScheduledExecutorService idleWatchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "llm-idle-watchdog");
        t.setDaemon(true);
        return t;
    });

    public OpenAiCompatLlmClient(LlmProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        String base = props.baseUrl().endsWith("/")
                ? props.baseUrl().substring(0, props.baseUrl().length() - 1)
                : props.baseUrl();
        this.endpoint = base + "/chat/completions";
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(props.connectTimeoutSeconds() == null ? 10 : props.connectTimeoutSeconds()))
                .build();
    }

    @Override
    public String modelName() {
        return props.model();
    }

    @Override
    public CallResult stream(List<ChatTurn> messages, StreamSink sink, CancellationToken token) throws Exception {
        long startedAt = System.currentTimeMillis();
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                // 注意：这是整请求超时，流式场景要给足，否则长回答会被截断
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + props.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(
                        buildBody(messages, true, null, props.temperatureOrDefault()), StandardCharsets.UTF_8))
                .build();

        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            String detail = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new IllegalStateException("LLM 返回 " + response.statusCode() + "：" + truncate(detail));
        }

        int promptTokens = 0;
        int completionTokens = 0;
        int reasoningTokens = 0;

        InputStream body = response.body();
        // 兜底①：取消时强制 close 上游流，让阻塞中的 readLine 抛异常立刻退出。
        // 标志位检查只发生在两行之间，上游 hang 住时它救不了，必须动真格断开。
        token.onCancel(() -> {
            try {
                body.close();
            } catch (IOException ignored) {
                // 已经关了/断了都无所谓，目的只是打断阻塞
            }
        });

        // 兜底②：空闲看门狗。上游长时间一行不发（既不结束也不报错）时强制断开，
        // 避免一条挂死的连接永久占用线程 + SSE 通道（前端表现为请求永远 pending）。
        AtomicLong lastDataAt = new AtomicLong(System.currentTimeMillis());
        ScheduledFuture<?> watchdog = idleWatchdog.scheduleAtFixedRate(() -> {
            long idle = System.currentTimeMillis() - lastDataAt.get();
            if (idle > Duration.ofSeconds(IDLE_TIMEOUT_SECONDS).toMillis()) {
                log.warn("上游 {} 空闲 {}s 超过阈值 {}s，强制断开", modelName(),
                        idle / 1000, IDLE_TIMEOUT_SECONDS);
                try {
                    body.close();
                } catch (IOException ignored) {
                    // 同上
                }
            }
        }, 10, 10, TimeUnit.SECONDS);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lastDataAt.set(System.currentTimeMillis());
                if (token.isCancelled()) {
                    log.debug("流式读取被取消：{}", token.reason());
                    return new CallResult(modelName(), promptTokens, completionTokens, reasoningTokens,
                            System.currentTimeMillis() - startedAt);
                }
                if (line.isEmpty() || !line.startsWith("data:")) {
                    continue; // 忽略心跳注释行与空行
                }
                String payload = line.substring("data:".length()).trim();
                if (DONE.equals(payload)) {
                    break;
                }

                JsonNode node = mapper.readTree(payload);
                // 末帧只带 usage、choices 为空数组：用量就在这里拿，漏了就没法核算成本
                JsonNode usage = node.path("usage");
                if (!usage.isMissingNode() && !usage.isNull()) {
                    promptTokens = usage.path("prompt_tokens").asInt(promptTokens);
                    completionTokens = usage.path("completion_tokens").asInt(completionTokens);
                    reasoningTokens = usage.path("completion_tokens_details").path("reasoning_tokens")
                            .asInt(reasoningTokens);
                }

                JsonNode delta = node.path("choices").path(0).path("delta");

                // 推理模型先吐思考过程，V4 / R1 都在这个字段
                JsonNode reasoning = delta.path("reasoning_content");
                if (!reasoning.isMissingNode() && !reasoning.isNull()) {
                    String think = reasoning.asString();
                    if (!think.isEmpty()) {
                        sink.reasoning(think);
                    }
                }

                JsonNode content = delta.path("content");
                if (!content.isMissingNode() && !content.isNull()) {
                    String text = content.asString();
                    if (!text.isEmpty()) {
                        sink.content(text);
                    }
                }
            }
        } catch (IOException e) {
            // close 流打断阻塞时走这里：是取消/看门狗行为就按正常退出算，否则向上抛
            if (token.isCancelled()) {
                log.debug("读流被取消中断：{}", token.reason());
                return new CallResult(modelName(), promptTokens, completionTokens, reasoningTokens,
                        System.currentTimeMillis() - startedAt);
            }
            throw new IllegalStateException("上游流中断（可能是空闲超时被看门狗断开）", e);
        } finally {
            watchdog.cancel(false);
        }

        return new CallResult(modelName(), promptTokens, completionTokens, reasoningTokens,
                System.currentTimeMillis() - startedAt);
    }

    @Override
    public LlmAnswer complete(List<ChatTurn> messages, int maxTokens, double temperature) throws Exception {
        long startedAt = System.currentTimeMillis();
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + props.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(
                        buildBody(messages, false, maxTokens, temperature), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("LLM 返回 " + response.statusCode() + "：" + truncate(response.body()));
        }

        JsonNode root = mapper.readTree(response.body());
        JsonNode choice = root.path("choices").path(0);
        JsonNode message = choice.path("message");
        String text = message.path("content").asString("");

        // 只有思考、没有正文 —— 说明 max_tokens 在推理阶段就耗尽了。
        // 这里绝不能拿 reasoning_content 顶替答案，否则「思考过程」会被当成标题/摘要写回去。
        if (text.isBlank()) {
            throw new IllegalStateException("模型未产出正文（finish_reason="
                    + choice.path("finish_reason").asString("?")
                    + "，可能是该模型不支持关闭思考，或 max_tokens 过小）");
        }

        JsonNode usage = root.path("usage");
        CallResult call = new CallResult(
                modelName(),
                usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0),
                usage.path("completion_tokens_details").path("reasoning_tokens").asInt(0),
                System.currentTimeMillis() - startedAt);
        return new LlmAnswer(text, call);
    }

    private String buildBody(List<ChatTurn> messages, boolean stream, Integer maxTokens, double temperature) {
        List<Map<String, String>> list = new ArrayList<>(messages.size());
        for (ChatTurn turn : messages) {
            Map<String, String> node = new LinkedHashMap<>();
            node.put("role", turn.role());
            node.put("content", turn.content());
            list.add(node);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.model());
        body.put("stream", stream);
        body.put("temperature", temperature);
        if (maxTokens != null) {
            body.put("max_tokens", maxTokens);
        }
        // 仅对快模型下发：主模型要让用户看到思考过程，不能关
        if (props.disableThinking() != null) {
            body.put("enable_thinking", !props.disableThinking());
        } else if (props.thinkingBudgetOrDefault() != null) {
            // 主模型不关思考，但限预算：思考 token 也计费且线性拉长响应时间
            body.put("thinking_budget", props.thinkingBudgetOrDefault());
        }
        body.put("messages", list);
        return mapper.writeValueAsString(body);
    }

    private String truncate(String s) {
        return s.length() <= 500 ? s : s.substring(0, 500) + "...";
    }
}
