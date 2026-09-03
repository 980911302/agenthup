package com.ruoyi.system.ai.job;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.ruoyi.common.core.text.Convert;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.ai.run.ChatRunStatus;
import com.ruoyi.system.domain.AiChatMessage;
import com.ruoyi.system.domain.AiChatRun;
import com.ruoyi.system.domain.AiJob;
import com.ruoyi.system.domain.AiJobLog;
import com.ruoyi.system.mapper.AiChatMessageMapper;
import com.ruoyi.system.mapper.AiChatRunMapper;
import com.ruoyi.system.mapper.AiJobLogMapper;
import com.ruoyi.system.mapper.AiJobMapper;
import com.ruoyi.system.service.ISysConfigService;

/**
 * AI 定时任务终态对账 + 日志清理。
 *
 * <p>为什么不在 ChatRunExecutor.terminalize() 里直接回写:那条链路是所有对话共用的热路径,
 * 为定时任务这一个来源加分支会让它长期背着一个只在少数场景生效的判断。
 * ai_chat_run 自身已有心跳恢复保证终态最终写入,这里跟着收敛即可。
 *
 * <p>为什么不用 {@code @Scheduled}:启用 {@code @EnableScheduling} 是全局开关,
 * 对账属于基础设施行为,不该出现在若依定时任务管理界面里让人误停。
 *
 * @author ruoyi
 */
@Component
public class AiJobReconciler
{
    private static final Logger log = LoggerFactory.getLogger(AiJobReconciler.class);

    @Autowired
    private AiJobLogMapper aiJobLogMapper;

    @Autowired
    private AiJobMapper aiJobMapper;

    @Autowired
    private AiChatRunMapper aiChatRunMapper;

    @Autowired
    private AiChatMessageMapper aiChatMessageMapper;

    @Autowired
    private ISysConfigService configService;

    @Autowired
    private AiJobTriggerService triggerService;

    @Autowired
    private AiJobDispatcher aiJobDispatcher;

    private ScheduledExecutorService scheduler;

    @PostConstruct
    public void start()
    {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ai-job-reconciler");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::reconcileSafely, 60, 60, TimeUnit.SECONDS);
        // 首次延迟 1 小时,之后每天清理
        scheduler.scheduleWithFixedDelay(this::cleanLogsSafely, 1, 24, TimeUnit.HOURS);
        log.info("AI定时任务对账已启动,间隔 60s");
    }

    @PreDestroy
    public void stop()
    {
        if (scheduler != null)
        {
            scheduler.shutdownNow();
        }
    }

    /**
     * 必须吞掉所有异常。scheduleWithFixedDelay 里抛出未捕获异常会让后续执行永久停止,
     * 对账就此静默失效 —— 日志会永远停在 DISPATCHED 而没有任何报错。
     */
    private void reconcileSafely()
    {
        try
        {
            reconcile();
        }
        catch (Exception e)
        {
            log.error("AI定时任务对账失败,下轮重试", e);
        }
    }

    private void cleanLogsSafely()
    {
        try
        {
            cleanExpiredLogs();
        }
        catch (Exception e)
        {
            log.error("AI定时任务日志清理失败,下轮重试", e);
        }
    }

    /**
     * 把 DISPATCHED 日志收敛到终态
     */
    private void reconcile()
    {
        Date before = new Date(System.currentTimeMillis() - AiJobConstants.RECONCILE_GRACE_SECONDS * 1000L);
        List<AiJobLog> pending = aiJobLogMapper.selectPendingLogs(before, AiJobConstants.RECONCILE_BATCH_SIZE);
        if (pending == null || pending.isEmpty())
        {
            return;
        }
        for (AiJobLog jobLog : pending)
        {
            try
            {
                reconcileOne(jobLog);
            }
            catch (Exception e)
            {
                log.error("AI定时任务单条对账失败 logId={}", jobLog.getLogId(), e);
            }
        }
    }

    private void reconcileOne(AiJobLog jobLog)
    {
        AiJob job = jobLog.getJobId() == null ? null : aiJobMapper.selectAiJobById(jobLog.getJobId());
        int timeoutSeconds = job != null && job.getTimeoutSeconds() != null && job.getTimeoutSeconds() > 0
                ? job.getTimeoutSeconds() : 600;

        if (StringUtils.isEmpty(jobLog.getRunId()))
        {
            // 创建 run 时就失败却仍停在 DISPATCHED 的异常路径,按超时兜底
            if (isTimedOut(jobLog.getFireTime(), timeoutSeconds))
            {
                markTimeout(jobLog, job);
            }
            return;
        }

        AiChatRun run = aiChatRunMapper.selectAiChatRunById(jobLog.getRunId());
        if (run == null)
        {
            if (isTimedOut(jobLog.getFireTime(), timeoutSeconds))
            {
                markTimeout(jobLog, job);
            }
            return;
        }

        if (!ChatRunStatus.isTerminal(run.getStatus()))
        {
            if (isTimedOut(jobLog.getFireTime(), timeoutSeconds))
            {
                markTimeout(jobLog, job);
            }
            return;
        }

        AiJobLog terminal = new AiJobLog();
        terminal.setRunId(run.getRunId());
        terminal.setStatus(run.getStatus());
        terminal.setErrorMessage(run.getErrorMessage());
        terminal.setDurationMs(calcDurationMs(run));
        fillResult(terminal, run);
        aiJobLogMapper.updateTerminalByRunId(terminal);

        if (job != null)
        {
            AiJob patch = new AiJob();
            patch.setJobId(job.getJobId());
            patch.setLastStatus(run.getStatus());
            if (StringUtils.isNotEmpty(run.getRunId()))
            {
                patch.setLastRunId(run.getRunId());
            }
            aiJobMapper.updateAiJob(patch);
        }

        // 失败且未达 max_retry 时重新派发
        if (ChatRunStatus.FAILED.equals(run.getStatus()) && job != null
                && AiJobConstants.STATUS_NORMAL.equals(job.getStatus()))
        {
            int maxRetry = job.getMaxRetry() == null ? 0 : job.getMaxRetry();
            int retryNo = jobLog.getRetryNo() == null ? 0 : jobLog.getRetryNo();
            if (retryNo < maxRetry)
            {
                int nextRetry = retryNo + 1;
                Date scheduled = jobLog.getScheduledTime() != null ? jobLog.getScheduledTime() : new Date();
                try
                {
                    // 同进程直接派发,避免依赖 trigger 仍存在(once 可能已删)
                    aiJobDispatcher.dispatch(job.getJobId(), scheduled, nextRetry,
                            triggerService.getNextFireTime(job.getJobId()));
                }
                catch (Exception e)
                {
                    log.warn("AI定时任务重试派发失败 jobId={} retry={}: {}", job.getJobId(), nextRetry, e.getMessage());
                }
            }
        }
    }

    private void fillResult(AiJobLog terminal, AiChatRun run)
    {
        long tokens = 0L;
        String summary = null;
        if (run.getRequestMessageId() != null)
        {
            AiChatMessage req = aiChatMessageMapper.selectMessageById(run.getRequestMessageId());
            if (req != null && req.getTokens() != null)
            {
                tokens += req.getTokens();
            }
        }
        if (run.getResponseMessageId() != null)
        {
            AiChatMessage resp = aiChatMessageMapper.selectMessageById(run.getResponseMessageId());
            if (resp != null)
            {
                if (resp.getTokens() != null)
                {
                    tokens += resp.getTokens();
                }
                if (StringUtils.isNotEmpty(resp.getContent()))
                {
                    summary = resp.getContent().length() > AiJobConstants.RESULT_SUMMARY_MAX
                            ? resp.getContent().substring(0, AiJobConstants.RESULT_SUMMARY_MAX)
                            : resp.getContent();
                }
            }
        }
        terminal.setTokensUsed(tokens);
        terminal.setResultSummary(summary);
    }

    private static Long calcDurationMs(AiChatRun run)
    {
        Date start = run.getStartedTime() != null ? run.getStartedTime() : run.getCreateTime();
        Date end = run.getFinishedTime();
        if (start == null || end == null)
        {
            return null;
        }
        return Math.max(0L, end.getTime() - start.getTime());
    }

    private static boolean isTimedOut(Date fireTime, int timeoutSeconds)
    {
        if (fireTime == null)
        {
            return false;
        }
        return System.currentTimeMillis() - fireTime.getTime() > timeoutSeconds * 1000L;
    }

    private void markTimeout(AiJobLog jobLog, AiJob job)
    {
        AiJobLog patch = new AiJobLog();
        patch.setLogId(jobLog.getLogId());
        patch.setStatus(AiJobConstants.LOG_TIMEOUT);
        patch.setErrorMessage("运行超时未进入终态");
        if (jobLog.getFireTime() != null)
        {
            patch.setDurationMs(System.currentTimeMillis() - jobLog.getFireTime().getTime());
        }
        aiJobLogMapper.updateAiJobLog(patch);

        if (job != null)
        {
            AiJob jobPatch = new AiJob();
            jobPatch.setJobId(job.getJobId());
            jobPatch.setLastStatus(AiJobConstants.LOG_TIMEOUT);
            aiJobMapper.updateAiJob(jobPatch);
        }
    }

    private void cleanExpiredLogs()
    {
        int days = intConfig(AiJobConstants.CONFIG_LOG_RETAIN_DAYS, AiJobConstants.DEFAULT_LOG_RETAIN_DAYS);
        // 配置为 0 表示不清理
        if (days <= 0)
        {
            return;
        }
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, -days);
        int n = aiJobLogMapper.cleanExpiredLogs(cal.getTime());
        if (n > 0)
        {
            log.info("清理 AI 定时任务过期日志 {} 条,保留 {} 天", n, days);
        }
    }

    private int intConfig(String key, int fallback)
    {
        int v = Convert.toInt(configService.selectConfigByKey(key), fallback);
        return v > 0 ? v : fallback;
    }
}
