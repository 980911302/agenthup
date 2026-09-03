package com.ruoyi.system.ai.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 上下文预算/阈值/目标计算测试(§4.7)。
 * <p>分母是 contextWindow − maxOutputTokens(总窗口扣掉输出预留才是输入可用预算)。
 * threshold = 输入预算 × 触发比例(默认 80%),target = 输入预算 × 目标比例(默认 40%)。
 * 参数越界(0/100/负数)时兜回默认值,不让对话崩。
 * <p>静态方法做纯计算(便于单测),@Component 包装从 sys_config 读比例。
 */
class ContextBudgetTest {

    @Test
    void inputBudget_subtractsOutputReserve() {
        assertEquals(96_000, ContextBudget.inputBudget(128_000, 32_000, 80, 40));
    }

    @Test
    void inputBudget_maxOutputNull_defaultsToEighth() {
        // maxOutput 没配时默认预留窗口的 1/8
        assertEquals(112_000, ContextBudget.inputBudget(128_000, null, 80, 40));
    }

    @Test
    void inputBudget_reservesAtLeastHalfWhenOutputOverconfigured() {
        // 输出预留超过窗口一半时,防御性地保证输入至少占一半
        assertEquals(64_000, ContextBudget.inputBudget(128_000, 128_000, 80, 40));
    }

    @Test
    void threshold_is80PercentOfInputBudget() {
        assertEquals(76_800, ContextBudget.threshold(128_000, 32_000, 80, 40));
    }

    @Test
    void target_is40PercentOfInputBudget() {
        assertEquals(38_400, ContextBudget.target(128_000, 32_000, 80, 40));
    }

    @Test
    void paramsOutOfRange_fallbackToDefaults() {
        // 触发比例为 0 / 100 / 负数都兜回默认 80
        assertEquals(76_800, ContextBudget.threshold(128_000, 32_000, 0, 40));
        assertEquals(76_800, ContextBudget.threshold(128_000, 32_000, 100, 40));
    }
}
