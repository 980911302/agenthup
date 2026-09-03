package com.ruoyi.system.kb.graph.community;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import com.ruoyi.system.kb.graph.GraphEntity;
import com.ruoyi.system.kb.graph.GraphRelation;
import com.ruoyi.system.kb.graph.community.ExtractiveCommunityReportBuilder.EvidenceSnippet;

class ExtractiveCommunityReportBuilderTest
{
    @Test
    void reportHasAtLeastOneSourceWhenEntitiesHaveChunkIds()
    {
        GraphEntity a = entity("Alpha", 101L, 102L);
        GraphEntity b = entity("Beta", 102L);
        GraphRelation r = rel("Alpha", "Beta", 101L);

        CommunityReportDraft d = ExtractiveCommunityReportBuilder.build(
            1L, "gv1", 0, 9L, List.of(a, b), List.of(r), List.of(),
            List.of(new EvidenceSnippet(101L, "alpha evidence")));

        assertFalse(d.getSourceChunkIds().isEmpty(), "每份报告至少一个出处");
        assertTrue(d.getSourceChunkIds().contains(101L));
        assertTrue(d.getSourceChunkIds().contains(102L));
        assertEquals("READY", d.getStatus());
        assertTrue(d.getSummary() != null && !d.getSummary().isBlank());
        assertTrue(d.getFindingsJson() != null && d.getFindingsJson().startsWith("["));
    }

    @Test
    void sameMaterialConvergesOnContentHash()
    {
        List<GraphEntity> entities = List.of(entity("X", 1L), entity("Y", 2L));
        List<GraphRelation> rels = List.of(rel("X", "Y", 1L));
        CommunityReportDraft a = ExtractiveCommunityReportBuilder.build(
            2L, "g", 0, 1L, entities, rels, List.of(), List.of());
        CommunityReportDraft b = ExtractiveCommunityReportBuilder.build(
            2L, "g", 0, 1L, entities, rels, List.of(), List.of());
        assertEquals(a.getContentHash(), b.getContentHash());
        assertEquals(
            ExtractiveCommunityReportBuilder.stableReportId(2L, "g", 0, 1L),
            ExtractiveCommunityReportBuilder.stableReportId(2L, "g", 0, 1L));
    }

    @Test
    void parentAggregatesChildSourcesAndMarksIncomplete()
    {
        CommunityReportDraft child = ExtractiveCommunityReportBuilder.build(
            3L, "g", 0, 10L,
            List.of(entity("C1", 50L)), List.of(), List.of(), List.of());
        child.setIncomplete(true);

        CommunityReportDraft parent = ExtractiveCommunityReportBuilder.build(
            3L, "g", 1, 99L,
            List.of(entity("P", 60L)), List.of(), List.of(child), List.of());

        assertTrue(parent.getSourceChunkIds().contains(50L));
        assertTrue(parent.getSourceChunkIds().contains(60L));
        assertTrue(parent.isIncomplete());
        assertTrue(parent.getFullContent().contains(child.getTitle()));
    }

    @Test
    void differentCommunitiesDifferentReportIds()
    {
        long a = ExtractiveCommunityReportBuilder.stableReportId(1L, "v", 0, 1L);
        long b = ExtractiveCommunityReportBuilder.stableReportId(1L, "v", 0, 2L);
        assertNotEquals(a, b);
        assertTrue(a > 0 && b > 0);
    }

    @Test
    void emptyEntitiesStillProducesReport()
    {
        CommunityReportDraft d = ExtractiveCommunityReportBuilder.build(
            1L, "g", 0, 1L, List.of(), List.of(), List.of(), List.of());
        assertEquals("READY", d.getStatus());
        assertTrue(d.getSourceChunkIds().isEmpty());
        assertTrue(d.getTitle().contains("社区"));
    }

    private static GraphEntity entity(String name, Long... sources)
    {
        GraphEntity e = new GraphEntity();
        e.setName(name);
        e.setEntityKey("1|" + name.toLowerCase() + "||");
        if (sources != null)
        {
            for (Long s : sources)
            {
                e.getSourceIds().add(s);
            }
        }
        return e;
    }

    private static GraphRelation rel(String s, String t, Long source)
    {
        GraphRelation r = new GraphRelation();
        r.setSourceName(s);
        r.setTargetName(t);
        r.setKeywords("related");
        r.getSourceIds().add(source);
        return r;
    }
}
