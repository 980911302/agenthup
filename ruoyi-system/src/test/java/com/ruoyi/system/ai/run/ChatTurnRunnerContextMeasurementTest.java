package com.ruoyi.system.ai.run;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 锁定当前可重建记忆与最近一次 prompt 观测的对账规则。 */
class ChatTurnRunnerContextMeasurementTest
{
    @Test
    void acceptsNormalTokenizerDeviation()
    {
        assertTrue(ChatTurnRunner.isPromptMeasurementConsistent(10_000, 12_000));
        assertTrue(ChatTurnRunner.isPromptMeasurementConsistent(12_000, 10_000));
    }

    @Test
    void rejectsStaleLowPromptFromTruncatedHistory()
    {
        assertFalse(ChatTurnRunner.isPromptMeasurementConsistent(2_900, 18_500));
    }

    @Test
    void rejectsOldHighPromptAfterMemoryWasCleared()
    {
        assertFalse(ChatTurnRunner.isPromptMeasurementConsistent(18_500, 2_900));
        assertFalse(ChatTurnRunner.isPromptMeasurementConsistent(0, 2_900));
    }
}
