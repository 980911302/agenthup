package com.ruoyi.system.ai.job;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import org.quartz.CronExpression;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobKey;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.TriggerKey;
import com.ruoyi.common.constant.ScheduleConstants;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.domain.AiJob;

/**
 * AI 定时任务调度辅助:JobKey/TriggerKey、misfire 映射、cron 校验
 *
 * @author ruoyi
 */
public final class AiJobScheduleSupport
{
    private AiJobScheduleSupport()
    {
    }

    /**
     * 构建任务键
     */
    public static JobKey jobKey(Long jobId)
    {
        return JobKey.jobKey("AI_JOB_" + jobId, AiJobConstants.JOB_GROUP);
    }

    /**
     * 构建触发器键
     */
    public static TriggerKey triggerKey(Long jobId)
    {
        return TriggerKey.triggerKey("AI_JOB_TRIG_" + jobId, AiJobConstants.JOB_GROUP);
    }

    /**
     * cron 表达式是否合法
     */
    public static boolean isValidCron(String cronExpression)
    {
        return StringUtils.isNotEmpty(cronExpression) && CronExpression.isValidExpression(cronExpression);
    }

    /**
     * 计算 cron 后续若干次触发时刻(表单预览 / 校验用)
     *
     * @param cronExpression cron
     * @param timezone 时区,空则服务器默认
     * @param count 次数
     * @return 触发时刻列表
     */
    public static List<Date> nextFireTimes(String cronExpression, String timezone, int count)
    {
        if (!isValidCron(cronExpression) || count <= 0)
        {
            return List.of();
        }
        try
        {
            CronExpression cron = new CronExpression(cronExpression);
            if (StringUtils.isNotEmpty(timezone))
            {
                cron.setTimeZone(TimeZone.getTimeZone(timezone));
            }
            List<Date> times = new ArrayList<>(count);
            Date cursor = new Date();
            for (int i = 0; i < count; i++)
            {
                Date next = cron.getNextValidTimeAfter(cursor);
                if (next == null)
                {
                    break;
                }
                times.add(next);
                cursor = next;
            }
            return times;
        }
        catch (ParseException e)
        {
            throw new ServiceException("cron 表达式格式错误");
        }
    }

    /**
     * 校验 cron 相邻两次触发间隔不小于 minIntervalMinutes
     *
     * @param cronExpression cron
     * @param timezone 时区
     * @param minIntervalMinutes 最小间隔(分钟)
     */
    public static void validateMinInterval(String cronExpression, String timezone, int minIntervalMinutes)
    {
        List<Date> times = nextFireTimes(cronExpression, timezone, 2);
        if (times.size() < 2)
        {
            return;
        }
        long gapMs = times.get(1).getTime() - times.get(0).getTime();
        long minMs = minIntervalMinutes * 60L * 1000L;
        if (gapMs < minMs)
        {
            throw new ServiceException("触发间隔不得小于 " + minIntervalMinutes + " 分钟");
        }
    }

    /**
     * 映射 cron misfire 策略。拒绝 1(IgnoreMisfires),避免停机补跑烧 token。
     */
    public static CronScheduleBuilder applyCronMisfirePolicy(String misfirePolicy, CronScheduleBuilder builder)
    {
        String policy = StringUtils.isEmpty(misfirePolicy) ? ScheduleConstants.MISFIRE_DO_NOTHING : misfirePolicy;
        switch (policy)
        {
            case ScheduleConstants.MISFIRE_DEFAULT:
            case ScheduleConstants.MISFIRE_DO_NOTHING:
                return builder.withMisfireHandlingInstructionDoNothing();
            case ScheduleConstants.MISFIRE_FIRE_AND_PROCEED:
                return builder.withMisfireHandlingInstructionFireAndProceed();
            case ScheduleConstants.MISFIRE_IGNORE_MISFIRES:
                throw new ServiceException("不支持的错过策略");
            default:
                throw new ServiceException("不支持的错过策略");
        }
    }

    /**
     * 映射 once(SimpleTrigger) misfire 策略
     */
    public static SimpleScheduleBuilder applySimpleMisfirePolicy(String misfirePolicy, SimpleScheduleBuilder builder)
    {
        String policy = StringUtils.isEmpty(misfirePolicy) ? ScheduleConstants.MISFIRE_DO_NOTHING : misfirePolicy;
        switch (policy)
        {
            case ScheduleConstants.MISFIRE_DEFAULT:
            case ScheduleConstants.MISFIRE_DO_NOTHING:
                return builder.withMisfireHandlingInstructionNextWithRemainingCount();
            case ScheduleConstants.MISFIRE_FIRE_AND_PROCEED:
                return builder.withMisfireHandlingInstructionFireNow();
            case ScheduleConstants.MISFIRE_IGNORE_MISFIRES:
                throw new ServiceException("不支持的错过策略");
            default:
                throw new ServiceException("不支持的错过策略");
        }
    }

    /**
     * 解析任务时区,空则 Asia/Shanghai
     */
    public static TimeZone resolveTimeZone(AiJob job)
    {
        String tz = job == null ? null : job.getTimezone();
        if (StringUtils.isEmpty(tz))
        {
            tz = "Asia/Shanghai";
        }
        return TimeZone.getTimeZone(tz);
    }
}
