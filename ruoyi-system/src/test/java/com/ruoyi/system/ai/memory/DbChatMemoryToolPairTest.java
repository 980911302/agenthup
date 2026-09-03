package com.ruoyi.system.ai.memory;

import com.ruoyi.system.domain.AiChatMessage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具往返跨轮保留:落库形态与重建配对。
 *
 * <p>核心不变量:{@code assistant(tool_calls)} 里每一个 id,都必须在紧随其后的 tool 消息里
 * 找到对应响应。缺一个就是上游 400,这条比"内容对不对"重要得多。
 */
class DbChatMemoryToolPairTest extends ChatMessageMapperTestSupport
{
    @Test
    void recordAssistantToolCalls_persistsToolCallsJson() throws Exception
    {
        setField(recorder, "toolMessagesInContext", true);
        String conv = "s1:40";

        long id = recorder.recordAssistantToolCalls(conv, "s1", 40L, "我先读一下配置",
                List.of(new AssistantMessage.ToolCall(
                        "call_1", "function", "readFile", "{\"path\":\"/app.yml\"}")));
        session.commit();

        assertTrue(id > 0, "应返回新行的 message_id");
        AiChatMessage row = mapper.selectMessageById(id);
        assertEquals("ASSISTANT", row.getMessageType());
        assertEquals("0", row.getVisibleToLlm(), "开关开启时中间 assistant 必须参与上下文");
        assertEquals("我先读一下配置", row.getContent(), "调工具前说的话不能丢");
        assertNotNull(row.getToolCalls());
        assertTrue(row.getToolCalls().contains("call_1"), "tool_calls JSON 必须含 id");
        assertTrue(row.getToolCalls().contains("readFile"), "tool_calls JSON 必须含工具名");
        assertTrue(row.getToolCalls().contains("/app.yml"), "tool_calls JSON 必须含原始入参");
    }

    /** 开关关闭时写 visible='1',老行为不受影响 —— 这是灰度与回滚的依据 */
    @Test
    void recordAssistantToolCalls_respectsSwitch() throws Exception
    {
        setField(recorder, "toolMessagesInContext", false);
        long id = recorder.recordAssistantToolCalls("s1:41", "s1", 41L, "",
                List.of(new AssistantMessage.ToolCall("call_x", "function", "readFile", "{}")));
        session.commit();

        assertEquals("1", mapper.selectMessageById(id).getVisibleToLlm(),
                "开关关闭时不得进上下文");
    }

    @Test
    void recordAssistantToolCalls_emptyToolCalls_writesNothing() throws Exception
    {
        setField(recorder, "toolMessagesInContext", true);
        assertEquals(0L, recorder.recordAssistantToolCalls("s1:42", "s1", 42L, "x", List.of()),
                "没有 tool_calls 就不该产生中间 assistant 行");
    }

    /** 主链路:一轮工具往返落库后,下一轮必须原样重建成配对消息 */
    @Test
    void get_rebuildsToolPair() throws Exception
    {
        setField(recorder, "toolMessagesInContext", true);
        String conv = "s1:50";

        recorder.insert(conv, "s1", 50L, "USER", "看看 app.yml 写了啥", "0", 8);
        recorder.recordAssistantToolCalls(conv, "s1", 50L, "我读一下",
                List.of(new AssistantMessage.ToolCall(
                        "call_1", "function", "readFile", "{\"path\":\"/app.yml\"}")));
        recorder.recordToolCall(conv, "s1", 50L, null, "readFile",
                "{\"path\":\"/app.yml\"}", "server:\n  port: 8080", "builtin", 12L, true,
                null, "call_1");
        recorder.insert(conv, "s1", 50L, "ASSISTANT", "端口是 8080", "0", 6);
        session.commit();

        List<Message> msgs = chatMemory.get(conv);

        assertEquals(4, msgs.size(), "USER + assistant(tool_calls) + tool + 最终 assistant");
        AssistantMessage am = assertInstanceOf(AssistantMessage.class, msgs.get(1));
        assertTrue(am.hasToolCalls(), "第二条必须带 tool_calls");
        assertEquals("call_1", am.getToolCalls().get(0).id());
        assertEquals("readFile", am.getToolCalls().get(0).name());
        assertEquals("我读一下", am.getText(), "调工具前说的话要还原");

        ToolResponseMessage trm = assertInstanceOf(ToolResponseMessage.class, msgs.get(2));
        assertEquals(1, trm.getResponses().size());
        assertEquals("call_1", trm.getResponses().get(0).id(), "id 配不上就是上游 400");
        assertTrue(trm.getResponses().get(0).responseData().contains("8080"),
                "工具结果原文必须进上下文,这正是本次改造的目的");
    }

    /** 一个 assistant 发起多个并行调用时,每个 id 都要有响应 */
    @Test
    void get_rebuildsParallelToolCalls() throws Exception
    {
        setField(recorder, "toolMessagesInContext", true);
        String conv = "s1:51";

        recorder.recordAssistantToolCalls(conv, "s1", 51L, "",
                List.of(new AssistantMessage.ToolCall("call_a", "function", "readFile", "{\"path\":\"/a\"}"),
                        new AssistantMessage.ToolCall("call_b", "function", "readFile", "{\"path\":\"/b\"}")));
        recorder.recordToolCall(conv, "s1", 51L, null, "readFile", "{\"path\":\"/a\"}",
                "AAA", "builtin", 1L, true, null, "call_a");
        recorder.recordToolCall(conv, "s1", 51L, null, "readFile", "{\"path\":\"/b\"}",
                "BBB", "builtin", 1L, true, null, "call_b");
        session.commit();

        List<Message> msgs = chatMemory.get(conv);
        ToolResponseMessage trm = assertInstanceOf(ToolResponseMessage.class, msgs.get(1));
        assertEquals(2, trm.getResponses().size(), "两个并行调用要有两条响应");
        assertEquals("call_a", trm.getResponses().get(0).id());
        assertEquals("call_b", trm.getResponses().get(1).id());
    }

    /** 鲁棒性:tool 行缺失时必须补一条合成响应,否则协议会返回 400。 */
    @Test
    void get_synthesizesMissingToolResponse() throws Exception
    {
        setField(recorder, "toolMessagesInContext", true);
        String conv = "s1:52";

        recorder.recordAssistantToolCalls(conv, "s1", 52L, "",
                List.of(new AssistantMessage.ToolCall("call_a", "function", "readFile", "{}"),
                        new AssistantMessage.ToolCall("call_b", "function", "readFile", "{}")));
        recorder.recordToolCall(conv, "s1", 52L, null, "readFile", "{}",
                "AAA", "builtin", 1L, true, null, "call_a");
        session.commit();

        List<Message> msgs = chatMemory.get(conv);
        ToolResponseMessage trm = assertInstanceOf(ToolResponseMessage.class, msgs.get(1));
        assertEquals(2, trm.getResponses().size(), "缺的那个必须补出来");
        assertEquals("call_b", trm.getResponses().get(1).id());
    }

    /** pruned='1' 的行装配成占位,原文留在库里供审计 */
    @Test
    void get_prunedRow_yieldsPlaceholder() throws Exception
    {
        setField(recorder, "toolMessagesInContext", true);
        String conv = "s1:53";

        recorder.recordAssistantToolCalls(conv, "s1", 53L, "",
                List.of(new AssistantMessage.ToolCall(
                        "call_1", "function", "readFile", "{\"path\":\"/big.csv\"}")));
        long toolId = recorder.recordToolCall(conv, "s1", 53L, null, "readFile",
                "{\"path\":\"/big.csv\"}", "一大堆内容", "builtin", 1L, true, null, "call_1");
        session.commit();

        mapper.markPrunedForTest(toolId);
        session.commit();

        List<Message> msgs = chatMemory.get(conv);
        ToolResponseMessage trm = assertInstanceOf(ToolResponseMessage.class, msgs.get(1));
        String data = trm.getResponses().get(0).responseData();
        assertTrue(data.startsWith("[已精简] "), "被清理的结果应出占位");
        assertFalse(data.contains("一大堆内容"), "原文不该再进上下文");
        assertEquals("一大堆内容", mapper.selectMessageById(toolId).getToolResult(),
                "库里原文必须完好 —— 审计与前端时间线还要用");
    }

    /** 孤儿 TOOL 行(前面没有 assistant)必须丢弃,否则协议直接崩 */
    @Test
    void get_orphanToolRow_isDropped() throws Exception
    {
        setField(recorder, "toolMessagesInContext", true);
        String conv = "s1:54";

        recorder.insert(conv, "s1", 54L, "USER", "问题", "0", 3);
        recorder.recordToolCall(conv, "s1", 54L, null, "readFile", "{}",
                "结果", "builtin", 1L, true, null, "call_orphan");
        session.commit();

        List<Message> msgs = chatMemory.get(conv);
        assertEquals(1, msgs.size(), "只应剩 USER,孤儿 tool 行被丢弃");
        assertFalse(msgs.stream().anyMatch(m -> m instanceof ToolResponseMessage));
    }
}
