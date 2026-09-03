package com.ruoyi.web.controller.ai;

import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.system.ai.metering.CacheTokens;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.system.mapper.AiChannelMapper;
import com.ruoyi.system.mapper.AiChatRunMapper;
import com.ruoyi.system.mapper.AiLlmCallMapper;
import com.ruoyi.system.mapper.AiMcpServerMapper;

/**
 * AI Token 消耗统计(首页)
 * <p>
 * 全部基于 ai_llm_call / ai_chat_run 聚合，无需额外汇总表。
 * 健康度指标另外查 ai_channel / ai_mcp_server 的 status / health_status 字段。
 *
 * @author ruoyi
 */
@RestController
@RequestMapping("/ai/stat")
public class AiStatController extends BaseController
{
    @Autowired
    private AiLlmCallMapper aiLlmCallMapper;

    @Autowired
    private AiChatRunMapper aiChatRunMapper;

    @Autowired
    private AiChannelMapper aiChannelMapper;

    @Autowired
    private AiMcpServerMapper aiMcpServerMapper;

    /**
     * 概览：token 合计、调用次数、会话数、真实率
     * GET /ai/stat/overview?days=7
     */
    @GetMapping("/overview")
    public AjaxResult overview(@RequestParam(defaultValue = "7") int days)
    {
        Date since = daysAgo(days);
        Map<String, Object> row = aiLlmCallMapper.selectOverview(since);
        if (row == null)
        {
            row = new HashMap<>();
        }
        long callCount = toLong(row.get("callCount"));
        long realCallCount = toLong(row.get("realCallCount"));
        double realRate = callCount > 0 ? (realCallCount * 100.0 / callCount) : 100.0;
        Map<String, Object> data = new HashMap<>();
        data.put("totalTokens", toLong(row.get("totalTokens")));
        data.put("promptTokens", toLong(row.get("promptTokens")));
        data.put("completionTokens", toLong(row.get("completionTokens")));
        data.put("callCount", callCount);
        data.put("sessionCount", toLong(row.get("sessionCount")));
        data.put("realRate", Math.round(realRate * 10) / 10.0);
        data.put("days", days);
        return AjaxResult.success(data);
    }

    /**
     * 按模型分组 TOP
     * GET /ai/stat/by-model?days=7
     */
    @GetMapping("/by-model")
    public AjaxResult byModel(@RequestParam(defaultValue = "7") int days,
                              @RequestParam(defaultValue = "5") int limit)
    {
        List<Map<String, Object>> list = aiLlmCallMapper.selectByModel(daysAgo(days), Math.max(1, limit));
        return AjaxResult.success(list);
    }

    /**
     * 按智能体分组 TOP
     * GET /ai/stat/by-agent?days=7
     */
    @GetMapping("/by-agent")
    public AjaxResult byAgent(@RequestParam(defaultValue = "7") int days,
                              @RequestParam(defaultValue = "5") int limit)
    {
        List<Map<String, Object>> list = aiLlmCallMapper.selectByAgent(daysAgo(days), Math.max(1, limit));
        return AjaxResult.success(list);
    }

    /**
     * 按天趋势
     * GET /ai/stat/trend?days=30
     */
    @GetMapping("/trend")
    public AjaxResult trend(@RequestParam(defaultValue = "30") int days)
    {
        List<Map<String, Object>> list = aiLlmCallMapper.selectTrend(daysAgo(days));
        return AjaxResult.success(list);
    }

    /**
     * 缓存命中聚合(首页健康指标)。
     * 命中率 = hitTokens / (hitTokens + missTokens);
     * 仅 usage_source='0' 行参与,估算行无 cache 字段。
     * GET /ai/stat/cache?days=7
     */
    @GetMapping("/cache")
    public AjaxResult cache(@RequestParam(defaultValue = "7") int days)
    {
        Map<String, Object> row = aiLlmCallMapper.selectCacheStats(daysAgo(days));
        if (row == null) row = new HashMap<>();
        long prompt = toLong(row.get("promptTokens"));
        long hit = CacheTokens.effectiveHit(toLong(row.get("hitTokens")), prompt);
        long miss = toLong(row.get("missTokens"));
        long total = hit + miss;
        double hitRate = total > 0 ? (hit * 100.0 / total) : 0.0;
        Map<String, Object> data = new HashMap<>();
        data.put("hitTokens", hit);
        data.put("missTokens", miss);
        data.put("promptTokens", prompt);
        data.put("hitRate", Math.round(hitRate * 10) / 10.0);
        return AjaxResult.success(data);
    }

    /**
     * 当前登录用户的用量统计，供 desktop「用量统计」设置页使用。
     * <p>与后台首页统计不同：所有查询均按 ai_chat_session.user_id 严格过滤。
     * GET /ai/stat/my-usage?days=7
     */
    @GetMapping("/my-usage")
    public AjaxResult myUsage(@RequestParam(defaultValue = "7") int days)
    {
        int normalizedDays = Math.max(1, Math.min(days, 365));
        Long userId = getUserId();
        Date since = startOfDay(normalizedDays - 1);

        Map<String, Object> summary = normalizeOverview(aiLlmCallMapper.selectUserOverview(since, userId));
        Map<String, Object> today = normalizeOverview(aiLlmCallMapper.selectUserOverview(startOfDay(0), userId));
        Map<String, Object> cacheRow = aiLlmCallMapper.selectUserCacheStats(since, userId);
        if (cacheRow == null) cacheRow = new HashMap<>();

        long prompt = toLong(cacheRow.get("promptTokens"));
        long hit = CacheTokens.effectiveHit(toLong(cacheRow.get("hitTokens")), prompt);
        long miss = toLong(cacheRow.get("missTokens"));
        long cacheTotal = hit + miss;
        double hitRate = cacheTotal > 0 ? (hit * 100.0 / cacheTotal) : 0.0;

        Map<String, Object> cache = new HashMap<>();
        cache.put("hitTokens", hit);
        cache.put("missTokens", miss);
        cache.put("promptTokens", prompt);
        cache.put("hitRate", Math.round(hitRate * 10) / 10.0);

        Map<String, Object> data = new HashMap<>();
        data.put("days", normalizedDays);
        data.put("today", today);
        data.put("summary", summary);
        data.put("cache", cache);
        data.put("trend", aiLlmCallMapper.selectUserTrend(since, userId));
        return AjaxResult.success(data);
    }

    /**
     * 任务健康度(首页):
     *  - 成功率 = SUCCEEDED / (SUCCEEDED + FAILED + INTERRUPTED)
     *  - 状态分布按 status 全量返回(供前端环形图)
     *  - 平均耗时(仅 SUCCEEDED/FAILED,毫秒)
     * GET /ai/stat/runs?days=7
     */
    @GetMapping("/runs")
    public AjaxResult runs(@RequestParam(defaultValue = "7") int days)
    {
        Date since = daysAgo(days);
        Map<String, Object> row = aiChatRunMapper.selectRunHealth(since);
        if (row == null) row = new HashMap<>();
        long succeeded = toLong(row.get("succeeded"));
        long failed = toLong(row.get("failed"));
        long interrupted = toLong(row.get("interrupted"));
        long cancell = toLong(row.get("cancelled"));
        long terminal = succeeded + failed + interrupted;
        double successRate = terminal > 0 ? (succeeded * 100.0 / terminal) : 0.0;
        Map<String, Object> data = new HashMap<>();
        data.put("succeeded", succeeded);
        data.put("failed", failed);
        data.put("interrupted", interrupted);
        data.put("cancelled", cancell);
        data.put("queued", toLong(row.get("queued")));
        data.put("running", toLong(row.get("running")));
        data.put("finalizing", toLong(row.get("finalizing")));
        data.put("total", toLong(row.get("total")));
        data.put("successRate", Math.round(successRate * 10) / 10.0);

        Map<String, Object> dur = aiChatRunMapper.selectRunDuration(since);
        if (dur != null)
        {
            data.put("avgDurationMs", toLong(dur.get("avgMs")));
            data.put("durationSamples", toLong(dur.get("sampleCount")));
        }
        return AjaxResult.success(data);
    }

    /**
     * 渠道健康度(配置/运行时两维度)。
     * GET /ai/stat/channels-health
     */
    @GetMapping("/channels-health")
    public AjaxResult channelsHealth()
    {
        Map<String, Object> row = aiChannelMapper.selectChannelHealth();
        if (row == null) row = new HashMap<>();
        long total = toLong(row.get("total"));
        long enabled = toLong(row.get("enabled"));
        long healthy = toLong(row.get("healthy"));
        long unhealthy = toLong(row.get("unhealthy"));
        long unknown = toLong(row.get("unknown"));
        // 健康率分母:已启用的渠道(停用的不算"出问题",只是没启用)
        double healthyRate = enabled > 0 ? (healthy * 100.0 / enabled) : 0.0;
        Map<String, Object> data = new HashMap<>();
        data.put("total", total);
        data.put("enabled", enabled);
        data.put("healthy", healthy);
        data.put("unhealthy", unhealthy);
        data.put("unknown", unknown);
        data.put("healthyRate", Math.round(healthyRate * 10) / 10.0);
        return AjaxResult.success(data);
    }

    /**
     * MCP 健康度(配置/运行时两维度)。
     * GET /ai/stat/mcp-health
     */
    @GetMapping("/mcp-health")
    public AjaxResult mcpHealth()
    {
        Map<String, Object> row = aiMcpServerMapper.selectMcpHealth();
        if (row == null) row = new HashMap<>();
        long total = toLong(row.get("total"));
        long enabled = toLong(row.get("enabled"));
        long healthy = toLong(row.get("healthy"));
        long unhealthy = toLong(row.get("unhealthy"));
        long unknown = toLong(row.get("unknown"));
        double healthyRate = enabled > 0 ? (healthy * 100.0 / enabled) : 0.0;
        Map<String, Object> data = new HashMap<>();
        data.put("total", total);
        data.put("enabled", enabled);
        data.put("healthy", healthy);
        data.put("unhealthy", unhealthy);
        data.put("unknown", unknown);
        data.put("healthyRate", Math.round(healthyRate * 10) / 10.0);
        return AjaxResult.success(data);
    }

    private static Date daysAgo(int days)
    {
        int d = Math.max(1, Math.min(days, 365));
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, -d);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTime();
    }

    private static Date startOfDay(int daysAgo)
    {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, -Math.max(0, daysAgo));
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTime();
    }

    private static Map<String, Object> normalizeOverview(Map<String, Object> row)
    {
        if (row == null) row = new HashMap<>();
        Map<String, Object> data = new HashMap<>();
        data.put("totalTokens", toLong(row.get("totalTokens")));
        data.put("promptTokens", toLong(row.get("promptTokens")));
        data.put("completionTokens", toLong(row.get("completionTokens")));
        data.put("callCount", toLong(row.get("callCount")));
        data.put("sessionCount", toLong(row.get("sessionCount")));
        return data;
    }

    private static long toLong(Object v)
    {
        if (v == null)
        {
            return 0L;
        }
        if (v instanceof Number)
        {
            return ((Number) v).longValue();
        }
        try
        {
            return Long.parseLong(v.toString());
        }
        catch (Exception e)
        {
            return 0L;
        }
    }
}
