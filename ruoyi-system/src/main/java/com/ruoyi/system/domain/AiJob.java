package com.ruoyi.system.domain;

import java.util.Date;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 智能体定时任务表 ai_job
 * <p>
 * 与 sys_job 语义不重叠:本表载荷是自然语言 prompt,权限归属 owner_user_id。
 * 底层 Quartz 复用,调度对象/权限/UI 独立。
 *
 * @author ruoyi
 */
public class AiJob extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 任务ID */
    private Long jobId;

    /** 任务名称 */
    private String jobName;

    /** 执行智能体ID(关联ai_agent) */
    private Long agentId;

    /** 触发时投喂给智能体的指令 */
    private String prompt;

    /** 固定附件元数据[{path,name,size}],JSON 文本 */
    private String attachments;

    /** 触发类型(cron周期 once一次性) */
    private String triggerType;

    /** cron表达式(trigger_type=cron时必填) */
    private String cronExpression;

    /** 执行时刻(trigger_type=once时必填) */
    private Date fireTime;

    /** 时区(不存则服务器迁移后 cron 会漂移) */
    private String timezone;

    /** 错过策略(2补跑一次 3放弃执行) */
    private String misfirePolicy;

    /** 会话模式(new每次新建 fixed固定追加) */
    private String sessionMode;

    /** fixed模式绑定的会话ID */
    private String sessionId;

    /** 单次运行超时(秒) */
    private Integer timeoutSeconds;

    /** 失败重试次数 */
    private Integer maxRetry;

    /** 累计执行上限(null不限),到达自动转已完成 */
    private Integer maxRuns;

    /** 过期时间,到期自动转已完成 */
    private Date expireTime;

    /** 创建来源(user后台手建 agent智能体自建) */
    private String source;

    /** agent自建时的来源运行ID,可溯源到用户原话 */
    private String sourceRunId;

    /** 归属用户(执行时以其身份鉴权) */
    private Long ownerUserId;

    /** 状态(0正常 1暂停 2已完成) */
    private String status;

    /** 上次触发时间 */
    private Date prevFireTime;

    /** 下次触发时间 */
    private Date nextFireTime;

    /** 累计触发次数 */
    private Integer runCount;

    /** 累计失败次数 */
    private Integer failCount;

    /** 最近一次运行ID */
    private String lastRunId;

    /** 最近一次结果 */
    private String lastStatus;

    /** 删除标志(0存在 2删除) */
    private String delFlag;

    // ---- 展示字段(列表页联表,不落库) ----

    /** 智能体名称 */
    private String agentName;

    /** 智能体图标(列表展示,不落库) */
    private String agentIcon;

    /** 智能体主题色序号(列表展示,不落库) */
    private String agentTheme;

    /** 智能体编码(配色 key,不落库) */
    private String agentCode;

    /** 归属用户名 */
    private String ownerName;

    public Long getJobId()
    {
        return jobId;
    }

    public void setJobId(Long jobId)
    {
        this.jobId = jobId;
    }

    public String getJobName()
    {
        return jobName;
    }

    public void setJobName(String jobName)
    {
        this.jobName = jobName;
    }

    public Long getAgentId()
    {
        return agentId;
    }

    public void setAgentId(Long agentId)
    {
        this.agentId = agentId;
    }

    public String getPrompt()
    {
        return prompt;
    }

    public void setPrompt(String prompt)
    {
        this.prompt = prompt;
    }

    public String getAttachments()
    {
        return attachments;
    }

    public void setAttachments(String attachments)
    {
        this.attachments = attachments;
    }

    public String getTriggerType()
    {
        return triggerType;
    }

    public void setTriggerType(String triggerType)
    {
        this.triggerType = triggerType;
    }

    public String getCronExpression()
    {
        return cronExpression;
    }

    public void setCronExpression(String cronExpression)
    {
        this.cronExpression = cronExpression;
    }

    public Date getFireTime()
    {
        return fireTime;
    }

    public void setFireTime(Date fireTime)
    {
        this.fireTime = fireTime;
    }

    public String getTimezone()
    {
        return timezone;
    }

    public void setTimezone(String timezone)
    {
        this.timezone = timezone;
    }

    public String getMisfirePolicy()
    {
        return misfirePolicy;
    }

    public void setMisfirePolicy(String misfirePolicy)
    {
        this.misfirePolicy = misfirePolicy;
    }

    public String getSessionMode()
    {
        return sessionMode;
    }

    public void setSessionMode(String sessionMode)
    {
        this.sessionMode = sessionMode;
    }

    public String getSessionId()
    {
        return sessionId;
    }

    public void setSessionId(String sessionId)
    {
        this.sessionId = sessionId;
    }

    public Integer getTimeoutSeconds()
    {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(Integer timeoutSeconds)
    {
        this.timeoutSeconds = timeoutSeconds;
    }

    public Integer getMaxRetry()
    {
        return maxRetry;
    }

    public void setMaxRetry(Integer maxRetry)
    {
        this.maxRetry = maxRetry;
    }

    public Integer getMaxRuns()
    {
        return maxRuns;
    }

    public void setMaxRuns(Integer maxRuns)
    {
        this.maxRuns = maxRuns;
    }

    public Date getExpireTime()
    {
        return expireTime;
    }

    public void setExpireTime(Date expireTime)
    {
        this.expireTime = expireTime;
    }

    public String getSource()
    {
        return source;
    }

    public void setSource(String source)
    {
        this.source = source;
    }

    public String getSourceRunId()
    {
        return sourceRunId;
    }

    public void setSourceRunId(String sourceRunId)
    {
        this.sourceRunId = sourceRunId;
    }

    public Long getOwnerUserId()
    {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId)
    {
        this.ownerUserId = ownerUserId;
    }

    public String getStatus()
    {
        return status;
    }

    public void setStatus(String status)
    {
        this.status = status;
    }

    public Date getPrevFireTime()
    {
        return prevFireTime;
    }

    public void setPrevFireTime(Date prevFireTime)
    {
        this.prevFireTime = prevFireTime;
    }

    public Date getNextFireTime()
    {
        return nextFireTime;
    }

    public void setNextFireTime(Date nextFireTime)
    {
        this.nextFireTime = nextFireTime;
    }

    public Integer getRunCount()
    {
        return runCount;
    }

    public void setRunCount(Integer runCount)
    {
        this.runCount = runCount;
    }

    public Integer getFailCount()
    {
        return failCount;
    }

    public void setFailCount(Integer failCount)
    {
        this.failCount = failCount;
    }

    public String getLastRunId()
    {
        return lastRunId;
    }

    public void setLastRunId(String lastRunId)
    {
        this.lastRunId = lastRunId;
    }

    public String getLastStatus()
    {
        return lastStatus;
    }

    public void setLastStatus(String lastStatus)
    {
        this.lastStatus = lastStatus;
    }

    public String getDelFlag()
    {
        return delFlag;
    }

    public void setDelFlag(String delFlag)
    {
        this.delFlag = delFlag;
    }

    public String getAgentName()
    {
        return agentName;
    }

    public void setAgentName(String agentName)
    {
        this.agentName = agentName;
    }

    public String getAgentIcon()
    {
        return agentIcon;
    }

    public void setAgentIcon(String agentIcon)
    {
        this.agentIcon = agentIcon;
    }

    public String getAgentTheme()
    {
        return agentTheme;
    }

    public void setAgentTheme(String agentTheme)
    {
        this.agentTheme = agentTheme;
    }

    public String getAgentCode()
    {
        return agentCode;
    }

    public void setAgentCode(String agentCode)
    {
        this.agentCode = agentCode;
    }

    public String getOwnerName()
    {
        return ownerName;
    }

    public void setOwnerName(String ownerName)
    {
        this.ownerName = ownerName;
    }

    @Override
    public String toString()
    {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
            .append("jobId", getJobId())
            .append("jobName", getJobName())
            .append("agentId", getAgentId())
            .append("prompt", getPrompt())
            .append("attachments", getAttachments())
            .append("triggerType", getTriggerType())
            .append("cronExpression", getCronExpression())
            .append("fireTime", getFireTime())
            .append("timezone", getTimezone())
            .append("misfirePolicy", getMisfirePolicy())
            .append("sessionMode", getSessionMode())
            .append("sessionId", getSessionId())
            .append("timeoutSeconds", getTimeoutSeconds())
            .append("maxRetry", getMaxRetry())
            .append("maxRuns", getMaxRuns())
            .append("expireTime", getExpireTime())
            .append("source", getSource())
            .append("sourceRunId", getSourceRunId())
            .append("ownerUserId", getOwnerUserId())
            .append("status", getStatus())
            .append("prevFireTime", getPrevFireTime())
            .append("nextFireTime", getNextFireTime())
            .append("runCount", getRunCount())
            .append("failCount", getFailCount())
            .append("lastRunId", getLastRunId())
            .append("lastStatus", getLastStatus())
            .append("delFlag", getDelFlag())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .append("remark", getRemark())
            .toString();
    }
}
