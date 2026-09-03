package com.ruoyi.system.ai.memory;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import com.ruoyi.system.ai.agent.ModelInputModalities;
import com.ruoyi.system.domain.AiChatMessage;
import com.ruoyi.system.tool.AiToolProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DbChatMemoryVisionTest
{
    @TempDir
    Path workspace;

    @Test
    void visionOff_skipsHistoryImagesAndAppendsNotice()
    {
        DbChatMemory mem = memory(gateOf(""));

        AiChatMessage row = row("sess-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "[{\"mime\":\"image/png\",\"path\":\"a.png\"}]");
        Message msg = mem.toUserMessage(row, "上一轮图");
        assertTrue(msg instanceof UserMessage);
        UserMessage user = (UserMessage) msg;
        assertTrue(user.getText().contains("历史媒体已省略"));
        assertTrue(user.getText().contains("图片"));
        assertTrue(user.getMedia() == null || user.getMedia().isEmpty());
    }

    @Test
    void visionOn_restoresExistingImage() throws Exception
    {
        String sessionId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        Path dir = workspace.resolve(sessionId);
        Files.createDirectories(dir);
        Files.write(dir.resolve("a.png"), new byte[] { 1, 2, 3, 4 });

        DbChatMemory mem = memory(gateOf("image"));

        AiChatMessage row = row(sessionId, "[{\"mime\":\"image/png\",\"path\":\"a.png\"}]");
        UserMessage user = (UserMessage) mem.toUserMessage(row, "看图");
        assertEquals(1, user.getMedia().size());
        assertFalse(user.getText().contains("已省略"));
    }

    @Test
    void visionOn_missingFileStillSkipped()
    {
        DbChatMemory mem = memory(gateOf("image"));
        AiChatMessage row = row("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "[{\"mime\":\"image/png\",\"path\":\"gone.png\"}]");
        UserMessage user = (UserMessage) mem.toUserMessage(row, "看图");
        assertTrue(user.getMedia() == null || user.getMedia().isEmpty());
        // 文件没了不是"模型收不下",不该出省略说明
        assertFalse(user.getText().contains("已省略"));
    }

    /** 只支持图片的模型:历史里的 PDF 不进 media,并且要说明它没送达。 */
    @Test
    void pdfSkippedWhenFileUnsupported()
    {
        DbChatMemory mem = memory(gateOf("image"));
        AiChatMessage row = row("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "[{\"mime\":\"application/pdf\",\"path\":\"a.pdf\"}]");
        UserMessage user = (UserMessage) mem.toUserMessage(row, "读 pdf");
        assertTrue(user.getMedia() == null || user.getMedia().isEmpty());
        assertTrue(user.getText().contains("文档"));
    }

    /** 支持文档的模型:历史里的 PDF 该跟图片一样被还原。 */
    @Test
    void pdfRestoredWhenFileSupported() throws Exception
    {
        String sessionId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        Path dir = workspace.resolve(sessionId);
        Files.createDirectories(dir);
        Files.write(dir.resolve("a.pdf"), new byte[] { 5, 6 });

        DbChatMemory mem = memory(gateOf("image,file"));
        AiChatMessage row = row(sessionId, "[{\"mime\":\"application/pdf\",\"path\":\"a.pdf\"}]");
        UserMessage user = (UserMessage) mem.toUserMessage(row, "读 pdf");
        assertEquals(1, user.getMedia().size());
    }

    /** 非媒体附件(csv 等)本就靠 read 按需取,既不进 media 也不该报"未送达"。 */
    @Test
    void plainFileAttachmentStaysSilent()
    {
        DbChatMemory mem = memory(gateOf("image,file"));
        AiChatMessage row = row("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "[{\"mime\":\"text/csv\",\"path\":\"a.csv\"}]");
        UserMessage user = (UserMessage) mem.toUserMessage(row, "看数据");
        assertTrue(user.getMedia() == null || user.getMedia().isEmpty());
        assertFalse(user.getText().contains("已省略"));
    }

    /** 门控返回 null 时按纯文本降级,不能让 NPE 被兜底 catch 吞掉。 */
    @Test
    void nullModalitiesDegradesGracefully()
    {
        ConversationVisionGate gate = mock(ConversationVisionGate.class);
        when(gate.modalities(anyString())).thenReturn(null);
        DbChatMemory mem = memory(gate);
        AiChatMessage row = row("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "[{\"mime\":\"image/png\",\"path\":\"a.png\"}]");
        UserMessage user = (UserMessage) mem.toUserMessage(row, "看图");
        assertTrue(user.getMedia() == null || user.getMedia().isEmpty());
        assertTrue(user.getText().contains("已省略"));
    }

    private ConversationVisionGate gateOf(String modalities)
    {
        ConversationVisionGate gate = mock(ConversationVisionGate.class);
        when(gate.modalities(anyString())).thenReturn(ModelInputModalities.parse(modalities));
        return gate;
    }

    private DbChatMemory memory(ConversationVisionGate gate)
    {
        DbChatMemory mem = new DbChatMemory();
        try
        {
            ChatMessageMapperTestSupport.setField(mem, "visionGate", gate);
            AiToolProperties props = new AiToolProperties();
            props.setWorkspaceRoot(workspace.toAbsolutePath().toString());
            props.setWorkspacePerSession(true);
            ChatMessageMapperTestSupport.setField(mem, "aiToolProperties", props);
            // DbChatMemory 已从静态 WorkspaceSandbox.resolveRoot 改为注入 WorkspaceScopeService。
            // 裸构造时该字段为 null,附件还原会整段吞异常 -> 历史图片一张都还原不出来。
            // 这里给一个 sessionMapper 查不到项目的实例:resolveWorkspaceKey 回落 sessionId,
            // 与改造前的静态调用行为完全一致。
            com.ruoyi.system.tool.WorkspaceScopeService scope =
                    new com.ruoyi.system.tool.WorkspaceScopeService();
            ChatMessageMapperTestSupport.setField(scope, "sessionMapper",
                    org.mockito.Mockito.mock(com.ruoyi.system.mapper.AiChatSessionMapper.class));
            ChatMessageMapperTestSupport.setField(mem, "workspaceScopeService", scope);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
        return mem;
    }

    private static AiChatMessage row(String sessionId, String attachments)
    {
        AiChatMessage m = new AiChatMessage();
        m.setConversationId(sessionId + ":6");
        m.setSessionId(sessionId);
        m.setAttachments(attachments);
        return m;
    }
}
