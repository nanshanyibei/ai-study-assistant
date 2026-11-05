package com.nanshan.study.chat;

import com.nanshan.study.config.LlmProperties;
import com.nanshan.study.llm.ChatTurn;
import com.nanshan.study.llm.LlmClient;
import com.nanshan.study.llm.UsageMeter;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5173"})
public class ChatController {

    private final ChatService chatService;
    private final LlmClient llmClient;
    private final LlmProperties props;
    private final UsageMeter meter;
    private final FastTaskService fastTask;

    public ChatController(ChatService chatService, LlmClient llmClient, LlmProperties props,
                          UsageMeter meter, FastTaskService fastTask) {
        this.chatService = chatService;
        this.llmClient = llmClient;
        this.props = props;
        this.meter = meter;
        this.fastTask = fastTask;
    }

    /**
     * 流式对话。SSE 事件约定：
     * meta      → {requestId, sessionId, model, fastModel}
     * rewrite   → {question}           多轮追问被改写成独立问句时出现（可选）
     * thinking  → {text}               推理模型的思考过程
     * delta     → {text}               正文增量
     * done      → {length, thinkingLength, promptTokens, completionTokens, costCny, title?}
     * cancelled → {reason, partialLength}
     * error     → {message}
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatRequest request) {
        // 5 分钟硬上限：与上游整体超时对齐。超时后 Tomcat 关闭连接、onTimeout 取消上游读取，
        // 避免「前端请求永远 pending」——真实事故见 2026-11-04 的挂死连接排查。
        SseEmitter emitter = new SseEmitter(300_000L);
        chatService.stream(request.sessionId(), request.message(), emitter);
        return emitter;
    }

    @PostMapping("/chat/cancel")
    public Map<String, Object> cancel(@RequestBody Map<String, String> body) {
        boolean cancelled = chatService.cancel(body.get("requestId"));
        return Map.of("cancelled", cancelled);
    }

    @GetMapping("/chat/{sessionId}/history")
    public List<ChatTurn> history(@PathVariable String sessionId) {
        return chatService.history(sessionId);
    }

    /** 会话元信息：标题与摘要，方便前端做侧边栏和调试上下文压缩效果 */
    @GetMapping("/chat/{sessionId}/meta")
    public Map<String, Object> meta(@PathVariable String sessionId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", chatService.title(sessionId));
        result.put("summary", chatService.summary(sessionId));
        result.put("turns", chatService.history(sessionId).size());
        return result;
    }

    @DeleteMapping("/chat/{sessionId}")
    public Map<String, Object> clear(@PathVariable String sessionId) {
        chatService.clear(sessionId);
        return Map.of("cleared", true);
    }

    /** 调用量与成本看板：哪个模型哪个用途花了多少，一眼可查 */
    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return meter.snapshot();
    }

    @DeleteMapping("/metrics")
    public Map<String, Object> resetMetrics() {
        meter.reset();
        return Map.of("reset", true);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        result.put("provider", props.provider());
        result.put("model", llmClient.modelName());
        result.put("fastModel", fastTask.fastModelName());
        result.put("mock", props.useMock());
        return result;
    }
}
