package com.ruoyi.system.mapper;

import java.util.Date;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.domain.AiJobLog;

/**
 * 智能体定时任务触发日志 数据层
 *
 * @author ruoyi
 */
public interface AiJobLogMapper
{
    /**
     * 查询触发日志列表
     *
     * @param aiJobLog 查询条件
     * @return 日志集合
     */
    public List<AiJobLog> selectAiJobLogList(AiJobLog aiJobLog);

    /**
     * 按ID查询日志
     *
     * @param logId 日志ID
     * @return 日志
     */
    public AiJobLog selectAiJobLogById(Long logId);

    /**
     * 新增触发日志
     *
     * @param aiJobLog 日志
     * @return 行数
     */
    public int insertAiJobLog(AiJobLog aiJobLog);

    /**
     * 对账回填终态
     *
     * @param aiJobLog 含 runId 与终态字段
     * @return 行数
     */
    public int updateTerminalByRunId(AiJobLog aiJobLog);

    /**
     * 按日志主键回写(派发后 SKIPPED/FAILED/TIMEOUT 等,无 runId 时也可用)
     *
     * @param aiJobLog 含 logId 与待更新字段
     * @return 行数
     */
    public int updateAiJobLog(AiJobLog aiJobLog);

    /**
     * 对账扫描:取仍处于 DISPATCHED 且已超过宽限期的记录
     *
     * @param before 宽限截止时间
     * @param limit 条数上限
     * @return 待对账日志
     */
    public List<AiJobLog> selectPendingLogs(@Param("before") Date before, @Param("limit") int limit);

    /**
     * 批量物理删除日志
     *
     * @param logIds 日志ID数组
     * @return 行数
     */
    public int deleteAiJobLogByIds(Long[] logIds);

    /**
     * 清理过期日志
     *
     * @param before 保留边界(该时刻之前的删除)
     * @return 行数
     */
    public int cleanExpiredLogs(@Param("before") Date before);
}
