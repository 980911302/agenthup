package com.ruoyi.system.ai.memory.longterm;

/**
 * 向量检索命中的记忆条目(仅携带检索所需的 id 与相似度,正文由调用方按 id 回查)。
 *
 * @param memoryId 记忆主键
 * @param score    余弦相似度(1 - 余弦距离)
 */
public record MemoryVectorHit(Long memoryId, double score)
{
}
