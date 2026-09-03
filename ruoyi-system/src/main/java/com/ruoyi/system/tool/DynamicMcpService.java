package com.ruoyi.system.tool;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.utils.EncryptUtils;
import com.ruoyi.system.domain.AiMcpServer;
import com.ruoyi.system.mapper.AiMcpServerMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;

/**
 * MCP 动态连接服务
 *
 * 启动时遍历 ai_mcp_server 表里启用的 server,逐个建立 MCP 客户端连接,
 * 把每个 client 包成 ToolCallbackProvider,动态注册到 Spring 容器
 *
 * 这样:
 *  - ToolSyncService 通过 List<ToolCallbackProvider> 自动收集 MCP 工具
 *  - 智能体调用工具时,Spring AI 走 MCP 协议去远端执行
 *
 * 重新连接:reconnect(serverId) 会先注销旧的 Bean,再为单个 server 重建
 *
 * @author ruoyi
 */
@Service
public class DynamicMcpService
{
    private static final Logger log = LoggerFactory.getLogger(DynamicMcpService.class);

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private AiMcpServerMapper aiMcpServerMapper;

    @Autowired
    private EncryptUtils encryptUtils;

    @Autowired
    private ToolCallbackRegistry toolCallbackRegistry;

    @Autowired
    private AiToolProperties aiToolProperties;

    /** 生命周期锁:与启动异步同步/工具同步串行,防交错注册/销毁 provider Bean */
    @Autowired
    private ToolLifecycle toolLifecycle;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** key = serverCode, value = McpSyncClient(连接生命周期管理/重连用,工具收集走 ToolCallbackProvider) */
    private final java.util.Map<String, McpSyncClient> activeClients = new java.util.concurrent.ConcurrentHashMap<>();

    public java.util.Map<String, McpSyncClient> getActiveClients()
    {
        return activeClients;
    }

    /**
     * 应用启动后调用:连接所有启用的 MCP server。
     * <p>无论成败都会回写 {@code health_status}（1 正常 / 2 异常），
     * 避免「连不上但界面仍显示健康」（旧成功状态残留）。
     */
    public void connectAll()
    {
        toolLifecycle.lock().lock();
        try
        {
            AiMcpServer q = new AiMcpServer();
            q.setStatus("0");
            List<AiMcpServer> servers = aiMcpServerMapper.selectAiMcpServerList(q);
            log.info("启动时连接 MCP server,启用数量: {}", servers.size());
            for (AiMcpServer s : servers)
            {
                try
                {
                    String bean = connect(s);
                    if (bean == null)
                    {
                        // connect 内部已 mark 异常；再兜底一次
                        markHealth(s.getServerCode(), "2");
                    }
                }
                catch (Exception e)
                {
                    log.error("连接 MCP server 失败 [{}]: {}", s.getServerName(), e.getMessage(), e);
                    markHealth(s.getServerCode(), "2");
                }
            }
            // 启用中却不在活跃连接表里的，一律标异常（防止漏写）
            for (AiMcpServer s : servers)
            {
                if (s.getServerCode() != null && !activeClients.containsKey(s.getServerCode()))
                {
                    markHealth(s.getServerCode(), "2");
                }
            }
        }
        finally
        {
            toolLifecycle.lock().unlock();
        }
    }

    /**
     * 为单个 MCP server 建立连接 + 注册 ToolCallbackProvider
     * @return 创建的 Bean 名称；失败返回 null，并已回写 health_status=2
     */
    public String connect(AiMcpServer server)
    {
        if (server == null || server.getServerCode() == null)
        {
            return null;
        }
        toolLifecycle.lock().lock();
        try
        {
            return doConnect(server);
        }
        finally
        {
            toolLifecycle.lock().unlock();
        }
    }

    /** 实际建连逻辑,调用方必须已持 {@link ToolLifecycle} 锁 */
    private String doConnect(AiMcpServer server)
    {
        String code = server.getServerCode();
        String beanName = ToolSyncService.MCP_PROVIDER_BEAN_PREFIX + code;
        if (applicationContext.containsBean(beanName) && activeClients.containsKey(code))
        {
            log.warn("MCP server [{}] 已连接,跳过", server.getServerName());
            markHealth(code, "1");
            return beanName;
        }
        // Bean 残留但 client 不在表里：先清掉，避免假阳性「已连接」
        if (applicationContext.containsBean(beanName) && !activeClients.containsKey(code))
        {
            log.warn("MCP server [{}] 存在残留 Bean 但无活跃 client，先注销再连", server.getServerName());
            unregister(code);
        }

        McpSyncClient client = null;
        try
        {
            client = createClient(server);
            ToolCallbackProvider provider = SyncMcpToolCallbackProvider.builder()
                .mcpClients(client)
                .build();
            client.initialize();
            // 走 Spring AI 框架:通过 provider 拿工具,验证连接 + 提前发现工具重名等问题
            ToolCallback[] callbacks = provider.getToolCallbacks();
            log.info("MCP server [{}] 通过 provider 拿到 {} 个工具", server.getServerName(), callbacks.length);
            for (ToolCallback cb : callbacks)
            {
                log.info("  - tool: name={}, desc={}", cb.getToolDefinition().name(),
                    cb.getToolDefinition().description());
            }

            ConfigurableListableBeanFactory factory =
                ((ConfigurableApplicationContext) applicationContext).getBeanFactory();
            factory.registerSingleton(beanName, provider);
            activeClients.put(code, client);
            markHealth(code, "1");
            log.info("MCP server [{}] 连接成功,已注册 Bean: {}", server.getServerName(), beanName);
            return beanName;
        }
        catch (Exception e)
        {
            log.error("MCP server [{}] 初始化失败: {}", server.getServerName(), e.getMessage());
            if (client != null)
            {
                try { client.close(); } catch (Exception ignore) {}
            }
            activeClients.remove(code);
            markHealth(code, "2");
            return null;
        }
    }

    /**
     * 回写健康状态到库。0 未知 / 1 正常 / 2 异常。
     * 写库失败只打日志，不抛到连接主路径。
     */
    public void markHealth(String serverCode, String healthStatus)
    {
        if (serverCode == null || healthStatus == null)
        {
            return;
        }
        try
        {
            aiMcpServerMapper.updateHealthByServerCode(serverCode, healthStatus, new Date());
        }
        catch (Exception e)
        {
            log.debug("回写 MCP [{}] 健康状态失败: {}", serverCode, e.getMessage());
        }
    }

    /**
     * 重新连接单个 server(用于"修改 MCP server 配置"后)。
     * <p>先注销旧连接(关闭 client + 销毁 Bean),再重建连接,最后刷新工具索引,
     * 让 AgentContextFactory 立刻拿到新工具。
     */
    public String reconnect(Long mcpServerId)
    {
        AiMcpServer server = aiMcpServerMapper.selectAiMcpServerById(mcpServerId);
        if (server == null)
        {
            return null;
        }
        toolLifecycle.lock().lock();
        try
        {
            // 先注销旧连接(关闭 client + 销毁 provider Bean)
            unregister(server.getServerCode());
            String result = doConnect(server);
            // connect 已写健康；再兜底失败场景
            if (result == null && server.getServerCode() != null)
            {
                markHealth(server.getServerCode(), "2");
            }
            // 无论是否成功都刷新:断线 server 的工具也要从索引里摘掉
            toolCallbackRegistry.refresh();
            return result;
        }
        finally
        {
            toolLifecycle.lock().unlock();
        }
    }

    /**
     * 断开并清理单个 server 的运行时资源(用于删除 MCP server):
     * 关闭 MCP client 连接 + 注销 ToolCallbackProvider Bean + 刷新工具索引。
     * 不删 ai_tool 记录(由 ToolSyncService.removeMcpTools 负责)。
     */
    public void disconnect(AiMcpServer server)
    {
        if (server == null || server.getServerCode() == null)
        {
            return;
        }
        toolLifecycle.lock().lock();
        try
        {
            unregister(server.getServerCode());
            toolCallbackRegistry.refresh();
            log.info("MCP server [{}] 已断开并清理运行时资源", server.getServerName());
        }
        finally
        {
            toolLifecycle.lock().unlock();
        }
    }

    /**
     * 注销单个 server 的运行时资源:关闭 MCP client + 销毁 Bean + 移除连接表。
     * <p>注意:MCP provider 用 {@code registerSingleton} 注册,只有实例没有 BeanDefinition,
     * 必须用 {@code destroySingleton} 销毁;若用 {@code destroyBean} 会因取不到
     * BeanDefinition 抛 {@code NoSuchBeanDefinitionException}(曾导致重连 500)。
     */
    private void unregister(String serverCode)
    {
        if (serverCode == null)
        {
            return;
        }
        // 1. 关闭并移除 MCP client(STDIO 模式会终止子进程,避免泄漏)
        McpSyncClient client = activeClients.remove(serverCode);
        if (client != null)
        {
            try
            {
                client.close();
            }
            catch (Exception e)
            {
                log.warn("关闭 MCP client [{}] 失败: {}", serverCode, e.getMessage());
            }
        }
        // 2. 销毁 provider Bean(registerSingleton 注册的必须用 destroySingleton)
        String beanName = ToolSyncService.MCP_PROVIDER_BEAN_PREFIX + serverCode;
        if (applicationContext.containsBean(beanName))
        {
            ConfigurableListableBeanFactory factory =
                ((ConfigurableApplicationContext) applicationContext).getBeanFactory();
            // DefaultListableBeanFactory extends DefaultSingletonBeanRegistry
            ((org.springframework.beans.factory.support.DefaultSingletonBeanRegistry) factory)
                .destroySingleton(beanName);
            log.info("MCP server [{}] 已注销 Bean: {}", serverCode, beanName);
        }
    }

    /**
     * 根据 transport 创建 McpSyncClient
     *
     * <p>超时必须显式设置:SDK 的 {@code SyncSpec} 默认 requestTimeout 只有 20s
     * (见 McpClient$SyncSpec 构造函数里的 Duration.ofSeconds(20)),
     * 工具真在干活时很容易踩线,而且连接失效时也要白等满这 20s 才报错。
     */
    private McpSyncClient createClient(AiMcpServer server)
    {
        String transport = server.getTransport();
        if ("STDIO".equalsIgnoreCase(transport))
        {
            ServerParameters params = ServerParameters.builder(server.getCommand())
                .args(parseStringList(server.getArgs()))
                .env(parseStringMap(encryptUtils.decrypt(server.getEnv())))
                .build();
            StdioClientTransport transport0 = new StdioClientTransport(params, McpJsonMapper.getDefault());
            return withTimeouts(McpClient.sync(transport0)).build();
        }
        if ("SSE".equalsIgnoreCase(transport))
        {
            HttpClientSseClientTransport transport0 = HttpClientSseClientTransport.builder(server.getEndpoint()).build();
            return withTimeouts(McpClient.sync(transport0)).build();
        }
        // HTTP streamable
        String url = server.getEndpoint();
        var transport0 = io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
            .builder(url).build();
        return withTimeouts(McpClient.sync(transport0)).build();
    }

    /** 统一套用配置里的超时,三种 transport 一致 */
    private McpClient.SyncSpec withTimeouts(McpClient.SyncSpec spec)
    {
        return spec
            .requestTimeout(java.time.Duration.ofMillis(aiToolProperties.getMcpRequestTimeoutMs()))
            .initializationTimeout(java.time.Duration.ofMillis(aiToolProperties.getMcpInitTimeoutMs()));
    }

    private List<String> parseStringList(String json)
    {
        if (json == null || json.isEmpty()) return List.of();
        try
        {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        }
        catch (Exception e)
        {
            log.warn("解析 JSON 数组失败,按逗号分隔处理: {}", e.getMessage());
            return List.of(json.split(","));
        }
    }

    private Map<String, String> parseStringMap(String json)
    {
        if (json == null || json.isEmpty()) return new HashMap<>();
        try
        {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        }
        catch (Exception e)
        {
            log.warn("解析 JSON 对象失败: {}", e.getMessage());
            return new HashMap<>();
        }
    }
}
