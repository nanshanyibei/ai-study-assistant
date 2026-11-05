package com.nanshan.study.chat;

import com.nanshan.study.llm.ChatTurn;
import com.nanshan.study.llm.LlmAnswer;
import com.nanshan.study.llm.LlmClient;
import com.nanshan.study.llm.UsageMeter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 辅助任务：全部走快模型（延迟、单价都低一档），且全部「失败即降级」。
 * <p>
 * 这三个任务每次都在用户可感知的路径上，用主模型做会产生两个问题：
 * 一是成本被悄悄放大（辅助调用的 token 往往比用户以为的多），
 * 二是延迟叠加（用户等的是答案，不是标题）。
 * <p>
 * 降级原则：任何一个失败都返回 null，由调用方回退到原行为，绝不因为「生成标题失败」而让对话失败。
 */
@Service
public class FastTaskService {

    private static final Logger log = LoggerFactory.getLogger(FastTaskService.class);

    private final LlmClient fastLlm;
    private final UsageMeter meter;

    public FastTaskService(@Qualifier("fastLlmClient") LlmClient fastLlm, UsageMeter meter) {
        this.fastLlm = fastLlm;
        this.meter = meter;
    }

    public String fastModelName() {
        return fastLlm.modelName();
    }

    /**
     * 生成会话标题。只在首轮调用一次，用于侧边栏和浏览器标题。
     *
     * @return 标题；失败返回 null（调用方保持默认标题）
     */
    public String generateTitle(String question, String answer) {
        try {
            List<ChatTurn> messages = List.of(
                    ChatTurn.system("""
                            你是会话标题生成器。根据下面第一轮问答，生成一个标题：
                            1. 不超过 12 个汉字；
                            2. 概括用户要解决的具体问题，不要写成「关于…的问题」这种空话；
                            3. 只输出标题本身，不要引号、不要结尾标点、不要解释。
                            """),
                    ChatTurn.user("问：" + truncate(question, 200) + "\n\n答：" + truncate(answer, 400))
            );
            LlmAnswer result = fastLlm.complete(messages, 48, 0.3);
            meter.record("title", result.call(), true);
            return clean(result.text(), 20);
        } catch (Exception e) {
            log.warn("标题生成失败，保持默认标题：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 把「它怎么算」这种依赖上下文的追问，改写成可独立检索的问句。
     * 这一步是 RAG 的前置条件：向量检索拿不到上下文，看到「它怎么算」必然召回错。
     *
     * @return 改写后的问句；失败或无需改写时返回 null
     */
    public String rewriteQuery(List<ChatTurn> recentTurns, String question) {
        if (recentTurns == null || recentTurns.isEmpty() || !needsRewrite(question)) {
            return null;
        }
        try {
            StringBuilder dialog = new StringBuilder();
            int from = Math.max(0, recentTurns.size() - 4);
            for (ChatTurn turn : recentTurns.subList(from, recentTurns.size())) {
                dialog.append("assistant".equals(turn.role()) ? "助手：" : "用户：")
                        .append(truncate(turn.content(), 200))
                        .append('\n');
            }
            List<ChatTurn> messages = List.of(
                    ChatTurn.system("""
                            把用户的最新提问改写成一个不依赖上下文、可独立理解的完整问句。
                            规则：
                            1. 补全所有代词和省略（它、这个、上面说的）；
                            2. 专业术语、数字、公式原样保留；
                            3. 不要回答问题，不要解释，不要加「改写为」之类的前缀；
                            4. 如果原问题本身已经独立完整，原样返回。
                            """),
                    ChatTurn.user("对话片段：\n" + dialog + "\n最新提问：" + question)
            );
            LlmAnswer result = fastLlm.complete(messages, 120, 0.1);
            meter.record("rewrite", result.call(), true);
            String rewritten = clean(result.text(), 200);
            // 改写没带来信息增量时不要覆盖原问句，否则等于凭空换题
            if (rewritten == null || rewritten.equals(question.trim())) {
                return null;
            }
            return rewritten;
        } catch (Exception e) {
            log.warn("query 改写失败，使用原问句：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 把被挤出窗口的旧对话压成要点，接在 system 后面继续参与上下文。
     * 相比直接截断，这样用户早期说过的约束（科目、目标、时间）不会丢。
     */
    public String summarize(String previousSummary, List<ChatTurn> evicted) {
        if (evicted == null || evicted.isEmpty()) {
            return previousSummary;
        }
        try {
            StringBuilder content = new StringBuilder();
            if (previousSummary != null && !previousSummary.isBlank()) {
                content.append("已有摘要：\n").append(previousSummary).append("\n\n");
            }
            content.append("新增对话：\n");
            for (ChatTurn turn : evicted) {
                content.append("assistant".equals(turn.role()) ? "助手：" : "用户：")
                        .append(truncate(turn.content(), 300))
                        .append('\n');
            }
            List<ChatTurn> messages = List.of(
                    ChatTurn.system("""
                            把下面的对话压缩成中文要点，不超过 200 字。
                            必须保留：用户关注的知识点/科目、已经给出的关键结论、尚未解决的问题。
                            用「- 」开头的短句罗列，不要客套话，不要复述原话。
                            """),
                    ChatTurn.user(content.toString())
            );
            LlmAnswer result = fastLlm.complete(messages, 300, 0.2);
            meter.record("summary", result.call(), true);
            String summary = clean(result.text(), 600);
            return summary == null ? previousSummary : summary;
        } catch (Exception e) {
            log.warn("上下文摘要失败，沿用旧摘要：{}", e.getMessage());
            return previousSummary;
        }
    }

    /**
     * 是否值得花一次快模型调用去改写。
     * 判断放在本地而不是让模型决定：多一次调用就多一次延迟和成本，
     * 而「短句 / 含指代」这个规则本身就能覆盖绝大多数需要改写的场景。
     */
    public static boolean needsRewrite(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String q = question.trim();
        if (q.length() <= 12) {
            return true;
        }
        return q.matches(".*(它|这个|那个|上述|上面|刚才|这题|该字段|继续|再说|展开|详细讲).*");
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 去掉模型爱加的前后缀引号/换行/「标题：」这类前缀 */
    private String clean(String text, int max) {
        if (text == null) {
            return null;
        }
        String s = text.trim()
                .replaceAll("^[\"'“”‘’《》【】\\s]+", "")
                .replaceAll("[\"'“”‘’《》【】\\s]+$", "")
                .replaceAll("^(标题|改写后的问句|摘要)[：:]\\s*", "")
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.length() > max) {
            s = s.substring(0, max);
        }
        // 去掉模型偶尔加的句号结尾，标题不该有
        return s.replaceAll("[。.]$", "");
    }
}
