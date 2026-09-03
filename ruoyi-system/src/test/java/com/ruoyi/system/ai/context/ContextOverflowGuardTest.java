package com.ruoyi.system.ai.context;

import com.ruoyi.system.ai.memory.TokenEstimator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 发给上游之前的最后一道防线。 */
class ContextOverflowGuardTest
{
    private ContextOverflowGuard guard;

    @BeforeEach
    void setUp()
    {
        guard = new ContextOverflowGuard();
        ReflectionTestUtils.setField(guard, "tokenEstimator", new TokenEstimator());
    }

    @Test
    void enforce_underBudget_returnsSameList()
    {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("sys"));
        messages.add(new UserMessage("短问题"));

        assertSame(messages, guard.enforce(messages, 100000));
        assertNull(guard.lastStats());
    }

    /**
     * 三层上下文控制必须认同一份豁免名单:清理层跳过 loadSkill、压缩层原文保留它,
     * 兜底层就不能整轮丢掉 —— 否则前两层白做,模型照样落进「以为加载过技能、规则却没了」。
     */
    @Test
    void enforce_preservesTurnsCarryingExcludedToolResults()
    {
        guard = guardWithExcludes("loadSkill");
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("系统提示"));
        // 第 1 轮带技能规则,后面 9 轮是普通问答
        messages.add(new UserMessage("用一下写作技能"));
        messages.add(assistantCalling("loadSkill"));
        messages.add(toolResponse("loadSkill", "写作技能的完整操作规则正文"));
        for (int i = 0; i < 9; i++)
        {
            messages.add(new UserMessage("问题" + i + "内容".repeat(200)));
            messages.add(new AssistantMessage("回答" + i + "内容".repeat(200)));
        }
        messages.add(new UserMessage("最后一问"));

        List<Message> kept = guard.enforce(messages, 800);

        assertNotSame(messages, kept, "超预算必须裁剪");
        assertTrue(kept.stream().anyMatch(m -> m instanceof ToolResponseMessage t
                        && t.getResponses().stream().anyMatch(r -> "loadSkill".equals(r.name()))),
                "带技能规则的那轮必须原地保留");
        // 成对不能被劈开:留了 tool 就必须留对应的 assistant(tool_calls),否则上游直接 400
        assertTrue(kept.stream().anyMatch(m -> m instanceof AssistantMessage a && a.hasToolCalls()),
                "assistant(tool_calls) 必须跟着一起保留");
        assertEquals("最后一问", kept.get(kept.size() - 1).getText(), "最后一轮永远保留");
    }

    /** 没有豁免名单时行为不变(cleaner 未注入 = 旧行为),避免这次改动影响存量部署。 */
    @Test
    void enforce_withoutExcludeList_behavesAsBefore()
    {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("系统提示"));
        messages.add(new UserMessage("用一下写作技能"));
        messages.add(assistantCalling("loadSkill"));
        messages.add(toolResponse("loadSkill", "技能规则".repeat(200)));
        for (int i = 0; i < 5; i++)
        {
            messages.add(new UserMessage("问题" + i + "内容".repeat(200)));
            messages.add(new AssistantMessage("回答" + i + "内容".repeat(200)));
        }
        messages.add(new UserMessage("最后一问"));

        List<Message> kept = guard.enforce(messages, 500);
        assertFalse(kept.stream().anyMatch(m -> m instanceof ToolResponseMessage),
                "未配置豁免名单时按旧行为整轮丢弃");
    }

    /** 保留豁免轮后仍然超预算时,必须让步 —— 兜底层的第一职责是让请求发得出去。 */
    @Test
    void enforce_stillOverBudgetAfterPreserving_dropsEverythingButLastTurn()
    {
        guard = guardWithExcludes("loadSkill");
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("系统提示"));
        messages.add(new UserMessage("用一下写作技能"));
        messages.add(assistantCalling("loadSkill"));
        messages.add(toolResponse("loadSkill", "超长技能规则".repeat(500)));
        messages.add(new UserMessage("最后一问"));

        List<Message> kept = guard.enforce(messages, 100);
        assertEquals("最后一问", kept.get(kept.size() - 1).getText());
        assertFalse(kept.stream().anyMatch(m -> m instanceof ToolResponseMessage),
                "预算实在不够时技能规则也要让路,不能让请求发不出去");
    }

    private static ContextOverflowGuard guardWithExcludes(String excludes)
    {
        ContextOverflowGuard g = new ContextOverflowGuard();
        ReflectionTestUtils.setField(g, "tokenEstimator", new TokenEstimator());
        ContextCleaner cleaner = new ContextCleaner();
        ReflectionTestUtils.setField(cleaner, "excludeToolsConfig", excludes);
        ReflectionTestUtils.setField(g, "contextCleaner", cleaner);
        return g;
    }

    private static AssistantMessage assistantCalling(String toolName)
    {
        return AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", toolName, "{}")))
                .build();
    }

    private static ToolResponseMessage toolResponse(String toolName, String data)
    {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", toolName, data)))
                .build();
    }

    @Test
    void enforce_overBudget_dropsOldestTurns()
    {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("系统提示"));
        for (int i = 0; i < 10; i++)
        {
            messages.add(new UserMessage("问题" + i + "内容".repeat(200)));
            messages.add(new AssistantMessage("回答" + i + "内容".repeat(200)));
        }
        messages.add(new UserMessage("本轮问题"));

        List<Message> out = guard.enforce(messages, 2000);

        assertTrue(out.size() < messages.size());
        assertTrue(out.get(0) instanceof SystemMessage);
        assertEquals("本轮问题", out.get(out.size() - 1).getText());
        ContextOverflowGuard.TrimStats stats = guard.lastStats();
        assertNotNull(stats);
        assertTrue(stats.turnsDropped() > 0);
        assertTrue(stats.tokensAfter() < stats.tokensBefore());
    }

    @Test
    void enforce_neverSplitsToolPairs()
    {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("sys"));
        for (int i = 0; i < 6; i++)
        {
            messages.add(new UserMessage("问题" + i));
            messages.add(AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            "call_" + i, "function", "readFile", "{\"path\":\"/f" + i + "\"}")))
                    .build());
            messages.add(ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            "call_" + i, "readFile", "内容".repeat(400))))
                    .build());
            messages.add(new AssistantMessage("回答" + i));
        }
        messages.add(new UserMessage("本轮问题"));

        List<Message> out = guard.enforce(messages, 1500);
        for (int i = 0; i < out.size(); i++)
        {
            if (out.get(i) instanceof ToolResponseMessage)
            {
                assertTrue(i > 0);
                Message prev = out.get(i - 1);
                assertTrue(prev instanceof AssistantMessage am && am.hasToolCalls(),
                        "tool 消息前面必须紧跟带 tool_calls 的 assistant");
            }
        }
    }

    @Test
    void enforce_singleHugeTurn_returnsAsIs()
    {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("sys"));
        messages.add(new UserMessage("巨长的输入".repeat(5000)));

        List<Message> out = guard.enforce(messages, 100);
        assertEquals(2, out.size());
    }

    @Test
    void enforce_nullOrEmpty_doesNotThrow()
    {
        assertNull(guard.enforce(null, 1000));
        assertTrue(guard.enforce(new ArrayList<>(), 1000).isEmpty());
    }

    @Test
    void enforce_invalidBudget_doesNothing()
    {
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("a"));
        messages.add(new UserMessage("b"));

        assertSame(messages, guard.enforce(messages, 0));
    }
}
