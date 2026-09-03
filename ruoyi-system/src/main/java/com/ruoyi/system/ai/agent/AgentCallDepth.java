package com.ruoyi.system.ai.agent;

import com.ruoyi.common.exception.ServiceException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 智能体递归深度与环路保护(§5.4)。
 * <p>agent-as-tool 不引入 Graph,最坏情况 A 调 B、B 调 A 无限递归,必须自己防。
 * <p>深度上限 {@link #MAX_DEPTH}。装配子 agent 工具时若 {@link #isMaxed()} 为真就不再挂子 agent
 * (模型看不到自然不会调,比抛异常友好);异常仅作兜底。
 * <p>本类不可变,每次 {@link #enter(Long)} 返回新实例,便于在调用链上下文传递。
 */
public final class AgentCallDepth {

    private static final int MAX_DEPTH = 3;

    private final List<Long> chain;

    private AgentCallDepth(List<Long> chain) {
        this.chain = chain;
    }

    /** 顶层 agent 的初始调用深度,chain 为空。 */
    public static AgentCallDepth root() {
        return new AgentCallDepth(List.of());
    }

    /**
     * 进入下一层子 agent。
     *
     * @param agentId 子智能体 ID
     * @return 新的调用深度(链追加了 agentId)
     * @throws ServiceException 已达深度上限或检测到环路
     */
    public AgentCallDepth enter(Long agentId) {
        if (chain.size() >= MAX_DEPTH) {
            throw new ServiceException("智能体调用层级超过上限(" + MAX_DEPTH + ")");
        }
        if (chain.contains(agentId)) {
            throw new ServiceException("检测到智能体循环调用: " + chain + " -> " + agentId);
        }
        List<Long> next = new ArrayList<>(chain);
        next.add(agentId);
        return new AgentCallDepth(Collections.unmodifiableList(next));
    }

    /** 深度已达上限,装配时应跳过子 agent 工具。 */
    public boolean isMaxed() {
        return chain.size() >= MAX_DEPTH;
    }

    /** 当前调用链,用于异常信息与日志。 */
    public List<Long> chain() {
        return chain;
    }
}
