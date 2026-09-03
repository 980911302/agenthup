package com.ruoyi.web.controller.ai;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.domain.AiJobLog;
import com.ruoyi.system.service.IAiJobLogService;

/**
 * 智能体定时任务触发日志 信息操作处理
 *
 * <p>AI 模块约定不加 {@code @PreAuthorize}；owner 隔离在 Service 层落实。
 *
 * @author ruoyi
 */
@RestController
@RequestMapping("/ai/jobLog")
public class AiJobLogController extends BaseController
{
    @Autowired
    private IAiJobLogService aiJobLogService;

    /**
     * 查询触发日志列表(非超管仅本人任务)
     */
    @GetMapping("/list")
    public TableDataInfo list(AiJobLog aiJobLog)
    {
        startPage();
        List<AiJobLog> list = aiJobLogService.selectAiJobLogList(aiJobLog, getUserId(), isAdmin());
        return getDataTable(list);
    }

    /**
     * 删除触发日志
     */
    @Log(title = "AI定时任务", businessType = BusinessType.DELETE)
    @DeleteMapping("/{logIds}")
    public AjaxResult remove(@PathVariable Long[] logIds)
    {
        return toAjax(aiJobLogService.deleteAiJobLogByIds(logIds, getUserId(), isAdmin()));
    }

    private boolean isAdmin()
    {
        return SecurityUtils.isAdmin(getUserId());
    }
}
