package com.ruoyi.system.kb.graph;

import java.util.List;
import com.ruoyi.system.kb.graph.provenance.GraphWriteBundle;

/**
 * 知识图谱存储抽象。
 * <p>生产实现 {@link Neo4jKbGraphStore};Neo4j 未启用时走 {@link NoOpKbGraphStore},
 * 向量检索不受影响。
 * <p>血统 v2：优先 {@link #replaceDocumentEvidence}/{@link #deleteDocumentEvidence}；
 * {@link #removeChunkRefs} 仅作兼容回退。
 */
public interface KbGraphStore
{
    boolean isAvailable();

    void upsertEntities(Long kbId, List<GraphEntity> entities);

    void upsertRelations(Long kbId, List<GraphRelation> relations);

    List<GraphEntity> findEntitiesByNames(Long kbId, List<String> names);

    /**
     * 全文/关键词匹配实体名。
     */
    List<GraphEntity> searchEntities(Long kbId, String query, int limit);

    /**
     * 取实体 1~n 跳邻居(含自身)。
     */
    List<GraphEntity> neighbors(Long kbId, String name, int hops, int limit);

    /**
     * 端点均在给定实体名集合内的关系（探索子图用，limit 封顶）。
     */
    default List<GraphRelation> relationsAmong(Long kbId, List<String> names, int limit)
    {
        if (kbId == null || names == null || names.isEmpty() || limit <= 0)
        {
            return List.of();
        }
        java.util.Set<String> set = new java.util.HashSet<>();
        for (String n : names)
        {
            if (n != null && !n.isBlank())
            {
                set.add(n);
            }
        }
        if (set.isEmpty())
        {
            return List.of();
        }
        List<GraphRelation> all = listRelations(kbId, Math.min(limit * 4, 500));
        List<GraphRelation> out = new java.util.ArrayList<>();
        for (GraphRelation r : all)
        {
            if (r == null)
            {
                continue;
            }
            if (set.contains(r.getSourceName()) && set.contains(r.getTargetName()))
            {
                out.add(r);
                if (out.size() >= limit)
                {
                    break;
                }
            }
        }
        return out;
    }

    /**
     * 从实体/关系的 sourceIds 中移除 chunk 引用;sourceIds 空则 DETACH DELETE。
     * <p>兼容路径；删除文档请优先 {@link #deleteDocumentEvidence}。
     */
    void removeChunkRefs(Long kbId, List<Long> chunkIds);

    /**
     * 按文档替换证据图：先删该 doc 旧 TextUnit/RelationEvidence，再写入新证据并重算聚合。
     * 清理失败必须抛错，调用方不得继续叠加写。
     */
    default void replaceDocumentEvidence(GraphWriteBundle bundle)
    {
        if (bundle == null)
        {
            return;
        }
        // 默认降级：旧路径（无证据节点）
        upsertEntities(bundle.getKbId(), bundle.getEntities());
        upsertRelations(bundle.getKbId(), bundle.getRelations());
    }

    /**
     * 删除文档全部证据并重算共享实体/关系；无证据的孤儿节点删除。
     */
    default void deleteDocumentEvidence(Long kbId, Long docId)
    {
        // 默认空：由实现覆盖
    }

    void deleteByKb(Long kbId);

    /**
     * 列出知识库下实体(管理台分页用,limit 封顶)。
     */
    List<GraphEntity> listEntities(Long kbId, int limit);

    /**
     * 按 chunk 证据反查实体（单文件子图用）。默认实现退回 list + 内存过滤。
     */
    default List<GraphEntity> listEntitiesBySourceChunks(Long kbId, java.util.Collection<Long> chunkIds, int limit)
    {
        if (kbId == null || chunkIds == null || chunkIds.isEmpty() || limit <= 0)
        {
            return List.of();
        }
        java.util.Set<Long> want = new java.util.HashSet<>();
        for (Long id : chunkIds)
        {
            if (id != null)
            {
                want.add(id);
            }
        }
        if (want.isEmpty())
        {
            return List.of();
        }
        List<GraphEntity> all = listEntities(kbId, Math.min(Math.max(limit * 8, 200), 500));
        List<GraphEntity> out = new java.util.ArrayList<>();
        for (GraphEntity e : all)
        {
            if (e == null || e.getSourceIds() == null)
            {
                continue;
            }
            for (Long sid : e.getSourceIds())
            {
                if (sid != null && want.contains(sid))
                {
                    out.add(e);
                    break;
                }
            }
            if (out.size() >= limit)
            {
                break;
            }
        }
        return out;
    }

    /**
     * 列出知识库下关系。
     */
    List<GraphRelation> listRelations(Long kbId, int limit);

    int countEntities(Long kbId);

    int countRelations(Long kbId);

    /** 证据节点数（测试/观测；默认 0） */
    default int countTextUnits(Long kbId, Long docId)
    {
        return 0;
    }

    default int countRelationEvidences(Long kbId, Long docId)
    {
        return 0;
    }
}
