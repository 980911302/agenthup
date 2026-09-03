package com.ruoyi.system.kb.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;

/**
 * 进程内检索观测：次数、延迟、降级、失败（KB-GR-13）。
 * <p>不依赖外部监控；管理台可拉取快照。
 */
@Component
public class KbSearchMetrics
{
    private final ConcurrentHashMap<String, ModeStats> byMode = new ConcurrentHashMap<>();
    private final LongAdder total = new LongAdder();
    private final LongAdder degraded = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final AtomicLong startedAt = new AtomicLong(System.currentTimeMillis());

    public void record(String mode, long latencyMs, boolean degradedFlag, boolean failedFlag)
    {
        String key = mode == null ? "unknown" : mode;
        ModeStats s = byMode.computeIfAbsent(key, k -> new ModeStats());
        s.count.increment();
        s.latencySumMs.add(Math.max(0, latencyMs));
        s.latencyMaxMs.accumulateAndGet(latencyMs, Math::max);
        // 简单水库采样 p95
        s.offerLatency(latencyMs);
        total.increment();
        if (degradedFlag)
        {
            s.degraded.increment();
            degraded.increment();
        }
        if (failedFlag)
        {
            s.failed.increment();
            failed.increment();
        }
    }

    public Map<String, Object> snapshot()
    {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("startedAt", startedAt.get());
        out.put("uptimeMs", System.currentTimeMillis() - startedAt.get());
        out.put("total", total.sum());
        out.put("degraded", degraded.sum());
        out.put("failed", failed.sum());
        double degRate = total.sum() == 0 ? 0.0 : degraded.sum() / (double) total.sum();
        out.put("degradeRate", degRate);
        Map<String, Object> modes = new LinkedHashMap<>();
        for (Map.Entry<String, ModeStats> e : byMode.entrySet())
        {
            modes.put(e.getKey(), e.getValue().toMap());
        }
        out.put("modes", modes);
        return out;
    }

    public void reset()
    {
        byMode.clear();
        total.reset();
        degraded.reset();
        failed.reset();
        startedAt.set(System.currentTimeMillis());
    }

    static final class ModeStats
    {
        final LongAdder count = new LongAdder();
        final LongAdder latencySumMs = new LongAdder();
        final AtomicLong latencyMaxMs = new AtomicLong(0);
        final LongAdder degraded = new LongAdder();
        final LongAdder failed = new LongAdder();
        private final Object lock = new Object();
        private final long[] ring = new long[256];
        private int ringPos;
        private int ringSize;

        void offerLatency(long ms)
        {
            synchronized (lock)
            {
                ring[ringPos] = ms;
                ringPos = (ringPos + 1) % ring.length;
                if (ringSize < ring.length)
                {
                    ringSize++;
                }
            }
        }

        Map<String, Object> toMap()
        {
            Map<String, Object> m = new LinkedHashMap<>();
            long c = count.sum();
            m.put("count", c);
            m.put("avgLatencyMs", c == 0 ? 0.0 : latencySumMs.sum() / (double) c);
            m.put("maxLatencyMs", latencyMaxMs.get());
            m.put("p95LatencyMs", p95());
            m.put("degraded", degraded.sum());
            m.put("failed", failed.sum());
            return m;
        }

        private long p95()
        {
            long[] copy;
            int n;
            synchronized (lock)
            {
                n = ringSize;
                if (n == 0)
                {
                    return 0;
                }
                copy = new long[n];
                for (int i = 0; i < n; i++)
                {
                    int idx = (ringPos - n + i + ring.length) % ring.length;
                    copy[i] = ring[idx];
                }
            }
            java.util.Arrays.sort(copy);
            int i = Math.min(n - 1, (int) Math.ceil(n * 0.95) - 1);
            return copy[Math.max(0, i)];
        }
    }
}
