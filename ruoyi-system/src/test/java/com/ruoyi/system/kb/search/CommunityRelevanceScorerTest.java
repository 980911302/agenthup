package com.ruoyi.system.kb.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CommunityRelevanceScorerTest
{
    @Test
    void exactPhraseScoresHigh()
    {
        double s = CommunityRelevanceScorer.score("分库分表策略", "分库分表", "公司采用分库分表策略", null);
        assertTrue(s >= 0.5);
    }

    @Test
    void unrelatedNearZero()
    {
        double s = CommunityRelevanceScorer.score("宇宙飞船燃料", "请假流程", "员工年假申请", null);
        assertTrue(s < 0.2);
    }

    @Test
    void vectorCanRescue()
    {
        double lexOnly = CommunityRelevanceScorer.score("xyz", "abc", "def", null);
        double withVec = CommunityRelevanceScorer.score("xyz", "abc", "def", 0.9);
        assertTrue(withVec >= 0.9);
        assertTrue(withVec >= lexOnly);
    }

    @Test
    void emptySafe()
    {
        assertEquals(0.0, CommunityRelevanceScorer.score(null, "a", "b", null));
        assertEquals(0.0, CommunityRelevanceScorer.score("q", null, null, null));
    }
}
