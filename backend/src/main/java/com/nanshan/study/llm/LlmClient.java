package com.nanshan.study.llm;

import java.util.List;

/**
 * 对话客户端抽象。
 * 实现方只需要保证：正文/思考增量分别回调、可被 CancellationToken 中断、异常向外抛。
 */
public interface LlmClient {

    /** 用于健康检查与日志标识，例如 mock / deepseek-ai/DeepSeek-V4.1-Flash */
    String modelName();

    /**
     * 阻塞式流式调用，方法返回时要么正常结束、要么被取消、要么抛异常。
     *
     * @param messages 完整上下文（含 system）
     * @param sink     增量回调（正文 + 思考）
     * @param token    取消令牌
     * @return 本次调用的用量与耗时；取消或异常时不保证有值
     */
    CallResult stream(List<ChatTurn> messages, StreamSink sink, CancellationToken token) throws Exception;

    /**
     * 非流式一次性调用。用于短输出、低延迟优先的辅助任务（标题、改写、摘要）。
     * 这类任务不值得占用流式通道，也不需要打字机效果。
     */
    LlmAnswer complete(List<ChatTurn> messages, int maxTokens, double temperature) throws Exception;
}
