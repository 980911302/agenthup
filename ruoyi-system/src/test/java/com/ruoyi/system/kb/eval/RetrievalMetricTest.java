package com.ruoyi.system.kb.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.system.kb.eval.EvalModels.EvalQuery;
import com.ruoyi.system.kb.eval.EvalModels.IndexedChunk;
import com.ruoyi.system.kb.eval.EvalModels.QuerySet;
import com.ruoyi.system.kb.eval.EvalModels.RankedHit;
import com.ruoyi.system.kb.eval.EvalModels.RelevantAnchor;

/**
 * 阶段 0：vector/graph/mix 离线检索指标基线（无付费 LLM/嵌入）。
 */
class RetrievalMetricTest
{
    private static final String[] MODES = { "vector", "graph", "mix" };

    @TempDir
    static Path tempDir;

    static OfflineEvalIndex index;
    static QuerySet querySet;
    static Map<String, Object> baselineReport;

    @BeforeAll
    static void setup() throws Exception
    {
        Map<String, Path> corpus = EvalCorpusMaterializer.materialize(tempDir.resolve("corpus"));
        index = new OfflineEvalIndex();
        index.build(corpus);
        String raw = EvalCorpusMaterializer.readResourceUtf8("kb/eval/queries.json");
        querySet = JSON.parseObject(raw, QuerySet.class);
        baselineReport = runAllModes();
        EvalReportWriter.writeJson("retrieval-baseline.json", baselineReport);
    }

    @Test
    void hasAtLeastFortyQueries()
    {
        assertTrue(querySet.queries.size() >= 40,
            "expected >=40 queries, got " + querySet.queries.size());
    }

    @Test
    void coversRequiredQueryTypes()
    {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (EvalQuery q : querySet.queries)
        {
            counts.merge(q.type, 1L, Long::sum);
        }
        for (String type : List.of("data-local", "activity-local", "multi-hop",
            "data-global", "table", "negative"))
        {
            assertTrue(counts.getOrDefault(type, 0L) > 0, "missing type " + type);
        }
    }

    @Test
    void deterministicMetricsAcrossReruns() throws Exception
    {
        Map<String, Object> second = runAllModes();
        for (String mode : MODES)
        {
            @SuppressWarnings("unchecked")
            Map<String, Object> a = (Map<String, Object>) ((Map<?, ?>) baselineReport.get("metrics")).get(mode);
            @SuppressWarnings("unchecked")
            Map<String, Object> b = (Map<String, Object>) ((Map<?, ?>) second.get("metrics")).get(mode);
            assertEquals(a.get("recallAt5"), b.get("recallAt5"), mode + " recall@5 drift");
            assertEquals(a.get("recallAt10"), b.get("recallAt10"), mode + " recall@10 drift");
            assertEquals(a.get("mrr"), b.get("mrr"), mode + " mrr drift");
            assertEquals(a.get("ndcgAt10"), b.get("ndcgAt10"), mode + " ndcg drift");
            assertEquals(a.get("citationHitRate"), b.get("citationHitRate"), mode + " citation drift");
        }
    }

    @Test
    void vectorBaselineIsRecordedAndFinite()
    {
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) baselineReport.get("metrics");
        @SuppressWarnings("unchecked")
        Map<String, Object> vector = (Map<String, Object>) metrics.get("vector");
        assertTrue(((Number) vector.get("queryCount")).intValue() >= 40);
        assertTrue(((Number) vector.get("scoredQueryCount")).intValue() > 0);
        double recall10 = ((Number) vector.get("recallAt10")).doubleValue();
        assertTrue(recall10 >= 0.0 && recall10 <= 1.0, "recall@10 out of range: " + recall10);
        assertEquals(0, ((Number) baselineReport.get("llmCalls")).intValue());
        assertEquals(0, ((Number) baselineReport.get("embeddingCalls")).intValue());
    }

    @Test
    void negativeQueriesDoNotRequireRelevantHits()
    {
        for (EvalQuery q : querySet.queries)
        {
            if (!"negative".equals(q.type))
            {
                continue;
            }
            assertTrue(q.relevantAnchors == null || q.relevantAnchors.isEmpty(),
                q.queryId + " negative must have empty anchors");
        }
    }

    @Test
    void reportContainsLatencyAndModes()
    {
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) baselineReport.get("metrics");
        for (String mode : MODES)
        {
            assertTrue(metrics.containsKey(mode), "missing mode " + mode);
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) metrics.get(mode);
            assertTrue(m.containsKey("latencyP50Nanos"));
            assertTrue(m.containsKey("latencyP95Nanos"));
        }
    }

    private static Map<String, Object> runAllModes()
    {
        Map<String, Object> byMode = new LinkedHashMap<>();
        for (String mode : MODES)
        {
            byMode.put(mode, evaluateMode(mode));
        }
        Map<String, Object> env = EvalReportWriter.envelope("retrieval-baseline", byMode);
        env.put("queryCount", querySet.queries.size());
        env.put("chunkCount", index.allChunks().size());
        env.put("note", "vector/graph/mix use offline lexical+entity index; not production embedding API");
        return env;
    }

    private static Map<String, Object> evaluateMode(String mode)
    {
        List<Double> recall5 = new ArrayList<>();
        List<Double> recall10 = new ArrayList<>();
        List<Double> mrr = new ArrayList<>();
        List<Double> ndcg = new ArrayList<>();
        List<Double> citation = new ArrayList<>();
        List<Long> latencies = new ArrayList<>();
        int scored = 0;

        for (EvalQuery q : querySet.queries)
        {
            long t0 = System.nanoTime();
            List<RankedHit> hits = index.search(mode, q.question, 10);
            long elapsed = System.nanoTime() - t0;
            latencies.add(elapsed);

            List<RelevantAnchor> anchors = q.relevantAnchors == null ? List.of() : q.relevantAnchors;
            int totalRelevant = countDistinctRelevantChunks(anchors);
            List<Boolean> ranked = new ArrayList<>();
            for (RankedHit h : hits)
            {
                ranked.add(OfflineEvalIndex.isRelevant(h.chunk, anchors));
            }

            if (totalRelevant > 0)
            {
                scored++;
                recall5.add(EvalMetrics.recallAtK(ranked, totalRelevant, 5));
                recall10.add(EvalMetrics.recallAtK(ranked, totalRelevant, 10));
                mrr.add(EvalMetrics.reciprocalRank(ranked));
                ndcg.add(EvalMetrics.ndcgAtKWithGold(ranked, totalRelevant, 10));
                boolean top1 = !ranked.isEmpty() && Boolean.TRUE.equals(ranked.get(0));
                citation.add(top1 ? 1.0 : 0.0);
            }
            else if ("negative".equals(q.type))
            {
                // 负例：不计入 recall；top1 不应“强行相关”
                citation.add(0.0);
            }
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("queryCount", querySet.queries.size());
        m.put("scoredQueryCount", scored);
        m.put("recallAt5", round(EvalMetrics.mean(recall5)));
        m.put("recallAt10", round(EvalMetrics.mean(recall10)));
        m.put("mrr", round(EvalMetrics.mean(mrr)));
        m.put("ndcgAt10", round(EvalMetrics.mean(ndcg)));
        m.put("citationHitRate", round(EvalMetrics.mean(citation)));
        m.put("latencyP50Nanos", (long) EvalMetrics.percentile(latencies, 0.50));
        m.put("latencyP95Nanos", (long) EvalMetrics.percentile(latencies, 0.95));
        m.put("embeddingCalls", 0);
        m.put("llmCalls", 0);
        return m;
    }

    /**
     * gold 相关 chunk 上界：语料中匹配任一 anchor 的 chunk 数。
     */
    private static int countDistinctRelevantChunks(List<RelevantAnchor> anchors)
    {
        if (anchors == null || anchors.isEmpty())
        {
            return 0;
        }
        int n = 0;
        for (IndexedChunk c : index.allChunks())
        {
            if (OfflineEvalIndex.isRelevant(c, anchors))
            {
                n++;
            }
        }
        // 至少 1：即使当前切片未命中锚点短语，仍要求检索尽量召回
        return Math.max(n, 1);
    }

    private static double round(double v)
    {
        if (Double.isNaN(v))
        {
            return Double.NaN;
        }
        return Math.round(v * 1_000_000d) / 1_000_000d;
    }
}
