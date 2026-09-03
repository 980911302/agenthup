package com.ruoyi.system.service;

import java.util.Date;
import java.util.List;
import com.ruoyi.system.domain.AiJob;

/**
 * 智能体定时任务 服务层
 *
 * @author ruoyi
 */
public interface IAiJobService
{
    /**
     * 查询任务
     *
     * @param jobId 任务ID
     * @param userId 当前用户
     * @param admin 是否超管
     * @return 任务
     */
    public AiJob selectAiJobById(Long jobId, Long userId, boolean admin);

    /**
     * 查询任务列表(非超管强制按 owner 过滤)
     *
     * @param aiJob 条件
     * @param userId 当前用户
     * @param admin 是否超管
     * @return 列表
     */
    public List<AiJob> selectAiJobList(AiJob aiJob, Long userId, boolean admin);

    /**
     * 新增任务并注册调度
     *
     * @param aiJob 任务
     * @return 行数
     */
    public int insertAiJob(AiJob aiJob);

    /**
     * 修改任务并重建调度
     *
     * @param aiJob 任务
     * @param userId 当前用户
     * @param admin 是否超管
     * @return 行数
     */
    public int updateAiJob(AiJob aiJob, Long userId, boolean admin);

    /**
     * 批量删除任务并移除调度
     *
     * @param jobIds 任务ID
     * @param userId 当前用户
     * @param admin 是否超管
     * @return 行数
     */
    public int deleteAiJobByIds(Long[] jobIds, Long userId, boolean admin);

    /**
     * 启用/暂停
     *
     * @param jobId 任务ID
     * @param status 目标状态 0/1
     * @param userId 当前用户
     * @param admin 是否超管
     * @return 行数
     */
    public int changeStatus(Long jobId, String status, Long userId, boolean admin);

    /**
     * 立即执行一次(走与调度相同的派发逻辑)
     *
     * @param jobId 任务ID
     * @param userId 当前用户
     * @param admin 是否超管
     */
    public void runOnce(Long jobId, Long userId, boolean admin);

    /**
     * 预览 cron 后续触发时刻
     *
     * @param cronExpression cron
     * @param timezone 时区
     * @param count 次数
     * @return 时刻列表
     */
    public List<Date> nextFireTimes(String cronExpression, String timezone, int count);
}
