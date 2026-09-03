package com.ruoyi.system.ai.memory.longterm;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import com.ruoyi.system.ai.memory.TokenEstimator;

/**
 * 记忆注入的 token 硬顶计算(读侧 spec §7.3)。
 *
 * <p>检索命中的记忆按相似度排序后逐条累加,超出 {@code maxInjectTokens} 即截断:
 * <ul>
 *   <li>头部(提示语 + 边界)是固定开销,先扣掉,预算只花在记忆正文条目上;</li>
 *   <li>条目是原子:已进入预算的条目不因后续条目超限而丢弃(避免内容不连贯);</li>
 *   <li>以 {@link TokenEstimator} 估算(cl100k 对中文偏保守,方向安全)。</li>
 * </ul>
 *
 * <p>纯计算、不依赖 Spring,便于单测锁截断行为。
 */
@Component
public class MemoryInjectionBudget
{
    private final TokenEstimator tokenEstimator;

    public MemoryInjectionBudget(TokenEstimator tokenEstimator)
    {
        this.tokenEstimator = tokenEstimator;
    }

    /**
     * 在 token 预算内保留尽量多的注入文本条目。
     *
     * @param headerTokens 固定头部(提示语 + 边界标签)的 token 数;超预算时整体放弃注入
     * @param maxTokens    注入总硬顶(token)
     * @param entries      已按相似度从高到低排序的注入条目文本(每条已含前缀如 {@code - [fact] })
     * @return 预算内能装下的条目;头部自身超预算返回空列表
     */
    public List<String> cap(int headerTokens, int maxTokens, List<String> entries)
    {
        if (maxTokens <= 0 || headerTokens >= maxTokens)
        {
            return List.of();
        }
        int budget = maxTokens - headerTokens;
        List<String> kept = new ArrayList<>();
        int used = 0;
        for (String entry : entries)
        {
            int cost = tokenEstimator.estimate(entry);
            if (used + cost > budget)
            {
                break; // 后续条目相似度更低,装不下的直接截断(spec §7.3:按相似度截断)
            }
            kept.add(entry);
            used += cost;
        }
        return kept;
    }
}
