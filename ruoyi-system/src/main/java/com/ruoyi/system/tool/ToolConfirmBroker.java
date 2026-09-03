package com.ruoyi.system.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 危险工具人工确认:工具线程阻塞等待,前端点确认后唤醒。
 *
 * <p>key 用 sessionId(同一会话同时只有一个活动 run)。confirmId 区分并行工具调用。
 * 超时默认拒绝,避免工具线程永久占坑。
 */
@Component
public class ToolConfirmBroker
{
    private static final Logger log = LoggerFactory.getLogger(ToolConfirmBroker.class);

    private final Map<String, Pending> pendings = new ConcurrentHashMap<>();

    @Value("${ai.chat.tool.confirm-timeout-seconds:120}")
    private long timeoutSeconds;

    public String newConfirmId()
    {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 阻塞等待用户确认。
     *
     * @return true=允许执行; false=拒绝或超时
     */
    public boolean await(String sessionId, String confirmId, String toolName)
    {
        if (sessionId == null || confirmId == null)
        {
            return false;
        }
        Pending pending = new Pending(sessionId, confirmId, toolName);
        pendings.put(confirmId, pending);
        try
        {
            long sec = timeoutSeconds > 0 ? timeoutSeconds : 120L;
            Boolean ok = pending.future.get(sec, TimeUnit.SECONDS);
            return Boolean.TRUE.equals(ok);
        }
        catch (TimeoutException e)
        {
            log.info("工具确认超时: session={} tool={} confirmId={}", sessionId, toolName, confirmId);
            return false;
        }
        catch (Exception e)
        {
            log.warn("工具确认等待失败: session={} tool={}: {}", sessionId, toolName, e.getMessage());
            return false;
        }
        finally
        {
            pendings.remove(confirmId);
        }
    }

    /**
     * 用户确认/拒绝。
     *
     * @return true 找到并完成了对应挂起;false 无此 confirm 或已过期
     */
    public boolean resolve(String confirmId, boolean approved)
    {
        if (confirmId == null || confirmId.isEmpty())
        {
            return false;
        }
        Pending pending = pendings.get(confirmId);
        if (pending == null)
        {
            return false;
        }
        return pending.future.complete(approved);
    }

    /** 会话结束时拒绝所有未决确认,避免 worker 挂死。 */
    public void cancelAll(String sessionId)
    {
        if (sessionId == null)
        {
            return;
        }
        for (Pending p : pendings.values())
        {
            if (sessionId.equals(p.sessionId))
            {
                p.future.complete(false);
            }
        }
    }

    private static final class Pending
    {
        private final String sessionId;
        private final String confirmId;
        private final String toolName;
        private final CompletableFuture<Boolean> future = new CompletableFuture<>();

        private Pending(String sessionId, String confirmId, String toolName)
        {
            this.sessionId = sessionId;
            this.confirmId = confirmId;
            this.toolName = toolName;
        }
    }
}
