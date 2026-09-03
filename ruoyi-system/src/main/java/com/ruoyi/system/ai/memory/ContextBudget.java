package com.ruoyi.system.ai.memory;

import com.ruoyi.common.core.text.Convert;
import com.ruoyi.system.service.ISysConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 按 {@code ai_model.context_window} / {@code max_output_tokens} 算上下文预算(§4.7)。
 *
 * <p><b>分母为什么是 contextWindow − maxOutputTokens 而不是 contextWindow</b>:
 * contextWindow 通常是输入+输出的总额度。假设 128K 窗口、maxOutputTokens=32K,
 * 直接对 128K 取 80% = 102.4K,再加输出 32K = 134K,<b>溢出</b>。
 * 先扣掉输出预留才安全:(128K − 32K) × 0.8 = 76.8K。
 *
 * <p><b>静态方法做纯计算</b>(便于单测),{@code @Component} 实例方法包装从 {@code sys_config} 读比例
 * (比例走配置便于上线后调,见 §4.10)。{@link ISysConfigService#selectConfigByKey} 走 RuoYi 的 Redis 缓存,
 * 不会每轮打库。
 *
 * <p>本段(任务 B 第一段)只实现计算,不触发压缩;触发在第二段 {@code ContextCompactor}。
 */
@Component
public class ContextBudget {

    /** 压缩触发比例默认值(历史 token 占输入预算的百分比) */
    private static final int DEFAULT_THRESHOLD_PCT = 80;
    /** 压缩目标比例默认值(压到这个百分比以下,避免反复触发) */
    private static final int DEFAULT_TARGET_PCT = 40;
    /** context_window 没配时的保守兜底 */
    private static final int DEFAULT_WINDOW = 32768;

    @Autowired
    private ISysConfigService configService;

    @Value("${ai.chat.default-context-window:32768}")
    private int defaultWindow;

    /** 输入可用预算 = 总窗口 − 输出预留(防御:不低于窗口一半)。 */
    public int inputBudget(Integer window, Integer maxOutput) {
        return inputBudget(window, maxOutput,
                pct("ai.context.compactThreshold", DEFAULT_THRESHOLD_PCT),
                pct("ai.context.compactTarget", DEFAULT_TARGET_PCT));
    }

    /** 超过即触发压缩。 */
    public int threshold(Integer window, Integer maxOutput) {
        return threshold(window, maxOutput,
                pct("ai.context.compactThreshold", DEFAULT_THRESHOLD_PCT),
                pct("ai.context.compactTarget", DEFAULT_TARGET_PCT));
    }

    /** 压缩后要降到这条线以下。 */
    public int target(Integer window, Integer maxOutput) {
        return target(window, maxOutput,
                pct("ai.context.compactThreshold", DEFAULT_THRESHOLD_PCT),
                pct("ai.context.compactTarget", DEFAULT_TARGET_PCT));
    }

    /**
     * 纯计算:输入预算。{@code thresholdPct}/{@code targetPct} 在 inputBudget 路径不使用,
     * 仅为与 {@link #threshold}/{@link #target} 保持一致的方法签名而保留。
     */
    static int inputBudget(Integer window, Integer maxOutput, int thresholdPct, int targetPct) {
        int w = window != null ? window : DEFAULT_WINDOW;
        int reserved = maxOutput != null ? maxOutput : w / 8;
        return Math.max(w - reserved, w / 2);
    }

    static int threshold(Integer window, Integer maxOutput, int thresholdPct, int targetPct) {
        return inputBudget(window, maxOutput, thresholdPct, targetPct) * clamp(thresholdPct) / 100;
    }

    static int target(Integer window, Integer maxOutput, int thresholdPct, int targetPct) {
        return inputBudget(window, maxOutput, thresholdPct, targetPct) * clamp(targetPct) / 100;
    }

    /** 参数越界兜回默认 80:0/100/负数都会让压缩失效或频繁触发,不能让配置错误把对话搞崩。 */
    private static int clamp(int pct) {
        return (pct > 0 && pct < 100) ? pct : DEFAULT_THRESHOLD_PCT;
    }

    private int pct(String key, int fallback) {
        int v = Convert.toInt(configService.selectConfigByKey(key), fallback);
        return (v > 0 && v < 100) ? v : fallback;
    }
}
