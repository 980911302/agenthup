package com.ruoyi.system.ai;

/**
 * 渠道配置发生变更(修改/删除)。
 *
 * <p>用事件而不是让 {@code AiChannelServiceImpl} 直接依赖 {@link ChatModelFactory}：
 * 工厂本身要注入 {@code IAiChannelService} 去查渠道，反向直连就构成循环依赖，
 * 而 Spring Boot 默认禁止循环引用，会直接启动失败。</p>
 *
 * @param channelId 变更的渠道；为 null 表示所有渠道都需要失效
 */
public record AiChannelChangedEvent(Long channelId)
{
}
