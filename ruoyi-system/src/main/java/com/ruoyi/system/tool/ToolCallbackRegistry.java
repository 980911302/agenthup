package com.ruoyi.system.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 按 name 索引全部 {@link ToolCallback}(§6.2)。
 *
 * <p>容器里 {@link ToolCallbackProvider} 提供的 {@link ToolCallback} 已经是构造好的,
 * 按 {@code toolDefinition().name()} 建索引。而 {@code ai_tool.tool_code} 存的就是这个 name
 * (ToolSyncService.upsertBuiltin/upsertMcp 都是 {@code setToolCode(cb.getToolDefinition().name())}),
 * 天然对齐 -- 因此<b>不做反射</b>(beanName/methodName 在同步逻辑下是空的,反射方案走不通)。
 *
 * <p>刷新时机:启动后(跟 ToolSyncApplicationRunner 一起)+ MCP reconnect 之后。
 *
 * @author ruoyi
 */
@Component
public class ToolCallbackRegistry
{
    private static final Logger log = LoggerFactory.getLogger(ToolCallbackRegistry.class);

    @Autowired
    private ApplicationContext applicationContext;

    /** name -> ToolCallback。volatile 保证可见性,refresh 整体替换 */
    private volatile Map<String, ToolCallback> index = Map.of();
    private volatile long lastRefreshMs;

    /**
     * 冷却期内的重复 refresh 直接跳过。
     * 热路径上「绑定工具缺失」会触发自愈刷新;MCP 真掉线时每次对话都全量 list 会把请求打穿。
     */
    public synchronized boolean refreshIfDue(long cooldownMs)
    {
        long now = System.currentTimeMillis();
        if (cooldownMs > 0 && lastRefreshMs > 0 && now - lastRefreshMs < cooldownMs)
        {
            return false;
        }
        refresh();
        return true;
    }

    /**
     * 重新扫描全部 ToolCallbackProvider,按 name 重建索引。
     * <p>单个 provider 掉线(MCP server 异常)只跳过它,不影响其他工具。
     */
    public synchronized void refresh()
    {
        Map<String, ToolCallback> map = new HashMap<>();
        Map<String, ToolCallbackProvider> providers = applicationContext.getBeansOfType(ToolCallbackProvider.class);
        for (ToolCallbackProvider p : providers.values())
        {
            try
            {
                for (ToolCallback cb : p.getToolCallbacks())
                {
                    map.put(cb.getToolDefinition().name(), cb);
                }
            }
            catch (Exception e)
            {
                // MCP 掉线降级:跳过该 provider,不影响其他工具
                log.warn("provider 获取工具失败,跳过: {}", e.getMessage());
            }
        }
        this.index = map;
        this.lastRefreshMs = System.currentTimeMillis();
        log.info("ToolCallbackRegistry 刷新完成,索引 {} 个工具", map.size());
        // 通知计量侧清空 tool definition token 缓存,避免 MCP 重连后读数过期
        try
        {
            applicationContext.publishEvent(new ToolRegistryRefreshedEvent());
        }
        catch (Exception e)
        {
            log.debug("发布 ToolRegistryRefreshedEvent 失败: {}", e.getMessage());
        }
    }

    /**
     * 按 toolCode 取工具。
     * @return 工具回调;MCP 掉线或未同步时返回 null(调用方跳过 + 告警,§6.2 R4)
     */
    public ToolCallback get(String toolCode)
    {
        return index.get(toolCode);
    }

    /** 全部工具(调试/统计用) */
    public Map<String, ToolCallback> all()
    {
        return index;
    }

    /** 当前索引的工具数量(诊断日志用) */
    public int size()
    {
        return index.size();
    }
}
