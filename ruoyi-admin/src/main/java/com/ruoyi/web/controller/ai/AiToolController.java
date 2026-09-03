package com.ruoyi.web.controller.ai;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.system.domain.AiTool;
import com.ruoyi.system.service.IAiToolService;
import com.ruoyi.system.tool.ToolSyncService;

/**
 * 工具 信息操作处理
 *
 * 工具不能手动 add/edit/remove,只能由系统自动同步:
 *  - 内置工具:代码中 @Tool 注解,启动时自动同步(日常无需手动)
 *  - MCP 工具:连接后同步;管理界面「同步 MCP」拉取远端工具列表变化
 * 用户可:查看 list/getInfo/启停某个工具 changeStatus
 *
 * @author ruoyi
 */
@RestController
@RequestMapping("/ai/tool")
public class AiToolController extends BaseController
{
    @Autowired
    private IAiToolService aiToolService;

    @Autowired
    private ToolSyncService toolSyncService;

    @GetMapping("/list")
    public TableDataInfo list(AiTool aiTool)
    {
        startPage();
        List<AiTool> list = aiToolService.selectAiToolList(aiTool);
        return getDataTable(list);
    }

    @GetMapping(value = "/{toolId}")
    public AjaxResult getInfo(@PathVariable Long toolId)
    {
        return success(aiToolService.selectAiToolById(toolId));
    }

    /**
     * 启停某个工具
     */
    @Log(title = "工具管理", businessType = BusinessType.UPDATE)
    @PutMapping("/status")
    public AjaxResult changeStatus(@RequestBody AiTool aiTool)
    {
        AiTool upd = new AiTool();
        upd.setToolId(aiTool.getToolId());
        upd.setStatus(aiTool.getStatus());
        return toAjax(aiToolService.updateAiTool(upd));
    }

    /**
     * 同步全部 MCP 工具(不含内置;内置启动时已同步)
     */
    @Log(title = "工具同步", businessType = BusinessType.UPDATE)
    @PostMapping("/sync/mcp")
    public AjaxResult syncMcpAll()
    {
        int n = toolSyncService.syncMcpOnly();
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("added", n);
        result.put("stats", toolSyncService.stats());
        return AjaxResult.success("MCP 工具同步完成,新增/复活 " + n + " 个", result);
    }

    /**
     * 同步指定 MCP server 的工具
     */
    @Log(title = "工具同步", businessType = BusinessType.UPDATE)
    @PostMapping("/sync/mcp/{mcpServerId}")
    public AjaxResult syncMcp(@PathVariable Long mcpServerId)
    {
        int n = toolSyncService.syncMcp(mcpServerId);
        return success("MCP 工具同步完成,新增/复活 " + n + " 个");
    }
}
