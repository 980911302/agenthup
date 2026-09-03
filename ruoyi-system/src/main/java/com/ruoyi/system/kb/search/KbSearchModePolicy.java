package com.ruoyi.system.kb.search;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 检索模式灰度策略（KB-GR-13）。
 * <p>默认只开放 basic；其余模式需配置显式开启。auto 永不作为 default-mode。
 */
@Component
public class KbSearchModePolicy
{
    /** 逗号分隔：basic,local,hybrid,global,drift,auto */
    @Value("${ai.kb.search.rollout.enabled-modes:basic,local,hybrid}")
    private String enabledModesRaw;

    @Value("${ai.kb.search.rollout.default-mode:basic}")
    private String defaultModeRaw;

    @Value("${ai.kb.search.rollout.admin-debug-trace:true}")
    private boolean adminDebugTrace;

    @Value("${ai.kb.search.rollout.strip-debug-trace:true}")
    private boolean stripDebugTrace;

    @Value("${ai.kb.search.rollout.max-concurrent:32}")
    private int maxConcurrent;

    @Value("${ai.kb.search.rollout.per-mode-timeout-ms:60000}")
    private long perModeTimeoutMs;

    public KbSearchMode defaultMode()
    {
        KbSearchMode m = KbSearchMode.of(defaultModeRaw);
        // 硬规则：auto 不得作默认
        if (m == KbSearchMode.auto)
        {
            return KbSearchMode.basic;
        }
        m = KbSearchMode.normalize(m);
        if (!isEnabled(m))
        {
            return KbSearchMode.basic;
        }
        return m;
    }

    public boolean isEnabled(KbSearchMode mode)
    {
        KbSearchMode m = KbSearchMode.normalize(mode);
        return enabledSet().contains(m);
    }

    /**
     * 请求模式若不在灰度白名单，降到 default（通常 basic）。
     */
    public KbSearchMode resolve(KbSearchMode requested)
    {
        if (requested == null)
        {
            return defaultMode();
        }
        KbSearchMode m = KbSearchMode.normalize(requested);
        if (isEnabled(m))
        {
            return m;
        }
        return defaultMode();
    }

    public boolean isAdminDebugTrace()
    {
        return adminDebugTrace;
    }

    public boolean isStripDebugTrace()
    {
        return stripDebugTrace;
    }

    public int getMaxConcurrent()
    {
        return Math.max(1, maxConcurrent);
    }

    public long getPerModeTimeoutMs()
    {
        return Math.max(1000L, perModeTimeoutMs);
    }

    public Set<KbSearchMode> enabledSet()
    {
        EnumSet<KbSearchMode> set = EnumSet.noneOf(KbSearchMode.class);
        if (enabledModesRaw == null || enabledModesRaw.isBlank())
        {
            set.add(KbSearchMode.basic);
            return set;
        }
        for (String part : enabledModesRaw.split("[,\\s]+"))
        {
            if (part == null || part.isBlank())
            {
                continue;
            }
            KbSearchMode m = KbSearchMode.of(part.trim().toLowerCase(Locale.ROOT));
            m = KbSearchMode.normalize(m);
            set.add(m);
        }
        if (set.isEmpty())
        {
            set.add(KbSearchMode.basic);
        }
        // basic 永远可用（安全底线）
        set.add(KbSearchMode.basic);
        return set;
    }

    /** 供单测注入 */
    public void overrideForTest(String enabled, String defaultMode, boolean adminDebug, boolean strip)
    {
        this.enabledModesRaw = enabled;
        this.defaultModeRaw = defaultMode;
        this.adminDebugTrace = adminDebug;
        this.stripDebugTrace = strip;
    }
}
