package com.ruoyi.system.kb.graph.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import com.ruoyi.system.kb.graph.provenance.GraphExtractOutcome;

class GraphExtractionProfileTest
{
    @Test
    void fingerprintChangesWithPromptVersion()
    {
        GraphExtractionProfile a = GraphExtractionProfile.defaults();
        GraphExtractionProfile b = GraphExtractionProfile.defaults();
        b.setPromptVersion("p2");
        assertFalse(a.fingerprint().equals(b.fingerprint()));
        assertEquals(a.fingerprint(), GraphExtractionProfile.defaults().fingerprint());
    }

    @Test
    void systemPromptIncludesLimitsAndVersion()
    {
        GraphExtractionProfile p = GraphExtractionProfile.defaults();
        p.setMaxEntitiesPerUnit(7);
        p.setPromptVersion("p9");
        String sys = p.buildSystemPrompt();
        assertTrue(sys.contains("7"));
        assertTrue(sys.contains("promptVersion=p9"));
        // 中文优先：类型说明与禁代词规则
        assertTrue(sys.contains("人物") || sys.contains("实体"));
        assertTrue(sys.contains("JSON"));
    }

    @Test
    void userMessageMarksHeadingAsBackground()
    {
        String u = ExtractPrompt.userMessage("第一章 → 概述", "本方案由甲乙双方签署。");
        assertTrue(u.contains("章节路径"));
        assertTrue(u.contains("正文") || u.contains("【正文】"));
        assertTrue(u.contains("本方案由甲乙双方签署"));
    }

    @Test
    void onlySuccessAndValidEmptyAreCacheable()
    {
        assertTrue(GraphExtractOutcome.cacheable(GraphExtractOutcome.SUCCESS));
        assertTrue(GraphExtractOutcome.cacheable(GraphExtractOutcome.VALID_EMPTY));
        assertFalse(GraphExtractOutcome.cacheable(GraphExtractOutcome.LLM_FAILED));
        assertFalse(GraphExtractOutcome.cacheable(GraphExtractOutcome.PARSE_FAILED));
        assertFalse(GraphExtractOutcome.cacheable(GraphExtractOutcome.VALIDATION_FAILED));
    }

    @Test
    void retriableClassification()
    {
        assertTrue(KbEntityExtractor.isRetriable(new java.net.SocketTimeoutException("timeout")));
        assertTrue(KbEntityExtractor.isRetriable(new RuntimeException("503 Service Unavailable")));
        assertFalse(KbEntityExtractor.isRetriable(new IllegalArgumentException("invalid request 400")));
    }
}
