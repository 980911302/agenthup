package com.ruoyi.system.domain;

/**
 * 智能体-子智能体关联表 ai_agent_child
 *
 * @author ruoyi
 */
public class AiAgentChild
{
    private static final long serialVersionUID = 1L;

    /** 父智能体ID */
    private Long parentAgentId;

    /** 子智能体ID */
    private Long childAgentId;

    /** 排序 */
    private Integer sort;

    /** 触发描述(什么时候调用这个子智能体) */
    private String triggerDesc;

    // ---- 展示字段(关联查询) ----
    /** 子智能体名称(关联ai_agent) */
    private String childAgentName;

    /** 子智能体编码(关联ai_agent) */
    private String childAgentCode;

    public Long getParentAgentId()
    {
        return parentAgentId;
    }

    public void setParentAgentId(Long parentAgentId)
    {
        this.parentAgentId = parentAgentId;
    }

    public Long getChildAgentId()
    {
        return childAgentId;
    }

    public void setChildAgentId(Long childAgentId)
    {
        this.childAgentId = childAgentId;
    }

    public Integer getSort()
    {
        return sort;
    }

    public void setSort(Integer sort)
    {
        this.sort = sort;
    }

    public String getTriggerDesc()
    {
        return triggerDesc;
    }

    public void setTriggerDesc(String triggerDesc)
    {
        this.triggerDesc = triggerDesc;
    }

    public String getChildAgentName()
    {
        return childAgentName;
    }

    public void setChildAgentName(String childAgentName)
    {
        this.childAgentName = childAgentName;
    }

    public String getChildAgentCode()
    {
        return childAgentCode;
    }

    public void setChildAgentCode(String childAgentCode)
    {
        this.childAgentCode = childAgentCode;
    }
}
