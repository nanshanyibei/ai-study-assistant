package com.nanshan.study.llm;

/**
 * 一条对话消息。role 取 system / user / assistant。
 */
public record ChatTurn(String role, String content) {

    public static ChatTurn user(String content) {
        return new ChatTurn("user", content);
    }

    public static ChatTurn assistant(String content) {
        return new ChatTurn("assistant", content);
    }

    public static ChatTurn system(String content) {
        return new ChatTurn("system", content);
    }
}
