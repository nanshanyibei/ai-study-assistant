package com.nanshan.study.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM 相关配置。全部走环境变量覆盖，代码里不出现任何密钥。
 * <p>
 * 双模型分工：
 * <ul>
 *   <li>{@code model} —— 主力对话模型，质量优先（如 deepseek-ai/DeepSeek-V4.1-Flash）</li>
 *   <li>{@code fastModel} —— 辅助任务模型，延迟/成本优先（标题、query 改写、上下文摘要）</li>
 * </ul>
 * 不配 fastModel 时退化为单模型，功能不变，只是辅助任务也走主模型。
 */
@ConfigurationProperties(prefix = "llm")
public record LlmProperties(
        String provider,
        String baseUrl,
        String apiKey,
        String model,
        String fastModel,
        Double temperature,
        Integer connectTimeoutSeconds,
        Integer maxHistoryTurns,
        String systemPrompt,
        /**
         * 是否向服务端发送 enable_thinking=false。
         * 主模型留空（不下发该参数）；快模型设 true —— 标题/改写/摘要这类短输出任务
         * 一旦落进「思考模式」，几十个 max_tokens 会全被推理吃掉，正文直接为空。
         */
        Boolean disableThinking,
        /**
         * 思考 token 预算（主模型用）。不配则不限制——实测同题 42.6s（思考1436tok）
         * 限 512 后 22.8s（思考513tok），快 47% 且正文不受影响。
         */
        Integer thinkingBudget,
        Pricing pricing
) {

    /**
     * 每百万 token 的人民币单价。用于成本核算，不影响调用结果。
     * 接新模型时记得同步改这一组数字，否则 /api/metrics 里的花费会失真。
     */
    public record Pricing(Double input, Double output, Double fastInput, Double fastOutput) {

        public double inputOrDefault() {
            return input == null ? 2.0 : input;
        }

        public double outputOrDefault() {
            return output == null ? 8.0 : output;
        }

        public double fastInputOrDefault() {
            return fastInput == null ? 0.7 : fastInput;
        }

        public double fastOutputOrDefault() {
            return fastOutput == null ? 2.1 : fastOutput;
        }
    }

    public boolean useMock() {
        return !"openai-compat".equalsIgnoreCase(provider) || apiKey == null || apiKey.isBlank();
    }

    public boolean useFastModel() {
        return fastModel != null && !fastModel.isBlank();
    }

    public double temperatureOrDefault() {
        return temperature == null ? 0.7 : temperature;
    }

    public int maxHistoryOrDefault() {
        return maxHistoryTurns == null ? 20 : maxHistoryTurns;
    }

    public Pricing pricingOrDefault() {
        return pricing == null ? new Pricing(null, null, null, null) : pricing;
    }

    /** 换一个模型名、其余配置不变，用于从主模型派生快模型客户端 */
    public LlmProperties withModel(String newModel, Boolean newDisableThinking) {
        return new LlmProperties(provider, baseUrl, apiKey, newModel, fastModel, temperature,
                connectTimeoutSeconds, maxHistoryTurns, systemPrompt, newDisableThinking,
                thinkingBudget, pricing);
    }

    public Integer thinkingBudgetOrDefault() {
        return thinkingBudget;
    }
}
