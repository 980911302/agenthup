package com.ruoyi.web.controller.ai;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
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
import com.ruoyi.system.domain.AiMemory;
import com.ruoyi.system.service.IAiMemoryAdminService;

/** 管理员长期记忆台账管理。 */
@RestController
@RequestMapping("/ai/memory")
public class AiMemoryController extends BaseController
{
    @Autowired
    private IAiMemoryAdminService aiMemoryAdminService;

    @PreAuthorize("@ss.hasPermi('ai:memory:list')")
    @GetMapping("/list")
    public TableDataInfo list(AiMemory query)
    {
        startPage();
        List<AiMemory> list = aiMemoryAdminService.selectAiMemoryList(query);
        return getDataTable(list);
    }

    @PreAuthorize("@ss.hasPermi('ai:memory:remove')")
    @Log(title = "长期记忆管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{memoryIds}")
    public AjaxResult remove(@PathVariable Long[] memoryIds)
    {
        return toAjax(aiMemoryAdminService.deleteAiMemoryByIds(memoryIds));
    }
}
