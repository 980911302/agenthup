package com.ruoyi.ai.contract.job;

import com.ruoyi.ai.contract.core.InvocationContext;
import java.time.Instant;
import java.util.Map;

public interface JobSchedulerPort
{
    ScheduledJob schedule(ScheduleRequest request, InvocationContext context);

    void cancel(String jobId, InvocationContext context);

    record ScheduleRequest(String jobRef, String cron, Instant runAt,
                           Map<String, Object> payload) { }

    record ScheduledJob(String jobId, Instant nextRunAt, String status) { }
}
