package com.ruoyi.system.ai.memory.longterm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.ruoyi.system.kb.vector.FloatVectorCodec;

/**
 * 内存暴力向量检索(单测/兜底用)。
 * <p>生产由 {@link PgMemoryVectorStore} 接管(依赖 pgvector)。本类不依赖任何数据库,
 * 直接按内存索引做余弦检索,对齐 {@code InMemoryKbVectorStore} 的定位。
 */
@Component
public class InMemoryMemoryVectorStore implements MemoryVectorStore
{
    private static final Logger log = LoggerFactory.getLogger(InMemoryMemoryVectorStore.class);

    /** tenant(userId:agentId) -> (memoryId -> embedding) */
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, float[]>> index = new ConcurrentHashMap<>();

    private static String key(Long userId, Long agentId)
    {
        return userId + ":" + agentId;
    }

    /**
     * 测试用:该 memoryId 是否已写入向量(不限层)。
     *
     * <p>存在的理由:读侧是纯向量检索,台账有行而向量缺失 = 这条记忆永远查不出来。
     * 这种缺陷断言台账是发现不了的,必须直接查向量侧。
     */
    public boolean hasVector(Long memoryId)
    {
        if (memoryId == null)
        {
            return false;
        }
        return index.values().stream().anyMatch(m -> m.containsKey(memoryId));
    }

    @Override
    public void upsert(MemoryTenant tenant, Long memoryId, float[] embedding)
    {
        if (tenant == null || memoryId == null || embedding == null || embedding.length == 0)
        {
            return;
        }
        index.computeIfAbsent(key(tenant.userId(), tenant.agentId()), k -> new ConcurrentHashMap<>())
                .put(memoryId, embedding);
    }

    @Override
    public List<MemoryVectorHit> searchLayered(Long userId, Long agentId,
                                               float[] query, int topK, double minScore)
    {
        if (userId == null || query == null || query.length == 0 || topK <= 0)
        {
            return List.of();
        }
        // 用户层 + 该 agent 层,一次收集
        List<MemoryVectorHit> hits = new ArrayList<>();
        addLayer(userId, 0L, query, minScore, hits);      // 用户层
        if (agentId != null && agentId != 0L)
        {
            addLayer(userId, agentId, query, minScore, hits);
        }
        hits.sort(Comparator.comparingDouble(MemoryVectorHit::score).reversed());
        return hits.size() > topK ? hits.subList(0, topK) : hits;
    }

    private void addLayer(Long userId, Long agentId, float[] query, double minScore, List<MemoryVectorHit> out)
    {
        ConcurrentHashMap<Long, float[]> layer = index.get(key(userId, agentId));
        if (layer == null)
        {
            return;
        }
        for (var e : layer.entrySet())
        {
            double score = FloatVectorCodec.cosine(query, e.getValue());
            if (score >= minScore)
            {
                out.add(new MemoryVectorHit(e.getKey(), score));
            }
        }
    }

    @Override
    public void delete(MemoryTenant tenant, List<Long> memoryIds)
    {
        if (tenant == null || memoryIds == null || memoryIds.isEmpty())
        {
            return;
        }
        var idSet = new java.util.HashSet<>(memoryIds);
        // 只删该租户层(跨租户删除在类型层面不可能)
        ConcurrentHashMap<Long, float[]> layer = index.get(key(tenant.userId(), tenant.agentId()));
        if (layer != null)
        {
            layer.keySet().removeIf(idSet::contains);
        }
    }

    @Override
    public void deleteByUser(Long userId)
    {
        if (userId == null)
        {
            return;
        }
        index.keySet().removeIf(k -> k.startsWith(userId + ":"));
    }
}
