package com.ruoyi.system.tool;

import com.ruoyi.system.domain.AiTool;
import com.ruoyi.system.mapper.AiToolMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 从 ai_tool 读按工具策略(单次运行调用上限 / 是否需人工确认),带短缓存。
 * <p>后台改工具配置后最多等 {@link #TTL_MS} 生效,避免每次工具调用打库。
 */
@Service
public class ToolPolicyService
{
    private static final long TTL_MS = 30_000L;

    /**
     * 危险操作人工确认总开关。false 时无视 ai_tool.require_confirm 与内置兜底,一律直接执行。
     *
     * <p>放在这里而不是 {@link ToolConfirmBroker}:这是唯一同时管住「库里配的」和
     * 「bash 内置兜底」两条来源的收口点。关掉后不再发
     * {@code tool_confirm_required} 事件,前端自然不会弹确认框。
     */
    @Value("${ai.chat.tool.confirm-enabled:true}")
    private boolean confirmEnabled;

    @Autowired
    private AiToolMapper aiToolMapper;

    private final AtomicReference<Map<String, Integer>> maxCallsCache =
            new AtomicReference<>(Collections.emptyMap());
    private final AtomicReference<Map<String, Boolean>> confirmCache =
            new AtomicReference<>(Collections.emptyMap());
    private final AtomicLong loadedAt = new AtomicLong(0);

    /** toolCode → max_calls_per_run(仅含配置了正数的工具)。 */
    public Map<String, Integer> maxCallsPerRun()
    {
        refreshIfStale();
        return maxCallsCache.get();
    }

    /** 危险操作确认功能是否开启(总开关)。 */
    public boolean isConfirmEnabled()
    {
        return confirmEnabled;
    }

    /** 该工具是否要求人工确认。总开关关闭时恒为 false。 */
    public boolean requireConfirm(String toolCode)
    {
        if (!confirmEnabled || toolCode == null || toolCode.isEmpty())
        {
            return false;
        }
        refreshIfStale();
        return Boolean.TRUE.equals(confirmCache.get().get(toolCode));
    }

    public void invalidate()
    {
        loadedAt.set(0);
    }

    private void refreshIfStale()
    {
        long now = System.currentTimeMillis();
        if (now - loadedAt.get() < TTL_MS)
        {
            return;
        }
        synchronized (this)
        {
            if (now - loadedAt.get() < TTL_MS)
            {
                return;
            }
            try
            {
                AiTool query = new AiTool();
                query.setStatus("0");
                List<AiTool> list = aiToolMapper.selectAiToolList(query);
                Map<String, Integer> maxCalls = new HashMap<>();
                Map<String, Boolean> confirms = new HashMap<>();
                if (list != null)
                {
                    for (AiTool t : list)
                    {
                        if (t.getToolCode() == null)
                        {
                            continue;
                        }
                        if (t.getMaxCallsPerRun() != null && t.getMaxCallsPerRun() > 0)
                        {
                            maxCalls.put(t.getToolCode(), t.getMaxCallsPerRun());
                        }
                        if ("1".equals(t.getRequireConfirm()))
                        {
                            confirms.put(t.getToolCode(), Boolean.TRUE);
                        }
                    }
                }
                // 兜底:列未同步或工具未入库时,危险工具仍要求确认
                confirms.putIfAbsent("bash", Boolean.TRUE);

                maxCallsCache.set(Collections.unmodifiableMap(maxCalls));
                confirmCache.set(Collections.unmodifiableMap(confirms));
                loadedAt.set(System.currentTimeMillis());
            }
            catch (Exception ignored)
            {
                // 读库失败时保留旧缓存;若从未加载过,至少危险工具兜底
                if (confirmCache.get().isEmpty())
                {
                    confirmCache.set(Map.of("bash", Boolean.TRUE));
                }
                loadedAt.set(System.currentTimeMillis());
            }
        }
    }
}
