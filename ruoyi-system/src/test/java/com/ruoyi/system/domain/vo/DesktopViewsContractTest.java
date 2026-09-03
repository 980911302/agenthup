package com.ruoyi.system.domain.vo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.system.ai.run.ChatRunState;
import com.ruoyi.system.domain.AiAgent;
import com.ruoyi.system.domain.AiChatMessage;
import com.ruoyi.system.domain.AiChatRun;
import com.ruoyi.system.domain.KbDocument;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 防止 desktop 响应重新直接序列化后台领域实体而泄露内部字段。 */
class DesktopViewsContractTest
{
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void agentOptionMustNotExposePromptOrManagementConfig() throws Exception
    {
        AiAgent agent = new AiAgent();
        agent.setAgentId(1L);
        agent.setAgentName("助手");
        agent.setAgentRole("绝不能发送给客户端的系统提示词");
        agent.setModelCode("internal-model");
        agent.setToolIds(new Long[] { 99L });

        String json = objectMapper.writeValueAsString(DesktopViews.agentOption(agent));

        assertTrue(json.contains("\"agentName\""));
        assertFalse(json.contains("agentRole"));
        assertFalse(json.contains("modelCode"));
        assertFalse(json.contains("toolIds"));
    }

    @Test
    void documentItemMustNotExposeStoredFilePath() throws Exception
    {
        KbDocument document = new KbDocument();
        document.setDocId(1L);
        document.setDocName("内部文档.pdf");
        document.setFilePath("/profile/upload/kb/1/internal.pdf");

        String json = objectMapper.writeValueAsString(DesktopViews.document(document));

        assertTrue(json.contains("\"docName\""));
        assertFalse(json.contains("filePath"));
        assertFalse(json.contains("/profile/upload"));
    }

    @Test
    void chatRunStateMustNotExposeInternalIdentifiersOrResultPath() throws Exception
    {
        AiChatRun run = new AiChatRun();
        run.setRunId("run-1");
        run.setSessionId("session-1");
        run.setUserId(10L);
        run.setClientRequestId("private-idempotency-key");
        run.setActiveKey("10:session-1");
        run.setWorkerId("worker-a");

        AiChatMessage message = new AiChatMessage();
        message.setMessageId(2L);
        message.setSessionId("session-1");
        message.setConversationId("session-1:1");
        message.setVisibleToLlm("0");
        message.setToolResult("ok");
        message.setToolResultPath("/private/tool-result.json");

        String json = objectMapper.writeValueAsString(DesktopViews.state(
                new ChatRunState(run, message, null, List.of(message), List.of(), 3L)));

        assertTrue(json.contains("\"sessionId\""));
        assertTrue(json.contains("\"hasFullToolResult\":true"));
        assertFalse(json.contains("userId"));
        assertFalse(json.contains("clientRequestId"));
        assertFalse(json.contains("activeKey"));
        assertFalse(json.contains("workerId"));
        assertFalse(json.contains("conversationId"));
        assertFalse(json.contains("visibleToLlm"));
        assertFalse(json.contains("toolResultPath"));
        assertFalse(json.contains("/private/tool-result.json"));
    }
}
