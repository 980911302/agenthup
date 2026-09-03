package com.ruoyi.system.ai.memory;

import java.util.List;

import com.ruoyi.system.domain.AiChatMessage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * timeline 查询只给 200 字预览;DbChatMemory 重建必须仍是全文。
 */
class TimelineColumnsTest extends ChatMessageMapperTestSupport
{
    @Test
    void timelineQueryReturnsPreviewAndTrueLength() throws Exception
    {
        String full = "R".repeat(500);
        recorder.recordToolCall("s1:1", "s1", 1L, null, "readFile",
                "{\"path\":\"/long.yml\"}", full, "builtin", 8L, true, null, "call-t");
        session.commit();

        AiChatMessage row = mapper.selectTimelineBySession("s1").stream()
                .filter(m -> "TOOL".equals(m.getMessageType()))
                .findFirst()
                .orElseThrow();
        assertTrue(row.getToolResult().length() <= 200, "timeline 不得下发工具结果全文");
        assertEquals(full.substring(0, 200), row.getToolResult());
        assertEquals(500, row.getToolResultLength());
    }

    @Test
    void dbChatMemoryStillSeesFullToolResult() throws Exception
    {
        setField(recorder, "toolMessagesInContext", true);
        String conv = "s1:7";
        String full = "FULL-TOOL-RESULT-" + "Y".repeat(480);
        recorder.insert(conv, "s1", 7L, "USER", "读一下", "0", 3);
        recorder.recordAssistantToolCalls(conv, "s1", 7L, "我读",
                List.of(new AssistantMessage.ToolCall(
                        "call-full", "function", "readFile", "{\"path\":\"/a\"}")));
        recorder.recordToolCall(conv, "s1", 7L, null, "readFile",
                "{\"path\":\"/a\"}", full, "builtin", 4L, true, null, "call-full");
        session.commit();

        List<Message> msgs = chatMemory.get(conv);
        ToolResponseMessage tool = msgs.stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(full, tool.getResponses().get(0).responseData(),
                "DbChatMemory 重建必须与改动前逐字节一致");
        AiChatMessage byId = mapper.selectMessageById(
                mapper.selectTimelineBySession("s1").stream()
                        .filter(m -> "TOOL".equals(m.getMessageType()))
                        .findFirst().orElseThrow().getMessageId());
        assertEquals(full, byId.getToolResult(), "selectMessageById 必须仍是全文");
    }
}
