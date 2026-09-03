package com.ruoyi.system.tool;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.ruoyi.system.domain.AiMcpServer;
import com.ruoyi.system.mapper.AiMcpServerMapper;
import jakarta.annotation.PreDestroy;

/**
 * 启动异步同步:MCP 连接 + ai_tool 同步 + 索引刷新,从启动主线程挪到后台。
 *
 * <p>原在 {@link ToolSyncApplicationRunner} 里同步执行,单个慢 MCP(uvx 现拉包、
 * SSE 握手不通走到 init 超时)会线性拖慢应用启动。异步化后应用先起来,工具索引
 * 在后台就绪,代价与保障:
 * <ul>
 *   <li><b>窗口期降级</b>:同步完成前进来的 agent 请求,缺的工具走
 *       {@code AgentContextFactory.resolveTools} 的自愈刷新 + 跳过(既有兜底),
 *       MCP 未连完的工具本轮跳过、下轮可用</li>
 *   <li><b>竞态</b>:全流程持 {@link ToolLifecycle} 锁,与管理端操作、手动同步
 *       串行;startupSyncRunning 旗标让保活线程在此期间整体避让</li>
 *   <li><b>失败隔离</b>:任何异常只记日志,不影响应用运行</li>
 * </ul>
 *
 * @author ruoyi
 */
@Component
public class ToolStartupSync
{
    private static final Logger log = LoggerFactory.getLogger(ToolStartupSync.class);

    @Autowired
    private ToolSyncService toolSyncService;

    @Autowired
    private DynamicMcpService dynamicMcpService;

    @Autowired
    private AiMcpServerMapper aiMcpServerMapper;

    @Autowired
    private ToolLifecycle toolLifecycle;

    @Autowired(required = false)
    private McpHealthChecker mcpHealthChecker;

    /** 幂等旗标:ApplicationRunner 只触发一次,防御重复提交 */
    private final AtomicBoolean submitted = new AtomicBoolean(false);

    /** 守护线程:JVM 退出不被它挡住;shutdown 时 shutdownNow 中断即可 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tool-startup-sync");
        t.setDaemon(true);
        return t;
    });

    /**
     * 提交启动同步(幂等)。立即返回,不阻塞启动主线程。
     */
    public void submitStartupSync()
    {
        if (!submitted.compareAndSet(false, true))
        {
            return;
        }
        executor.submit(this::doSync);
    }

    private void doSync()
    {
        toolLifecycle.setStartupSyncRunning(true);
        toolLifecycle.lock().lock();
        long start = System.currentTimeMillis();
        try
        {
            log.info("启动同步(异步)开始:连接 MCP server 并同步工具");
            // 0. 演示数据:表空时插入一条 uvx fetch MCP server,让你看到 MCP 流程跑通
            ensureDemoMcpServer();
            // 1. 清理孤儿 MCP 工具(server 已删除但工具残留的历史脏数据,旧版 remove 未清理)
            int purged = toolSyncService.purgeOrphanMcpTools();
            if (purged > 0)
            {
                log.info("启动自愈:清理孤儿 MCP 工具 {} 条", purged);
            }
            // 2. 连接所有启用的 MCP server（失败会回写 health_status=2）
            dynamicMcpService.connectAll();
            // 2.1 再校准一遍：启用但无活跃连接 → 异常；有连接则 ping
            if (mcpHealthChecker != null)
            {
                try
                {
                    mcpHealthChecker.refreshAll();
                }
                catch (Exception he)
                {
                    log.warn("启动后 MCP 健康校准失败: {}", he.getMessage());
                }
            }
            // 3. 同步所有工具到 ai_tool(内置 + MCP),内部末尾已刷新工具索引
            int n = toolSyncService.syncAll();
            log.info("启动同步(异步)完成,新增/复活 {} 个工具,耗时 {}ms",
                    n, System.currentTimeMillis() - start);
        }
        catch (Exception e)
        {
            log.error("启动同步(异步)失败(不影响应用运行)", e);
        }
        finally
        {
            toolLifecycle.lock().unlock();
            toolLifecycle.setStartupSyncRunning(false);
        }
    }

    /**
     * 演示用:首次启动时,如果 ai_mcp_server 表为空,插入一条 STDIO 配置指向 uvx mcp-server-fetch
     * 真正用时删掉这段
     */
    private void ensureDemoMcpServer()
    {
        try
        {
            AiMcpServer q = new AiMcpServer();
            List<AiMcpServer> list = aiMcpServerMapper.selectAiMcpServerList(q);
            if (!list.isEmpty())
            {
                return;
            }
            AiMcpServer s = new AiMcpServer();
            s.setServerName("演示-fetch");
            s.setServerCode("demo-fetch");
            s.setTransport("STDIO");
            s.setCommand("uvx");
            s.setArgs("[\"mcp-server-fetch\"]");
            s.setEndpoint(null);
            s.setStatus("0");
            s.setRemark("首次启动演示用,uvx mcp-server-fetch 提供 fetch 工具");
            aiMcpServerMapper.insertAiMcpServer(s);
            log.info("已插入演示 MCP server(id={}),首次启动 uvx 会自动拉取 mcp-server-fetch", s.getMcpServerId());
        }
        catch (Exception e)
        {
            log.warn("插入演示 MCP server 失败(不影响启动): {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown()
    {
        executor.shutdownNow();
    }
}
