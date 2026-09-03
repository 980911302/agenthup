package com.ruoyi.system.ai.memory.longterm;

/**
 * 记忆图谱存储抽象(第一版不实现,装配 {@link NoOpMemoryGraphStore})。
 *
 * <p>未来开图时的约束(spec §4.3):
 * <ul>
 *   <li>标签 {@code :Memory} / {@code :MemEntity},<b>不复用</b>知识库的 {@code :Entity}
 *       —— 现网 {@code :Entity} 的唯一约束 (kbId, entityKey) 会跳过属性为 null 的节点,
 *       记忆实体混进去等于完全不受约束,重复节点无限堆积;</li>
 *   <li>复合唯一约束 {@code (agentId, userId, memoryId)};边也带 {@code agentId}/{@code userId};</li>
 *   <li>自建全文索引,不蹭 {@code entity_name_ft}。</li>
 * </ul>
 */
public interface MemoryGraphStore
{
    boolean isAvailable();
}
