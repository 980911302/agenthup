package com.ruoyi.system.domain;

/**
 * 会话-知识库关联表 ai_chat_session_kb
 * <p>
 * 一个会话在对话开始(或中途)选定的多个知识库，供 RAG 检索工具使用。
 * 替代原智能体级绑定(ai_agent_kb，已废弃删除)；知识库选择下沉到会话。
 *
 * @author ruoyi
 */
public class AiChatSessionKb
{
    private static final long serialVersionUID = 1L;

    /** 会话ID(关联ai_chat_session) */
    private String sessionId;

    /** 知识库ID(关联kb_knowledge.kb_id) */
    private Long kbId;

    /** 显示顺序(装配检索工具时按此排序) */
    private Integer sort;

    /** 创建时间 */
    private java.util.Date createTime;

    public String getSessionId()
    {
        return sessionId;
    }

    public void setSessionId(String sessionId)
    {
        this.sessionId = sessionId;
    }

    public Long getKbId()
    {
        return kbId;
    }

    public void setKbId(Long kbId)
    {
        this.kbId = kbId;
    }

    public Integer getSort()
    {
        return sort;
    }

    public void setSort(Integer sort)
    {
        this.sort = sort;
    }

    public java.util.Date getCreateTime()
    {
        return createTime;
    }

    public void setCreateTime(java.util.Date createTime)
    {
        this.createTime = createTime;
    }
}
