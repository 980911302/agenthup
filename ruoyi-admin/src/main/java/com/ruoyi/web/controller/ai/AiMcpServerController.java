package com.ruoyi.web.controller.ai;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.system.domain.AiMcpServer;
import com.ruoyi.system.service.IAiMcpServerService;
import com.ruoyi.system.tool.DynamicMcpService;
import com.ruoyi.system.tool.McpHealthChecker;
import com.ruoyi.system.tool.ToolSyncService;

@RestController
@RequestMapping("/ai/mcpServer")
public class AiMcpServerController extends BaseController
{
    @Autowired
    private IAiMcpServerService aiMcpServerService;

    @Autowired
    private DynamicMcpService dynamicMcpService;

    @Autowired
    private McpHealthChecker mcpHealthChecker;

    @Autowired
    private ToolSyncService toolSyncService;

    @GetMapping("/list")
    public TableDataInfo list(AiMcpServer aiMcpServer)
    {
        startPage();
        List<AiMcpServer> list = aiMcpServerService.selectAiMcpServerList(aiMcpServer);
        return getDataTable(list);
    }

    @GetMapping(value = "/{mcpServerId}")
    public AjaxResult getInfo(@PathVariable Long mcpServerId)
    {
        return success(aiMcpServerService.selectAiMcpServerById(mcpServerId));
    }

    @PreAuthorize("@ss.hasPermi('ai:mcpServer:add')")
    @Log(title = "MCP服务", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody AiMcpServer aiMcpServer)
    {
        aiMcpServer.setCreateBy(getUsername());
        int n = aiMcpServerService.insertAiMcpServer(aiMcpServer);
        // 保存后自动连接 + 同步工具
        if (n > 0 && "0".equals(aiMcpServer.getStatus()))
        {
            try
            {
                String bean = dynamicMcpService.connect(aiMcpServer);
                if (bean != null) { toolSyncService.syncMcp(aiMcpServer.getMcpServerId()); }
            }
            catch (Exception e) { /* 连接失败不影响保存 */ }
        }
        return toAjax(n);
    }

    @PreAuthorize("@ss.hasPermi('ai:mcpServer:edit')")
    @Log(title = "MCP服务", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AiMcpServer aiMcpServer)
    {
        aiMcpServer.setUpdateBy(getUsername());
        int n = aiMcpServerService.updateAiMcpServer(aiMcpServer);
        // 修改后自动重连 + 重新同步
        if (n > 0)
        {
            try
            {
                dynamicMcpService.reconnect(aiMcpServer.getMcpServerId());
                toolSyncService.syncMcp(aiMcpServer.getMcpServerId());
            }
            catch (Exception e) { /* 重连失败不影响保存 */ }
        }
        return toAjax(n);
    }

    @PreAuthorize("@ss.hasPermi('ai:mcpServer:remove')")
    @Log(title = "MCP服务", businessType = BusinessType.DELETE)
    @DeleteMapping("/{mcpServerIds}")
    public AjaxResult remove(@PathVariable Long[] mcpServerIds)
    {
        // 删除前先取出 server(软删后就查不到了),用于断开运行时连接 + 删工具
        java.util.List<com.ruoyi.system.domain.AiMcpServer> servers = new java.util.ArrayList<>();
        for (Long id : mcpServerIds)
        {
            com.ruoyi.system.domain.AiMcpServer s = aiMcpServerService.selectAiMcpServerById(id);
            if (s != null)
            {
                servers.add(s);
            }
        }
        int n = aiMcpServerService.deleteAiMcpServerByIds(mcpServerIds);
        // 逐个清理运行时资源 + ai_tool 记录
        for (com.ruoyi.system.domain.AiMcpServer s : servers)
        {
            try
            {
                dynamicMcpService.disconnect(s);
                toolSyncService.removeMcpTools(s.getMcpServerId());
            }
            catch (Exception e)
            {
                /* 清理失败不影响删除结果,记日志 */
                logger.warn("清理已删除 MCP server [{}] 运行时资源失败: {}", s.getServerName(), e.getMessage());
            }
        }
        return toAjax(n);
    }

    /**
     * 手动重连(修改了 server 配置后)
     */
    @PreAuthorize("@ss.hasPermi('ai:mcpServer:edit')")
    @Log(title = "MCP服务", businessType = BusinessType.UPDATE)
    @PostMapping("/{mcpServerId}/reconnect")
    public AjaxResult reconnect(@PathVariable Long mcpServerId)
    {
        String bean = dynamicMcpService.reconnect(mcpServerId);
        int synced = toolSyncService.syncMcp(mcpServerId);
        return AjaxResult.success(bean == null ? "重连失败" : "重连成功", java.util.Map.of(
            "bean", bean == null ? "" : bean,
            "synced", synced
        ));
    }

    /**
     * 运行时连接状态(全部)。
     *
     * <p>与列表接口里的 {@code status} 区分:那个是「启用/停用」的配置意图,
     * 这里是「此刻连没连上」的事实。两者可能不一致 —— 配置启用但连接早断了,
     * 正是「第一次调用 MCP 工具必超时」的根源。
     */
    @GetMapping("/runtime-status")
    public AjaxResult runtimeStatus()
    {
        return AjaxResult.success(mcpHealthChecker.snapshot());
    }
}
