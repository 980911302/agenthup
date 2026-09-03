package com.ruoyi.system.domain;

import java.util.Date;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 智能体定时任务触发日志 ai_job_log
 * <p>
 * 不能用 ai_chat_run 代替:调度视角记录(含未创建出 run 的跳过/misfire),
 * 以及 scheduled_time 与 fire_time 的调度延迟。
 *
 * @author ruoyi
 */
public class AiJobLog extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 日志ID */
    private Long logId;

    /** 任务ID */
    private Long jobId;

    /** 任务名快照(任务改名后历史仍可读) */
    private String jobName;

    /** 智能体ID快照 */
    private Long agentId;

    /** 计划触发时刻 */
    private Date scheduledTime;

    /** 实际触发时刻 */
    private Date fireTime;

    /** 产生的运行ID(未创建成功则为空) */
    private String runId;

    /** 会话ID */
    private String sessionId;

    /** SKIPPED/DISPATCHED/SUCCEEDED/FAILED/CANCELLED/TIMEOUT */
    private String status;

    /** 跳过原因 */
    private String skipReason;

    /** 第几次重试(0为首次) */
    private Integer retryNo;

    /** 端到端耗时(毫秒) */
    private Long durationMs;

    /** 本次token消耗 */
    private Long tokensUsed;

    /** 结果摘要,列表页直出不必回查message */
    private String resultSummary;

    /** 失败原因 */
    private String errorMessage;

    /**
     * 归属用户ID(查询过滤用,不落库;非管理员列表仅本人任务的日志)
     */
    private Long ownerUserId;

    public Long getLogId()
    {
        return logId;
    }

    public void setLogId(Long logId)
    {
        this.logId = logId;
    }

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

    public Date getScheduledTime()
    {
        return scheduledTime;
    }

    public void setScheduledTime(Date scheduledTime)
    {
        this.scheduledTime = scheduledTime;
    }

    public Date getFireTime()
    {
        return fireTime;
    }

    public void setFireTime(Date fireTime)
    {
        this.fireTime = fireTime;
    }

    public String getRunId()
    {
        return runId;
    }

    public void setRunId(String runId)
    {
        this.runId = runId;
    }

    public String getSessionId()
    {
        return sessionId;
    }

    public void setSessionId(String sessionId)
    {
        this.sessionId = sessionId;
    }

    public String getStatus()
    {
        return status;
    }

    public void setStatus(String status)
    {
        this.status = status;
    }

    public String getSkipReason()
    {
        return skipReason;
    }

    public void setSkipReason(String skipReason)
    {
        this.skipReason = skipReason;
    }

    public Integer getRetryNo()
    {
        return retryNo;
    }

    public void setRetryNo(Integer retryNo)
    {
        this.retryNo = retryNo;
    }

    public Long getDurationMs()
    {
        return durationMs;
    }

    public void setDurationMs(Long durationMs)
    {
        this.durationMs = durationMs;
    }

    public Long getTokensUsed()
    {
        return tokensUsed;
    }

    public void setTokensUsed(Long tokensUsed)
    {
        this.tokensUsed = tokensUsed;
    }

    public String getResultSummary()
    {
        return resultSummary;
    }

    public void setResultSummary(String resultSummary)
    {
        this.resultSummary = resultSummary;
    }

    public String getErrorMessage()
    {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage)
    {
        this.errorMessage = errorMessage;
    }

    public Long getOwnerUserId()
    {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId)
    {
        this.ownerUserId = ownerUserId;
    }

    @Override
    public String toString()
    {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
            .append("logId", getLogId())
            .append("jobId", getJobId())
            .append("jobName", getJobName())
            .append("agentId", getAgentId())
            .append("scheduledTime", getScheduledTime())
            .append("fireTime", getFireTime())
            .append("runId", getRunId())
            .append("sessionId", getSessionId())
            .append("status", getStatus())
            .append("skipReason", getSkipReason())
            .append("retryNo", getRetryNo())
            .append("durationMs", getDurationMs())
            .append("tokensUsed", getTokensUsed())
            .append("resultSummary", getResultSummary())
            .append("errorMessage", getErrorMessage())
            .append("ownerUserId", getOwnerUserId())
            .append("createTime", getCreateTime())
            .toString();
    }
}
