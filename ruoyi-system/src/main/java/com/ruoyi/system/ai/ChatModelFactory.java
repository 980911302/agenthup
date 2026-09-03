package com.ruoyi.system.ai;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.EncryptUtils;
import com.ruoyi.system.ai.metering.CacheUsageProbe;
import com.ruoyi.system.domain.AiChannel;
import com.ruoyi.system.domain.AiModelChannel;
import com.ruoyi.system.service.IAiChannelService;
import com.ruoyi.system.service.IAiModelSupplyService;

/**
 * 动态 ChatModel 工厂
 *
 * 按模型 ID 路由到一条启用的渠道供应,解密 apiKey 后动态构造 OpenAI 兼容的 ChatModel
 * (OPENAI / ANTHROPIC / GEMINI / OLLAMA 等凡走 OpenAI 兼容端点的渠道均走此路径)。
 *
 * 缓存:①模型实例按 channelId:modelName 缓存(避免每次请求都 new,baseUrl/apiKey 构造时
 * 烧进 OpenAiApi);②路由(modelId → 供应)与渠道行走 30s TTL 快照,缓存命中路径零 SQL。
 * 渠道一有变更就必须按 channelId 失效三层缓存 —— 见 {@link #onChannelChanged};
 * 供应侧(权重/状态/modelName)变更无事件,靠 TTL 兜底(≤30s)。
 *
 * @author ruoyi
 */
@Component
public class ChatModelFactory
{
    private static final Logger log = LoggerFactory.getLogger(ChatModelFactory.class);

    @Autowired
    private IAiModelSupplyService aiModelSupplyService;

    @Autowired
    private IAiChannelService aiChannelService;

    @Autowired
    private EncryptUtils encryptUtils;

    @Autowired
    private CacheUsageProbe cacheUsageProbe;

    /** 缓存:key = channelId:modelName */
    private final ConcurrentMap<String, ChatModel> cache = new ConcurrentHashMap<>();

    /**
     * 路由缓存(30s TTL):modelId → 选中的 (channelId, modelName)。
     * 渠道启停/改配置有 {@link AiChannelChangedEvent} 即时失效;供应侧(权重/状态/
     * modelName)变更没有事件,靠 TTL 兜底 —— 最坏 30s 内仍按旧供应路由,可接受。
     */
    private static final long ROUTE_TTL_MS = 30_000L;

    private final ConcurrentMap<Long, RouteEntry> routes = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, ChannelEntry> channelRows = new ConcurrentHashMap<>();

    private record Route(Long channelId, String modelName) { }
    private record RouteEntry(Route route, long expiresAt) { }
    private record ChannelEntry(AiChannel channel, long expiresAt) { }

    /**
     * 根据模型 ID 获取一个可用的 ChatModel
     *
     * @param modelId 模型ID
     * @return ChatModel
     */
    public ChatModel get(Long modelId)
    {
        if (modelId == null)
        {
            throw new ServiceException("请选择对话模型");
        }
        Route route = resolveRoute(modelId);
        AiChannel channel = channelRow(route.channelId());
        if (channel == null)
        {
            throw new ServiceException("模型渠道不存在或已删除");
        }
        String modelName = route.modelName();
        if (modelName == null || modelName.trim().isEmpty())
        {
            throw new ServiceException("该模型供应未配置调用标识(modelName)");
        }
        if (channel.getBaseUrl() == null || channel.getBaseUrl().trim().isEmpty())
        {
            throw new ServiceException("渠道[" + channel.getChannelName() + "]未配置 baseUrl");
        }

        // 2. 缓存命中直接返回;apiKey 只在构造新实例时解密,不再每次调用都做 AES
        String cacheKey = channel.getChannelId() + ":" + modelName;
        ChatModel chatModel = cache.get(cacheKey);
        if (chatModel == null)
        {
            String apiKey = encryptUtils.decrypt(channel.getApiKey());
            chatModel = buildChatModel(channel, modelName, apiKey);
            cache.put(cacheKey, chatModel);
            log.info("构造 ChatModel:渠道[{}],类型[{}],模型[{}]",
                channel.getChannelName(), channel.getChannelType(), modelName);
        }
        return chatModel;
    }

    /** 模型 → 供应路由(启用 + 渠道可用,按权重降序取第一个),30s TTL */
    private Route resolveRoute(Long modelId)
    {
        long now = System.currentTimeMillis();
        RouteEntry hit = routes.get(modelId);
        if (hit != null && now < hit.expiresAt())
        {
            return hit.route();
        }
        List<AiModelChannel> supplies = aiModelSupplyService.listByModelId(modelId);
        if (supplies == null || supplies.isEmpty())
        {
            throw new ServiceException("该模型暂无可用渠道,请先在模型管理中配置供应");
        }
        AiModelChannel supply = supplies.stream()
            .filter(s -> "0".equals(s.getStatus()))
            .sorted(this::compareWeightDesc)
            .filter(this::isChannelActive)
            .findFirst()
            .orElseThrow(() -> new ServiceException("该模型暂无可用渠道,请检查渠道与供应状态"));
        Route route = new Route(supply.getChannelId(), supply.getModelName());
        routes.put(modelId, new RouteEntry(route, now + ROUTE_TTL_MS));
        return route;
    }

    /** 渠道行快照(30s TTL):路由候选检查与最终取行共用,消灭原来的成对重复查询 */
    private AiChannel channelRow(Long channelId)
    {
        long now = System.currentTimeMillis();
        ChannelEntry hit = channelRows.get(channelId);
        if (hit != null && now < hit.expiresAt())
        {
            return hit.channel();
        }
        AiChannel channel = aiChannelService.selectAiChannelById(channelId);
        if (channel != null)
        {
            channelRows.put(channelId, new ChannelEntry(channel, now + ROUTE_TTL_MS));
        }
        return channel;
    }

    /**
     * 渠道变更后丢弃其缓存实例,下次调用按新配置重建。
     *
     * <p>没有这一步,改完 baseUrl / apiKey 必须重启后端才生效 ——
     * 页面上配置已经改了、请求却还打向旧地址,极难排查。</p>
     */
    @EventListener
    public void onChannelChanged(AiChannelChangedEvent event)
    {
        evict(event.channelId());
        if (event.channelId() != null)
        {
            channelRows.remove(event.channelId());
        }
        else
        {
            channelRows.clear();
        }
        // 路由选择依赖渠道启停,任一渠道变更都全部重解析(供应侧变更靠 TTL 兜底)
        routes.clear();
    }

    /**
     * 清理指定渠道的全部模型实例;channelId 为 null 时清空整个缓存。
     *
     * <p>键是 {@code channelId + ":" + modelName},按 {@code "渠道号:"} 前缀匹配 ——
     * 带上冒号才不会让渠道 1 的失效顺手清掉渠道 10、100。</p>
     */
    public void evict(Long channelId)
    {
        if (channelId == null)
        {
            int size = cache.size();
            cache.clear();
            log.info("清空 ChatModel 缓存,共 {} 个实例", size);
            return;
        }
        String prefix = channelId + ":";
        boolean removed = cache.keySet().removeIf(key -> key.startsWith(prefix));
        if (removed)
        {
            log.info("渠道[{}]配置已变更,相关 ChatModel 缓存已失效,下次调用按新配置重建", channelId);
        }
    }

    /**
     * 构造 ChatModel(OpenAI 及各类 OpenAI 兼容渠道)
     */
    private ChatModel buildChatModel(AiChannel channel, String modelName, String apiKey)
    {
        // Spring AI 的 OpenAiApi 默认 completionsPath=/v1/chat/completions,会自动拼 /v1 前缀;
        // 但渠道 baseUrl 可能已带版本段(ark 存 …/api/plan/v3、deepseek 存 …/v1、官方存 https://api.openai.com),
        // 一概剥 /v1 再叠默认路径会拼出 …/v3/v1/chat/completions 双版本 404。由 OpenAiCompatibleEndpoint
        // 按「baseUrl 末段是否是 vN 版本段」决定依赖路径要不要带 /v1,baseUrl 归一则只去结尾斜杠。
        // webClientBuilder 挂缓存探针:工具循环里 Spring AI 会丢弃 nativeUsage,
        // 必须在 SSE 原文里截获 prompt_tokens_details.cached_tokens。
        OpenAiApi api = OpenAiApi.builder()
            .baseUrl(OpenAiCompatibleEndpoint.baseUrl(channel.getBaseUrl()))
            .completionsPath(OpenAiCompatibleEndpoint.completionsPath(channel.getBaseUrl()))
            .apiKey(apiKey)
            .webClientBuilder(cacheUsageProbe.decorate(WebClient.builder()))
            .build();
        // streamUsage(true) -> 请求带 stream_options.include_usage=true，流式末包返回真实 usage
        OpenAiChatOptions options = OpenAiChatOptions.builder()
            .model(modelName)
            .streamUsage(true)
            .build();
        return OpenAiChatModel.builder()
            .openAiApi(api)
            .defaultOptions(options)
            .build();
    }

    /**
     * 校验供应对应渠道是否启用(渠道行走 30s TTL 快照)
     */
    private boolean isChannelActive(AiModelChannel supply)
    {
        if (supply.getChannelId() == null)
        {
            return false;
        }
        AiChannel channel = channelRow(supply.getChannelId());
        return channel != null && "0".equals(channel.getStatus());
    }

    /**
     * 权重降序比较(空权重按 0 处理)
     */
    private int compareWeightDesc(AiModelChannel a, AiModelChannel b)
    {
        int wa = a.getWeight() == null ? 0 : a.getWeight();
        int wb = b.getWeight() == null ? 0 : b.getWeight();
        return Integer.compare(wb, wa);
    }
}
