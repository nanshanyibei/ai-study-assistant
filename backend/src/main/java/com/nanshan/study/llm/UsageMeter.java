package com.nanshan.study.llm;

import com.nanshan.study.config.LlmProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/**
 * 调用量与成本计量。
 * <p>
 * 做这个不是为了好看：大模型应用的成本几乎全在 token 上，而 token 消耗是「看不见」的——
 * 一次多轮问答的输入可能翻好几倍，思考模型的 completion 里还夹着用户从没看到的推理 token。
 * 没有这层计量，就无法回答「贵在哪」「降级有没有效果」这两个问题。
 * <p>
 * 按 (模型, 用途) 两维聚合，所以能直接看出「标题生成占了多少钱」。
 * 进程内统计，重启归零；生产环境应替换为 Micrometer + Prometheus 或落库。
 */
@Component
public class UsageMeter {

    /** 累计桶。用 LongAdder 而不是 AtomicLong：写多读少，热点争用低 */
    private static final class Bucket {
        final String model;
        final String purpose;
        final LongAdder calls = new LongAdder();
        final LongAdder promptTokens = new LongAdder();
        final LongAdder completionTokens = new LongAdder();
        final LongAdder reasoningTokens = new LongAdder();
        final LongAdder elapsedMs = new LongAdder();
        final DoubleAdder costCny = new DoubleAdder();

        Bucket(String model, String purpose) {
            this.model = model;
            this.purpose = purpose;
        }
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final LlmProperties props;

    public UsageMeter(LlmProperties props) {
        this.props = props;
    }

    /**
     * @param purpose 用途标识：chat / title / rewrite / summary
     * @param fast    true 表示走快模型，计费单价用 fast 那一档
     */
    public void record(String purpose, CallResult result, boolean fast) {
        if (result == null) {
            return;
        }
        String key = result.model() + "|" + purpose;
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(result.model(), purpose));

        bucket.calls.increment();
        bucket.promptTokens.add(result.promptTokens());
        bucket.completionTokens.add(result.completionTokens());
        bucket.reasoningTokens.add(result.reasoningTokens());
        bucket.elapsedMs.add(result.elapsedMs());
        bucket.costCny.add(cost(result, fast));
    }

    /** 一次调用的人民币成本：输入按 prompt 单价，输出按 completion 单价（思考 token 已含在其中） */
    public double costOf(CallResult result, boolean fast) {
        if (result == null) {
            return 0;
        }
        LlmProperties.Pricing p = props.pricingOrDefault();
        double inputPrice = fast ? p.fastInputOrDefault() : p.inputOrDefault();
        double outputPrice = fast ? p.fastOutputOrDefault() : p.outputOrDefault();
        return result.promptTokens() / 1_000_000.0 * inputPrice
                + result.completionTokens() / 1_000_000.0 * outputPrice;
    }

    private double cost(CallResult result, boolean fast) {
        return costOf(result, fast);
    }

    /** 给前端/看板用的快照 */
    public Map<String, Object> snapshot() {
        long calls = 0;
        long prompt = 0;
        long completion = 0;
        long reasoning = 0;
        long elapsed = 0;
        double cost = 0;

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Bucket b : buckets.values()) {
            calls += b.calls.sum();
            prompt += b.promptTokens.sum();
            completion += b.completionTokens.sum();
            reasoning += b.reasoningTokens.sum();
            elapsed += b.elapsedMs.sum();
            cost += b.costCny.sum();

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("model", b.model);
            row.put("purpose", b.purpose);
            row.put("calls", b.calls.sum());
            row.put("promptTokens", b.promptTokens.sum());
            row.put("completionTokens", b.completionTokens.sum());
            row.put("reasoningTokens", b.reasoningTokens.sum());
            row.put("avgLatencyMs", b.calls.sum() == 0 ? 0 : b.elapsedMs.sum() / b.calls.sum());
            row.put("costCny", round(b.costCny.sum()));
            rows.add(row);
        }
        rows.sort((a, b) -> Long.compare((Long) b.get("calls"), (Long) a.get("calls")));

        Map<String, Object> total = new LinkedHashMap<>();
        total.put("calls", calls);
        total.put("promptTokens", prompt);
        total.put("completionTokens", completion);
        total.put("reasoningTokens", reasoning);
        total.put("avgLatencyMs", calls == 0 ? 0 : elapsed / calls);
        total.put("costCny", round(cost));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("breakdown", rows);
        return result;
    }

    public void reset() {
        buckets.clear();
    }

    private double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
