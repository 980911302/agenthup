package com.ruoyi.toolmcpserver;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.ruoyi.system.ai.agent.ScreenshotToolCallback;
import com.ruoyi.system.tool.AiToolProperties;
import com.ruoyi.system.tool.FileTools;
import com.ruoyi.system.tool.ShellTool;

/**
 * 装配内置「执行型」工具为独立的 {@link ToolCallback} 列表。
 *
 * <p><b>命名一致性的根基</b>:工具就是主应用进程里那三个类的同款实例,
 * 零逻辑复制 —— {@code getToolDefinition()} 读出的 name/description/inputSchema
 * 与内置完全一致,再由 {@link McpToolBootstrap} 原样搬成 MCP 工具。
 *
 * <p><b>为什么不用 SPI/扫描</b>:工具类单测里都是 {@code new Xxx(props)} 直接构造,
 * 这里照搬该模式,不引任何 Spring 自动装配。
 */
@Configuration
public class BuiltinToolBeans
{
    /**
     * 执行三件套:bash + 六个文件工具 + captureScreenshot。
     *
     * <p>bash/read/write/edit 返回的是 {@code OutcomeForwardingCallback} /
     * {@code AwareForwardingCallback} 包装实例 —— bash 因此在 MCP 层还能读到
     * {@code ToolOutcomeAware.lastCallOk()} 做 isError 映射(见 McpToolBootstrap)。
     */
    @Bean
    public List<ToolCallback> execToolCallbacks(AiToolProperties aiToolProperties)
    {
        ShellTool shell = new ShellTool(aiToolProperties);
        FileTools files = new FileTools(aiToolProperties);
        ScreenshotToolCallback screenshot = new ScreenshotToolCallback(aiToolProperties);

        List<ToolCallback> list = new ArrayList<>();
        list.add(shell.bashTool());
        list.add(files.readTool());
        list.add(files.writeTool());
        list.add(files.editTool());
        list.add(files.grepTool());
        list.add(files.findTool());
        list.add(files.lsTool());
        list.add(screenshot);
        return List.copyOf(list);
    }
}