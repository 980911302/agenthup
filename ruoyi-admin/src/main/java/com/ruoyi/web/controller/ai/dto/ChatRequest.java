package com.ruoyi.web.controller.ai.dto;

/**
 * AI 对话请求
 * <p>
 * 多轮上下文由后端 ChatMemory 按 conversationId(= sessionId:agentId)自动 load/save,
 * 前端不再传历史。sessionId 与 agentId 都是必填 —— 对话统一走「会话 + 智能体」一条路径。
 * <p>
 * 曾经的 modelId / conversationId 字段随「通用对话」下线一并移除:
 * 模型由智能体自身的 modelCode 决定,记忆键由 sessionId + agentId 推导。
 *
 * @author ruoyi
 */
public class ChatRequest
{
    /** 本次用户输入 */
    private String message;

    /** 业务会话ID(关联 ai_chat_session,必填) */
    private String sessionId;

    /** 智能体ID(本次对话由哪个 agent 应答,必填) */
    private Long agentId;

    /** 本次消息携带的会话附件(可空)。文件已在工作区,这里只带元数据 */
    private java.util.List<ChatAttachment> attachments;

    public String getMessage()
    {
        return message;
    }

    public void setMessage(String message)
    {
        this.message = message;
    }

    public String getSessionId()
    {
        return sessionId;
    }

    public void setSessionId(String sessionId)
    {
        this.sessionId = sessionId;
    }

    public Long getAgentId()
    {
        return agentId;
    }

    public void setAgentId(Long agentId)
    {
        this.agentId = agentId;
    }

    public java.util.List<ChatAttachment> getAttachments()
    {
        return attachments;
    }

    public void setAttachments(java.util.List<ChatAttachment> attachments)
    {
        this.attachments = attachments;
    }
}
