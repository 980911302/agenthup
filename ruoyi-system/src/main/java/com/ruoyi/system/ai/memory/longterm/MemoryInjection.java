package com.ruoyi.system.ai.memory.longterm;

import java.util.List;
import java.util.Optional;

/**
 * 记忆注入结果(读侧 spec §7)。
 *
 * @param injectedText 注入文本(<b>只拼进发给模型的那份 user 消息</b>,落库仍用用户原话);
 *                     为空表示本轮不注入
 * @param hitMemoryIds 实际注入的记忆 id,用于命中回写 hit_count / last_hit_time(异步)
 */
public record MemoryInjection(Optional<String> injectedText, List<Long> hitMemoryIds)
{
    /** 本轮不注入的哨兵值 */
    public static MemoryInjection none()
    {
        return new MemoryInjection(Optional.empty(), List.of());
    }

    public boolean isInjected()
    {
        return injectedText != null && injectedText.isPresent();
    }
}
