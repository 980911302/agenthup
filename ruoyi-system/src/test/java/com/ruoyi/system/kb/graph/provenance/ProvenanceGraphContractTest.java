package com.ruoyi.system.kb.graph.provenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.system.kb.graph.GraphEntity;
import com.ruoyi.system.kb.graph.GraphRelation;
import com.ruoyi.system.kb.graph.KbGraphConstants;

/**
 * 证据血统合约：重建/删除/共享/超过 10 来源/并发 generation。
 */
class ProvenanceGraphContractTest
{
    private InMemoryProvenanceGraphStore store;

    @BeforeEach
    void setUp()
    {
        store = new InMemoryProvenanceGraphStore();
    }

    @Test
    void sharedEntityRebuildAKeepsB()
    {
        Long kb = 1L;
        writeDoc(kb, 10L, 1L, 100L, "Alpha", "Beta", "works_with", 1001L);
        writeDoc(kb, 20L, 1L, 200L, "Alpha", "Beta", "works_with", 2001L);

        // Alpha+Beta = 2 entities
        assertEquals(2, store.countEntities(kb));
        assertEquals(1, store.countRelations(kb));
        assertEquals(1, store.countRelationEvidences(kb, 10L));
        assertEquals(1, store.countRelationEvidences(kb, 20L));

        // 重建 A：新 chunk / 新 generation
        writeDoc(kb, 10L, 2L, 101L, "Alpha", "Beta", "works_with", 1002L);

        assertEquals(1, store.countRelationEvidences(kb, 10L));
        assertEquals(1, store.countRelationEvidences(kb, 20L));
        assertEquals(2, store.countEntities(kb));
        assertEquals(1, store.countRelations(kb));
        // B 的 chunk 仍在 lineage
        assertTrue(store.entityLineageSize(kb, "Alpha") >= 2);
    }

    @Test
    void deleteAThenBCleansSharedAndOrphans()
    {
        Long kb = 2L;
        writeDoc(kb, 10L, 1L, 100L, "Shared", "OtherA", "rel", 1001L);
        writeDoc(kb, 20L, 1L, 200L, "Shared", "OtherB", "rel", 2001L);

        store.deleteDocumentEvidence(kb, 10L);
        assertEquals(0, store.countRelationEvidences(kb, 10L));
        assertEquals(1, store.countRelationEvidences(kb, 20L));
        assertTrue(store.findEntitiesByNames(kb, List.of("Shared")).size() == 1);
        assertTrue(store.findEntitiesByNames(kb, List.of("OtherB")).size() == 1);
        assertTrue(store.findEntitiesByNames(kb, List.of("OtherA")).isEmpty());

        store.deleteDocumentEvidence(kb, 20L);
        assertEquals(0, store.countEntities(kb));
        assertEquals(0, store.countRelations(kb));
        assertEquals(0, store.countRelationEvidences(kb, null));
    }

    @Test
    void moreThanTenSourcesDeleteStillCorrect()
    {
        Long kb = 3L;
        // 12 个文档引用同一实体对
        for (int i = 1; i <= 12; i++)
        {
            writeDoc(kb, (long) i, 1L, 1000L + i, "Hub", "Spoke", "links", 10_000L + i);
        }
        assertEquals(2, store.countEntities(kb));
        assertEquals(12, store.countRelationEvidences(kb, null));
        // 展示缓存封顶 10，完整血统 > 10
        GraphEntity hub = store.findEntitiesByNames(kb, List.of("Hub")).get(0);
        assertEquals(KbGraphConstants.MAX_SOURCE_IDS, hub.getSourceIds().size());
        assertTrue(store.entityLineageSize(kb, "Hub") >= 12);

        store.deleteDocumentEvidence(kb, 1L);
        assertEquals(11, store.countRelationEvidences(kb, null));
        assertEquals(2, store.countEntities(kb));

        for (long doc = 2; doc <= 12; doc++)
        {
            store.deleteDocumentEvidence(kb, doc);
        }
        assertEquals(0, store.countEntities(kb));
        assertEquals(0, store.countRelations(kb));
    }

    @Test
    void threeRebuildsConverge()
    {
        Long kb = 4L;
        Long doc = 40L;
        for (int gen = 1; gen <= 3; gen++)
        {
            writeDoc(kb, doc, gen, 400L + gen, "E1", "E2", "r", 4000L + gen);
        }
        assertEquals(1, store.countTextUnits(kb, doc));
        assertEquals(1, store.countRelationEvidences(kb, doc));
        assertEquals(2, store.countEntities(kb));
        assertEquals(1, store.countRelations(kb));
        // 仅最后 gen 的 chunk 在 lineage
        assertEquals(1, store.entityLineageSize(kb, "E1"));
    }

    @Test
    void staleGenerationRejectedBySupportLogic()
    {
        // 模拟 active gen=2 时 gen=1 不得覆盖
        assertTrue(GraphGenerationSupport.isStaleGeneration(2L, 1L));
        assertTrue(GraphGenerationSupport.isStaleGeneration(2L, 2L));
        assertFalse(GraphGenerationSupport.isStaleGeneration(2L, 3L));
        assertFalse(GraphGenerationSupport.canCommit(99L, 2L, 88L, 2L));
        assertTrue(GraphGenerationSupport.canCommit(99L, 2L, 99L, 2L));
    }

    @Test
    void concurrentReplaceLastWriterWinsWithoutDuplication() throws Exception
    {
        Long kb = 5L;
        Long doc = 50L;
        InMemoryProvenanceGraphStore s = store;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int g = 1; g <= 8; g++)
        {
            final int gen = g;
            futures.add(pool.submit(() -> {
                try
                {
                    start.await();
                    writeDoc(s, kb, doc, gen, 500L + gen, "X", "Y", "r", 5000L + gen);
                }
                catch (Exception e)
                {
                    errors.incrementAndGet();
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures)
        {
            f.get();
        }
        pool.shutdownNow();
        assertEquals(0, errors.get());
        // 同一 doc 只应有 1 份 TextUnit / 1 份证据（最后一次 replace 覆盖）
        assertEquals(1, s.countTextUnits(kb, doc));
        assertEquals(1, s.countRelationEvidences(kb, doc));
        assertEquals(2, s.countEntities(kb));
    }

    @Test
    void generationClaimSerializesActiveRun()
    {
        AtomicReference<Long> activeRun = new AtomicReference<>(null);
        AtomicReference<Long> activeGen = new AtomicReference<>(0L);

        long g1 = GraphGenerationSupport.nextGeneration(activeGen.get());
        long run1 = 1001L;
        activeRun.set(run1);
        activeGen.set(g1);

        long g2 = GraphGenerationSupport.nextGeneration(activeGen.get());
        long run2 = 1002L;
        activeRun.set(run2);
        activeGen.set(g2);

        // 旧 run1 不能提交
        assertFalse(GraphGenerationSupport.canCommit(activeRun.get(), activeGen.get(), run1, g1));
        assertTrue(GraphGenerationSupport.canCommit(activeRun.get(), activeGen.get(), run2, g2));
    }

    private void writeDoc(Long kb, Long doc, long gen, long runId,
        String src, String tgt, String pred, long chunkId)
    {
        writeDoc(store, kb, doc, gen, runId, src, tgt, pred, chunkId);
    }

    private static void writeDoc(InMemoryProvenanceGraphStore store, Long kb, Long doc, long gen,
        long runId, String src, String tgt, String pred, long chunkId)
    {
        GraphWriteBundle b = new GraphWriteBundle();
        b.setKbId(kb);
        b.setDocId(doc);
        b.setGeneration(gen);
        b.setRunId(runId);

        GraphTextUnit tu = new GraphTextUnit();
        tu.setChunkId(chunkId);
        tu.setContentHash("h" + chunkId);
        tu.setContent(src + " " + pred + " " + tgt);
        b.getTextUnits().add(tu);

        GraphEntity e1 = new GraphEntity();
        e1.setName(src);
        e1.setType("CONCEPT");
        e1.setSourceIds(List.of(chunkId));
        GraphEntity e2 = new GraphEntity();
        e2.setName(tgt);
        e2.setType("CONCEPT");
        e2.setSourceIds(List.of(chunkId));
        b.getEntities().add(e1);
        b.getEntities().add(e2);

        GraphRelation r = new GraphRelation();
        r.setSourceName(src);
        r.setTargetName(tgt);
        r.setKeywords(pred);
        r.setDescription(src + " " + pred + " " + tgt);
        r.setSourceIds(List.of(chunkId));
        r.setWeight(1.0);
        b.getRelations().add(r);

        GraphRelationEvidence ev = new GraphRelationEvidence();
        ev.setSourceEntityKey(src);
        ev.setTargetEntityKey(tgt);
        ev.setPredicate(pred);
        ev.setDescription(r.getDescription());
        ev.setTextUnitChunkId(chunkId);
        b.getEvidences().add(ev);

        store.replaceDocumentEvidence(b);
    }
}
