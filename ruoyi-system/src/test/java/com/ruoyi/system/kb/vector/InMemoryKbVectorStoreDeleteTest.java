package com.ruoyi.system.kb.vector;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 覆盖删除文档时「按 chunkId 删向量」路径 ——
 * 对应 ai-kb-issues P0:先删 chunk 再靠子查询删向量会静默漏删。
 */
class InMemoryKbVectorStoreDeleteTest
{
    private InMemoryKbVectorStore store;

    @BeforeEach
    void setUp()
    {
        store = new InMemoryKbVectorStore();
    }

    @Test
    void deleteByChunkIds_removesVectorsEvenIfDocStillReferenced()
    {
        Long kbId = 1L;
        Long docId = 10L;
        KbChunkVector v1 = vec(101L, kbId, docId, new float[] { 1f, 0f, 0f }, "alpha");
        KbChunkVector v2 = vec(102L, kbId, docId, new float[] { 0f, 1f, 0f }, "beta");
        KbChunkVector keep = vec(201L, kbId, 20L, new float[] { 0f, 0f, 1f }, "keep");
        store.upsert(kbId, List.of(v1, v2, keep));

        // 模拟方案 B:先按 id 删向量,再(别处)删 chunk —— 不依赖 doc 行是否还在
        store.deleteByChunkIds(kbId, List.of(101L, 102L));

        List<KbSearchHit> hits = store.search(kbId, new float[] { 1f, 0f, 0f }, 5, -1.0);
        assertEquals(1, hits.size());
        assertEquals(201L, hits.get(0).getChunkId());
        assertEquals("keep", hits.get(0).getContent());
    }

    @Test
    void deleteByChunkIds_emptyOrNullIsNoOp()
    {
        Long kbId = 1L;
        store.upsert(kbId, List.of(vec(1L, kbId, 1L, new float[] { 1f, 0f }, "x")));
        store.deleteByChunkIds(kbId, null);
        store.deleteByChunkIds(kbId, List.of());
        assertEquals(1, store.search(kbId, new float[] { 1f, 0f }, 5, -1.0).size());
    }

    @Test
    void deleteByDoc_stillWorks()
    {
        Long kbId = 1L;
        store.upsert(kbId, List.of(
            vec(1L, kbId, 10L, new float[] { 1f, 0f }, "d10"),
            vec(2L, kbId, 20L, new float[] { 0f, 1f }, "d20")));
        store.deleteByDoc(kbId, 10L);
        List<KbSearchHit> hits = store.search(kbId, new float[] { 1f, 0f }, 5, -1.0);
        assertEquals(1, hits.size());
        assertEquals(2L, hits.get(0).getChunkId());
    }

    private static KbChunkVector vec(Long chunkId, Long kbId, Long docId, float[] emb, String content)
    {
        KbChunkVector v = new KbChunkVector();
        v.setChunkId(chunkId);
        v.setKbId(kbId);
        v.setDocId(docId);
        v.setEmbedding(emb);
        v.setContent(content);
        v.setDocName("doc-" + docId);
        v.setChunkIndex(0);
        return v;
    }
}
