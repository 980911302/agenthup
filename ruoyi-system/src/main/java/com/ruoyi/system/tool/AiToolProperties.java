package com.ruoyi.system.tool;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 工具配置（前缀 ruoyi.ai.tool）
 *
 * @author ruoyi
 */
@Component
@ConfigurationProperties(prefix = "ruoyi.ai.tool")
public class AiToolProperties
{
    /**
     * 工作区工具(read/write/edit/bash/grep/find/ls)的默认工作目录。
     * 相对路径相对此目录解析;绝对路径不限制。空则用进程 user.dir。
     */
    private String cwd = "";

    /** AI 工作区沙箱根目录（绝对路径），为空时回退 {ruoyi.profile}/ai-workspace */
    private String workspaceRoot = "";

    /** 是否按会话隔离工作区(true: {root}/{sessionId};false: 全局共用,兼容旧行为) */
    private boolean workspacePerSession = true;

    /**
     * 技能附件根目录:技能带的参考文件存这里,按 {root}/{skillId}/ 分目录。
     *
     * <p><b>必须与 workspace-root 分开</b>:技能附件是全局共享的只读资产,
     * 而工作区按会话隔离且模型可写。混在一起等于让模型改得动公共技能的文档。
     * loadSkill 时按需拷贝一份进当轮会话沙箱,模型读的始终是副本。
     *
     * <p>为空时回退 {ruoyi.profile}/ai-skills;生产多实例要指向共享持久卷。
     */
    private String skillRoot = "";

    /** 是否允许 Shell 工具执行本机命令 */
    private boolean shellEnabled = true;

    /** Shell 命令超时毫秒（默认 30s） */
    private long shellTimeoutMs = 30_000L;

    /**
     * MCP 单次请求超时毫秒。
     * <p>SDK 默认只有 20s，工具真在干活(爬网页、跑查询)时很容易踩线，这里放宽到 60s。
     */
    private long mcpRequestTimeoutMs = 60_000L;

    /** MCP 初始化(握手 + tools/list)超时毫秒 */
    private long mcpInitTimeoutMs = 30_000L;

    /**
     * MCP 连接保活间隔秒数，0 表示关闭保活。
     * <p><b>必须小于链路上最短的空闲超时</b>：SSE / streamable HTTP 是长连接，
     * nginx 默认 60s、多数云网关 60~300s 就会掐掉空闲连接。客户端若不主动探测，
     * 会在下一次工具调用时把请求写进一个已死的连接，白等一个 requestTimeout。
     * 默认 30s 足够压住 60s 这条最常见的线。
     */
    private int mcpKeepaliveSeconds = 30;

    /** 保活 ping 失败时是否自动重连 */
    private boolean mcpAutoReconnect = true;

    /**
     * 内置执行型工具(bash + six file tools + captureScreenshot)的来源:
     * <p>{@code local}(默认):主应用注册本地这批 {@code ShellTool}/{@code FileTools}/
     * {@code ScreenshotToolCallback} 实例。
     * <p>{@code mcp}:主应用<b>不再注册本地这批</b>,全部由远端 tool-mcp-server 同步进
     * ai_tool 的 MCP 工具顶上(见 tool-mcp-server/README.md)。远端工具名与内置完全一致,
     * 而 ai_tool.tool_code 唯一键 + Registry last-write-wins 会让同名字互相覆盖 —— 所以必须
     * 二选一,否则 agent 拿到的工具名/输出文案会混搭,破坏“一行一义”。
     */
    private String execToolsMode = "local";

    /**
     * 远端执行工具 MCP server 的 HTTP 起源(如 {@code http://10.72.121.63:8090}),
     * 工作区抽屉在 mode=mcp 时经它代理 tree/file/download 等。为空则抽屉只读本地工作区。
     */
    private String remoteWorkspaceBaseUrl = "";

    /**
     * 子智能体流空闲超时秒数:超过该时长无任何 chunk 即判定连接僵死,中断并降级返回。
     * <p>必须大于最长的工具执行静默期(工具执行期间流无输出):shell 默认 30s、
     * MCP 默认 60s,留足余量取 300s。<=0 表示关闭。
     */
    private int subAgentIdleTimeoutSeconds = 300;

    public String getCwd()
    {
        return cwd;
    }

    public void setCwd(String cwd)
    {
        this.cwd = cwd;
    }

    public String getWorkspaceRoot()
    {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(String workspaceRoot)
    {
        this.workspaceRoot = workspaceRoot;
    }

    public String getSkillRoot()
    {
        return skillRoot;
    }

    public void setSkillRoot(String skillRoot)
    {
        this.skillRoot = skillRoot;
    }

    public boolean isWorkspacePerSession()
    {
        return workspacePerSession;
    }

    public void setWorkspacePerSession(boolean workspacePerSession)
    {
        this.workspacePerSession = workspacePerSession;
    }

    public boolean isShellEnabled()
    {
        return shellEnabled;
    }

    public void setShellEnabled(boolean shellEnabled)
    {
        this.shellEnabled = shellEnabled;
    }

    public long getShellTimeoutMs()
    {
        return shellTimeoutMs;
    }

    public void setShellTimeoutMs(long shellTimeoutMs)
    {
        this.shellTimeoutMs = shellTimeoutMs;
    }

    public long getMcpRequestTimeoutMs()
    {
        return mcpRequestTimeoutMs;
    }

    public void setMcpRequestTimeoutMs(long mcpRequestTimeoutMs)
    {
        this.mcpRequestTimeoutMs = mcpRequestTimeoutMs;
    }

    public long getMcpInitTimeoutMs()
    {
        return mcpInitTimeoutMs;
    }

    public void setMcpInitTimeoutMs(long mcpInitTimeoutMs)
    {
        this.mcpInitTimeoutMs = mcpInitTimeoutMs;
    }

    public int getMcpKeepaliveSeconds()
    {
        return mcpKeepaliveSeconds;
    }

    public void setMcpKeepaliveSeconds(int mcpKeepaliveSeconds)
    {
        this.mcpKeepaliveSeconds = mcpKeepaliveSeconds;
    }

    public boolean isMcpAutoReconnect()
    {
        return mcpAutoReconnect;
    }

    public void setMcpAutoReconnect(boolean mcpAutoReconnect)
    {
        this.mcpAutoReconnect = mcpAutoReconnect;
    }

    public int getSubAgentIdleTimeoutSeconds()
    {
        return subAgentIdleTimeoutSeconds;
    }

    /**
     * 是否打印请求前缀指纹(systemPrompt 与每个工具定义的 hash),用于排查 KV-cache 命中异常。
     *
     * <p>不用日志级别控制:logback.xml 里 {@code <logger name="com.ruoyi" level="info"/>}
     * 会覆盖 application.yml 的 debug 设置,debug 日志出不来。默认关。
     */
    private boolean logPrefixFingerprint = false;

    public boolean isLogPrefixFingerprint()
    {
        return logPrefixFingerprint;
    }

    public void setLogPrefixFingerprint(boolean logPrefixFingerprint)
    {
        this.logPrefixFingerprint = logPrefixFingerprint;
    }

    public void setSubAgentIdleTimeoutSeconds(int subAgentIdleTimeoutSeconds)
    {
        this.subAgentIdleTimeoutSeconds = subAgentIdleTimeoutSeconds;
    }

    public String getExecToolsMode()
    {
        return execToolsMode;
    }

    public void setExecToolsMode(String execToolsMode)
    {
        this.execToolsMode = execToolsMode;
    }

    /** 执行型工具是否整体交给远端 MCP server(mode=mcp) */
    public boolean isExecToolsDelegatedToMcp()
    {
        return "mcp".equalsIgnoreCase(execToolsMode);
    }

    public String getRemoteWorkspaceBaseUrl()
    {
        return remoteWorkspaceBaseUrl;
    }

    public void setRemoteWorkspaceBaseUrl(String remoteWorkspaceBaseUrl)
    {
        this.remoteWorkspaceBaseUrl = remoteWorkspaceBaseUrl;
    }

    /** 工作区抽屉是否走远端代理(mode=mcp 且配了远端地址) */
    public boolean isRemoteWorkspaceEnabled()
    {
        return isExecToolsDelegatedToMcp()
                && remoteWorkspaceBaseUrl != null && !remoteWorkspaceBaseUrl.isBlank();
    }
}
