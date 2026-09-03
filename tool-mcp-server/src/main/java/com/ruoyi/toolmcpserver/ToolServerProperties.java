package com.ruoyi.toolmcpserver;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 独立 tool-mcp-server 的传输层配置(前缀 {@code ruoyi.ai.tool.server})。
 *
 * <p>与端口/应用相关配置({@code server.port})分开:这里只管「MCP 传输怎么挂、
 * 并发怎么限」,端口走标准 {@code server.port}(默认 8090)。
 */
@ConfigurationProperties(prefix = "ruoyi.ai.tool.server")
public class ToolServerProperties
{
    /** MCP 端点路径(streamable HTTP:同一路径同时承接 initialize/消息 POST 与 SSE GET) */
    private String endpoint = "/mcp";

    /**
     * 进程类工具(bash / captureScreenshot)的最大并发。
     * <p>OPI 这类 2G 内存设备经不起并发 chromium / bash 进程,超配额直接返回明确错误,
     * 而不是排队 —— 排队会占用 MCP client 的请求超时窗口,反而更难收敛。
     */
    private int maxConcurrency = 4;

    /**
     * 共享工作区键(会话绑定的占位)。standalone server 没有主应用那套会话,
     * v1 所有工具调用统一落到 {@code {workspace-root}/{workspaceKey}} 这一个沙箱根,
     * 保证路径围栏/符号链接防护(WorkspaceContext + WorkspaceSandbox)仍然生效。
     */
    private String workspaceKey = "_shared";

    /** 服务端单次工具调用的处理超时:必须 ≥ bash 上限(600s),不然长命令被服务端自己掐掉 */
    private long requestTimeoutSeconds = 600L;

    /** SSE 保活间隔秒数:压住 nginx 等链路上常见的 60s 空闲超时(见 CLAUDE.md 的踩坑清单) */
    private int keepAliveSeconds = 30;

    public String getEndpoint()
    {
        return endpoint;
    }

    public void setEndpoint(String endpoint)
    {
        this.endpoint = endpoint;
    }

    public int getMaxConcurrency()
    {
        return maxConcurrency;
    }

    public void setMaxConcurrency(int maxConcurrency)
    {
        this.maxConcurrency = maxConcurrency;
    }

    public String getWorkspaceKey()
    {
        return workspaceKey;
    }

    public void setWorkspaceKey(String workspaceKey)
    {
        this.workspaceKey = workspaceKey;
    }

    public long getRequestTimeoutSeconds()
    {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(long requestTimeoutSeconds)
    {
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }

    public int getKeepAliveSeconds()
    {
        return keepAliveSeconds;
    }

    public void setKeepAliveSeconds(int keepAliveSeconds)
    {
        this.keepAliveSeconds = keepAliveSeconds;
    }
}