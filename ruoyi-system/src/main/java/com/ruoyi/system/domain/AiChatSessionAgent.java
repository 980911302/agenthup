package com.ruoyi.system.domain;

/**
 * 会话-智能体关联表 ai_chat_session_agent
 * <p>
 * 一个会话里参与过哪些智能体、各自消耗多少 token。
 * role 区分 supervisor / worker，便于成本归因。
 *
 * @author ruoyi
 */
public class AiChatSessionAgent
{
    private static final long serialVersionUID = 1L;

    /** 主键 */
    private Long id;

    /** 会话ID */
    private String sessionId;

    /** 智能体ID */
    private Long agentId;

    /** 本会话中的角色(supervisor/worker) */
    private String role;

    /** 该智能体在本会话消耗的 token */
    private Long tokensUsed;

    /** 累计输入 token */
    private Long promptTokens;

    /** 累计输出 token */
    private Long completionTokens;

    /** 累计 LLM 调用次数 */
    private Integer llmCallCount;

    /** 该智能体在本会话被调用的轮数 */
    private Integer turnCount;

    /** 首次接入时间 */
    private java.util.Date firstActiveTime;

    /** 最近活动时间 */
    private java.util.Date lastActiveTime;

    /** 创建时间 */
    private java.util.Date createTime;

    // ---- 展示字段(关联查询) ----

    /** 智能体名称(关联ai_agent) */
    private String agentName;

    /** 智能体编码(关联ai_agent) */
    private String agentCode;

    /** 智能体图标(关联ai_agent) */
    private String agentIcon;

    public Long getId()
    {
        return id;
    }

    public void setId(Long id)
    {
        this.id = id;
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

    public String getRole()
    {
        return role;
    }

    public void setRole(String role)
    {
        this.role = role;
    }

    public Long getTokensUsed()
    {
        return tokensUsed;
    }

    public void setTokensUsed(Long tokensUsed)
    {
        this.tokensUsed = tokensUsed;
    }

    public Long getPromptTokens()
    {
        return promptTokens;
    }

    public void setPromptTokens(Long promptTokens)
    {
        this.promptTokens = promptTokens;
    }

    public Long getCompletionTokens()
    {
        return completionTokens;
    }

    public void setCompletionTokens(Long completionTokens)
    {
        this.completionTokens = completionTokens;
    }

    public Integer getLlmCallCount()
    {
        return llmCallCount;
    }

    public void setLlmCallCount(Integer llmCallCount)
    {
        this.llmCallCount = llmCallCount;
    }

    public Integer getTurnCount()
    {
        return turnCount;
    }

    public void setTurnCount(Integer turnCount)
    {
        this.turnCount = turnCount;
    }

    public java.util.Date getFirstActiveTime()
    {
        return firstActiveTime;
    }

    public void setFirstActiveTime(java.util.Date firstActiveTime)
    {
        this.firstActiveTime = firstActiveTime;
    }

    public java.util.Date getLastActiveTime()
    {
        return lastActiveTime;
    }

    public void setLastActiveTime(java.util.Date lastActiveTime)
    {
        this.lastActiveTime = lastActiveTime;
    }

    public java.util.Date getCreateTime()
    {
        return createTime;
    }

    public void setCreateTime(java.util.Date createTime)
    {
        this.createTime = createTime;
    }

    public String getAgentName()
    {
        return agentName;
    }

    public void setAgentName(String agentName)
    {
        this.agentName = agentName;
    }

    public String getAgentCode()
    {
        return agentCode;
    }

    public void setAgentCode(String agentCode)
    {
        this.agentCode = agentCode;
    }

    public String getAgentIcon()
    {
        return agentIcon;
    }

    public void setAgentIcon(String agentIcon)
    {
        this.agentIcon = agentIcon;
    }
}
