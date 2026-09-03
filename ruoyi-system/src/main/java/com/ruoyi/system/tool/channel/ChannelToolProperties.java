package com.ruoyi.system.tool.channel;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code ai.chat.tool.channel.*}。默认关闭，上线先走 allow-user-ids 灰度。
 */
@Component
public class ChannelToolProperties
{
    @Value("${ai.chat.tool.channel.enabled:false}")
    private boolean enabled;

    @Value("${ai.chat.tool.channel.timeout-seconds:120}")
    private long timeoutSeconds;

    /**
     * 挂起中的调用发现「本实例上已经没有客户端订阅这一轮」后,还愿意多等多久。
     * 侧边栏关掉时浏览器工具根本跑不起来,等满 timeout-seconds 只是把这一轮拖死;
     * 但也不能一断开就判死 —— 关掉再打开、重连、序号缺口恢复都会短暂离线,
     * 客户端重新订阅时 {@code ChannelToolBroker#redeliver} 会把请求补发一遍。
     * 负数关闭该判定,退回只看 timeout-seconds 的旧行为(0 视为未配置,取默认值)。
     */
    @Value("${ai.chat.tool.channel.disconnect-grace-seconds:30}")
    private long disconnectGraceSeconds;

    @Value("${ai.chat.tool.channel.max-tools-per-session:32}")
    private int maxToolsPerSession;

    @Value("${ai.chat.tool.channel.max-concurrent-calls:32}")
    private int maxConcurrentCalls;

    private List<Long> allowUserIds = List.of();

    @Value("${ai.chat.tool.channel.allow-user-ids:#{T(java.util.Collections).emptyList()}}")
    public void setAllowUserIds(List<Long> allowUserIds)
    {
        this.allowUserIds = allowUserIds == null ? List.of() : List.copyOf(allowUserIds);
    }

    public boolean enabled()
    {
        return enabled;
    }

    public long timeoutSeconds()
    {
        return timeoutSeconds > 0 ? timeoutSeconds : 120L;
    }

    /** 单测走 {@link #overrideForTest} 不设该值,退回默认 30 秒。 */
    public long disconnectGraceSeconds()
    {
        return disconnectGraceSeconds != 0 ? disconnectGraceSeconds : 30L;
    }

    public int maxToolsPerSession()
    {
        return maxToolsPerSession > 0 ? maxToolsPerSession : ChannelToolSchemas.DEFAULT_MAX_TOOLS;
    }

    public int maxConcurrentCalls()
    {
        return maxConcurrentCalls > 0 ? maxConcurrentCalls : 32;
    }

    public List<Long> allowUserIds()
    {
        return allowUserIds;
    }

    /** enabled 是前置；名单非空时只放行名单内 userId。 */
    public boolean allowedFor(Long userId)
    {
        if (!enabled)
        {
            return false;
        }
        if (allowUserIds == null || allowUserIds.isEmpty())
        {
            return true;
        }
        return userId != null && allowUserIds.contains(userId);
    }

    public void overrideForTest(boolean enabled, List<Long> allowUserIds, long timeoutSeconds,
                                int maxTools, int maxConcurrent)
    {
        this.enabled = enabled;
        this.allowUserIds = allowUserIds == null ? List.of() : List.copyOf(allowUserIds);
        this.timeoutSeconds = timeoutSeconds;
        this.maxToolsPerSession = maxTools;
        this.maxConcurrentCalls = maxConcurrent;
    }

    /** 负数表示关闭离线判定,单测靠它把行为钉在纯超时路径上。 */
    public void overrideDisconnectGraceForTest(long seconds)
    {
        this.disconnectGraceSeconds = seconds;
    }
}
