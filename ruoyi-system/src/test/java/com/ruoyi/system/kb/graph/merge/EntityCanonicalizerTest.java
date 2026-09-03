package com.ruoyi.system.kb.graph.merge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import com.ruoyi.system.kb.graph.GraphEntity;
import com.ruoyi.system.kb.graph.GraphRelation;
import com.ruoyi.system.kb.graph.merge.EntityCanonicalizer.Identity;

class EntityCanonicalizerTest
{
    @Test
    void nfkcAndAliasResolve()
    {
        assertEquals("postgresql", EntityNormalizer.normalizeName("  PostgreSQL  "));
        assertEquals("PostgreSQL", EntityAliasTable.resolveDisplayName("pg"));
        assertEquals("PostgreSQL", EntityAliasTable.resolveDisplayName("Postgres"));
        Identity a = EntityCanonicalizer.identityOf(1L, "pg", "技术", null);
        Identity b = EntityCanonicalizer.identityOf(1L, "PostgreSQL", "技术", null);
        assertEquals(a.entityKey, b.entityKey);
        assertEquals(a.candidateKey, b.candidateKey);
    }

    @Test
    void sameNameDifferentTypeDoNotShareEntityKey()
    {
        Identity org = EntityCanonicalizer.identityOf(1L, "苹果", "组织", null);
        Identity fruit = EntityCanonicalizer.identityOf(1L, "苹果", "水果", null);
        assertNotEquals(org.entityKey, fruit.entityKey);
        assertNotEquals(org.candidateKey, fruit.candidateKey);
    }

    @Test
    void senseDiscriminatorAllowsHomonyms()
    {
        Identity a = EntityCanonicalizer.identityOf(1L, "Mercury", "概念", "planet");
        Identity b = EntityCanonicalizer.identityOf(1L, "Mercury", "概念", "element");
        assertNotEquals(a.entityKey, b.entityKey);
        assertEquals(a.candidateKey, b.candidateKey);
    }

    @Test
    void symmetricRelationNormalizesEndpointOrder()
    {
        String k1 = RelationCanonicalizer.relationKey(1L, "B", "相关", "A");
        String k2 = RelationCanonicalizer.relationKey(1L, "A", "相关", "B");
        assertEquals(k1, k2);
        assertTrue(RelationCanonicalizer.isSymmetric("相关"));
        assertFalse(RelationCanonicalizer.isSymmetric("属于"));
    }

    @Test
    void directedRelationKeepsOrder()
    {
        String k1 = RelationCanonicalizer.relationKey(1L, "张伟", "负责", "图谱");
        String k2 = RelationCanonicalizer.relationKey(1L, "图谱", "负责", "张伟");
        assertNotEquals(k1, k2);
    }

    @Test
    void aliasEndpointsShareRelationKey()
    {
        GraphRelation t1 = new GraphRelation();
        t1.setSourceName("pg");
        t1.setTargetName("Neo4j");
        t1.setKeywords("相关");
        RelationCanonicalizer.applyTo(t1, 9L);
        GraphRelation t2 = new GraphRelation();
        t2.setSourceName("Neo4j");
        t2.setTargetName("PostgreSQL");
        t2.setKeywords("相关");
        RelationCanonicalizer.applyTo(t2, 9L);
        assertEquals(t1.getRelationKey(), t2.getRelationKey());
    }

    @Test
    void weightUsesEvidenceCount()
    {
        assertEquals(3.0, RelationCanonicalizer.weightFromEvidence(2, 0), 1e-9);
        assertTrue(RelationCanonicalizer.weightFromEvidence(5, 2)
            > RelationCanonicalizer.weightFromEvidence(1, 2));
    }

    @Test
    void stableEntityIdDeterministic()
    {
        String id1 = EntityCanonicalizer.stableEntityId("1|postgresql|技术|");
        String id2 = EntityCanonicalizer.stableEntityId("1|postgresql|技术|");
        assertEquals(id1, id2);
    }

    @Test
    void applyToEntitySetsKeys()
    {
        GraphEntity e = new GraphEntity();
        e.setName("pg");
        e.setType("技术");
        EntityCanonicalizer.applyTo(e, 3L);
        assertEquals("PostgreSQL", e.getName());
        assertTrue(e.getEntityKey().contains("postgresql"));
        assertFalse(e.getCandidateKey().isBlank());
    }
}
