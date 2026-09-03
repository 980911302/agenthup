package com.ruoyi.adapter.ai.job;

import com.ruoyi.ai.contract.core.InvocationContext;
import com.ruoyi.ai.contract.job.JobSchedulerPort;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.AiJob;
import com.ruoyi.system.ai.job.AiJobConstants;
import com.ruoyi.system.service.IAiJobService;
import java.time.Instant;
import java.util.Date;
import org.springframework.stereotype.Component;

/** 已持久化 AI 任务到通用调度端口的适配器。 */
@Component
public class QuartzJobSchedulerAdapter implements JobSchedulerPort
{
    private final IAiJobService jobs;

    public QuartzJobSchedulerAdapter(IAiJobService jobs)
    {
        this.jobs = jobs;
    }

    @Override
    public ScheduledJob schedule(ScheduleRequest request, InvocationContext context)
    {
        Identity identity = identity(context);
        Long jobId = number(request.jobRef());
        AiJob job = jobs.selectAiJobById(jobId, identity.userId(), identity.admin());
        if (job == null) throw new ServiceException("定时任务不存在: " + request.jobRef());
        if (request.cron() != null && !request.cron().isBlank())
        {
            job.setTriggerType(AiJobConstants.TRIGGER_CRON);
            job.setCronExpression(request.cron());
            job.setFireTime(null);
        }
        else if (request.runAt() != null)
        {
            job.setTriggerType(AiJobConstants.TRIGGER_ONCE);
            job.setFireTime(Date.from(request.runAt()));
            job.setCronExpression(null);
        }
        jobs.updateAiJob(job, identity.userId(), identity.admin());
        jobs.changeStatus(jobId, AiJobConstants.STATUS_NORMAL, identity.userId(), identity.admin());
        AiJob latest = jobs.selectAiJobById(jobId, identity.userId(), identity.admin());
        Instant next = latest.getNextFireTime() == null ? null : latest.getNextFireTime().toInstant();
        return new ScheduledJob(String.valueOf(jobId), next, "SCHEDULED");
    }

    @Override
    public void cancel(String jobId, InvocationContext context)
    {
        Identity identity = identity(context);
        jobs.changeStatus(number(jobId), AiJobConstants.STATUS_PAUSE,
                identity.userId(), identity.admin());
    }

    private static Long number(String value)
    {
        try { return Long.valueOf(value); }
        catch (Exception e) { throw new ServiceException("jobRef 必须是数字 ID"); }
    }

    private static Identity identity(InvocationContext context)
    {
        boolean admin = context != null
                && (context.roles().contains("ADMIN") || context.roles().contains("SYSTEM"));
        Long userId = null;
        if (context != null && context.principalId() != null)
        {
            try { userId = Long.valueOf(context.principalId()); }
            catch (NumberFormatException ignored) { }
        }
        return new Identity(userId, admin);
    }

    private record Identity(Long userId, boolean admin) { }
}
