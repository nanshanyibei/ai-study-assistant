package com.nanshan.study.chat;

/**
 * 流式对话请求体。
 *
 * @param sessionId 会话标识，前端生成（一个标签页一个）
 * @param message   用户输入
 */
public record ChatRequest(String sessionId, String message) {

    public ChatRequest {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
    }
}
