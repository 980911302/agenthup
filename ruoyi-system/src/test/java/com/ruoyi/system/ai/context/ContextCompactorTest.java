package com.ruoyi.system.ai.context;

import com.ruoyi.system.ai.memory.ChatMessageMapperTestSupport;
import com.ruoyi.system.ai.memory.ContextBudget;
import com.ruoyi.system.domain.AiChatMessage;
import com.ruoyi.system.domain.AiLlmCall;
import com.ruoyi.system.mapper.AiLlmCallMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 压缩的三条不变量:该压才压、边界留够最近几轮、失败不能把对话带崩。 */
class ContextCompactorTest extends ChatMessageMapperTestSupport
{
    private AiLlmCallMapper llmCallMapper;

    /** token 没到阈值时一条 SUMMARY 都不该产生 —— 压缩要花钱,还会打断缓存前缀 */
    @Test
    void compactIfNeeded_belowThreshold_doesNothing() throws Exception
    {
        ContextCompactor compactor = newCompactor(2);
        String conv = "s1:60";
        recorder.insert(conv, "s1", 60L, "USER", "短问题", "0", 5);
        session.commit();

        assertFalse(compactor.compactIfNeeded(conv, "s1", 60L,
                new StubChatModel("摘要"), 100000, 8000, 7L, "qwen-max"));
        assertNull(recorder.latestSummary(conv), "没到阈值就不该产生 SUMMARY");
    }

    /** 超阈值时产生 SUMMARY 行,且最近 N 轮不进压缩范围 */
    @Test
    void compactIfNeeded_overThreshold_writesSummaryAndKeepsRecentTurns() throws Exception
    {
        ContextCompactor compactor = newCompactor(2);
        String conv = "s1:61";
        for (int i = 0; i < 6; i++)
        {
            recorder.insert(conv, "s1", 61L, "USER", "问题" + i, "0", 1200);
            recorder.insert(conv, "s1", 61L, "ASSISTANT", "回答" + i, "0", 1200);
        }
        session.commit();

        assertTrue(compactor.compactIfNeeded(conv, "s1", 61L,
                new StubChatModel("这是前情提要"), 20000, 5000, 7L, "qwen-max"));

        AiChatMessage summary = recorder.latestSummary(conv);
        assertNotNull(summary, "应写出 SUMMARY 行");
        assertEquals("这是前情提要", summary.getContent());
        assertEquals("SUMMARY", summary.getMessageType());
        assertNotNull(summary.getSummaryToId(), "必须记录覆盖到哪一条");

        List<AiChatMessage> all = mapper.selectTimelineBySession("s1");
        long fourthUserId = all.stream()
                .filter(m -> "USER".equals(m.getMessageType()))
                .skip(4).findFirst().orElseThrow().getMessageId();
        assertTrue(summary.getSummaryToId() < fourthUserId,
                "最近 2 轮必须留在压缩边界之外");
    }

    /** LLM 挂了不能把这轮对话带崩:吞掉异常,返回 false,不写任何 SUMMARY */
    @Test
    void compactIfNeeded_llmFails_degradesQuietly() throws Exception
    {
        ContextCompactor compactor = newCompactor(2);
        String conv = "s1:62";
        for (int i = 0; i < 6; i++)
        {
            recorder.insert(conv, "s1", 62L, "USER", "问题" + i, "0", 900);
            recorder.insert(conv, "s1", 62L, "ASSISTANT", "回答" + i, "0", 900);
        }
        session.commit();

        assertFalse(compactor.compactIfNeeded(conv, "s1", 62L,
                new FailingChatModel(), 2000, 500, 7L, "qwen-max"), "失败必须返回 false");
        assertNull(recorder.latestSummary(conv), "失败时绝不能留下半截 SUMMARY");
    }

    /** 压缩会真实烧 token,必须与常规调用采用同一张明细表计量。 */
    @Test
    void compactIfNeeded_recordsLlmCall() throws Exception
    {
        ContextCompactor compactor = newCompactor(2);
        String conv = "s1:63";
        for (int i = 0; i < 6; i++)
        {
            recorder.insert(conv, "s1", 63L, "USER", "问题" + i, "0", 900);
            recorder.insert(conv, "s1", 63L, "ASSISTANT", "回答" + i, "0", 900);
        }
        session.commit();

        assertTrue(compactor.compactIfNeeded(conv, "s1", 63L,
                new StubChatModel("提要"), 2000, 500, 7L, "qwen-max"));

        ArgumentCaptor<AiLlmCall> cap = ArgumentCaptor.forClass(AiLlmCall.class);
        verify(llmCallMapper).insertLlmCall(cap.capture());
        AiLlmCall call = cap.getValue();
        assertEquals("s1", call.getSessionId());
        assertEquals(conv, call.getConversationId());
        assertEquals(63L, call.getAgentId());
        assertEquals(7L, call.getModelId(), "模型归因不能丢,否则按模型统计会漏");
        assertEquals("qwen-max", call.getModelName());
        assertTrue(call.getTotalTokens() > 0, "总量必须有值");
        assertEquals("1", call.getUsageSource(), "StubChatModel 不返回 usage,应标记为本地估算");
    }

    /** 压缩没发生就不能凭空记一笔账。 */
    @Test
    void compactIfNeeded_belowThreshold_recordsNothing() throws Exception
    {
        ContextCompactor compactor = newCompactor(2);
        String conv = "s1:64";
        recorder.insert(conv, "s1", 64L, "USER", "短问题", "0", 5);
        session.commit();

        assertFalse(compactor.compactIfNeeded(conv, "s1", 64L,
                new StubChatModel("提要"), 100000, 8000, 7L, "qwen-max"));
        verifyNoInteractions(llmCallMapper);
    }

    /** 图片在压缩边界之前时,提要里至少要留下附件名称与类型的痕迹。 */
    @Test
    void compactIfNeeded_keepsAttachmentHints() throws Exception
    {
        ContextCompactor compactor = newCompactor(2);
        String conv = "s1:65";

        AiChatMessage withImage = new AiChatMessage();
        withImage.setConversationId(conv);
        withImage.setSessionId("s1");
        withImage.setAgentId(65L);
        withImage.setMessageType("USER");
        withImage.setContent("看看这张图");
        withImage.setVisibleToLlm("0");
        withImage.setAttachments("[{\"mime\":\"image/png\",\"name\":\"报表.png\",\"path\":\"up/报表.png\"}]");
        withImage.setTokens(900);
        withImage.setCreateTime(new java.util.Date());
        mapper.insertMessage(withImage);
        for (int i = 0; i < 6; i++)
        {
            recorder.insert(conv, "s1", 65L, "ASSISTANT", "回答" + i, "0", 900);
            recorder.insert(conv, "s1", 65L, "USER", "问题" + i, "0", 900);
        }
        session.commit();

        CapturingChatModel capturing = new CapturingChatModel("提要");
        assertTrue(compactor.compactIfNeeded(conv, "s1", 65L,
                capturing, 2000, 500, 7L, "qwen-max"));

        assertTrue(capturing.lastPrompt.contains("报表.png"), "历史里必须提到附件名");
        assertTrue(capturing.lastPrompt.contains("image/png"), "附件类型也要留给压缩模型");
    }

    /** 保留最近轮次已能到 target 时,不该再多压历史。 */
    @Test
    void findBoundary_stopsAtKeepRecentTurns_whenAlreadyUnderTarget() throws Exception
    {
        ContextCompactor compactor = newCompactor(2);
        String conv = "s1:70";
        for (int i = 0; i < 10; i++)
        {
            recorder.insert(conv, "s1", 70L, "USER", "问题" + i, "0", 700);
            recorder.insert(conv, "s1", 70L, "ASSISTANT", "回答" + i, "0", 700);
        }
        session.commit();

        assertTrue(compactor.compactIfNeeded(conv, "s1", 70L,
                new StubChatModel("提要"), 20000, 5000, 7L, "qwen-max"));

        AiChatMessage summary = recorder.latestSummary(conv);
        assertNotNull(summary);
        List<AiChatMessage> users = usersOf("s1");
        assertTrue(summary.getSummaryToId() < users.get(8).getMessageId(), "边界必须在倒数第 2 个 USER 之前");
        assertTrue(summary.getSummaryToId() >= users.get(7).getMessageId(), "保留 2 轮就够时不该再多压");
    }

    /** 最近轮次本身超过 target 时,必须自动减少保留轮数继续压缩。 */
    @Test
    void findBoundary_shrinksKeepTurns_whenRecentTurnsExceedTarget() throws Exception
    {
        ContextCompactor compactor = newCompactor(4);
        String conv = "s1:71";
        for (int i = 0; i < 8; i++)
        {
            recorder.insert(conv, "s1", 71L, "USER", "问题" + i, "0", 800);
            recorder.insert(conv, "s1", 71L, "ASSISTANT", "回答" + i, "0", 800);
        }
        session.commit();

        assertTrue(compactor.compactIfNeeded(conv, "s1", 71L,
                new StubChatModel("提要"), 20000, 5000, 7L, "qwen-max"));

        AiChatMessage summary = recorder.latestSummary(conv);
        assertNotNull(summary);
        List<AiChatMessage> users = usersOf("s1");
        assertTrue(summary.getSummaryToId() >= users.get(4).getMessageId(),
                "保留 4 轮压不到 target 时,必须自动少留几轮继续压");
        assertTrue(summary.getSummaryToId() < users.get(5).getMessageId(), "达标即止:压到 3 轮就够");
    }

    /** 连一轮都超过 target 时仍要尽力压到只剩最后一轮。 */
    @Test
    void findBoundary_compactsToLimit_whenEvenOneTurnExceedsTarget() throws Exception
    {
        ContextCompactor compactor = newCompactor(4);
        String conv = "s1:72";
        for (int i = 0; i < 5; i++)
        {
            recorder.insert(conv, "s1", 72L, "USER", "问题" + i, "0", 4000);
            recorder.insert(conv, "s1", 72L, "ASSISTANT", "回答" + i, "0", 4000);
        }
        session.commit();

        assertTrue(compactor.compactIfNeeded(conv, "s1", 72L,
                new StubChatModel("提要"), 20000, 5000, 7L, "qwen-max"));

        AiChatMessage summary = recorder.latestSummary(conv);
        assertNotNull(summary);
        List<AiChatMessage> users = usersOf("s1");
        assertTrue(summary.getSummaryToId() >= users.get(users.size() - 2).getMessageId(), "应压到只剩最后一轮");
        assertTrue(summary.getSummaryToId() < users.get(users.size() - 1).getMessageId(), "最后一轮必须保住");
    }

    private List<AiChatMessage> usersOf(String sessionId)
    {
        return mapper.selectTimelineBySession(sessionId).stream()
                .filter(m -> "USER".equals(m.getMessageType()))
                .toList();
    }

    /**
     * 压缩不能把已加载的技能规则摘要掉:{@code ContextCleaner} 特意把 loadSkill 列为永不清理,
     * 压缩层必须认同一条线,否则模型仍以为自己加载过技能、规则却没了(换个入口的同一种行为损坏)。
     * 规则原文由代码直接接在提要后面,不经过摘要模型,压缩前后逐字节一致。
     */
    @Test
    void compact_preservesLoadSkillResultVerbatim() throws Exception
    {
        ContextCompactor compactor = newCompactor(2);
        ContextCleaner cleaner = new ContextCleaner();
        setField(cleaner, "excludeToolsConfig", "loadSkill");
        setField(compactor, "contextCleaner", cleaner);
        setField(compactor, "preserveToolMaxChars", 12000);
        // 工具消息要跨轮留在上下文里(生产默认),否则 selectVisible 直接把 TOOL 行滤掉,
        // 压缩器根本看不到技能规则,也就谈不上保留
        setField(recorder, "toolMessagesInContext", true);

        String conv = "s1:70";
        String rules = "写作技能的完整操作规则:第一步定选题,第二步列大纲,第三步逐段成文。";
        for (int i = 0; i < 6; i++)
        {
            recorder.insert(conv, "s1", 70L, "USER", "问题" + i, "0", 1200);
            recorder.recordToolCall(conv, "s1", 70L, null, "loadSkill", "{}", rules,
                    "builtin", 1L, true, null, "call-" + i, null, null, null, rules);
            recorder.insert(conv, "s1", 70L, "ASSISTANT", "回答" + i, "0", 1200);
        }
        session.commit();

        assertTrue(compactor.compactIfNeeded(conv, "s1", 70L,
                new StubChatModel("这是提要正文"), 20000, 5000, 7L, "qwen-max"));

        String summary = recorder.latestSummary(conv).getContent();
        assertTrue(summary.contains("这是提要正文"), "提要本体应在: " + summary);
        assertTrue(summary.contains(rules), "技能规则必须原文保留,不能被摘要掉: " + summary);
        assertEquals(1, countOccurrences(summary, rules), "重复 loadSkill 的相同结果只保留一份");
    }

    /** 没有豁免工具时不产生保留段 —— 提要不该无端多出一截。 */
    @Test
    void compact_withoutPreservedTools_summaryUnchanged() throws Exception
    {
        ContextCompactor compactor = newCompactor(2);
        String conv = "s1:71";
        for (int i = 0; i < 6; i++)
        {
            recorder.insert(conv, "s1", 71L, "USER", "问题" + i, "0", 1200);
            recorder.insert(conv, "s1", 71L, "ASSISTANT", "回答" + i, "0", 1200);
        }
        session.commit();

        assertTrue(compactor.compactIfNeeded(conv, "s1", 71L,
                new StubChatModel("这是提要正文"), 20000, 5000, 7L, "qwen-max"));

        assertEquals("这是提要正文", recorder.latestSummary(conv).getContent().trim());
    }

    private static int countOccurrences(String haystack, String needle)
    {
        int n = 0;
        int i = haystack.indexOf(needle);
        while (i >= 0)
        {
            n++;
            i = haystack.indexOf(needle, i + needle.length());
        }
        return n;
    }

    private ContextCompactor newCompactor(int keepTurns) throws Exception
    {
        ContextCompactor c = new ContextCompactor();
        setField(c, "recorder", recorder);
        setField(c, "tokenEstimator", tokenEstimator);
        setField(c, "contextBudget", new StubBudget());
        setField(c, "enabled", true);
        setField(c, "keepRecentTurns", keepTurns);
        llmCallMapper = mock(AiLlmCallMapper.class);
        setField(c, "llmCallMapper", llmCallMapper);
        return c;
    }

    /** 压缩调用挂死:超时后安静返回 false,不能把整轮对话拖死在首 token 之前。 */
    @Test
    void compactIfNeeded_llmHangs_timesOutQuietly() throws Exception
    {
        ContextCompactor compactor = newCompactor(2);
        setField(compactor, "compactTimeoutSeconds", 1L);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(1);
        setField(compactor, "compactionExecutor", pool);
        String conv = "s1:62";
        for (int i = 0; i < 6; i++)
        {
            recorder.insert(conv, "s1", 62L, "USER", "问题" + i, "0", 1200);
            recorder.insert(conv, "s1", 62L, "ASSISTANT", "回答" + i, "0", 1200);
        }
        session.commit();

        long start = System.nanoTime();
        boolean compacted;
        try
        {
            compacted = compactor.compactIfNeeded(conv, "s1", 62L,
                    new HangingChatModel(), 20000, 5000, 7L, "qwen-max");
        }
        finally
        {
            pool.shutdownNow();
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertFalse(compacted, "超时应视为压缩未发生,本轮照常对话");
        assertTrue(elapsedMs < 3_500, "超时应秒级返回而不是等模型, 实际 " + elapsedMs + "ms");
        assertNull(recorder.latestSummary(conv), "超时不得写入摘要");
    }

    /** 挂死模型:调用永不返回,模拟上游半开连接。 */
    private static final class HangingChatModel extends StubChatModel
    {
        HangingChatModel()
        {
            super("");
        }

        @Override
        public ChatResponse call(Prompt prompt)
        {
            try
            {
                Thread.sleep(10_000);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            return super.call(prompt);
        }
    }

    /** 避开 ISysConfigService(要 Redis),threshold 与 target 都给纯计算结果。 */
    private static final class StubBudget extends ContextBudget
    {
        @Override
        public int threshold(Integer window, Integer maxOutput)
        {
            return testInputBudget(window, maxOutput) * 80 / 100;
        }

        @Override
        public int target(Integer window, Integer maxOutput)
        {
            return testInputBudget(window, maxOutput) * 40 / 100;
        }

        private static int testInputBudget(Integer window, Integer maxOutput)
        {
            int w = window != null ? window : 32768;
            int reserved = maxOutput != null ? maxOutput : w / 8;
            return Math.max(w - reserved, w / 2);
        }
    }

    private static class StubChatModel implements ChatModel
    {
        private final String reply;

        StubChatModel(String reply)
        {
            this.reply = reply;
        }

        @Override
        public ChatResponse call(Prompt prompt)
        {
            return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
        }
    }

    /** 记下压缩模型实际收到的 prompt,用来断言历史渲染结果。 */
    private static final class CapturingChatModel extends StubChatModel
    {
        private String lastPrompt = "";

        CapturingChatModel(String reply)
        {
            super(reply);
        }

        @Override
        public ChatResponse call(Prompt prompt)
        {
            lastPrompt = prompt.getInstructions().stream()
                    .map(org.springframework.ai.chat.messages.Message::getText)
                    .reduce("", (a, b) -> a + b);
            return super.call(prompt);
        }
    }

    private static final class FailingChatModel extends StubChatModel
    {
        FailingChatModel()
        {
            super("");
        }

        @Override
        public ChatResponse call(Prompt prompt)
        {
            throw new IllegalStateException("上游炸了");
        }
    }
}
