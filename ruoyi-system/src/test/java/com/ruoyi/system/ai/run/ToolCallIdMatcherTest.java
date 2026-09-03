package com.ruoyi.system.ai.run;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 锁住 tool_call_id 的分发规则。
 *
 * <p>拿不到 id 就无法重建 assistant(tool_calls)+tool 的配对,而配不上就是上游 400。
 */
class ToolCallIdMatcherTest
{
    @Test
    void take_matchesByNameAndArguments()
    {
        ToolCallIdMatcher m = new ToolCallIdMatcher(List.of(
                new AssistantMessage.ToolCall("call_a", "function", "readFile", "{\"path\":\"/a\"}"),
                new AssistantMessage.ToolCall("call_b", "function", "writeFile", "{\"path\":\"/b\"}")));

        assertEquals("call_b", m.take("writeFile", "{\"path\":\"/b\"}"));
        assertEquals("call_a", m.take("readFile", "{\"path\":\"/a\"}"));
    }

    /** 同名同参的两次调用必须各自拿到一个 id,不能都拿第一个 —— 否则少一条 tool 响应 */
    @Test
    void take_isConsuming_soDuplicateCallsGetDistinctIds()
    {
        ToolCallIdMatcher m = new ToolCallIdMatcher(List.of(
                new AssistantMessage.ToolCall("call_1", "function", "readFile", "{\"path\":\"/x\"}"),
                new AssistantMessage.ToolCall("call_2", "function", "readFile", "{\"path\":\"/x\"}")));

        assertEquals("call_1", m.take("readFile", "{\"path\":\"/x\"}"));
        assertEquals("call_2", m.take("readFile", "{\"path\":\"/x\"}"));
        assertNull(m.take("readFile", "{\"path\":\"/x\"}"), "取完就该是 null,不能循环发放");
    }

    @Test
    void take_unknownCall_returnsNull()
    {
        ToolCallIdMatcher m = new ToolCallIdMatcher(List.of(
                new AssistantMessage.ToolCall("call_1", "function", "readFile", "{}")));

        assertNull(m.take("deleteFile", "{}"), "没匹配上必须返回 null,不能瞎发一个 id");
    }

    @Test
    void nullCalls_doesNotThrow()
    {
        assertNull(new ToolCallIdMatcher(null).take("any", "{}"));
    }
}
