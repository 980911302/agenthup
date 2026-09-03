package com.ruoyi.system.kb.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class EvalMetricsTest
{
    @Test
    void recallMrrNdcgAreDeterministic()
    {
        List<Boolean> ranked = List.of(false, true, true, false, false);
        // top5 命中 2 个相关 / gold 2 → recall=1.0；首相关在 rank2 → RR=0.5
        assertEquals(1.0, EvalMetrics.recallAtK(ranked, 2, 5), 1e-9);
        assertEquals(0.5, EvalMetrics.recallAtK(ranked, 2, 2), 1e-9);
        assertEquals(0.5, EvalMetrics.reciprocalRank(ranked), 1e-9);
        double ndcg = EvalMetrics.ndcgAtKWithGold(ranked, 2, 10);
        assertTrue(ndcg > 0.5 && ndcg <= 1.0);

        // 重复计算一致
        assertEquals(ndcg, EvalMetrics.ndcgAtKWithGold(ranked, 2, 10), 1e-12);
    }

    @Test
    void emptyGoldIsNanForRecall()
    {
        assertTrue(Double.isNaN(EvalMetrics.recallAtK(List.of(true), 0, 5)));
        assertEquals(0.0, EvalMetrics.reciprocalRank(List.of(false, false)), 1e-9);
    }

    @Test
    void percentileIsStable()
    {
        List<Long> values = List.of(10L, 20L, 30L, 40L, 100L);
        assertEquals(30.0, EvalMetrics.percentile(values, 0.50), 1e-9);
        assertEquals(100.0, EvalMetrics.percentile(values, 0.95), 1e-9);
    }
}
