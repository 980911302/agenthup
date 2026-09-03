package com.ruoyi.system.ai.job;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.spring.SpringUtils;
import com.ruoyi.system.ai.run.ActiveChatRunException;
import com.ruoyi.system.ai.run.ChatRunAttachment;
import com.ruoyi.system.ai.run.ChatRunCreateCommand;
import com.ruoyi.system.ai.run.ChatRunService;
import com.ruoyi.system.domain.AiAgent;
import com.ruoyi.system.domain.AiChatRun;
import com.ruoyi.system.domain.AiChatSession;
import com.ruoyi.system.domain.AiJob;
import com.ruoyi.system.domain.AiJobLog;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.system.mapper.AiChatSessionMapper;
import com.ruoyi.system.mapper.AiJobLogMapper;
import com.ruoyi.system.mapper.AiJobMapper;
import com.ruoyi.system.service.IAiAgentService;
import com.ruoyi.system.service.ISysUserService;

/**
 * AI 定时任务的唯一调度入口。
 *
 * <p>jobDataMap 只放 jobId,不放整个 AiJob 实体 —— 与 ScheduleUtils 把 SysJob 整体塞进
 * TASK_PROPERTIES 的做法相反。原因:实体若随 trigger 常驻,用户改了 prompt 而没重建 trigger
 * 时会拿旧值执行。每次现查一遍最省心。
 *
 * <p>关于 {@code @DisallowConcurrentExecution}:本方法只负责【创建 run 就返回】,不等智能体跑完,
 * 执行体在 ChatRunExecutor 的线程池里异步进行。所以这个注解只能防住「派发动作」本身重叠,
 * 挡不住「上一轮还在跑、这一轮又来了」。后者由 uk_ai_chat_run_active 兜住(fixed 模式)。
 *
 * <p>Quartz 自己实例化 Job,Spring 依赖通过 {@link SpringUtils} 取;本类同时注册为
 * {@code @Component},供对账重试等同 JVM 内直接调用 {@link #dispatch}。
 *
 * @author ruoyi
 */
@Component
@DisallowConcurrentExecution
public class AiJobDispatcher implements Job
{
    private static final Logger log = LoggerFactory.getLogger(AiJobDispatcher.class);

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException
    {
        JobDataMap data = context.getMergedJobDataMap();
        Long jobId = data.getLong(AiJobConstants.JOB_DATA_JOB_ID);
        if (jobId == null || jobId == 0L)
        {
            // Quartz 有时以 String 存 long
            Object raw = data.get(AiJobConstants.JOB_DATA_JOB_ID);
            if (raw != null)
            {
                jobId = Long.valueOf(String.valueOf(raw));
            }
        }
        int retryNo = 0;
        if (data.containsKey(AiJobConstants.JOB_DATA_RETRY_NO))
        {
            Object rn = data.get(AiJobConstants.JOB_DATA_RETRY_NO);
            if (rn != null)
            {
                retryNo = Integer.parseInt(String.valueOf(rn));
            }
        }
        Date scheduledFireTime = context.getScheduledFireTime();
        if (scheduledFireTime == null)
        {
            scheduledFireTime = new Date();
        }
        Date nextFireTime = context.getNextFireTime();
        try
        {
            SpringUtils.getBean(AiJobDispatcher.class)
                    .dispatch(jobId, scheduledFireTime, retryNo, nextFireTime);
        }
        catch (Exception e)
        {
            // 业务异常已在 dispatch 内落日志;这里只防未预期错误冒泡拖垮调度线程
            log.error("AI定时任务派发异常 jobId={}", jobId, e);
        }
    }

    /**
     * 派发一次运行。
     *
     * @param jobId 任务ID
     * @param scheduledFireTime 计划触发时刻(用于幂等键)
     * @param retryNo 重试序号,0 为首次
     * @param nextFireTime Quartz 计算的下次触发,可为 null
     */
    public void dispatch(Long jobId, Date scheduledFireTime, int retryNo, Date nextFireTime)
    {
        AiJobMapper jobMapper = SpringUtils.getBean(AiJobMapper.class);
        AiJobLogMapper logMapper = SpringUtils.getBean(AiJobLogMapper.class);
        IAiAgentService agentService = SpringUtils.getBean(IAiAgentService.class);
        ChatRunService chatRunService = SpringUtils.getBean(ChatRunService.class);
        AiChatSessionMapper sessionMapper = SpringUtils.getBean(AiChatSessionMapper.class);
        ISysUserService userService = SpringUtils.getBean(ISysUserService.class);
        AiJobTriggerService triggerService = SpringUtils.getBean(AiJobTriggerService.class);

        AiJob job = jobMapper.selectAiJobById(jobId);
        // selectAiJobById 已过滤 del_flag;非正常态不落日志直接返回
        // 手动 runOnce 也要求任务为 NORMAL,避免与此处静默 no-op 不一致
        if (job == null || !AiJobConstants.STATUS_NORMAL.equals(job.getStatus()))
        {
            return;
        }

        Date fireTime = new Date();
        // 过期任务:在创建 run 之前收口,避免仍派发一次
        if (job.getExpireTime() != null && !job.getExpireTime().after(fireTime))
        {
            AiJobLog expiredLog = new AiJobLog();
            expiredLog.setJobId(job.getJobId());
            expiredLog.setJobName(job.getJobName());
            expiredLog.setAgentId(job.getAgentId());
            expiredLog.setScheduledTime(scheduledFireTime != null ? scheduledFireTime : fireTime);
            expiredLog.setFireTime(fireTime);
            expiredLog.setStatus(AiJobConstants.LOG_SKIPPED);
            expiredLog.setSkipReason("任务已过期");
            expiredLog.setRetryNo(retryNo);
            logMapper.insertAiJobLog(expiredLog);
            jobMapper.markCompleted(job.getJobId());
            try
            {
                triggerService.deleteJob(job.getJobId());
            }
            catch (Exception e)
            {
                log.warn("删除已过期任务 trigger 失败 jobId={}: {}", job.getJobId(), e.getMessage());
            }
            return;
        }

        // scheduledFireTime==null 表示手动执行:幂等键用 manual- 前缀;日志仍记实际触发时刻
        String clientRequestId = buildClientRequestId(jobId, scheduledFireTime, retryNo);
        if (scheduledFireTime == null)
        {
            scheduledFireTime = fireTime;
        }

        AiJobLog jobLog = new AiJobLog();
        jobLog.setJobId(job.getJobId());
        jobLog.setJobName(job.getJobName());
        jobLog.setAgentId(job.getAgentId());
        jobLog.setScheduledTime(scheduledFireTime);
        jobLog.setFireTime(fireTime);
        jobLog.setStatus(AiJobConstants.LOG_DISPATCHED);
        jobLog.setRetryNo(retryNo);
        logMapper.insertAiJobLog(jobLog);

        AiAgent agent = agentService.selectAiAgentById(job.getAgentId());
        if (agent == null || !"0".equals(agent.getStatus()))
        {
            finishLog(logMapper, jobLog, AiJobConstants.LOG_SKIPPED, "智能体不存在或已停用", null, null, null);
            updateSnapshot(jobMapper, job, fireTime, nextFireTime, null, AiJobConstants.LOG_SKIPPED, 0);
            checkCompletion(jobMapper, triggerService, job, fireTime);
            return;
        }

        String ownerUsername = resolveUsername(userService, job.getOwnerUserId(), job.getCreateBy());
        String sessionId;
        try
        {
            sessionId = resolveSession(job, jobMapper, sessionMapper, ownerUsername);
        }
        catch (Exception e)
        {
            finishLog(logMapper, jobLog, AiJobConstants.LOG_FAILED, null, null, null, e.getMessage());
            updateSnapshot(jobMapper, job, fireTime, nextFireTime, null, AiJobConstants.LOG_FAILED, 1);
            checkCompletion(jobMapper, triggerService, job, fireTime);
            return;
        }

        jobLog.setSessionId(sessionId);
        List<ChatRunAttachment> attachments = parseAttachments(job.getAttachments());

        try
        {
            AiChatRun run = chatRunService.create(new ChatRunCreateCommand(
                    sessionId,
                    job.getAgentId(),
                    job.getPrompt(),
                    attachments,
                    clientRequestId,
                    job.getOwnerUserId(),
                    ownerUsername,
                    false,
                    null,
                    null,
                    null,
                    null, null, null, null));

            jobLog.setRunId(run.getRunId());
            jobLog.setSessionId(sessionId);
            // 仍保持 DISPATCHED,终态由 AiJobReconciler 回填
            AiJobLog patch = new AiJobLog();
            patch.setLogId(jobLog.getLogId());
            patch.setRunId(run.getRunId());
            patch.setSessionId(sessionId);
            logMapper.updateAiJobLog(patch);

            updateSnapshot(jobMapper, job, fireTime, nextFireTime, run.getRunId(), AiJobConstants.LOG_DISPATCHED, 0);
        }
        catch (ActiveChatRunException e)
        {
            finishLog(logMapper, jobLog, AiJobConstants.LOG_SKIPPED, "上一轮运行未结束", sessionId, null, null);
            updateSnapshot(jobMapper, job, fireTime, nextFireTime, null, AiJobConstants.LOG_SKIPPED, 0);
        }
        catch (Exception e)
        {
            log.warn("AI定时任务创建 run 失败 jobId={}: {}", jobId, e.getMessage());
            finishLog(logMapper, jobLog, AiJobConstants.LOG_FAILED, null, sessionId, null, StringUtils.substring(e.getMessage(), 0, 2000));
            updateSnapshot(jobMapper, job, fireTime, nextFireTime, null, AiJobConstants.LOG_FAILED, 1);
        }

        checkCompletion(jobMapper, triggerService, job, fireTime);
    }

    /**
     * 幂等键:计划触发用 job-{id}-{ts}-{retry};手动执行(scheduledFireTime==null)用 manual-{id}-{now}
     */
    public static String buildClientRequestId(Long jobId, Date scheduledFireTime, int retryNo)
    {
        if (scheduledFireTime == null)
        {
            return "manual-" + jobId + "-" + System.currentTimeMillis();
        }
        return "job-" + jobId + "-" + scheduledFireTime.getTime() + "-" + retryNo;
    }

    private String resolveSession(AiJob job, AiJobMapper jobMapper, AiChatSessionMapper sessionMapper,
                                  String ownerUsername)
    {
        if (AiJobConstants.SESSION_MODE_FIXED.equals(job.getSessionMode()))
        {
            String sessionId = job.getSessionId();
            if (StringUtils.isEmpty(sessionId))
            {
                sessionId = newSessionId();
                ensureJobSession(sessionMapper, sessionId, job, ownerUsername);
                AiJob patch = new AiJob();
                patch.setJobId(job.getJobId());
                patch.setSessionId(sessionId);
                jobMapper.updateAiJob(patch);
                job.setSessionId(sessionId);
                return sessionId;
            }
            AiChatSession existing = sessionMapper.selectAiChatSessionById(sessionId);
            if (existing == null)
            {
                ensureJobSession(sessionMapper, sessionId, job, ownerUsername);
            }
            else if (job.getOwnerUserId() != null && !job.getOwnerUserId().equals(existing.getUserId()))
            {
                throw new IllegalStateException("无权使用该会话");
            }
            return sessionId;
        }

        // new:每次新建
        String sessionId = newSessionId();
        ensureJobSession(sessionMapper, sessionId, job, ownerUsername);
        return sessionId;
    }

    private void ensureJobSession(AiChatSessionMapper sessionMapper, String sessionId, AiJob job,
                                  String ownerUsername)
    {
        AiChatSession session = new AiChatSession();
        session.setSessionId(sessionId);
        session.setTitle(truncateTitle(job.getJobName(), job.getPrompt()));
        session.setSessionType(AiJobConstants.SESSION_TYPE_JOB);
        session.setSourceJobId(job.getJobId());
        session.setUserId(job.getOwnerUserId());
        session.setStatus("0");
        session.setTotalTokens(0L);
        session.setContextLength(0L);
        session.setCreateBy(ownerUsername);
        sessionMapper.insertAiChatSession(session);
    }

    private static String newSessionId()
    {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String truncateTitle(String jobName, String prompt)
    {
        if (StringUtils.isNotEmpty(jobName))
        {
            return jobName.length() > 50 ? jobName.substring(0, 50) : jobName;
        }
        if (StringUtils.isEmpty(prompt))
        {
            return "AI定时任务";
        }
        return prompt.length() > 50 ? prompt.substring(0, 50) : prompt;
    }

    private static String resolveUsername(ISysUserService userService, Long ownerUserId, String fallback)
    {
        if (ownerUserId != null)
        {
            SysUser user = userService.selectUserById(ownerUserId);
            if (user != null && StringUtils.isNotEmpty(user.getUserName()))
            {
                return user.getUserName();
            }
        }
        return StringUtils.isEmpty(fallback) ? "system" : fallback;
    }

    private static List<ChatRunAttachment> parseAttachments(String json)
    {
        if (StringUtils.isEmpty(json))
        {
            return List.of();
        }
        try
        {
            JSONArray arr = JSON.parseArray(json);
            if (arr == null || arr.isEmpty())
            {
                return List.of();
            }
            List<ChatRunAttachment> list = new ArrayList<>(arr.size());
            for (int i = 0; i < arr.size(); i++)
            {
                JSONObject o = arr.getJSONObject(i);
                if (o == null)
                {
                    continue;
                }
                ChatRunAttachment a = new ChatRunAttachment();
                a.setPath(o.getString("path"));
                a.setName(o.getString("name"));
                a.setMime(o.getString("mime"));
                a.setSize(o.getLong("size"));
                list.add(a);
            }
            return list;
        }
        catch (Exception e)
        {
            return List.of();
        }
    }

    private static void finishLog(AiJobLogMapper logMapper, AiJobLog jobLog, String status,
                                  String skipReason, String sessionId, String runId, String errorMessage)
    {
        AiJobLog patch = new AiJobLog();
        patch.setLogId(jobLog.getLogId());
        patch.setStatus(status);
        patch.setSkipReason(skipReason);
        patch.setSessionId(sessionId);
        patch.setRunId(runId);
        patch.setErrorMessage(errorMessage);
        logMapper.updateAiJobLog(patch);
    }

    private static void updateSnapshot(AiJobMapper jobMapper, AiJob job, Date fireTime, Date nextFireTime,
                                       String lastRunId, String lastStatus, int failIncrement)
    {
        Date next = nextFireTime;
        if (next == null && AiJobConstants.TRIGGER_CRON.equals(job.getTriggerType())
                && AiJobScheduleSupport.isValidCron(job.getCronExpression()))
        {
            List<Date> times = AiJobScheduleSupport.nextFireTimes(job.getCronExpression(), job.getTimezone(), 1);
            next = times.isEmpty() ? null : times.get(0);
        }
        jobMapper.updateFireSnapshot(job.getJobId(), fireTime, next, lastRunId, lastStatus, failIncrement);
    }

    private static void checkCompletion(AiJobMapper jobMapper, AiJobTriggerService triggerService,
                                        AiJob job, Date fireTime)
    {
        boolean complete = false;
        if (AiJobConstants.TRIGGER_ONCE.equals(job.getTriggerType()))
        {
            complete = true;
        }
        if (job.getExpireTime() != null && !job.getExpireTime().after(fireTime))
        {
            complete = true;
        }
        if (job.getMaxRuns() != null)
        {
            // updateFireSnapshot 已 +1;用库内最新值判断
            AiJob latest = jobMapper.selectAiJobById(job.getJobId());
            if (latest != null && latest.getRunCount() != null && latest.getRunCount() >= job.getMaxRuns())
            {
                complete = true;
            }
        }
        if (!complete)
        {
            return;
        }
        jobMapper.markCompleted(job.getJobId());
        try
        {
            triggerService.deleteJob(job.getJobId());
        }
        catch (Exception e)
        {
            log.warn("删除已完成任务 trigger 失败 jobId={}: {}", job.getJobId(), e.getMessage());
        }
    }
}
