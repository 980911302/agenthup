package com.ruoyi.system.service;

import java.util.List;
import com.ruoyi.system.domain.AiJobLog;

/**
 * 智能体定时任务触发日志 服务层
 *
 * @author ruoyi
 */
public interface IAiJobLogService
{

    /**
     * 查询日志列表(非超管仅本人任务下的日志)
     *
     * @param aiJobLog 条件
     * @param userId 当前用户
     * @param admin 是否超管
     * @return 列表
     */
    public List<AiJobLog> selectAiJobLogList(AiJobLog aiJobLog, Long userId, boolean admin);

    /**
     * 批量删除日志(非超管仅可删本人任务下的日志)
     *
     * @param logIds 日志ID
     * @param userId 当前用户
     * @param admin 是否超管
     * @return 行数
     */
    public int deleteAiJobLogByIds(Long[] logIds, Long userId, boolean admin);

    /**
     * 清理过期日志
     *
     * @param before 保留边界
     * @return 行数
     */
    public int cleanExpiredLogs(java.util.Date before);
}
