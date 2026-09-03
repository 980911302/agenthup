package com.ruoyi.system.ai.job;

import java.util.Date;
import java.util.TimeZone;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.AiJob;

/**
 * AI 定时任务 Quartz trigger 的注册 / 暂停 / 恢复 / 删除 / 立即执行
 *
 * <p>先删后建,避免 JDBC JobStore 下多节点 {@code ObjectAlreadyExistsException};
 * 绝不调用 {@code scheduler.clear()}。
 *
 * @author ruoyi
 */
@Service
public class AiJobTriggerService
{
    private static final Logger log = LoggerFactory.getLogger(AiJobTriggerService.class);

    @Autowired
    private Scheduler scheduler;

    /**
     * 注册或重建调度(幂等:存在则先删)
     *
     * @param job 任务定义
     */
    public void createScheduleJob(AiJob job)
    {
        if (job == null || job.getJobId() == null)
        {
            return;
        }
        try
        {
            JobKey jobKey = AiJobScheduleSupport.jobKey(job.getJobId());
            if (scheduler.checkExists(jobKey))
            {
                scheduler.deleteJob(jobKey);
            }

            JobDetail jobDetail = JobBuilder.newJob(AiJobDispatcher.class)
                    .withIdentity(jobKey)
                    .usingJobData(AiJobConstants.JOB_DATA_JOB_ID, job.getJobId())
                    .storeDurably(false)
                    .build();

            Trigger trigger = buildTrigger(job);
            if (trigger == null)
            {
                log.warn("AI任务 {} 无有效下次触发时间,跳过注册", job.getJobId());
                return;
            }
            scheduler.scheduleJob(jobDetail, trigger);

            if (AiJobConstants.STATUS_PAUSE.equals(job.getStatus()))
            {
                scheduler.pauseJob(jobKey);
            }
        }
        catch (SchedulerException e)
        {
            throw new ServiceException("注册 AI 定时任务失败: " + e.getMessage());
        }
    }

    /**
     * 暂停 trigger
     */
    public void pauseJob(Long jobId) throws SchedulerException
    {
        JobKey jobKey = AiJobScheduleSupport.jobKey(jobId);
        if (scheduler.checkExists(jobKey))
        {
            scheduler.pauseJob(jobKey);
        }
    }

    /**
     * 恢复 trigger;不存在则按当前定义重建(调用方应先写入 status=0)
     */
    public void resumeJob(AiJob job) throws SchedulerException
    {
        if (job == null || job.getJobId() == null)
        {
            return;
        }
        JobKey jobKey = AiJobScheduleSupport.jobKey(job.getJobId());
        if (scheduler.checkExists(jobKey))
        {
            scheduler.resumeJob(jobKey);
        }
        else
        {
            createScheduleJob(job);
        }
    }

    /**
     * 删除 JobDetail 及其 trigger
     */
    public void deleteJob(Long jobId) throws SchedulerException
    {
        JobKey jobKey = AiJobScheduleSupport.jobKey(jobId);
        if (scheduler.checkExists(jobKey))
        {
            scheduler.deleteJob(jobKey);
        }
    }

    /**
     * 立即触发一次(手动执行 / 失败重试)。retryNo 写入 JobDataMap 供幂等键使用。
     *
     * @param jobId 任务ID
     * @param retryNo 重试序号,首次为 0
     * @return 是否成功触发
     */
    public boolean triggerNow(Long jobId, int retryNo) throws SchedulerException
    {
        JobKey jobKey = AiJobScheduleSupport.jobKey(jobId);
        if (!scheduler.checkExists(jobKey))
        {
            // 手动执行时任务可能处于暂停且 trigger 仍在;不存在则无法 trigger
            return false;
        }
        JobDataMap data = new JobDataMap();
        data.put(AiJobConstants.JOB_DATA_JOB_ID, jobId);
        data.put(AiJobConstants.JOB_DATA_RETRY_NO, retryNo);
        scheduler.triggerJob(jobKey, data);
        return true;
    }

    /**
     * 读取 trigger 的下次触发时间
     */
    public Date getNextFireTime(Long jobId)
    {
        try
        {
            Trigger trigger = scheduler.getTrigger(AiJobScheduleSupport.triggerKey(jobId));
            return trigger == null ? null : trigger.getNextFireTime();
        }
        catch (SchedulerException e)
        {
            log.warn("读取下次触发时间失败 jobId={}: {}", jobId, e.getMessage());
            return null;
        }
    }

    private Trigger buildTrigger(AiJob job)
    {
        if (AiJobConstants.TRIGGER_ONCE.equals(job.getTriggerType()))
        {
            return buildOnceTrigger(job);
        }
        return buildCronTrigger(job);
    }

    private CronTrigger buildCronTrigger(AiJob job)
    {
        if (!AiJobScheduleSupport.isValidCron(job.getCronExpression()))
        {
            throw new ServiceException("cron 表达式格式错误");
        }
        TimeZone tz = AiJobScheduleSupport.resolveTimeZone(job);
        CronScheduleBuilder scheduleBuilder = CronScheduleBuilder.cronSchedule(job.getCronExpression())
                .inTimeZone(tz);
        scheduleBuilder = AiJobScheduleSupport.applyCronMisfirePolicy(job.getMisfirePolicy(), scheduleBuilder);

        // 与 ScheduleUtils 一致:没有下次执行时间则不注册,避免挂一个永不到期的 trigger
        try
        {
            org.quartz.CronExpression cron = new org.quartz.CronExpression(job.getCronExpression());
            cron.setTimeZone(tz);
            if (cron.getNextValidTimeAfter(new Date()) == null)
            {
                return null;
            }
        }
        catch (Exception e)
        {
            throw new ServiceException("cron 表达式格式错误");
        }

        return TriggerBuilder.newTrigger()
                .withIdentity(AiJobScheduleSupport.triggerKey(job.getJobId()))
                .withSchedule(scheduleBuilder)
                .build();
    }

    private SimpleTrigger buildOnceTrigger(AiJob job)
    {
        Date fireTime = job.getFireTime();
        if (fireTime == null)
        {
            throw new ServiceException("执行时刻必须晚于当前时间");
        }
        // 已过期的 once 不再注册,避免启动重建时立刻 misfire 补跑
        if (fireTime.getTime() <= System.currentTimeMillis())
        {
            return null;
        }
        SimpleScheduleBuilder scheduleBuilder = SimpleScheduleBuilder.simpleSchedule()
                .withRepeatCount(0);
        scheduleBuilder = AiJobScheduleSupport.applySimpleMisfirePolicy(job.getMisfirePolicy(), scheduleBuilder);
        return TriggerBuilder.newTrigger()
                .withIdentity(AiJobScheduleSupport.triggerKey(job.getJobId()))
                .startAt(fireTime)
                .withSchedule(scheduleBuilder)
                .build();
    }
}
