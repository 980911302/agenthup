package com.ruoyi.web.controller.ai;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.domain.AiJob;
import com.ruoyi.system.service.IAiJobService;

/**
 * 智能体定时任务 信息操作处理
 *
 * <p>AI 模块约定不加 {@code @PreAuthorize}；owner 隔离在 Service 层落实。
 *
 * @author ruoyi
 */
@RestController
@RequestMapping("/ai/job")
public class AiJobController extends BaseController
{
    @Autowired
    private IAiJobService aiJobService;

    /**
     * 查询定时任务列表(非超管仅本人)
     */
    @GetMapping("/list")
    public TableDataInfo list(AiJob aiJob)
    {
        startPage();
        List<AiJob> list = aiJobService.selectAiJobList(aiJob, getUserId(), isAdmin());
        return getDataTable(list);
    }

    /**
     * 获取定时任务详细信息
     */
    @GetMapping(value = "/{jobId}")
    public AjaxResult getInfo(@PathVariable Long jobId)
    {
        return success(aiJobService.selectAiJobById(jobId, getUserId(), isAdmin()));
    }

    /**
     * 新增定时任务
     */
    @Log(title = "AI定时任务", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody AiJob aiJob)
    {
        aiJob.setCreateBy(getUsername());
        // 归属固定为当前登录用户,禁止客户端伪造 owner
        aiJob.setOwnerUserId(getUserId());
        return toAjax(aiJobService.insertAiJob(aiJob));
    }

    /**
     * 修改定时任务
     */
    @Log(title = "AI定时任务", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AiJob aiJob)
    {
        aiJob.setUpdateBy(getUsername());
        return toAjax(aiJobService.updateAiJob(aiJob, getUserId(), isAdmin()));
    }

    /**
     * 启用/暂停
     */
    @Log(title = "AI定时任务", businessType = BusinessType.UPDATE)
    @PutMapping("/changeStatus")
    public AjaxResult changeStatus(@RequestBody AiJob aiJob)
    {
        return toAjax(aiJobService.changeStatus(aiJob.getJobId(), aiJob.getStatus(), getUserId(), isAdmin()));
    }

    /**
     * 立即执行一次(与调度同源派发; scheduledFireTime=null → manual- 幂等键)
     */
    @Log(title = "AI定时任务", businessType = BusinessType.UPDATE)
    @PostMapping("/run/{jobId}")
    public AjaxResult run(@PathVariable Long jobId)
    {
        aiJobService.runOnce(jobId, getUserId(), isAdmin());
        return success();
    }

    /**
     * 批量删除定时任务
     */
    @Log(title = "AI定时任务", businessType = BusinessType.DELETE)
    @DeleteMapping("/{jobIds}")
    public AjaxResult remove(@PathVariable Long[] jobIds)
    {
        return toAjax(aiJobService.deleteAiJobByIds(jobIds, getUserId(), isAdmin()));
    }

    /**
     * 预览 cron 后续触发时刻(默认 5 次),前端表单实时校验用
     */
    @GetMapping("/nextFireTimes")
    public AjaxResult nextFireTimes(@RequestParam String cronExpression,
            @RequestParam(required = false) String timezone)
    {
        List<Date> times = aiJobService.nextFireTimes(cronExpression, timezone, 5);
        List<String> result = new ArrayList<>(times.size());
        for (Date time : times)
        {
            result.add(DateUtils.parseDateToStr(DateUtils.YYYY_MM_DD_HH_MM_SS, time));
        }
        return success(result);
    }

    private boolean isAdmin()
    {
        return SecurityUtils.isAdmin(getUserId());
    }
}
