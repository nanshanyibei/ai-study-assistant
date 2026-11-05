package com.nanshan.study.llm;

/**
 * 一次模型调用的用量与耗时。
 * <p>
 * 服务端没有下发 usage 时（部分网关会省略），token 记 0 而不是抛异常——
 * 计量是旁路能力，不能因为它让主链路失败。
 * <p>
 * 注意：{@code completionTokens} 已包含推理模型的思考 token（OpenAI 兼容协议的约定），
 * 计费时不要拿 reasoningTokens 再加一次。
 */
public record CallResult(
        String model,
        int promptTokens,
        int completionTokens,
        int reasoningTokens,
        long elapsedMs
) {
    public static CallResult unknown(String model, long elapsedMs) {
        return new CallResult(model, 0, 0, 0, elapsedMs);
    }

    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
