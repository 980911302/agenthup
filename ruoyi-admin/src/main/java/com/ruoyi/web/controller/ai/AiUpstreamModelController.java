package com.ruoyi.web.controller.ai;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.system.domain.AiUpstreamModel;
import com.ruoyi.system.service.IAiUpstreamModelService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** 上游模型清单控制器 */
@RestController
@RequestMapping("/ai/upstreamModel")
public class AiUpstreamModelController extends BaseController
{
    @Autowired
    private IAiUpstreamModelService aiUpstreamModelService;

    // 模型管理的供应弹窗也需要读取该清单，具备渠道或模型查看权限任一即可。
    @PreAuthorize("@ss.hasAnyPermi('ai:channel:list,ai:model:list')")
    @GetMapping("/list")
    public TableDataInfo list(AiUpstreamModel query)
    {
        startPage();
        List<AiUpstreamModel> list = aiUpstreamModelService.list(query);
        return getDataTable(list);
    }

    @PreAuthorize("@ss.hasPermi('ai:channel:edit')")
    @Log(title = "上游模型", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody AiUpstreamModel model)
    {
        return toAjax(aiUpstreamModelService.addManual(model, getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('ai:channel:edit')")
    @Log(title = "上游模型", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AiUpstreamModel model)
    {
        return toAjax(aiUpstreamModelService.update(model, getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('ai:channel:edit')")
    @Log(title = "上游模型", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(aiUpstreamModelService.deleteByIds(ids));
    }

    @PreAuthorize("@ss.hasPermi('ai:channel:edit')")
    @Log(title = "上游模型", businessType = BusinessType.UPDATE)
    @PostMapping("/sync/{channelId}")
    public AjaxResult sync(@PathVariable Long channelId)
    {
        int count = aiUpstreamModelService.syncFromUpstream(channelId, getUsername());
        return AjaxResult.success("同步完成,共 " + count + " 个模型", count);
    }
}
