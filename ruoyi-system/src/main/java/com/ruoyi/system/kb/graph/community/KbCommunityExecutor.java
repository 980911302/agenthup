package com.ruoyi.system.kb.graph.community;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 按 kbId 单飞 + 防抖提交社区重建。
 */
@Component
public class KbCommunityExecutor
{
    private static final Logger log = LoggerFactory.getLogger(KbCommunityExecutor.class);

    @Autowired
    private KbCommunityService communityService;

    @Value("${ai.kb.graph.community.enabled:false}")
    private boolean communityEnabled;

    @Value("${ai.kb.graph.community.debounce-seconds:30}")
    private int debounceSeconds;

    private final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "kb-community-debounce");
        t.setDaemon(true);
        return t;
    });

    private final ConcurrentHashMap<Long, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicBoolean> running = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, String> latestVersion = new ConcurrentHashMap<>();

    public void scheduleRebuild(Long kbId, String graphVersion)
    {
        if (!communityEnabled || kbId == null)
        {
            return;
        }
        communityService.markDirty(kbId, graphVersion);
        latestVersion.put(kbId, graphVersion != null ? graphVersion : "");
        ScheduledFuture<?> old = pending.get(kbId);
        if (old != null)
        {
            old.cancel(false);
        }
        ScheduledFuture<?> fut = scheduler.schedule(() -> runNow(kbId),
            Math.max(1, debounceSeconds), TimeUnit.SECONDS);
        pending.put(kbId, fut);
        log.debug("社区重建已防抖排队 kbId={} delay={}s", kbId, debounceSeconds);
    }

    public void runNow(Long kbId)
    {
        if (!communityEnabled || kbId == null)
        {
            return;
        }
        AtomicBoolean flag = running.computeIfAbsent(kbId, k -> new AtomicBoolean(false));
        if (!flag.compareAndSet(false, true))
        {
            log.info("社区任务已在运行,跳过 kbId={}", kbId);
            return;
        }
        pending.remove(kbId);
        try
        {
            String gv = latestVersion.get(kbId);
            communityService.rebuild(kbId, gv);
        }
        catch (Exception e)
        {
            log.error("社区任务异常 kbId={}", kbId, e);
        }
        finally
        {
            flag.set(false);
        }
    }

    @PreDestroy
    public void shutdown()
    {
        scheduler.shutdownNow();
    }
}
