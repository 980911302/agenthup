package com.ruoyi.system.domain;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 会话表 ai_chat_session
 * <p>
 * 一次对话 = 一个会话，聚合 token 消耗与上下文长度。
 * 具体的各智能体上下文不在表中存储，走文件(sessions/{sessionId}/{agentId}.md)。
 *
 * @author ruoyi
 */
public class AiChatSession extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 会话ID(业务生成，如 uuid) */
    private String sessionId;

    /** 会话标题(可由首条消息摘要生成) */
    private String title;

    /** 会话类型(chat普通对话 job定时任务) */
    private String sessionType;

    /** 客户端形态(desktop/mobile/api)；与 session_type 正交 */
    private String clientType;

    /** 客户端工具清单快照(规范化+排序后的JSON) */
    private String clientTools;

    /** 客户端能力版本；变了才重写清单 */
    private String clientToolsVer;

    /** 来源任务ID(session_type=job 时回指 ai_job) */
    private Long sourceJobId;

    /** 所属项目ID(空=未分组) */
    private Long projectId;

    /** 发起用户ID(关联sys_user) */
    private Long userId;

    /** 会话状态(0活跃 1已结束) */
    private String status;

    /** 会话累计 token(所有 agent 合计) */
    private Long totalTokens;

    /** 累计输入 token */
    private Long promptTokens;

    /** 累计输出 token */
    private Long completionTokens;

    /** 累计 LLM 调用次数 */
    private Integer llmCallCount;

    /** 当前会话总上下文长度(字符数) */
    private Long contextLength;

    /** 会话累计消息条数(ai_chat_message 落表行数,含 THINKING / TOOL 等全部类型) */
    private Integer messageCount;

    // ---- 关联字段(非数据库列，用于详情/列表展示) ----

    /** 本会话参与的智能体列表(关联查询) */
    private java.util.List<AiChatSessionAgent> agents;

    /** 发起用户名称(关联sys_user) */
    private String userName;

    /** 智能体数量(列表页展示用) */
    private Integer agentCount;

    /** 会话主智能体ID(role=supervisor)，前端切换会话时据此恢复智能体选择 */
    private Long supervisorAgentId;

    /** 会话主智能体名称，列表展示用 */
    private String supervisorAgentName;

    /** 会话选中的知识库ID(逗号串，列表页/切换会话恢复用；非数据库列) */
    private String kbIds;

    public String getSessionId()
    {
        return sessionId;
    }

    public void setSessionId(String sessionId)
    {
        this.sessionId = sessionId;
    }

    public String getTitle()
    {
        return title;
    }

    public void setTitle(String title)
    {
        this.title = title;
    }

    public String getSessionType()
    {
        return sessionType;
    }

    public void setSessionType(String sessionType)
    {
        this.sessionType = sessionType;
    }

    public String getClientType()
    {
        return clientType;
    }

    public void setClientType(String clientType)
    {
        this.clientType = clientType;
    }

    public String getClientTools()
    {
        return clientTools;
    }

    public void setClientTools(String clientTools)
    {
        this.clientTools = clientTools;
    }

    public String getClientToolsVer()
    {
        return clientToolsVer;
    }

    public void setClientToolsVer(String clientToolsVer)
    {
        this.clientToolsVer = clientToolsVer;
    }

    public Long getSourceJobId()
    {
        return sourceJobId;
    }

    public void setSourceJobId(Long sourceJobId)
    {
        this.sourceJobId = sourceJobId;
    }

    public Long getProjectId()
    {
        return projectId;
    }

    public void setProjectId(Long projectId)
    {
        this.projectId = projectId;
    }

    public Long getUserId()
    {
        return userId;
    }

    public void setUserId(Long userId)
    {
        this.userId = userId;
    }

    public String getStatus()
    {
        return status;
    }

    public void setStatus(String status)
    {
        this.status = status;
    }

    public Long getTotalTokens()
    {
        return totalTokens;
    }

    public void setTotalTokens(Long totalTokens)
    {
        this.totalTokens = totalTokens;
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

    public Long getContextLength()
    {
        return contextLength;
    }

    public void setContextLength(Long contextLength)
    {
        this.contextLength = contextLength;
    }

    public Integer getMessageCount()
    {
        return messageCount;
    }

    public void setMessageCount(Integer messageCount)
    {
        this.messageCount = messageCount;
    }

    public java.util.List<AiChatSessionAgent> getAgents()
    {
        return agents;
    }

    public void setAgents(java.util.List<AiChatSessionAgent> agents)
    {
        this.agents = agents;
    }

    public String getUserName()
    {
        return userName;
    }

    public void setUserName(String userName)
    {
        this.userName = userName;
    }

    public Integer getAgentCount()
    {
        return agentCount;
    }

    public void setAgentCount(Integer agentCount)
    {
        this.agentCount = agentCount;
    }

    public Long getSupervisorAgentId()
    {
        return supervisorAgentId;
    }

    public void setSupervisorAgentId(Long supervisorAgentId)
    {
        this.supervisorAgentId = supervisorAgentId;
    }

    public String getSupervisorAgentName()
    {
        return supervisorAgentName;
    }

    public void setSupervisorAgentName(String supervisorAgentName)
    {
        this.supervisorAgentName = supervisorAgentName;
    }

    public String getKbIds()
    {
        return kbIds;
    }

    public void setKbIds(String kbIds)
    {
        this.kbIds = kbIds;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
            .append("sessionId", getSessionId())
            .append("title", getTitle())
            .append("sessionType", getSessionType())
            .append("sourceJobId", getSourceJobId())
            .append("userId", getUserId())
            .append("status", getStatus())
            .append("totalTokens", getTotalTokens())
            .append("contextLength", getContextLength())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .append("remark", getRemark())
            .toString();
    }
}
