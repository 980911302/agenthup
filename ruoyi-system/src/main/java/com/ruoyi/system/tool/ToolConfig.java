package com.ruoyi.system.tool;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI 工具配置
 *
 * 把所有带 @Tool 注解的 Bean 注册成 Spring AI 的 ToolCallbackProvider,
 * 这样:
 *  - ChatClient 可以通过 tools(callbackProvider) 拿到工具
 *  - ToolSyncService 可以从容器中拿到 ToolCallback 列表,同步到 ai_tool 表
 *
 * 后续 MCP 工具也用同样的 ToolCallbackProvider 暴露,通过 spring-ai-mcp-client 创建
 *
 * @author ruoyi
 */
@Configuration
public class ToolConfig
{
    @Bean
    public ToolCallbackProvider builtinToolCallbackProvider(
        BuiltinTools builtinTools, AgentMetaTools agentMetaTools, AiJobTools aiJobTools)
    {
        return MethodToolCallbackProvider.builder()
            .toolObjects(builtinTools, agentMetaTools, aiJobTools)
            .build();
    }

    /**
     * 文件/Shell 工具（FunctionToolCallback 方式注册）
     *
     * <p>业务只读查询(queryDeptList/queryUserBrief)不在工具 MCP server 的暴露名单里,
     * 永远本地注册;bash + 六个文件工具则看 mode:local 注册本地, mcp 时整体交给远端
     * tool-mcp-server(见 AiToolProperties.execToolsMode)。
     */
    @Bean
    public ToolCallbackProvider extendedToolCallbackProvider(
        FileTools fileTools, ShellTool shellTool, BusinessTools businessTools,
        AiToolProperties aiToolProperties)
    {
        List<ToolCallback> list = new ArrayList<>();
        // 业务只读查询(部门/用户),由智能体勾选启用;重启后 syncAll 会复活软删的 ai_tool 行
        list.add(businessTools.queryDeptListTool());
        list.add(businessTools.queryUserBriefTool());
        if (!aiToolProperties.isExecToolsDelegatedToMcp())
        {
            list.add(fileTools.readTool());
            list.add(fileTools.writeTool());
            list.add(fileTools.editTool());
            list.add(fileTools.grepTool());
            list.add(fileTools.findTool());
            list.add(fileTools.lsTool());
            list.add(shellTool.bashTool());
        }

        ToolCallback[] callbacks = list.toArray(new ToolCallback[0]);
        return () -> callbacks;
    }
}
