package com.ruoyi.system.kb.graph.community;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import com.ruoyi.system.domain.KbGraphCommunity;
import com.ruoyi.system.domain.KbGraphEntityCommunity;
import com.ruoyi.system.kb.graph.GraphEntity;
import com.ruoyi.system.kb.graph.GraphRelation;

class ConnectedComponentsCommunityDetectorTest
{
    @Test
    void nonIsolatedEntitiesHaveLevel0Community()
    {
        ConnectedComponentsCommunityDetector det = new ConnectedComponentsCommunityDetector(42);
        List<GraphEntity> entities = List.of(
            entity("A"), entity("B"), entity("C"), entity("D"));
        List<GraphRelation> rels = List.of(
            rel("A", "B"), rel("B", "C")); // D isolated

        CommunityDetectionResult r = det.detect(1L, "gv1", entities, rels);
        assertTrue(r.isAvailable());
        assertEquals(2, r.getLevelCount());

        Set<String> level0 = new HashSet<>();
        for (KbGraphEntityCommunity m : r.getMemberships())
        {
            if (m.getLevel() == 0)
            {
                level0.add(m.getEntityName());
            }
        }
        assertTrue(level0.contains("A"));
        assertTrue(level0.contains("B"));
        assertTrue(level0.contains("C"));
        assertTrue(level0.contains("D")); // 孤立点也有社区
    }

    @Test
    void hierarchyHasNoCyclesAndParentsExist()
    {
        ConnectedComponentsCommunityDetector det = new ConnectedComponentsCommunityDetector(7);
        List<GraphEntity> entities = List.of(entity("X"), entity("Y"), entity("Z"));
        List<GraphRelation> rels = List.of(rel("X", "Y"), rel("Y", "Z"));
        CommunityDetectionResult r = det.detect(2L, "gv2", entities, rels);

        Map<String, KbGraphCommunity> byKey = new HashMap<>();
        for (KbGraphCommunity c : r.getCommunities())
        {
            byKey.put(c.getLevel() + ":" + c.getCommunityId(), c);
        }
        for (KbGraphCommunity c : r.getCommunities())
        {
            if (c.getParentCommunityId() != null)
            {
                assertTrue(c.getLevel() == 0, "only level0 has parent in this detector");
                assertTrue(byKey.containsKey("1:" + c.getParentCommunityId()),
                    "parent must exist");
            }
        }
    }

    @Test
    void sameSeedStable()
    {
        List<GraphEntity> entities = List.of(
            entity("E1"), entity("E2"), entity("E3"), entity("E4"), entity("E5"));
        List<GraphRelation> rels = List.of(
            rel("E1", "E2"), rel("E2", "E3"), rel("E4", "E5"));

        ConnectedComponentsCommunityDetector d1 = new ConnectedComponentsCommunityDetector(99);
        ConnectedComponentsCommunityDetector d2 = new ConnectedComponentsCommunityDetector(99);
        CommunityDetectionResult a = d1.detect(3L, "g", entities, rels);
        CommunityDetectionResult b = d2.detect(3L, "g", entities, rels);

        assertEquals(a.getCommunities().size(), b.getCommunities().size());
        assertEquals(a.getMemberships().size(), b.getMemberships().size());
        // 同一实体在 level0 的 communityId 稳定
        Map<String, Long> mapA = level0Map(a);
        Map<String, Long> mapB = level0Map(b);
        assertEquals(mapA, mapB);
    }

    @Test
    void differentSeedMayDiffer()
    {
        List<GraphEntity> entities = List.of(
            entity("E1"), entity("E2"), entity("E3"), entity("E4"),
            entity("E5"), entity("E6"), entity("E7"), entity("E8"));
        List<GraphRelation> rels = List.of(
            rel("E1", "E2"), rel("E2", "E3"), rel("E3", "E4"),
            rel("E4", "E5"), rel("E5", "E6"), rel("E6", "E7"), rel("E7", "E8"));

        var a = new ConnectedComponentsCommunityDetector(1).detect(4L, "g", entities, rels);
        var b = new ConnectedComponentsCommunityDetector(99999).detect(4L, "g", entities, rels);
        // 不强制不同，但算法应可运行
        assertFalse(a.getMemberships().isEmpty());
        assertFalse(b.getMemberships().isEmpty());
    }

    @Test
    void noopProbeUnavailable()
    {
        NoOpCommunityDetector noop = new NoOpCommunityDetector();
        assertFalse(noop.probe().isAvailable());
        CommunityDetectionResult r = noop.detect(1L, "v");
        assertFalse(r.isAvailable());
        assertTrue(r.getSkipReason() != null && !r.getSkipReason().isBlank());
    }

    private static Map<String, Long> level0Map(CommunityDetectionResult r)
    {
        Map<String, Long> m = new HashMap<>();
        for (KbGraphEntityCommunity ec : r.getMemberships())
        {
            if (ec.getLevel() != null && ec.getLevel() == 0)
            {
                m.put(ec.getEntityName(), ec.getCommunityId());
            }
        }
        return m;
    }

    private static GraphEntity entity(String name)
    {
        GraphEntity e = new GraphEntity();
        e.setKbId(1L);
        e.setName(name);
        e.setEntityKey("1|" + name.toLowerCase() + "||");
        return e;
    }

    private static GraphRelation rel(String s, String t)
    {
        GraphRelation r = new GraphRelation();
        r.setSourceName(s);
        r.setTargetName(t);
        r.setKeywords("related");
        r.setWeight(1.0);
        return r;
    }
}
