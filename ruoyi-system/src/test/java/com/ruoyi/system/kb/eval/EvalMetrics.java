package com.ruoyi.system.kb.eval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 确定性检索指标：Recall@k、MRR、nDCG@k、Success@k。
 * 相关判定由调用方以 0/1 列表传入（按排名顺序）。
 */
public final class EvalMetrics
{
    private EvalMetrics()
    {
    }

    /** 单查询 Recall@k = |relevant ∩ topk| / |all relevant|；无 gold 时返回 NaN。 */
    public static double recallAtK(List<Boolean> rankedRelevance, int totalRelevant, int k)
    {
        if (totalRelevant <= 0)
        {
            return Double.NaN;
        }
        int hit = 0;
        int limit = Math.min(k, rankedRelevance.size());
        for (int i = 0; i < limit; i++)
        {
            if (Boolean.TRUE.equals(rankedRelevance.get(i)))
            {
                hit++;
            }
        }
        return hit / (double) totalRelevant;
    }

    /** 首个相关结果排名的倒数；无相关返回 0。 */
    public static double reciprocalRank(List<Boolean> rankedRelevance)
    {
        for (int i = 0; i < rankedRelevance.size(); i++)
        {
            if (Boolean.TRUE.equals(rankedRelevance.get(i)))
            {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    public static double ndcgAtK(List<Boolean> rankedRelevance, int k)
    {
        double dcg = 0.0;
        int limit = Math.min(k, rankedRelevance.size());
        for (int i = 0; i < limit; i++)
        {
            if (Boolean.TRUE.equals(rankedRelevance.get(i)))
            {
                dcg += 1.0 / log2(i + 2);
            }
        }
        int idealRelevant = 0;
        for (Boolean b : rankedRelevance)
        {
            if (Boolean.TRUE.equals(b))
            {
                idealRelevant++;
            }
        }
        // ideal 以「全部 gold 都排最前」为上界；若只知道 top 内相关，用 top 内相关数
        int idealCount = Math.max(idealRelevant, countTrue(rankedRelevance.subList(0, limit)));
        idealCount = Math.min(idealCount, k);
        if (idealCount == 0)
        {
            // 无相关：若查询本应有 gold，调用方应用 totalRelevant 重算；此处返回 0
            return 0.0;
        }
        double idcg = 0.0;
        for (int i = 0; i < idealCount; i++)
        {
            idcg += 1.0 / log2(i + 2);
        }
        return idcg == 0 ? 0.0 : dcg / idcg;
    }

    /** 用 gold 数量计算 ideal DCG 的 nDCG。 */
    public static double ndcgAtKWithGold(List<Boolean> rankedRelevance, int totalRelevant, int k)
    {
        if (totalRelevant <= 0)
        {
            return Double.NaN;
        }
        double dcg = 0.0;
        int limit = Math.min(k, rankedRelevance.size());
        for (int i = 0; i < limit; i++)
        {
            if (Boolean.TRUE.equals(rankedRelevance.get(i)))
            {
                dcg += 1.0 / log2(i + 2);
            }
        }
        int idealCount = Math.min(totalRelevant, k);
        double idcg = 0.0;
        for (int i = 0; i < idealCount; i++)
        {
            idcg += 1.0 / log2(i + 2);
        }
        return idcg == 0 ? 0.0 : dcg / idcg;
    }

    public static double mean(List<Double> values)
    {
        List<Double> finite = new ArrayList<>();
        for (Double v : values)
        {
            if (v != null && !v.isNaN() && !v.isInfinite())
            {
                finite.add(v);
            }
        }
        if (finite.isEmpty())
        {
            return Double.NaN;
        }
        double sum = 0;
        for (Double v : finite)
        {
            sum += v;
        }
        return sum / finite.size();
    }

    public static double percentile(List<Long> sortedNanos, double p)
    {
        if (sortedNanos == null || sortedNanos.isEmpty())
        {
            return Double.NaN;
        }
        List<Long> copy = new ArrayList<>(sortedNanos);
        Collections.sort(copy);
        int idx = (int) Math.ceil(p * copy.size()) - 1;
        idx = Math.max(0, Math.min(copy.size() - 1, idx));
        return copy.get(idx);
    }

    private static double log2(int n)
    {
        return Math.log(n) / Math.log(2);
    }

    private static int countTrue(List<Boolean> list)
    {
        int n = 0;
        for (Boolean b : list)
        {
            if (Boolean.TRUE.equals(b))
            {
                n++;
            }
        }
        return n;
    }
}
