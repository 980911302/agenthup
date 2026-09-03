package com.ruoyi.system.service.impl;

import java.util.Date;
import java.util.List;
import jakarta.annotation.PostConstruct;
import org.quartz.SchedulerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.constant.ScheduleConstants;
import com.ruoyi.common.core.text.Convert;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.ai.job.AiJobConstants;
import com.ruoyi.system.ai.job.AiJobDispatcher;
import com.ruoyi.system.ai.job.AiJobScheduleSupport;
import com.ruoyi.system.ai.job.AiJobTriggerService;
import com.ruoyi.system.domain.AiAgent;
import com.ruoyi.system.domain.AiChatSession;
import com.ruoyi.system.domain.AiJob;
import com.ruoyi.system.mapper.AiChatSessionMapper;
import com.ruoyi.system.mapper.AiJobMapper;
import com.ruoyi.system.service.IAiAgentService;
import com.ruoyi.system.service.IAiJobService;
import com.ruoyi.system.service.ISysConfigService;

/**
 * 智能体定时任务 服务层实现
 *
 * <p>{@code @DependsOn("sysJobServiceImpl")} 保证本类在 SysJobServiceImpl 完成
 * {@code scheduler.clear()} 与 sys_job 重建之后再全量注册 AI 任务,避免被静默清空。
 *
 * @author ruoyi
 */
@Service
@DependsOn("sysJobServiceImpl")
public class AiJobServiceImpl implements IAiJobService
{
    private static final Logger log = LoggerFactory.getLogger(AiJobServiceImpl.class);

    @Autowired
    private AiJobMapper aiJobMapper;

    @Autowired
    private AiJobTriggerService triggerService;

    @Autowired
    private IAiAgentService agentService;

    @Autowired
    private ISysConfigService configService;

    @Autowired
    private AiChatSessionMapper sessionMapper;

    @Autowired
    private AiJobDispatcher aiJobDispatcher;

    /**
     * 启动时按 selectSchedulableJobs 全量重建 trigger,逐个先删后建。
     * JDBC 集群下本节点 trigger 可能已被其他节点 clear 掉,全量重建是唯一自愈路径。
     * 自己绝不调用 scheduler.clear()。
     */
    @PostConstruct
    public void init()
    {
        List<AiJob> jobs = aiJobMapper.selectSchedulableJobs();
        int ok = 0;
        for (AiJob job : jobs)
        {
            try
            {
                triggerService.createScheduleJob(job);
                ok++;
            }
            catch (Exception e)
            {
                log.error("重建 AI 定时任务失败 jobId={}: {}", job.getJobId(), e.getMessage());
            }
        }
        log.info("AI 定时任务调度重建完成: 成功 {}/{}", ok, jobs.size());
    }

    @Override
    public AiJob selectAiJobById(Long jobId, Long userId, boolean admin)
    {
        AiJob job = aiJobMapper.selectAiJobById(jobId);
        if (job == null)
        {
            return null;
        }
        assertOwner(job, userId, admin);
        return job;
    }

    @Override
    public List<AiJob> selectAiJobList(AiJob aiJob, Long userId, boolean admin)
    {
        if (!admin)
        {
            aiJob.setOwnerUserId(userId);
        }
        return aiJobMapper.selectAiJobList(aiJob);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insertAiJob(AiJob aiJob)
    {
        validateForWrite(aiJob, true);
        if (StringUtils.isEmpty(aiJob.getStatus()))
        {
            aiJob.setStatus(AiJobConstants.STATUS_NORMAL);
        }
        if (StringUtils.isEmpty(aiJob.getMisfirePolicy()))
        {
            aiJob.setMisfirePolicy(ScheduleConstants.MISFIRE_DO_NOTHING);
        }
        if (StringUtils.isEmpty(aiJob.getSessionMode()))
        {
            aiJob.setSessionMode(AiJobConstants.SESSION_MODE_NEW);
        }
        if (StringUtils.isEmpty(aiJob.getTriggerType()))
        {
            aiJob.setTriggerType(AiJobConstants.TRIGGER_CRON);
        }
        if (StringUtils.isEmpty(aiJob.getSource()))
        {
            aiJob.setSource("user");
        }
        if (StringUtils.isEmpty(aiJob.getTimezone()))
        {
            aiJob.setTimezone("Asia/Shanghai");
        }
        // 预填下次触发,列表页无需等第一次调度
        aiJob.setNextFireTime(computeNextFireTime(aiJob));

        int rows = aiJobMapper.insertAiJob(aiJob);
        if (rows > 0 && AiJobConstants.STATUS_NORMAL.equals(aiJob.getStatus()))
        {
            triggerService.createScheduleJob(aiJob);
            // 注册后校正 next_fire_time
            Date next = triggerService.getNextFireTime(aiJob.getJobId());
            if (next != null)
            {
                AiJob patch = new AiJob();
                patch.setJobId(aiJob.getJobId());
                patch.setNextFireTime(next);
                aiJobMapper.updateAiJob(patch);
            }
        }
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateAiJob(AiJob aiJob, Long userId, boolean admin)
    {
        AiJob existing = requireOwned(aiJob.getJobId(), userId, admin);
        // 归属不允许通过更新篡改
        aiJob.setOwnerUserId(existing.getOwnerUserId());
        // 状态仅允许 changeStatus 变更,忽略请求体 status,防止复活已完成任务
        aiJob.setStatus(null);
        validateForWrite(aiJob, false);

        aiJob.setNextFireTime(computeNextFireTime(aiJob));

        int rows = aiJobMapper.updateAiJob(aiJob);
        if (rows > 0)
        {
            // 已完成:允许改 prompt 等配置字段,但绝不重新注册 trigger
            if (AiJobConstants.STATUS_COMPLETED.equals(existing.getStatus()))
            {
                try
                {
                    triggerService.deleteJob(existing.getJobId());
                }
                catch (SchedulerException e)
                {
                    throw new ServiceException("更新 AI 定时任务调度失败: " + e.getMessage());
                }
                return rows;
            }
            AiJob latest = aiJobMapper.selectAiJobById(aiJob.getJobId());
            try
            {
                if (AiJobConstants.STATUS_NORMAL.equals(latest.getStatus()))
                {
                    triggerService.createScheduleJob(latest);
                    Date next = triggerService.getNextFireTime(latest.getJobId());
                    if (next != null)
                    {
                        AiJob patch = new AiJob();
                        patch.setJobId(latest.getJobId());
                        patch.setNextFireTime(next);
                        aiJobMapper.updateAiJob(patch);
                    }
                }
                else
                {
                    triggerService.deleteJob(latest.getJobId());
                }
            }
            catch (SchedulerException e)
            {
                throw new ServiceException("更新 AI 定时任务调度失败: " + e.getMessage());
            }
        }
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteAiJobByIds(Long[] jobIds, Long userId, boolean admin)
    {
        if (jobIds == null || jobIds.length == 0)
        {
            return 0;
        }
        for (Long jobId : jobIds)
        {
            requireOwned(jobId, userId, admin);
            try
            {
                triggerService.deleteJob(jobId);
            }
            catch (SchedulerException e)
            {
                throw new ServiceException("删除 AI 定时任务调度失败: " + e.getMessage());
            }
        }
        return aiJobMapper.deleteAiJobByIds(jobIds);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int changeStatus(Long jobId, String status, Long userId, boolean admin)
    {
        AiJob job = requireOwned(jobId, userId, admin);
        if (!AiJobConstants.STATUS_NORMAL.equals(status) && !AiJobConstants.STATUS_PAUSE.equals(status))
        {
            throw new ServiceException("不支持的任务状态");
        }
        if (AiJobConstants.STATUS_COMPLETED.equals(job.getStatus()))
        {
            throw new ServiceException("已完成的任务不能变更状态");
        }
        // 从暂停/非正常恢复为正常时,与 insert 相同过配额
        if (AiJobConstants.STATUS_NORMAL.equals(status)
                && !AiJobConstants.STATUS_NORMAL.equals(job.getStatus())
                && job.getOwnerUserId() != null)
        {
            int max = intConfig(AiJobConstants.CONFIG_MAX_PER_USER, AiJobConstants.DEFAULT_MAX_PER_USER);
            int enabled = aiJobMapper.countEnabledByOwner(job.getOwnerUserId());
            if (enabled >= max)
            {
                throw new ServiceException("定时任务数量已达上限");
            }
        }
        AiJob patch = new AiJob();
        patch.setJobId(jobId);
        patch.setStatus(status);
        int rows = aiJobMapper.updateAiJob(patch);
        if (rows > 0)
        {
            try
            {
                if (AiJobConstants.STATUS_NORMAL.equals(status))
                {
                    AiJob latest = aiJobMapper.selectAiJobById(jobId);
                    triggerService.resumeJob(latest);
                }
                else
                {
                    triggerService.pauseJob(jobId);
                }
            }
            catch (SchedulerException e)
            {
                throw new ServiceException("变更 AI 定时任务状态失败: " + e.getMessage());
            }
        }
        return rows;
    }

    @Override
    public void runOnce(Long jobId, Long userId, boolean admin)
    {
        AiJob job = requireOwned(jobId, userId, admin);
        if (!AiJobConstants.STATUS_NORMAL.equals(job.getStatus()))
        {
            throw new ServiceException("仅正常状态的任务可立即执行");
        }
        // scheduledFireTime=null → dispatch 使用 manual-{jobId}-{now} 幂等键
        Date next = triggerService.getNextFireTime(jobId);
        aiJobDispatcher.dispatch(jobId, null, 0, next);
    }

    @Override
    public List<Date> nextFireTimes(String cronExpression, String timezone, int count)
    {
        return AiJobScheduleSupport.nextFireTimes(cronExpression, timezone, count);
    }

    private void validateForWrite(AiJob aiJob, boolean creating)
    {
        if (aiJob == null)
        {
            throw new ServiceException("任务不能为空");
        }
        if (StringUtils.isEmpty(aiJob.getJobName()))
        {
            throw new ServiceException("任务名称不能为空");
        }
        if (aiJob.getAgentId() == null || aiJob.getAgentId() <= 0)
        {
            throw new ServiceException("智能体不存在或已停用");
        }
        AiAgent agent = agentService.selectAiAgentById(aiJob.getAgentId());
        if (agent == null || !"0".equals(agent.getStatus()))
        {
            throw new ServiceException("智能体不存在或已停用");
        }
        if (StringUtils.isEmpty(aiJob.getPrompt()))
        {
            throw new ServiceException("指令内容不能为空");
        }
        if (aiJob.getPrompt().length() > AiJobConstants.MAX_PROMPT_CHARS)
        {
            throw new ServiceException("指令内容过长");
        }

        String misfire = aiJob.getMisfirePolicy();
        if (StringUtils.isNotEmpty(misfire))
        {
            if (ScheduleConstants.MISFIRE_IGNORE_MISFIRES.equals(misfire)
                    || (!ScheduleConstants.MISFIRE_FIRE_AND_PROCEED.equals(misfire)
                    && !ScheduleConstants.MISFIRE_DO_NOTHING.equals(misfire)
                    && !ScheduleConstants.MISFIRE_DEFAULT.equals(misfire)))
            {
                // default 0 也允许入库后按 3 处理;1 一律拒绝
                if (ScheduleConstants.MISFIRE_IGNORE_MISFIRES.equals(misfire))
                {
                    throw new ServiceException("不支持的错过策略");
                }
                if (!ScheduleConstants.MISFIRE_DEFAULT.equals(misfire)
                        && !ScheduleConstants.MISFIRE_FIRE_AND_PROCEED.equals(misfire)
                        && !ScheduleConstants.MISFIRE_DO_NOTHING.equals(misfire))
                {
                    throw new ServiceException("不支持的错过策略");
                }
            }
        }

        String triggerType = StringUtils.isEmpty(aiJob.getTriggerType())
                ? AiJobConstants.TRIGGER_CRON : aiJob.getTriggerType();
        if (AiJobConstants.TRIGGER_CRON.equals(triggerType))
        {
            if (!AiJobScheduleSupport.isValidCron(aiJob.getCronExpression()))
            {
                throw new ServiceException("cron 表达式格式错误");
            }
            int minInterval = intConfig(AiJobConstants.CONFIG_MIN_INTERVAL_MINUTES,
                    AiJobConstants.DEFAULT_MIN_INTERVAL_MINUTES);
            AiJobScheduleSupport.validateMinInterval(aiJob.getCronExpression(), aiJob.getTimezone(), minInterval);
        }
        else if (AiJobConstants.TRIGGER_ONCE.equals(triggerType))
        {
            if (aiJob.getFireTime() == null || !aiJob.getFireTime().after(new Date()))
            {
                throw new ServiceException("执行时刻必须晚于当前时间");
            }
        }
        else
        {
            throw new ServiceException("不支持的触发类型");
        }

        String sessionMode = StringUtils.isEmpty(aiJob.getSessionMode())
                ? AiJobConstants.SESSION_MODE_NEW : aiJob.getSessionMode();
        if (AiJobConstants.SESSION_MODE_FIXED.equals(sessionMode)
                && StringUtils.isNotEmpty(aiJob.getSessionId()))
        {
            AiChatSession session = sessionMapper.selectAiChatSessionById(aiJob.getSessionId());
            if (session == null)
            {
                throw new ServiceException("会话不存在");
            }
            if (aiJob.getOwnerUserId() != null && !aiJob.getOwnerUserId().equals(session.getUserId()))
            {
                throw new ServiceException("无权使用该会话");
            }
        }

        if (creating)
        {
            if (aiJob.getOwnerUserId() == null)
            {
                throw new ServiceException("归属用户不能为空");
            }
            // 仅统计启用中;新建默认正常时计入配额
            String status = StringUtils.isEmpty(aiJob.getStatus())
                    ? AiJobConstants.STATUS_NORMAL : aiJob.getStatus();
            if (AiJobConstants.STATUS_NORMAL.equals(status))
            {
                int max = intConfig(AiJobConstants.CONFIG_MAX_PER_USER, AiJobConstants.DEFAULT_MAX_PER_USER);
                int enabled = aiJobMapper.countEnabledByOwner(aiJob.getOwnerUserId());
                if (enabled >= max)
                {
                    throw new ServiceException("定时任务数量已达上限");
                }
            }
        }
        else if (AiJobConstants.STATUS_NORMAL.equals(aiJob.getStatus()) && aiJob.getOwnerUserId() != null)
        {
            // 从暂停恢复为正常时也要过配额
            AiJob existing = aiJobMapper.selectAiJobById(aiJob.getJobId());
            if (existing != null && !AiJobConstants.STATUS_NORMAL.equals(existing.getStatus()))
            {
                int max = intConfig(AiJobConstants.CONFIG_MAX_PER_USER, AiJobConstants.DEFAULT_MAX_PER_USER);
                int enabled = aiJobMapper.countEnabledByOwner(aiJob.getOwnerUserId());
                if (enabled >= max)
                {
                    throw new ServiceException("定时任务数量已达上限");
                }
            }
        }
    }

    private Date computeNextFireTime(AiJob aiJob)
    {
        if (AiJobConstants.TRIGGER_ONCE.equals(aiJob.getTriggerType()))
        {
            return aiJob.getFireTime();
        }
        if (AiJobScheduleSupport.isValidCron(aiJob.getCronExpression()))
        {
            List<Date> times = AiJobScheduleSupport.nextFireTimes(
                    aiJob.getCronExpression(), aiJob.getTimezone(), 1);
            return times.isEmpty() ? null : times.get(0);
        }
        return null;
    }

    private AiJob requireOwned(Long jobId, Long userId, boolean admin)
    {
        AiJob job = aiJobMapper.selectAiJobById(jobId);
        if (job == null)
        {
            throw new ServiceException("任务不存在");
        }
        assertOwner(job, userId, admin);
        return job;
    }

    private void assertOwner(AiJob job, Long userId, boolean admin)
    {
        if (admin)
        {
            return;
        }
        if (job.getOwnerUserId() == null || !job.getOwnerUserId().equals(userId))
        {
            throw new ServiceException("无权操作该任务");
        }
    }

    private int intConfig(String key, int fallback)
    {
        int v = Convert.toInt(configService.selectConfigByKey(key), fallback);
        return v > 0 ? v : fallback;
    }
}
