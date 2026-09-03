package com.ruoyi.system.ai.memory.longterm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * 记忆图谱未启用时的空实现(照 {@code NoOpKbGraphStore} 范式)。
 *
 * <p>第一版只做向量通道,图谱是预留接口。读侧链路(每轮向量检索注入)一次都不会查到图,
 * 故这里 isAvailable()=false,上层据此跳过一切图相关逻辑。
 */
@Component
@ConditionalOnMissingBean(name = "pgMemoryGraphStore")
public class NoOpMemoryGraphStore implements MemoryGraphStore
{
    @Override
    public boolean isAvailable()
    {
        return false;
    }
}
