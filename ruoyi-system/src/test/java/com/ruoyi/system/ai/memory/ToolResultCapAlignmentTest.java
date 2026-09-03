package com.ruoyi.system.ai.memory;

import com.ruoyi.system.ai.context.ToolResultText;
import com.ruoyi.system.domain.AiChatMessage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端锁「工具结果口径三处同源」:<b>本轮所见 == 跨轮重建 == tokens 依据</b>。
 *
 * <p>只测 {@link ToolResultText} 的算法不够 —— 历史上出问题的正是三个<b>调用点</b>各自
 * 拼公式:行数维度只加在本轮那一份,跨轮重建与 tokens 都漏了。所以这里从落库到重建走一遍真库
 * (H2),断言三个数值真的对得上。
 */
class ToolResultCapAlignmentTest extends ChatMessageMapperTestSupport
{
    private static final int MAX_LINES = ToolResultText.DEFAULT_MAX_LINES;
    private static final int MAX_CHARS = ToolResultText.DEFAULT_MAX_CHARS;

    /**
     * 回归:行多字符少的输出(ls -R / find / 大 CSV)。
     *
     * <p>修复前跨轮重建只做字符截断,这类结果在下一轮会自己长回全量 ——
     * 重建出的不是模型当时看到的那条消息,消息前缀也从这里断掉。
     */
    @Test
    void lineHeavyResult_rebuildMatchesWhatModelSawInTurn() throws Exception
    {
        setField(recorder, "toolMessagesInContext", true);
        setField(chatMemory, "maxResultLines", MAX_LINES);
        setField(chatMemory, "maxResultChars", MAX_CHARS);

        // 行短、行多:4000 行约 2.3 万字符,字符数不超但行数翻倍 —— 正是行数维度要治的形态
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4000; i++)
        {
            sb.append("f").append(i).append('\n');
        }
        String raw = sb.toString();
        assertTrue(raw.length() < MAX_CHARS,
                "构造前提:字符数没超,只有行数超 —— 否则测不到行数维度,实际=" + raw.length());

        // 本轮:RecordingToolCallback.capForModel 交给模型的就是这一段
        String seenInTurn = ToolResultText.cap(raw, MAX_LINES, MAX_CHARS);
        assertNotEquals(raw, seenInTurn, "构造前提:本轮确实被行数截断了");

        String conv = "s1:70";
        recorder.insert(conv, "s1", 70L, "USER", "列一下源码文件", "0", 8);
        recorder.recordAssistantToolCalls(conv, "s1", 70L, "我扫一下目录",
                List.of(new AssistantMessage.ToolCall(
                        "call_1", "function", "runShell", "{\"cmd\":\"find ./src\"}")));
        // 落库存全文,contextText 传本轮真正交给模型的那段
        long toolId = recorder.recordToolCall(conv, "s1", 70L, null, "runShell",
                "{\"cmd\":\"find ./src\"}", raw, "builtin", 30L, true,
                null, "call_1", null, "call_1", null, seenInTurn);
        session.commit();

        // ① 跨轮重建 == 本轮所见
        String rebuilt = firstToolResponseText(chatMemory.get(conv));
        assertEquals(seenInTurn, rebuilt, "跨轮重建必须还原成模型当时看到的那一段");

        // ② tokens 依据 == 本轮所见
        AiChatMessage row = mapper.selectMessageById(toolId);
        assertEquals(tokenEstimator.estimate(seenInTurn), (int) row.getTokens(),
                "tokens 必须按进上下文的那段算,不能按全文");

        // ③ 表里仍是全文,审计不受截断影响
        assertEquals(raw, row.getToolResult(), "落库存全文,截断只作用于进上下文的那份");
    }

    /** 字符维度同样成立,且重建不会在已截断的文本上二次劣化(cap 幂等)。 */
    @Test
    void charHeavyResult_rebuildIsStableAcrossTurns() throws Exception
    {
        setField(recorder, "toolMessagesInContext", true);
        setField(chatMemory, "maxResultLines", MAX_LINES);
        setField(chatMemory, "maxResultChars", MAX_CHARS);

        String raw = "X".repeat(100_000);
        String seenInTurn = ToolResultText.cap(raw, MAX_LINES, MAX_CHARS);

        String conv = "s1:71";
        recorder.insert(conv, "s1", 71L, "USER", "读那个大文件", "0", 8);
        recorder.recordAssistantToolCalls(conv, "s1", 71L, "",
                List.of(new AssistantMessage.ToolCall(
                        "call_2", "function", "readFile", "{\"path\":\"/big.log\"}")));
        recorder.recordToolCall(conv, "s1", 71L, null, "readFile",
                "{\"path\":\"/big.log\"}", raw, "builtin", 20L, true,
                null, "call_2", null, "call_2", null, seenInTurn);
        session.commit();

        String first = firstToolResponseText(chatMemory.get(conv));
        String second = firstToolResponseText(chatMemory.get(conv));
        assertEquals(seenInTurn, first);
        assertEquals(first, second, "同一条工具结果每轮重建必须一模一样,否则前缀缓存每轮都断");
    }

    private static String firstToolResponseText(List<Message> msgs)
    {
        for (Message m : msgs)
        {
            if (m instanceof ToolResponseMessage trm && !trm.getResponses().isEmpty())
            {
                return trm.getResponses().get(0).responseData();
            }
        }
        throw new AssertionError("重建结果里没有 tool 响应消息");
    }
}
