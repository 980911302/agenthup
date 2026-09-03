package com.ruoyi.system.ai;

/**
 * 智能体装配相关的配置行发生变更(智能体/工具/技能/模型)。
 *
 * <p>与 {@link AiChannelChangedEvent} 同一动机:用事件而不是让 Service 直连
 * {@code AgentAssemblyCache},避免「缓存注入 Service 读」与「Service 调缓存失效」
 * 构成循环依赖(Spring Boot 默认禁止,会启动失败)。</p>
 *
 * @param kind 变更的配置类别
 * @param id 变更的行 id;MODEL 类别忽略 id(modelCode 本身可能被改,按整表失效)
 */
public record AiConfigChangedEvent(Kind kind, Long id)
{
    public enum Kind { AGENT, TOOL, SKILL, MODEL }
}
