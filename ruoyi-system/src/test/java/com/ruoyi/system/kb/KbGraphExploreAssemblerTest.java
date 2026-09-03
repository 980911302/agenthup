package com.ruoyi.system.kb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import com.ruoyi.system.kb.graph.GraphEntity;
import com.ruoyi.system.kb.graph.GraphRelation;

class KbGraphExploreAssemblerTest
{
    @Test
    void clampsLimits()
    {
        assertEquals(KbGraphExploreAssembler.DEFAULT_NODE_LIMIT, KbGraphExploreAssembler.clampNodes(null));
        assertEquals(KbGraphExploreAssembler.MAX_NODE_LIMIT, KbGraphExploreAssembler.clampNodes(9999));
        assertEquals(2, KbGraphExploreAssembler.clampDepth(5));
        assertEquals(1, KbGraphExploreAssembler.clampDepth(0));
    }

    @Test
    void productNodeHidesKeys()
    {
        GraphEntity e = new GraphEntity();
        e.setName("权限体系");
        e.setType("CONCEPT");
        e.setEntityKey("secret-key");
        e.setSourceIds(List.of(1L, 2L));
        Map<String, Object> m = KbGraphExploreAssembler.productNode(e);
        assertEquals("权限体系", m.get("id"));
        assertFalse(m.containsKey("entityKey"));
        assertEquals(2, m.get("sourceCount"));
    }

    @Test
    void productNodeIdUsesGraphNameNotCanonical()
    {
        GraphEntity e = new GraphEntity();
        e.setName("agentcontextfactory");
        e.setCanonicalName("AgentContextFactory");
        e.setType("CONCEPT");
        Map<String, Object> m = KbGraphExploreAssembler.productNode(e);
        assertEquals("agentcontextfactory", m.get("id"));
        assertEquals("AgentContextFactory", m.get("name"));
    }

    @Test
    void filtersAndPickSeeds()
    {
        GraphEntity a = ent("A", 1);
        GraphEntity b = ent("B", 5);
        List<GraphEntity> seeds = KbGraphExploreAssembler.pickSeeds(List.of(a, b), 1);
        assertEquals("B", seeds.get(0).getName());
        assertTrue(KbGraphExploreAssembler.typeAllowed(a, Set.of("CONCEPT")));
        assertFalse(KbGraphExploreAssembler.typeAllowed(a, Set.of("PERSON")));
        GraphRelation r = new GraphRelation();
        r.setPredicate("依赖");
        r.setKeywords("依赖");
        assertTrue(KbGraphExploreAssembler.relationAllowed(r, Set.of("依赖")));
    }

    private static GraphEntity ent(String name, int sources)
    {
        GraphEntity e = new GraphEntity();
        e.setName(name);
        e.setType("CONCEPT");
        List<Long> ids = new java.util.ArrayList<>();
        for (int i = 0; i < sources; i++) ids.add((long) i);
        e.setSourceIds(ids);
        return e;
    }
}
