package com.ruoyi.system.kb.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 上线/灰度质量门禁检查（静态配置 + 运行时策略）。
 */
@Component
public class KbSearchQualityGate
{
    @Autowired
    private KbSearchModePolicy modePolicy;

    @Value("${ai.kb.search.drift.max-retrievals:12}")
    private int driftMaxRetrievals;

    @Value("${ai.kb.search.drift.max-tokens:8000}")
    private int driftMaxTokens;

    @Value("${ai.kb.search.drift.timeout-ms:30000}")
    private long driftTimeoutMs;

    @Value("${ai.kb.search.global.max-selected:12}")
    private int globalMaxSelected;

    @Value("${ai.kb.search.rollout.max-concurrent:32}")
    private int maxConcurrent;

    public GateReport evaluate()
    {
        GateReport report = new GateReport();
        // 1) basic 永远开启
        report.check("basic_always_enabled", modePolicy.isEnabled(KbSearchMode.basic),
            "basic 必须在 enabled-modes 中");
        // 2) auto 不作默认
        report.check("auto_not_default", modePolicy.defaultMode() != KbSearchMode.auto,
            "default-mode 不得为 auto");
        // 3) 各模式有成本/并发上限
        report.check("drift_budget", driftMaxRetrievals > 0 && driftMaxTokens > 0 && driftTimeoutMs > 0,
            "drift 必须配置 retrievals/tokens/timeout");
        report.check("global_budget", globalMaxSelected > 0 && globalMaxSelected <= 50,
            "global max-selected 应在 1~50");
        report.check("search_concurrency", maxConcurrent > 0,
            "rollout.max-concurrent 必须 > 0");
        // 4) 灰度顺序提示：若开了 drift/auto，建议 local/hybrid 已开
        Set<KbSearchMode> en = modePolicy.enabledSet();
        if (en.contains(KbSearchMode.drift) || en.contains(KbSearchMode.auto))
        {
            report.check("rollout_order_local",
                en.contains(KbSearchMode.local) || en.contains(KbSearchMode.hybrid),
                "开放 drift/auto 前建议先开 local 或 hybrid");
        }
        if (en.contains(KbSearchMode.global))
        {
            report.check("rollout_order_hybrid",
                en.contains(KbSearchMode.hybrid) || en.contains(KbSearchMode.local),
                "开放 global 前建议先开 local/hybrid");
        }
        report.passed = report.failures.isEmpty();
        report.enabledModes = en.stream().map(Enum::name).sorted().toList();
        report.defaultMode = modePolicy.defaultMode().name();
        return report;
    }

    public static final class GateReport
    {
        public boolean passed;
        public String defaultMode;
        public List<String> enabledModes = List.of();
        public final List<Map<String, Object>> checks = new ArrayList<>();
        public final List<String> failures = new ArrayList<>();

        void check(String id, boolean ok, String message)
        {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("ok", ok);
            row.put("message", message);
            checks.add(row);
            if (!ok)
            {
                failures.add(id + ": " + message);
            }
        }
    }
}
