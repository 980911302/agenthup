package com.ruoyi.system.kb.search;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import com.ruoyi.system.kb.vector.KbSearchHit;

class DriftFollowupPlannerTest
{
    @Test
    void generatesFromEntitiesAndDedupesRoot()
    {
        KbSearchHit h = new KbSearchHit();
        h.setEntityNames(List.of("GraphRAG", "Leiden"));
        h.setHeadingPath("知识库/社区发现");
        h.setContent("层级社区");

        Set<String> asked = new LinkedHashSet<>();
        asked.add(DriftFollowupPlanner.normalize("GraphRAG 是什么"));

        List<String> plan = DriftFollowupPlanner.plan(
            "GraphRAG 是什么", List.of(h), asked, 3, 0.75);

        assertFalse(plan.isEmpty());
        assertTrue(plan.size() <= 3);
        for (String p : plan)
        {
            assertFalse(DriftFollowupPlanner.tooSimilar(p, "GraphRAG 是什么", 0.75),
                "should not re-ask root: " + p);
        }
    }

    @Test
    void tooSimilarDetectsNearDup()
    {
        assertTrue(DriftFollowupPlanner.tooSimilar("分库分表策略", "分库分表 策略", 0.5));
        assertFalse(DriftFollowupPlanner.tooSimilar("分库分表", "年假申请流程", 0.75));
    }

    @Test
    void maxZeroReturnsEmpty()
    {
        assertTrue(DriftFollowupPlanner.plan("q", List.of(), Set.of(), 0, 0.7).isEmpty());
    }
}
