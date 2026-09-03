package com.ruoyi.system.tool;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.ruoyi.system.domain.AiMcpServer;
import com.ruoyi.system.mapper.AiMcpServerMapper;
import io.modelcontextprotocol.client.McpSyncClient;

/**
 * MCP 连接保活与健康状态。
 *
 * <p><b>解决的问题</b>:连接原本只在启动时建立一次,之后无人维护。
 * SSE / streamable HTTP 都是长连接,空闲一段时间会被服务端或中间网关(nginx 默认 60s)掐掉,
 * 而客户端毫不知情 —— 于是「第一次调用 MCP 工具必定超时,之后又正常」:
 * 第一次把请求写进了已死的连接,白等满一个 requestTimeout 才失败,失败过程中底层重连,
 * 所以第二次就好了。定时 ping 就是为了在用户碰到之前先把死连接换掉。
 *
 * <p><b>健康字段</b>:探测结果会回写 {@code ai_mcp_server.health_status / health_check_time}
 * (0未知 1正常 2异常),供列表「健康」列展示。此前只写内存 map、不落库,所以界面一直停在「未知」。
 *
 * <p><b>为什么不用 @Scheduled</b>:启用 {@code @EnableScheduling} 是全局开关,
 * 而保活属于基础设施行为,不该出现在 RuoYi 的定时任务管理界面里让人误停。
 * 这里用组件自持的单线程调度器,生命周期跟着 Bean 走。
 *
 * @author ruoyi
 */
@Component
public class McpHealthChecker
{
    private static final Logger log = LoggerFactory.getLogger(McpHealthChecker.class);

    @Autowired
    private DynamicMcpService dynamicMcpService;

    @Autowired
    private AiMcpServerMapper aiMcpServerMapper;

    @Autowired
    private AiToolProperties aiToolProperties;

    @Autowired
    private ToolLifecycle toolLifecycle;

    /** serverCode -> 最近一次探测结果 */
    private final Map<String, Health> healthMap = new ConcurrentHashMap<>();

    private ScheduledExecutorService scheduler;

    @PostConstruct
    public void start()
    {
        int interval = aiToolProperties.getMcpKeepaliveSeconds();
        if (interval <= 0)
        {
            log.info("MCP 保活已关闭(mcp-keepalive-seconds<=0)");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mcp-keepalive");
            // 守护线程:不要因为它挡住 JVM 退出
            t.setDaemon(true);
            return t;
        });
        // 首次延迟一个间隔：启动 connectAll 已回写健康；周期探测负责保活与掉线纠正
        scheduler.scheduleWithFixedDelay(this::periodicCheck, interval, interval, TimeUnit.SECONDS);
        log.info("MCP 保活已启动,间隔 {}s,自动重连={}", interval, aiToolProperties.isMcpAutoReconnect());
    }

    /**
     * 周期轮询入口:异步启动同步进行中时整轮避让。
     * <p>启动窗口内「启用但未连接」是建连中途态,此时去标异常 + 自动重连
     * 会与启动流程抢连接、互写健康状态,直接跳过,下一轮再来。
     */
    private void periodicCheck()
    {
        if (toolLifecycle.isStartupSyncRunning())
        {
            log.debug("启动同步进行中,本轮 MCP 保活跳过");
            return;
        }
        checkAll();
    }

    /**
     * 全量健康校准（启动 connectAll 之后或管理端手动触发）。
     * 对「启用中但无活跃连接」的 server 标异常，对活跃连接立即 ping。
     */
    public void refreshAll()
    {
        checkAll();
    }

    @PreDestroy
    public void stop()
    {
        if (scheduler != null)
        {
            scheduler.shutdownNow();
        }
    }

    /**
     * 逐个 ping 活跃连接；并校准「启用但未连上」的 server。
     * <p>只 ping activeClients 会漏掉启动就连失败的端 —— 它们永远不在 map 里，
     * 健康列会一直残留上一次成功的「正常」。
     *
     * <p>整个方法吞掉所有异常 —— 调度器里抛出未捕获异常会让
     * {@code scheduleWithFixedDelay} <b>永久停止后续执行</b>,保活就此静默失效。
     */
    private void checkAll()
    {
        try
        {
            // ping 只读现有连接,不持锁(单次 ping 可能耗到 requestTimeout,
            // 持锁会阻塞管理端操作)
            Map<String, McpSyncClient> clients = dynamicMcpService.getActiveClients();
            for (Map.Entry<String, McpSyncClient> e : new LinkedHashMap<>(clients).entrySet())
            {
                pingOne(e.getKey(), e.getValue());
            }
            // 「启用却没有活跃连接」校准 + 自动重连:会改连接与健康状态,
            // 必须拿生命周期锁;拿不到说明启动/管理端正在动连接,本轮跳过即可
            if (!toolLifecycle.lock().tryLock())
            {
                log.debug("生命周期锁被占用,本轮跳过无连接 server 校准");
                return;
            }
            try
            {
                calibrateUnconnected();
            }
            finally
            {
                toolLifecycle.lock().unlock();
            }
        }
        catch (Throwable t)
        {
            log.warn("MCP 保活轮询异常: {}", t.getMessage());
        }
    }

    /** 启用中却没有活跃连接的 server → 标异常（可按配置尝试重连）,调用方须已持锁 */
    private void calibrateUnconnected()
    {
        Map<String, McpSyncClient> clients = dynamicMcpService.getActiveClients();
        AiMcpServer q = new AiMcpServer();
        q.setStatus("0");
        List<AiMcpServer> enabled = aiMcpServerMapper.selectAiMcpServerList(q);
        for (AiMcpServer s : enabled)
        {
            if (s == null || s.getServerCode() == null)
            {
                continue;
            }
            if (clients.containsKey(s.getServerCode()))
            {
                continue;
            }
            Health h = healthMap.computeIfAbsent(s.getServerCode(), k -> new Health());
            h.connected = false;
            h.lastError = "无活跃连接(启动失败或已断开)";
            h.lastCheckAt = System.currentTimeMillis();
            persistHealth(s.getServerCode(), "2");
            if (aiToolProperties.isMcpAutoReconnect())
            {
                reconnect(s.getServerCode(), h);
            }
        }
    }

    private void pingOne(String serverCode, McpSyncClient client)
    {
        Health h = healthMap.computeIfAbsent(serverCode, k -> new Health());
        try
        {
            client.ping();
            h.connected = true;
            h.lastError = null;
            h.lastOkAt = System.currentTimeMillis();
            persistHealth(serverCode, "1");
        }
        catch (Exception ex)
        {
            h.connected = false;
            h.lastError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            log.warn("MCP [{}] 保活失败: {}", serverCode, h.lastError);
            persistHealth(serverCode, "2");
            if (aiToolProperties.isMcpAutoReconnect())
            {
                reconnect(serverCode, h);
            }
        }
        h.lastCheckAt = System.currentTimeMillis();
    }

    /**
     * 把探测结果落到 ai_mcp_server,列表「健康」列才不是永远「未知」。
     * 写库失败只打日志 —— 不能让保活线程因 DB 抖动停摆。
     */
    private void persistHealth(String serverCode, String healthStatus)
    {
        try
        {
            aiMcpServerMapper.updateHealthByServerCode(serverCode, healthStatus, new Date());
        }
        catch (Exception e)
        {
            log.debug("回写 MCP [{}] 健康状态失败: {}", serverCode, e.getMessage());
        }
    }

    private void reconnect(String serverCode, Health h)
    {
        try
        {
            AiMcpServer q = new AiMcpServer();
            q.setServerCode(serverCode);
            List<AiMcpServer> found = aiMcpServerMapper.selectAiMcpServerList(q);
            AiMcpServer target = found.stream()
                    .filter(s -> serverCode.equals(s.getServerCode()))
                    .findFirst().orElse(null);
            if (target == null)
            {
                log.warn("MCP [{}] 已不在配置中,跳过重连", serverCode);
                return;
            }
            if (!"0".equals(target.getStatus()))
            {
                log.info("MCP [{}] 已停用,跳过重连", serverCode);
                return;
            }
            String bean = dynamicMcpService.reconnect(target.getMcpServerId());
            h.reconnectCount++;
            h.connected = bean != null;
            if (bean != null)
            {
                h.lastError = null;
                h.lastOkAt = System.currentTimeMillis();
                persistHealth(serverCode, "1");
                log.info("MCP [{}] 自动重连成功", serverCode);
            }
            else
            {
                persistHealth(serverCode, "2");
                log.warn("MCP [{}] 自动重连失败,下个周期再试", serverCode);
            }
        }
        catch (Exception e)
        {
            log.warn("MCP [{}] 自动重连异常: {}", serverCode, e.getMessage());
        }
    }

    /**
     * 运行时连接状态快照(给管理界面用)。
     *
     * <p>注意与 {@code ai_mcp_server.status} 区分:那个是「启用/停用」的配置意图,
     * 这里才是「此刻连没连上」的事实。界面上两者都要显示,否则会出现
     * 「显示已启用、实际早断了」的误导。
     */
    public List<Map<String, Object>> snapshot()
    {
        Map<String, McpSyncClient> clients = dynamicMcpService.getActiveClients();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, McpSyncClient> e : clients.entrySet())
        {
            String code = e.getKey();
            Health h = healthMap.get(code);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("serverCode", code);
            // isInitialized 是 SDK 的即时判断，比上一轮 ping 的缓存结果更新
            boolean initialized = false;
            try
            {
                initialized = e.getValue().isInitialized();
            }
            catch (Exception ignored)
            {
            }
            row.put("initialized", initialized);
            row.put("connected", h == null ? initialized : (h.connected && initialized));
            row.put("lastCheckAt", h == null ? null : h.lastCheckAt);
            row.put("lastOkAt", h == null ? null : h.lastOkAt);
            row.put("lastError", h == null ? null : h.lastError);
            row.put("reconnectCount", h == null ? 0 : h.reconnectCount);
            list.add(row);
        }
        return list;
    }

    /** 单个 server 的探测结果 */
    private static final class Health
    {
        volatile boolean connected;
        volatile long lastCheckAt;
        volatile long lastOkAt;
        volatile String lastError;
        volatile int reconnectCount;
    }
}
