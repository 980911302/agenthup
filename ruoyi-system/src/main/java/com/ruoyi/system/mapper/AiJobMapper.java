package com.ruoyi.system.mapper;

import java.util.Date;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.domain.AiJob;

/**
 * 智能体定时任务 数据层
 *
 * @author ruoyi
 */
public interface AiJobMapper
{
    /**
     * 查询任务列表
     *
     * @param aiJob 查询条件
     * @return 任务集合
     */
    public List<AiJob> selectAiJobList(AiJob aiJob);

    /**
     * 按ID查询任务
     *
     * @param jobId 任务ID
     * @return 任务
     */
    public AiJob selectAiJobById(Long jobId);

    /**
     * 启动时重建调度用:只取未删除且状态为正常的
     *
     * @return 可调度任务集合
     */
    public List<AiJob> selectSchedulableJobs();

    /**
     * 新增任务
     *
     * @param aiJob 任务
     * @return 行数
     */
    public int insertAiJob(AiJob aiJob);

    /**
     * 修改任务
     *
     * @param aiJob 任务
     * @return 行数
     */
    public int updateAiJob(AiJob aiJob);

    /**
     * 批量逻辑删除
     *
     * @param jobIds 任务ID数组
     * @return 行数
     */
    public int deleteAiJobByIds(Long[] jobIds);

    /**
     * 触发后回写运行态快照,避免整行 update 覆盖并发修改
     *
     * @param jobId 任务ID
     * @param prevFireTime 上次触发时间
     * @param nextFireTime 下次触发时间
     * @param lastRunId 最近运行ID
     * @param lastStatus 最近结果
     * @param failIncrement 失败次数增量(0或1)
     * @return 行数
     */
    public int updateFireSnapshot(@Param("jobId") Long jobId,
                                  @Param("prevFireTime") Date prevFireTime,
                                  @Param("nextFireTime") Date nextFireTime,
                                  @Param("lastRunId") String lastRunId,
                                  @Param("lastStatus") String lastStatus,
                                  @Param("failIncrement") int failIncrement);

    /**
     * 配额校验:统计某用户启用中的任务数
     *
     * @param ownerUserId 归属用户
     * @return 启用中任务数
     */
    public int countEnabledByOwner(@Param("ownerUserId") Long ownerUserId);

    /**
     * max_runs / expire_time 到顶时转已完成
     *
     * @param jobId 任务ID
     * @return 行数
     */
    public int markCompleted(@Param("jobId") Long jobId);
}
