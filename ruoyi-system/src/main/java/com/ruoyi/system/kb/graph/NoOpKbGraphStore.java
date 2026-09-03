package com.ruoyi.system.kb.graph;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Neo4j 未启用时的空实现:图操作 no-op,检索自动退回向量。
 */
@Component
@ConditionalOnMissingBean(name = "neo4jKbGraphStore")
public class NoOpKbGraphStore implements KbGraphStore
{
    @Override
    public boolean isAvailable()
    {
        return false;
    }

    @Override
    public void upsertEntities(Long kbId, List<GraphEntity> entities)
    {
    }

    @Override
    public void upsertRelations(Long kbId, List<GraphRelation> relations)
    {
    }

    @Override
    public List<GraphEntity> findEntitiesByNames(Long kbId, List<String> names)
    {
        return List.of();
    }

    @Override
    public List<GraphEntity> searchEntities(Long kbId, String query, int limit)
    {
        return List.of();
    }

    @Override
    public List<GraphEntity> neighbors(Long kbId, String name, int hops, int limit)
    {
        return List.of();
    }

    @Override
    public void removeChunkRefs(Long kbId, List<Long> chunkIds)
    {
    }

    @Override
    public void deleteByKb(Long kbId)
    {
    }

    @Override
    public List<GraphEntity> listEntities(Long kbId, int limit)
    {
        return List.of();
    }

    @Override
    public List<GraphRelation> listRelations(Long kbId, int limit)
    {
        return List.of();
    }

    @Override
    public int countEntities(Long kbId)
    {
        return 0;
    }

    @Override
    public int countRelations(Long kbId)
    {
        return 0;
    }
}
