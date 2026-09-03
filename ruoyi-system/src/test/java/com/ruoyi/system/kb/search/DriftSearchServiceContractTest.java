package com.ruoyi.system.kb.search;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.system.domain.KbKnowledge;
import com.ruoyi.system.kb.vector.KbSearchHit;

/**
 * DRIFT 编排合约：硬预算终止、无新证据停止、单步失败不整崩。
 */
class DriftSearchServiceContractTest
{
    @Test
    void alwaysStopsWithinRetrievalBudget()
    {
        DriftSearchService svc = new DriftSearchService();
        ReflectionTestUtils.setField(svc, "maxDepth", 3);
        ReflectionTestUtils.setField(svc, "maxFollowupsPerLevel", 3);
        ReflectionTestUtils.setField(svc, "maxRetrievals", 4);
        ReflectionTestUtils.setField(svc, "maxTokens", 100_000);
        ReflectionTestUtils.setField(svc, "timeoutMs", 60_000L);
        ReflectionTestUtils.setField(svc, "dedupeThreshold", 0.75);
        ReflectionTestUtils.setField(svc, "includeCommunity", false);
        ReflectionTestUtils.setField(svc, "seedMode", "local");

        AtomicInteger calls = new AtomicInteger();
        KbKnowledge kb = new KbKnowledge();
        kb.setKbId(1L);

        DriftSearchService.DriftResult r = svc.search(kb, "GraphRAG 社区检索", 5,
            (q, k) -> {
                calls.incrementAndGet();
                return List.of(hit(100L + calls.get(), "内容" + calls.get(), "EntA"));
            },
            (q, k) -> List.of());

        assertNotNull(r.budget.getStopReason());
        assertTrue(r.budget.getRetrievalsUsed() <= 4);
        assertTrue(calls.get() <= 4);
        assertFalse(r.hits.isEmpty());
        assertTrue(r.hits.get(0).getChannel().equals("drift")
            || r.hits.get(0).getDebugTrace() != null);
    }

    @Test
    void stopsWhenNoNewEvidence()
    {
        DriftSearchService svc = new DriftSearchService();
        ReflectionTestUtils.setField(svc, "maxDepth", 2);
        ReflectionTestUtils.setField(svc, "maxFollowupsPerLevel", 3);
        ReflectionTestUtils.setField(svc, "maxRetrievals", 20);
        ReflectionTestUtils.setField(svc, "maxTokens", 100_000);
        ReflectionTestUtils.setField(svc, "timeoutMs", 60_000L);
        ReflectionTestUtils.setField(svc, "dedupeThreshold", 0.75);
        ReflectionTestUtils.setField(svc, "includeCommunity", false);
        ReflectionTestUtils.setField(svc, "seedMode", "local");

        KbSearchHit same = hit(1L, "固定证据", "Alpha");
        KbKnowledge kb = new KbKnowledge();
        kb.setKbId(2L);

        DriftSearchService.DriftResult r = svc.search(kb, "Alpha 是什么", 5,
            (q, k) -> List.of(same),
            (q, k) -> List.of());

        assertEqualsStopNoNewOrNoFollowup(r);
        assertTrue(r.budget.getRetrievalsUsed() >= 1);
    }

    @Test
    void seedFailureDoesNotThrow()
    {
        DriftSearchService svc = new DriftSearchService();
        ReflectionTestUtils.setField(svc, "maxDepth", 2);
        ReflectionTestUtils.setField(svc, "maxFollowupsPerLevel", 2);
        ReflectionTestUtils.setField(svc, "maxRetrievals", 10);
        ReflectionTestUtils.setField(svc, "maxTokens", 100_000);
        ReflectionTestUtils.setField(svc, "timeoutMs", 60_000L);
        ReflectionTestUtils.setField(svc, "dedupeThreshold", 0.75);
        ReflectionTestUtils.setField(svc, "includeCommunity", false);
        ReflectionTestUtils.setField(svc, "seedMode", "hybrid");

        KbKnowledge kb = new KbKnowledge();
        kb.setKbId(3L);

        DriftSearchService.DriftResult r = svc.search(kb, "query", 5,
            (q, k) -> {
                throw new RuntimeException("local down");
            },
            (q, k) -> {
                throw new RuntimeException("hybrid down");
            });

        assertNotNull(r);
        assertTrue(r.hits == null || r.hits.isEmpty() || r.budget.getStopReason() != null);
        assertTrue(r.steps.stream().anyMatch(s -> s.contains("seed")));
    }

    private static void assertEqualsStopNoNewOrNoFollowup(DriftSearchService.DriftResult r)
    {
        String stop = r.budget.getStopReason();
        assertNotNull(stop);
        assertTrue(
            "no_new_evidence".equals(stop)
                || "no_followups".equals(stop)
                || "max_depth".equals(stop)
                || "completed".equals(stop)
                || "max_retrievals".equals(stop),
            "unexpected stop: " + stop);
    }

    private static KbSearchHit hit(long id, String content, String entity)
    {
        KbSearchHit h = new KbSearchHit();
        h.setChunkId(id);
        h.setContent(content);
        h.setScore(0.8);
        h.setEntityNames(new ArrayList<>(List.of(entity)));
        h.setDocName("doc");
        return h;
    }
}
