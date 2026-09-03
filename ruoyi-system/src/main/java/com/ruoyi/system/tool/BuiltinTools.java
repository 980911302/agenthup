package com.ruoyi.system.tool;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import com.ruoyi.system.tool.WorkspaceContext;

/**
 * 内置工具
 *
 * 用 Spring AI 的 @Tool 注解标注方法,启动后 Spring AI 自动注册为 ToolCallback
 * ToolSyncService 在启动时遍历所有 ToolCallback,同步到 ai_tool 表(tool_type=1)
 *
 * @author ruoyi
 */
@Component
public class BuiltinTools
{
    @Tool(description = "获取当前服务器时间,返回 ISO 8601 格式字符串")
    public String getCurrentTime()
    {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    @Tool(description = "查询当前对话的环境信息:当前会话ID(sessionId)与服务器时间。"
            + "当需要会话ID用于关联记录、排障或传给其它工具参数时调用本工具,无参数。")
    public String getSessionInfo()
    {
        String sessionId = WorkspaceContext.getOrNull();
        return "sessionId=" + (sessionId == null ? "" : sessionId)
                + "\ntime=" + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    // add / calculator / toUpperCase 已移除:脚手架示例,从未绑定 agent,
    // 只污染 listAvailableTools 候选列表(见 docs/ai/ai-tool-layer-cleanup.md Task D)
}

