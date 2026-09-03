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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ContextCleaner 纯函数单测 —— 配对完整性是最重要的用例。
 */
class ContextCleanerTest
{
    private ContextCleaner cleaner;

    @BeforeEach
    void setUp()
    {
        cleaner = new ContextCleaner();
        ReflectionTestUtils.setField(cleaner, "tokenEstimator", new TokenEstimator());
        ReflectionTestUtils.setField(cleaner, "triggerRatio", 0.6);
        ReflectionTestUtils.setField(cleaner, "keep", 1);
        ReflectionTestUtils.setField(cleaner, "clearAtLeastRatio", 0.01);
        ReflectionTestUtils.setField(cleaner, "clearToolInputs", true);
        ReflectionTestUtils.setField(cleaner, "excludeToolsConfig", "");
    }

    @Test
    void belowTrigger_returnsSameList()
    {
        List<Message> msgs = baseWithPairs(1, 50);
        // 预算很大 → 不触发
        List<Message> out = cleaner.clean(msgs, 1_000_000);
        assertSame(msgs, out);
    }

    @Test
    void wouldFreeBelowClearAtLeast_returnsUnchanged()
    {
        ReflectionTestUtils.setField(cleaner, "clearAtLeastRatio", 0.99);
        List<Message> msgs = baseWithPairs(3, 200);
        int budget = cleaner.estimateTokens(msgs); // 约等于当前用量
        // trigger 用 0.01 强制进入清理分支,但 clearAtLeast 几乎等于全预算 → 放弃
        ReflectionTestUtils.setField(cleaner, "triggerRatio", 0.01);
        List<Message> out = cleaner.clean(msgs, budget);
        // 应原样(或至少未减少对数)—— 因 clearAtLeast 过高
        assertEquals(countToolPairs(msgs), countToolPairs(out));
        assertTrue(out.stream().noneMatch(m ->
                m instanceof ToolResponseMessage tr
                        && tr.getResponses().stream().anyMatch(r ->
                        r.responseData() != null && r.responseData().contains("[已精简]"))));
    }

    @Test
    void normalClean_keepsRecentPairs_andPlaceholdersOld()
    {
        ReflectionTestUtils.setField(cleaner, "keep", 1);
        ReflectionTestUtils.setField(cleaner, "triggerRatio", 0.01);
        List<Message> msgs = baseWithPairs(3, 800);
        int budget = Math.max(100, cleaner.estimateTokens(msgs) / 2);
        List<Message> out = cleaner.clean(msgs, budget);

        assertTrue(countToolPairs(out) >= 1, "至少保留 keep 对");
        // 应出现占位
        boolean hasPlaceholder = out.stream().anyMatch(m ->
                m instanceof ToolResponseMessage tr
                        && tr.getResponses().stream().anyMatch(r ->
                        r.responseData() != null && r.responseData().contains("[已精简]")));
        assertTrue(hasPlaceholder, "旧工具结果应被占位替换");
        ContextCleaner.CleanStats stats = cleaner.lastStats();
        assertTrue(stats != null && stats.pairsCleared() >= 1);
    }

    @Test
    void pairingIntegrity_everyToolCallHasFollowingResult()
    {
        ReflectionTestUtils.setField(cleaner, "keep", 1);
        ReflectionTestUtils.setField(cleaner, "triggerRatio", 0.01);
        List<Message> msgs = baseWithPairs(4, 600);
        int budget = Math.max(50, cleaner.estimateTokens(msgs) / 2);
        List<Message> out = cleaner.clean(msgs, budget);

        assertToolPairsIntact(out);
    }

    @Test
    void excludeTools_neverCleared()
    {
        ReflectionTestUtils.setField(cleaner, "excludeToolsConfig", "keepMe");
        ReflectionTestUtils.setField(cleaner, "keep", 0);
        ReflectionTestUtils.setField(cleaner, "triggerRatio", 0.01);
        List<Message> msgs = new ArrayList<>();
        msgs.add(new SystemMessage("sys"));
        msgs.add(new UserMessage("go"));
        msgs.add(assistantWithTool("c1", "keepMe", "{\"x\":\"" + "Y".repeat(500) + "\"}"));
        msgs.add(toolResult("c1", "keepMe", "big-result-" + "Z".repeat(500)));
        msgs.add(assistantWithTool("c2", "other", "{\"x\":\"" + "Y".repeat(500) + "\"}"));
        msgs.add(toolResult("c2", "other", "other-result-" + "Z".repeat(500)));
        int budget = Math.max(50, cleaner.estimateTokens(msgs) / 2);
        List<Message> out = cleaner.clean(msgs, budget);

        // keepMe 的结果不应变成占位
        boolean keepMeIntact = out.stream()
                .filter(m -> m instanceof ToolResponseMessage)
                .map(m -> (ToolResponseMessage) m)
                .flatMap(tr -> tr.getResponses().stream())
                .anyMatch(r -> "keepMe".equals(r.name())
                        && r.responseData() != null
                        && r.responseData().contains("big-result")
                        && !r.responseData().contains("[已精简]"));
        assertTrue(keepMeIntact, "exclude 工具的结果必须保留");
    }

    @Test
    void systemAndUser_neverRemoved()
    {
        ReflectionTestUtils.setField(cleaner, "keep", 0);
        ReflectionTestUtils.setField(cleaner, "triggerRatio", 0.01);
        List<Message> msgs = baseWithPairs(2, 700);
        int budget = Math.max(50, cleaner.estimateTokens(msgs) / 2);
        List<Message> out = cleaner.clean(msgs, budget);
        assertTrue(out.get(0) instanceof SystemMessage);
        assertTrue(out.stream().anyMatch(m -> m instanceof UserMessage));
    }

    @Test
    void buildPlaceholder_includesRestorableHint()
    {
        String p = ContextCleaner.buildPlaceholder("writeFile",
                "{\"path\":\"src/App.vue\",\"content\":\"...\"}", "写入成功");
        assertTrue(p.contains("[已精简]"));
        assertTrue(p.contains("writeFile"));
        assertTrue(p.contains("src/App.vue"));
        assertTrue(p.contains("read"));
    }

    /**
     * 清两遍必须与清一遍逐字相同 —— 这条是缓存前缀的地基。
     * <p>占位文本一旦被二次包装,断点就从「最新清理的那对」退回「最早的那对」,
     * 其后的全部历史一起失配。
     */
    @Test
    void secondClean_isIdempotent_andReportsNothingCleared()
    {
        ReflectionTestUtils.setField(cleaner, "keep", 1);
        ReflectionTestUtils.setField(cleaner, "triggerRatio", 0.01);
        List<Message> msgs = baseWithPairs(3, 800);
        int budget = Math.max(100, cleaner.estimateTokens(msgs) / 2);

        List<Message> first = cleaner.clean(msgs, budget);
        assertTrue(cleaner.lastStats().pairsCleared() >= 1, "第一遍应清掉旧对");

        // 同样的预算再清一遍:已清过的对不该再被动
        List<Message> second = cleaner.clean(first, budget);
        assertSame(first, second, "无新可清对象时应原样返回,不得重建列表");
        assertNull(cleaner.lastStats(), "什么都没清就不该产出 stats");
    }

    /** 新增一对之后再清:只动新的那对,已清占位逐字不变。 */
    @Test
    void reclean_afterNewPair_leavesEarlierPlaceholdersByteIdentical()
    {
        ReflectionTestUtils.setField(cleaner, "keep", 1);
        ReflectionTestUtils.setField(cleaner, "triggerRatio", 0.01);
        List<Message> msgs = baseWithPairs(3, 800);
        int budget = Math.max(100, cleaner.estimateTokens(msgs) / 2);

        List<Message> first = cleaner.clean(msgs, budget);
        List<String> placeholdersAfterFirst = placeholderTexts(first);
        assertFalse(placeholdersAfterFirst.isEmpty());

        // 追加一对新的大工具往返,再触发一次清理
        List<Message> grown = new ArrayList<>(first);
        String payload = "W".repeat(900);
        grown.add(assistantWithTool("call-new", "writeFile",
                "{\"path\":\"new.txt\",\"content\":\"" + payload + "\"}"));
        grown.add(toolResult("call-new", "writeFile", "ok wrote " + payload));

        List<Message> second = cleaner.clean(grown, budget);

        // 先前的占位必须原封不动地出现在新列表里
        List<String> after = placeholderTexts(second);
        assertTrue(after.containsAll(placeholdersAfterFirst),
                "已清占位不得被二次包装:\n清一遍=" + placeholdersAfterFirst + "\n清两遍=" + after);
        assertToolPairsIntact(second);
    }

    @Test
    void buildPlaceholder_onExistingPlaceholder_returnsItUnchanged()
    {
        String once = ContextCleaner.buildPlaceholder("writeFile",
                "{\"path\":\"src/App.vue\",\"content\":\"...\"}", "写入成功");
        String twice = ContextCleaner.buildPlaceholder("writeFile",
                "{\"path\":\"src/App.vue\",\"_cleared\":true}", once);
        assertEquals(once, twice);
    }

    @Test
    void summarizeArgs_isIdempotent_withAndWithoutPath()
    {
        String withPath = ContextCleaner.summarizeArgs("writeFile",
                "{\"path\":\"a.txt\",\"content\":\"" + "X".repeat(500) + "\"}");
        assertEquals(withPath, ContextCleaner.summarizeArgs("writeFile", withPath));

        // 无 path 的分支曾把上次的整段 JSON 塞进 preview,越缠越长
        String noPath = ContextCleaner.summarizeArgs("runShell",
                "{\"command\":\"" + "ls ".repeat(200) + "\"}");
        assertEquals(noPath, ContextCleaner.summarizeArgs("runShell", noPath));
    }

    @Test
    void toolPlaceholders_isPlaceholder_detectsPrefix()
    {
        String p = ToolPlaceholders.buildPlaceholder("readFile",
                "{\"path\":\"/data/x.csv\"}", "1024 行数据");
        assertTrue(ToolPlaceholders.isPlaceholder(p), "自己造的占位必须被认出来");
        assertFalse(ToolPlaceholders.isPlaceholder("普通工具结果"), "普通结果不能被误判");
        assertFalse(ToolPlaceholders.isPlaceholder(null), "null 不能抛异常");
    }

    /** 清理必须报出被清掉的 tool_call_id,否则无法把标记落库 */
    @Test
    void cleanStats_reportsClearedToolCallIds()
    {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("sys"));
        for (int i = 0; i < 8; i++)
        {
            messages.add(AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            "call_" + i, "function", "readFile", "{\"path\":\"/f" + i + "\"}")))
                    .build());
            messages.add(ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            "call_" + i, "readFile", "内容".repeat(500))))
                    .build());
        }

        cleaner.clean(messages, 2000);
        ContextCleaner.CleanStats stats = cleaner.lastStats();

        assertNotNull(stats, "应触发清理");
        assertTrue(stats.pairsCleared() > 0);
        assertEquals(stats.pairsCleared(), stats.clearedToolCallIds().size(),
                "每清一对就该报一个 id");
        assertTrue(stats.clearedToolCallIds().contains("call_0"),
                "最旧的一对必然在被清之列");
    }

    /** loadSkill 的返回是操作指令正文,清掉会让模型丢失技能行为。 */
    @Test
    void loadSkillPairs_areNeverCleared()
    {
        ReflectionTestUtils.setField(cleaner, "excludeToolsConfig", "loadSkill");

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("sys"));
        messages.add(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call_skill", "function", "loadSkill", "{\"skillName\":\"周报\"}")))
                .build());
        messages.add(ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "call_skill", "loadSkill", "第一步…第二步…".repeat(300))))
                .build());
        for (int i = 0; i < 8; i++)
        {
            messages.add(AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            "call_" + i, "function", "readFile", "{\"path\":\"/f" + i + "\"}")))
                    .build());
            messages.add(ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            "call_" + i, "readFile", "内容".repeat(500))))
                    .build());
        }

        List<Message> out = cleaner.clean(messages, 2000);
        ContextCleaner.CleanStats stats = cleaner.lastStats();

        assertNotNull(stats, "应触发清理");
        assertTrue(stats.pairsCleared() > 0, "普通工具往返应被清掉");
        assertTrue(out.stream()
                        .filter(m -> m instanceof ToolResponseMessage)
                        .flatMap(m -> ((ToolResponseMessage) m).getResponses().stream())
                        .filter(r -> "loadSkill".equals(r.name()))
                        .allMatch(r -> r.responseData().contains("第一步")),
                "技能正文必须原样保留,不能被换成占位");
    }

    /** 默认配置就该把 loadSkill 挡住 —— 靠部署时记得配是不现实的。 */
    @Test
    void excludeTools_defaultsToLoadSkill()
    {
        ContextCleaner fresh = new ContextCleaner();
        assertEquals("loadSkill",
                ReflectionTestUtils.getField(fresh, "excludeToolsConfig"),
                "字段默认值要与 @Value 的兜底值一致,不能只改 yml");
    }

    /** 绝对上限的字段默认值要与 @Value 兜底一致 —— 直接 new 出来时不能静默退回纯比例制。 */
    @Test
    void triggerMaxTokens_defaultMatchesValueFallback()
    {
        ContextCleaner fresh = new ContextCleaner();
        assertEquals(60_000,
                ReflectionTestUtils.getField(fresh, "triggerMaxTokens"),
                "字段默认值要与 @Value 的兜底值一致,不能只改 yml");
    }

    /**
     * 绝对上限与比例线取小;&lt;=0 关闭绝对上限。
     */
    @Test
    void triggerThreshold_takesMinOfRatioAndCap()
    {
        ReflectionTestUtils.setField(cleaner, "triggerRatio", 0.6);

        ReflectionTestUtils.setField(cleaner, "triggerMaxTokens", 60_000);
        assertEquals(60_000, cleaner.triggerThreshold(991_808), "长窗口应被绝对上限压住");
        assertEquals(6_000, cleaner.triggerThreshold(10_000), "小窗口仍走比例线");

        ReflectionTestUtils.setField(cleaner, "triggerMaxTokens", 0);
        assertEquals(595_084, cleaner.triggerThreshold(991_808), "<=0 时关闭绝对上限");
    }

    /**
     * 100 万窗口下的真实故障:三道门全部够不着,一次都不清。
     *
     * <p>复现线上那次浏览器自动化 —— 上下文峰值 9.6 万、工具往返占 75%,
     * 而比例线是 59.5 万、clearAtLeast 是 9.9 万(比整个上下文还高)。
     * 结果 51 轮工具往返原样堆着被完整重发 51 遍。
     */
    @Test
    void millionTokenWindow_stillCleans()
    {
        ReflectionTestUtils.setField(cleaner, "triggerMaxTokens", 60_000);
        ReflectionTestUtils.setField(cleaner, "clearAtLeastRatio", 0.1);
        ReflectionTestUtils.setField(cleaner, "keep", 3);

        int inputBudget = 991_808; // 1,000,000 窗口 − 8,192 输出预留
        // 规模对齐线上那次:上下文 8 万量级、工具往返占绝大部分,且落在
        // 「超过 60000 绝对上限」与「低于 0.1×预算=99180」之间 —— 新旧行为正好在此分岔
        List<Message> msgs = baseWithPairs(80, 4000);
        int before = cleaner.estimateTokens(msgs);

        // 前提:正是「够不着比例线」让清理永不触发
        assertTrue(before > 60_000, "构造的上下文应超过绝对上限,实际 " + before);
        assertTrue(before < inputBudget * 0.6, "构造的上下文应够不着比例线,实际 " + before);
        // 且旧的 clearAtLeast 基准(0.1×预算=99180)高于整个上下文 —— 清光也过不去
        assertTrue(inputBudget * 0.1 > before, "旧基准门槛应高于整个上下文,实际 " + before);

        List<Message> out = cleaner.clean(msgs, inputBudget);

        assertNotSame(msgs, out, "长窗口下也应触发清理");
        assertNotNull(cleaner.lastStats());
        assertTrue(cleaner.estimateTokens(out) < before, "清理后应变小");
        assertToolPairsIntact(out);
    }

    private static List<String> placeholderTexts(List<Message> messages)
    {
        List<String> out = new ArrayList<>();
        for (Message m : messages)
        {
            if (m instanceof ToolResponseMessage tr)
            {
                for (ToolResponseMessage.ToolResponse r : tr.getResponses())
                {
                    if (r.responseData() != null && r.responseData().startsWith("[已精简] "))
                    {
                        out.add(r.responseData());
                    }
                }
            }
        }
        return out;
    }

    // ---- helpers ----

    private static List<Message> baseWithPairs(int pairs, int payloadChars)
    {
        List<Message> msgs = new ArrayList<>();
        msgs.add(new SystemMessage("you are a helpful assistant"));
        msgs.add(new UserMessage("do the work"));
        String payload = "X".repeat(Math.max(payloadChars, 10));
        for (int i = 0; i < pairs; i++)
        {
            String id = "call-" + i;
            String name = "writeFile";
            msgs.add(assistantWithTool(id, name,
                    "{\"path\":\"f" + i + ".txt\",\"content\":\"" + payload + "\"}"));
            msgs.add(toolResult(id, name, "ok wrote " + payload));
        }
        return msgs;
    }

    private static AssistantMessage assistantWithTool(String id, String name, String args)
    {
        return AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, args)))
                .build();
    }

    private static ToolResponseMessage toolResult(String id, String name, String data)
    {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(id, name, data)))
                .build();
    }

    private static int countToolPairs(List<Message> messages)
    {
        return ContextCleaner.findToolPairs(messages).size();
    }

    private static void assertToolPairsIntact(List<Message> messages)
    {
        for (int i = 0; i < messages.size(); i++)
        {
            Message m = messages.get(i);
            if (!(m instanceof AssistantMessage am) || !am.hasToolCalls())
            {
                continue;
            }
            // 后面必须紧跟 ToolResponseMessage,且覆盖所有 tool_call id
            assertTrue(i + 1 < messages.size(), "assistant(tool_calls) 后必须有 tool 结果");
            assertTrue(messages.get(i + 1) instanceof ToolResponseMessage,
                    "assistant(tool_calls) 后必须是 ToolResponseMessage");
            ToolResponseMessage tr = (ToolResponseMessage) messages.get(i + 1);
            for (AssistantMessage.ToolCall tc : am.getToolCalls())
            {
                boolean found = tr.getResponses().stream().anyMatch(r -> tc.id().equals(r.id()));
                assertTrue(found, "缺少 tool_call_id=" + tc.id() + " 的结果");
            }
            assertFalse(am.getToolCalls().isEmpty());
        }
    }
}
