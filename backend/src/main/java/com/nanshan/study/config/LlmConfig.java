package com.nanshan.study.config;

import com.nanshan.study.llm.LlmClient;
import com.nanshan.study.llm.MockLlmClient;
import com.nanshan.study.llm.OpenAiCompatLlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    /** 主力对话模型：RAG 问答、长回答，质量优先 */
    @Bean
    @Primary
    public LlmClient llmClient(LlmProperties props, ObjectMapper mapper) {
        if (props.useMock()) {
            log.warn("LLM 未配置（provider={}），已启用 mock 模式，只验证链路不产出真实回答", props.provider());
            return new MockLlmClient();
        }
        log.info("主模型：{} / {}", props.baseUrl(), props.model());
        return new OpenAiCompatLlmClient(props, mapper);
    }

    /**
     * 辅助任务模型：标题生成、query 改写、上下文摘要。
     * 这些任务不需要强智能，但每次都串在用户可感知的路径上，所以挑的是延迟和单价最低的一档。
     */
    @Bean
    public LlmClient fastLlmClient(LlmProperties props, ObjectMapper mapper) {
        if (props.useMock()) {
            return new MockLlmClient();
        }
        if (!props.useFastModel()) {
            log.warn("未配置 llm.fast-model，辅助任务将复用主模型 {}", props.model());
            return new OpenAiCompatLlmClient(props, mapper);
        }
        log.info("快模型：{} / {}", props.baseUrl(), props.fastModel());
        // 辅助任务是短输出，必须关掉思考：否则 max_tokens 会被推理过程吃光，正文返回空串
        return new OpenAiCompatLlmClient(props.withModel(props.fastModel(), Boolean.TRUE), mapper);
    }
}
