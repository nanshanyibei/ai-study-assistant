package com.nanshan.study.llm;

/**
 * 非流式调用的返回值：一次性拿到完整文本 + 用量。
 * 标题生成、query 改写、上下文摘要这类短输出任务走这里，不需要流式。
 */
public record LlmAnswer(String text, CallResult call) {
}
