package com.ruoyi.toolmcpserver;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.tool.ToolOutcomeAware;
import com.ruoyi.system.tool.WorkspaceContext;
import com.ruoyi.system.tool.WorkspaceSandbox;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.http.HttpServlet;

/**
 * 把 {@link BuiltinToolBeans} 装配出来的 ToolCallback 曝光成 streamable HTTP 上的
 * MCP 服务端(endpoint 默认 {@code /mcp},随 application.yml 配置)。
 *
 * <p><b>协议形态(贴合主应用的 MCP client)</b>:主应用 DynamicMcpService 用
 * {@code HttpClientStreamableHttpTransport} 连 HTTP 端点 —— initialize/消息统一 POST
 * 到同一路径、另用 GET 建 SSE 流。因此服务端用 mcp-core 0.17 自带的
 * {@code HttpServletStreamableServerTransportProvider}(原生 HttpServlet,直接塞进
 * embedded Tomcat),代码形似 MCP 裸 SDK 手写装配,与仓库「不用 spring.ai.* 自动配置」
 * 的风格一致。
 *
 * <p><b>isError 映射(与内置工具行为一致)</b>:
 * <ul>
 *   <li>bash:正文始终原样返回(含 {@code Command exited with code N} / 危险命令拒绝文案),
 *       isError 取 {@link ToolOutcomeAware#lastCallOk()} —— 非零退出/被拒 = 失败。</li>
 *   <li>文件工具:不实现 ToolOutcomeAware,失败以正文形式返回(如 "Path not found"),isError=false。</li>
 *   <li>captureScreenshot:成功返回 JSON 正文;ServiceException(缺无头浏览器/超时/参数非法)
 *       捕获为 isError=true 且正文保留中文提示。</li>
 * </ul>
 *
 * <p><b>沙箱语义</b>:主应用里 WorkspaceContext 由 RecordingToolCallback 按会话绑定;
 * standalone 没有会话,主应用会在 args envelope 里注入 {@code _workspaceKey},
 * 这里在每个 callHandler 外层弹出来 bind(没有该字段回退共享键,见
 * {@link ToolServerProperties#getWorkspaceKey()}),让 ProjectPaths/WorkspaceSandbox
 * 的路径围栏与符号链接防护原样生效,读写落到 {@code {workspace-root}/{workspaceKey}}。
 */
@Configuration
public class McpToolBootstrap
{
    private static final Logger log = LoggerFactory.getLogger(McpToolBootstrap.class);

    static final String SERVER_NAME = "agent-tool-server";
    static final String SERVER_VERSION = "1.0.0";

    /** 真正会拉起子进程的工具(bash 命令 / 无头浏览器):受并发限流保护,防打爆沙箱内存 */
    private static final Set<String> PROCESS_BOUND_TOOLS = Set.of("bash", "captureScreenshot");

    private final ToolServerProperties serverProps;

    public McpToolBootstrap(ToolServerProperties serverProps)
    {
        this.serverProps = serverProps;
    }

    /**
     * MCP 传输:既是 {@link HttpServlet}(挂进 embedded Tomcat),又是 transport provider
     * (McpServer.sync 给它注册 session factory,会话在首个 initialize 时惰性建立)。
     */
    @Bean
    public HttpServletStreamableServerTransportProvider mcpTransport()
    {
        HttpServletStreamableServerTransportProvider provider =
                HttpServletStreamableServerTransportProvider.builder()
                        .mcpEndpoint(serverProps.getEndpoint())
                        .keepAliveInterval(Duration.ofSeconds(serverProps.getKeepAliveSeconds()))
                        .build();
        log.info("MCP transport 端点: {} (keepAlive={}s)",
                serverProps.getEndpoint(), serverProps.getKeepAliveSeconds());
        return provider;
    }

    @Bean
    public ServletRegistrationBean<HttpServlet> mcpServletRegistration(
            HttpServletStreamableServerTransportProvider mcpTransport)
    {
        ServletRegistrationBean<HttpServlet> reg = new ServletRegistrationBean<>(
                mcpTransport, serverProps.getEndpoint());
        // SSE 流依赖 Servlet 异步上下文,必须开
        reg.setAsyncSupported(true);
        return reg;
    }

    /** 进程类工具的并发闸:OPI 2G 内存经不起太多并发进程 */
    @Bean
    public Semaphore toolProcessGate()
    {
        return new Semaphore(serverProps.getMaxConcurrency());
    }

    /** 构建并启动 MCP server.build() 即完成装配(transport 的 sessionFactory 已挂上),无副作用启动。 */
    @Bean
    public McpSyncServer mcpServer(HttpServletStreamableServerTransportProvider mcpTransport,
                                   List<ToolCallback> execToolCallbacks,
                                   Semaphore toolProcessGate)
    {
        List<McpServerFeatures.SyncToolSpecification> specs = execToolCallbacks.stream()
                .map(cb -> toSyncToolSpecification(cb, toolProcessGate))
                .toList();
        McpSyncServer server = McpServer.sync(mcpTransport)
                .serverInfo(SERVER_NAME, SERVER_VERSION)
                .requestTimeout(Duration.ofSeconds(serverProps.getRequestTimeoutSeconds()))
                .tools(specs)
                .build();
        log.info("MCP tool server 就绪: 工具={}, endpoint={}",
                specs.stream().map(s -> s.tool().name()).toList(),
                serverProps.getEndpoint());
        return server;
    }

    /** ToolCallback → MCP 工具:name/description/inputSchema 原样取自 ToolDefinition,保证与内置一致。 */
    private McpServerFeatures.SyncToolSpecification toSyncToolSpecification(ToolCallback callback,
                                                                            Semaphore processGate)
    {
        ToolDefinition def = callback.getToolDefinition();
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(def.name())
                .description(def.description())
                .inputSchema(McpJsonMapper.getDefault(), def.inputSchema())
                .build();
        boolean processBound = PROCESS_BOUND_TOOLS.contains(def.name());
        log.info("挂载 MCP 工具: {} (并发受限={})", def.name(), processBound);
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> invoke(callback, request, processGate, processBound))
                .build();
    }

    private McpSchema.CallToolResult invoke(ToolCallback callback, McpSchema.CallToolRequest request,
                                            Semaphore processGate, boolean processBound)
    {
        // 主应用 RecordingToolCallback 会在 args envelope 里带 _workspaceKey:
        // 弹出来按会话 bind,读写就落到 {workspace-root}/{key} —— 与主应用工作区抽屉同目录。
        // 没有该字段(直连/旧调用)回退 server 配置的共享键。
        Map<String, Object> args = request.arguments() == null
                ? new HashMap<>() : new HashMap<>(request.arguments());
        Object envelope = args.remove(WorkspaceSandbox.WORKSPACE_KEY_FIELD);
        String workspaceKey = envelope instanceof String s && !s.isBlank()
                ? s : serverProps.getWorkspaceKey();
        // 边界前置校验:非法 key 必须在这里 fail-closed —— ProjectPaths.cwd 会吞掉
        // resolveRoot 抛出的异常并回退进程 cwd(等于静默逃出沙箱),不能依赖它拒绝。
        // 规则与 resolveRoot 同源:显式配置的共享键,或过 SessionIds 正则。
        if (!serverProps.getWorkspaceKey().equals(workspaceKey)
                && !WorkspaceSandbox.isValidSessionId(workspaceKey))
        {
            log.warn("MCP 工具 {} 拒绝执行:非法的工作区键", callback.getToolDefinition().name());
            return McpSchema.CallToolResult.builder()
                    .addTextContent("非法的工作区键,拒绝执行")
                    .isError(Boolean.TRUE)
                    .build();
        }
        String prevSession = WorkspaceContext.bind(workspaceKey);
        String prevWorkspace = WorkspaceContext.bindWorkspaceKey(workspaceKey);
        try
        {
            String argsJson = toJson(args);
            String text = processBound
                    ? withPermit(processGate, () -> callback.call(argsJson))
                    : callback.call(argsJson);
            boolean ok = resolveOk(callback);
            return McpSchema.CallToolResult.builder()
                    .addTextContent(text)
                    .isError(Boolean.valueOf(!ok))
                    .build();
        }
        catch (RuntimeException e)
        {
            log.warn("MCP 工具 {} 调用失败: {}", callback.getToolDefinition().name(), e.getMessage());
            return McpSchema.CallToolResult.builder()
                    .addTextContent(describeError(e))
                    .isError(Boolean.TRUE)
                    .build();
        }
        finally
        {
            WorkspaceContext.restore(prevSession);
            WorkspaceContext.restoreWorkspaceKey(prevWorkspace);
        }
    }

    /** 未实现 ToolOutcomeAware 或返回 null 时,保持「未抛异常即成功」(与 RecordingToolCallback 同规则)。 */
    private static boolean resolveOk(ToolCallback callback)
    {
        if (callback instanceof ToolOutcomeAware aware)
        {
            Boolean ok = aware.lastCallOk();
            if (ok != null)
            {
                return ok;
            }
        }
        return true;
    }

    /** 进程类工具超配额:不排队,直接明确报错 —— 排队会占用 client 的请求超时窗口。 */
    private static String withPermit(Semaphore gate, Supplier<String> action)
    {
        if (!gate.tryAcquire())
        {
            throw new ServiceException("工具进程并发已达上限,请稍后重试或减少本轮并行调用数");
        }
        try
        {
            return action.get();
        }
        finally
        {
            gate.release();
        }
    }

    private static String toJson(Map<String, Object> arguments)
    {
        try
        {
            return McpJsonMapper.getDefault().writeValueAsString(
                    arguments == null ? Map.of() : arguments);
        }
        catch (IOException e)
        {
            throw new ServiceException("工具参数序列化失败: " + e.getMessage());
        }
    }

    private static String describeError(RuntimeException e)
    {
        if (e instanceof ServiceException se)
        {
            return se.getMessage();
        }
        String msg = e.getMessage();
        return (msg == null || msg.isBlank()) ? e.getClass().getSimpleName() : msg;
    }
}