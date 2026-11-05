package com.nanshan.study.llm;

import java.util.List;

/**
 * 无 API Key 时的本地假流，用于验证「前端渲染 → SSE → 取消」整条链路。
 * 刻意先发思考、再发正文，方便检查两条通道的 UI 表现。
 */
public class MockLlmClient implements LlmClient {

    private static final String THINKING = """
            用户在问一个通用问题。我应该：
            1. 先确认这是 mock 模式，回答内容不代表真实模型能力；
            2. 说明链路验证点：SSE、取消、markdown、公式；
            3. 提示如何切换到真实模型。
            """;

    private static final String ANSWER = """
            **结论**：这是 mock 模式返回的示例回答，用于验证流式链路。

            ### 一、链路验证点
            1. SSE 逐段下发，前端打字机效果正常
            2. 点击「停止」后服务端读取循环退出，不再消耗 token
            3. markdown 渲染生效：**加粗**、`行内代码`、列表

            ### 二、公式渲染验证
            软考中常见的可靠性指标：$MTBF$ 与 $MTTR$ 决定可用度

            $$A = \\frac{MTBF}{MTBF + MTTR}$$

            若 $MTBF=999h$、$MTTR=1h$，则可用度约为 99.9%。

            ### 三、下一步
            在 `backend/.env` 里配置 `LLM_API_KEY` 即可切到真实模型。
            """;

    @Override
    public String modelName() {
        return "mock";
    }

    @Override
    public CallResult stream(List<ChatTurn> messages, StreamSink sink, CancellationToken token) throws Exception {
        long startedAt = System.currentTimeMillis();
        String question = messages.get(messages.size() - 1).content();

        for (String chunk : chunk(THINKING, 6)) {
            if (token.isCancelled()) {
                return CallResult.unknown(modelName(), System.currentTimeMillis() - startedAt);
            }
            sink.reasoning(chunk);
            Thread.sleep(15);
        }

        String body = "> 你的问题：" + question + "\n\n" + ANSWER;
        for (String chunk : chunk(body, 4)) {
            if (token.isCancelled()) {
                return CallResult.unknown(modelName(), System.currentTimeMillis() - startedAt);
            }
            sink.content(chunk);
            Thread.sleep(15);
        }

        return new CallResult(modelName(), THINKING.length() + question.length(),
                body.length(), THINKING.length(), System.currentTimeMillis() - startedAt);
    }

    @Override
    public LlmAnswer complete(List<ChatTurn> messages, int maxTokens, double temperature) {
        long startedAt = System.currentTimeMillis();
        String prompt = messages.isEmpty() ? "" : messages.get(messages.size() - 1).content();
        String text = "[mock] " + prompt.replaceAll("\\s+", " ").trim();
        if (text.length() > 60) {
            text = text.substring(0, 60);
        }
        return new LlmAnswer(text, new CallResult(modelName(), prompt.length(), text.length(), 0,
                System.currentTimeMillis() - startedAt));
    }

    private List<String> chunk(String text, int size) {
        return java.util.stream.IntStream.iterate(0, i -> i < text.length(), i -> i + size)
                .mapToObj(i -> text.substring(i, Math.min(text.length(), i + size)))
                .toList();
    }
}
