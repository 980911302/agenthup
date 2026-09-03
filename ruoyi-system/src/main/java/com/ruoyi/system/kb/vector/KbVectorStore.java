package com.ruoyi.system.kb.vector;

import java.util.List;

/**
 * 向量存储抽象。
 * <p>第一期实现 {@link InMemoryKbVectorStore}:MySQL 存 BLOB,内存暴力余弦。
 * <p>天花板(必须写进注释):
 * <pre>
 * 30,000 chunk × 1024 维 × 4 byte ≈ 120 MB 内存
 * 暴力检索一次 ≈ 3000 万次乘加,Java 侧个位数毫秒
 * 部门级知识库(千份文档)够用;到 10 万 chunk 量级必须换外部向量库。
 * </pre>
 */
public interface KbVectorStore
{
    void upsert(Long kbId, List<KbChunkVector> vectors);

    List<KbSearchHit> search(Long kbId, float[] query, int topK, double minScore);

    void deleteByDoc(Long kbId, Long docId);

    /**
     * 按 chunkId 批量删向量。
     * <p>删除文档时请先查 chunkId 再调本方法,再删 kb_chunk ——
     * 不要依赖「子查询 kb_chunk」类 SQL 的执行顺序。
     */
    void deleteByChunkIds(Long kbId, List<Long> chunkIds);

    /** 使某知识库内存索引失效,下次检索时从 DB 重载(外部向量库可 no-op) */
    void invalidate(Long kbId);

    /** 删除知识库下全部向量(默认 no-op) */
    default void deleteByKb(Long kbId)
    {
    }
}
