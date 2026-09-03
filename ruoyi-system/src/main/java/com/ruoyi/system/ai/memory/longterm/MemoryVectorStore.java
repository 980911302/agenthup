package com.ruoyi.system.ai.memory.longterm;

import java.util.List;

/**
 * 记忆向量检索抽象(mem_vector_{dim} 表)。
 *
 * <p>租户键是第一参数;分层通过 {@code searchLayered(userId, agentId, ...)}
 * 一次 SQL 查两层({@code agent_id in (0, ?)}),不做两次查询再手工合并。
 *
 * <p>第一版生产实现 {@link PgMemoryVectorStore};无向量基础设施时理论上可给 NoOp,
 * 但检索注入是读侧主通道,降级语义由上层(不注入)保证。
 */
public interface MemoryVectorStore
{
    /** 写一条向量。agentId=0 表示用户层。 */
    void upsert(MemoryTenant tenant, Long memoryId, float[] embedding);

    /** 分层检索:同查用户层与该 agent 层,合并后按相似度统一排序取 topK。 */
    List<MemoryVectorHit> searchLayered(Long userId, Long agentId,
                                        float[] query, int topK, double minScore);

    /** 删除指定记忆的向量。租户键是第一参数 —— 跨租户删除在 SQL 层面就不可能发生。 */
    void delete(MemoryTenant tenant, List<Long> memoryIds);

    /** 合规清理:抹掉该用户全部层的向量。 */
    void deleteByUser(Long userId);
}
