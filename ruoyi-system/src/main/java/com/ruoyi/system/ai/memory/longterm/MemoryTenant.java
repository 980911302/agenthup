package com.ruoyi.system.ai.memory.longterm;

import java.util.Objects;

/**
 * 记忆分层租户键。
 *
 * <p>把「用户层 + 用户×agent 层」收进一个值对象:<b>userId 永远强制</b>,
 * {@code agentId=0} 表示用户层(跨该用户的所有 agent 共享)。构造即校验,
 * 不给「缺 userId」的非法组合留缝 —— 漏掉 user_id 等值条件,用户层就变成全平台共享,这是合规红线。
 *
 * <p>用哨兵 {@code 0} 而不是 {@code null} 表示用户层:{@code ai_agent} 的
 * {@code auto_increment=100},0 绝不会与真实 agent 冲突;且 NULL 在唯一索引与等值过滤里语义特殊。
 *
 * @param userId 用户ID,永不为 null
 * @param agentId agentID;null 或 0 表示用户层
 */
public record MemoryTenant(Long userId, Long agentId)
{
    /** 用户层哨兵:同一用户的全部 agent 共享 */
    public static final long USER_SCOPE = 0L;

    public MemoryTenant
    {
        Objects.requireNonNull(userId, "userId 不可为空 —— 跨用户隔离是红线");
        if (agentId == null)
        {
            agentId = USER_SCOPE;
        }
    }

    /** 用户层租户(跨该用户所有 agent 共享) */
    public static MemoryTenant ofUser(Long userId)
    {
        return new MemoryTenant(userId, USER_SCOPE);
    }

    /** 用户 × 指定 agent 的专属层 */
    public static MemoryTenant ofAgent(Long userId, Long agentId)
    {
        return new MemoryTenant(userId, agentId);
    }

    public boolean isUserScope()
    {
        return agentId == USER_SCOPE;
    }
}
