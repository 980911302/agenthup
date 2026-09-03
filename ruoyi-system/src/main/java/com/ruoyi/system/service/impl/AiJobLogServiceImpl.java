package com.ruoyi.system.service.impl;

import java.util.Date;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.AiJob;
import com.ruoyi.system.domain.AiJobLog;
import com.ruoyi.system.mapper.AiJobLogMapper;
import com.ruoyi.system.mapper.AiJobMapper;
import com.ruoyi.system.service.IAiJobLogService;

/**
 * 智能体定时任务触发日志 服务层实现
 *
 * <p>owner 隔离与 {@code AiJobServiceImpl} 一致:非超管只能看/删本人任务产生的日志。
 *
 * @author ruoyi
 */
@Service
public class AiJobLogServiceImpl implements IAiJobLogService
{
    @Autowired
    private AiJobLogMapper aiJobLogMapper;

    @Autowired
    private AiJobMapper aiJobMapper;

    @Override
    public List<AiJobLog> selectAiJobLogList(AiJobLog aiJobLog, Long userId, boolean admin)
    {
        if (!admin)
        {
            aiJobLog.setOwnerUserId(userId);
        }
        return aiJobLogMapper.selectAiJobLogList(aiJobLog);
    }

    @Override
    public int deleteAiJobLogByIds(Long[] logIds, Long userId, boolean admin)
    {
        if (logIds == null || logIds.length == 0)
        {
            return 0;
        }
        for (Long logId : logIds)
        {
            AiJobLog jobLog = aiJobLogMapper.selectAiJobLogById(logId);
            if (jobLog == null)
            {
                throw new ServiceException("日志不存在");
            }
            assertLogOwner(jobLog, userId, admin);
        }
        return aiJobLogMapper.deleteAiJobLogByIds(logIds);
    }

    @Override
    public int cleanExpiredLogs(Date before)
    {
        return aiJobLogMapper.cleanExpiredLogs(before);
    }

    /**
     * 校验日志所属任务是否归当前用户(超管放行)
     */
    private void assertLogOwner(AiJobLog jobLog, Long userId, boolean admin)
    {
        if (admin)
        {
            return;
        }
        if (jobLog.getJobId() == null)
        {
            throw new ServiceException("无权操作该日志");
        }
        AiJob job = aiJobMapper.selectAiJobById(jobLog.getJobId());
        if (job == null || job.getOwnerUserId() == null || !job.getOwnerUserId().equals(userId))
        {
            throw new ServiceException("无权操作该日志");
        }
    }
}
