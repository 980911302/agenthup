package com.ruoyi.system.kb.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.ruoyi.system.kb.search.KbSearchMode;

/**
 * 全模式离线对比（不依赖 Spring/DB）：对同一 gold 列表用各模式排名相关性算指标。
 * <p>生产可接真实检索；此处用调用方提供的 per-mode ranked relevance。
 */
public final class MultiModeEvalHarness
{
    private MultiModeEvalHarness() {}

    public static final class QueryCase
    {
        public String id;
        public String query;
        /** fact / multi_hop / global_theme */
        public String type;
        /** mode -> ranked boolean relevance */
        public Map<String, List<Boolean>> relevanceByMode = new LinkedHashMap<>();
        public int totalRelevant;
    }

    public static final class ModeAggregate
    {
        public double recallAt10;
        public double mrr;
        public double ndcgAt10;
        public int queries;
    }

    public static Map<String, ModeAggregate> compare(List<QueryCase> cases, int k)
    {
        Map<String, List<Double>> recall = new LinkedHashMap<>();
        Map<String, List<Double>> mrr = new LinkedHashMap<>();
        Map<String, List<Double>> ndcg = new LinkedHashMap<>();

        if (cases != null)
        {
            for (QueryCase c : cases)
            {
                if (c == null || c.relevanceByMode == null)
                {
                    continue;
                }
                for (Map.Entry<String, List<Boolean>> e : c.relevanceByMode.entrySet())
                {
                    String mode = e.getKey().toLowerCase(Locale.ROOT);
                    List<Boolean> rel = e.getValue() != null ? e.getValue() : List.of();
                    recall.computeIfAbsent(mode, x -> new ArrayList<>())
                        .add(EvalMetrics.recallAtK(rel, c.totalRelevant, k));
                    mrr.computeIfAbsent(mode, x -> new ArrayList<>())
                        .add(EvalMetrics.reciprocalRank(rel));
                    ndcg.computeIfAbsent(mode, x -> new ArrayList<>())
                        .add(EvalMetrics.ndcgAtK(rel, k));
                }
            }
        }

        Map<String, ModeAggregate> out = new LinkedHashMap<>();
        for (String mode : recall.keySet())
        {
            ModeAggregate a = new ModeAggregate();
            a.queries = recall.get(mode).size();
            a.recallAt10 = avgFinite(recall.get(mode));
            a.mrr = avgFinite(mrr.get(mode));
            a.ndcgAt10 = avgFinite(ndcg.get(mode));
            out.put(mode, a);
        }
        return out;
    }

    /**
     * Basic 回归门：basic 的平均 Recall@k 不得低于 baseline * (1 - tolerance)。
     */
    public static boolean basicNoRegression(ModeAggregate basicNow, ModeAggregate basicBaseline,
        double tolerance)
    {
        if (basicNow == null || basicBaseline == null)
        {
            return false;
        }
        if (Double.isNaN(basicBaseline.recallAt10) || basicBaseline.recallAt10 <= 0)
        {
            return basicNow.recallAt10 >= 0;
        }
        double floor = basicBaseline.recallAt10 * (1.0 - Math.max(0, tolerance));
        return basicNow.recallAt10 + 1e-9 >= floor;
    }

    /**
     * Local 在 multi_hop 上相对 basic 的 Recall 提升。
     */
    public static double lift(ModeAggregate better, ModeAggregate baseline)
    {
        if (better == null || baseline == null)
        {
            return Double.NaN;
        }
        if (Double.isNaN(baseline.recallAt10))
        {
            return Double.NaN;
        }
        return better.recallAt10 - baseline.recallAt10;
    }

    private static double avgFinite(List<Double> xs)
    {
        if (xs == null || xs.isEmpty())
        {
            return Double.NaN;
        }
        double s = 0;
        int n = 0;
        for (Double d : xs)
        {
            if (d != null && !d.isNaN())
            {
                s += d;
                n++;
            }
        }
        return n == 0 ? Double.NaN : s / n;
    }

    /** 构造一条简单 case 辅助测试 */
    public static QueryCase caseOf(String id, String type, int totalRel,
        Map<String, List<Boolean>> byMode)
    {
        QueryCase c = new QueryCase();
        c.id = id;
        c.type = type;
        c.totalRelevant = totalRel;
        c.relevanceByMode = byMode;
        return c;
    }

    public static String modeName(KbSearchMode m)
    {
        return m == null ? "basic" : KbSearchMode.normalize(m).name();
    }
}
