package com.ruoyi.system.ai;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.EncryptUtils;
import com.ruoyi.system.domain.AiChannel;
import com.ruoyi.system.domain.AiModelChannel;
import com.ruoyi.system.service.IAiChannelService;
import com.ruoyi.system.service.IAiModelSupplyService;

/**
 * 动态 EmbeddingModel 工厂。
 *
 * <p>与 {@link ChatModelFactory} 同构:按模型 ID 路由到一条启用的渠道供应,解密 apiKey 后
 * 构造 OpenAI 兼容的 {@link OpenAiEmbeddingModel}。缓存 key = {@code channelId:modelName},
 * 渠道变更时按 channelId 失效。
 *
 * @author ruoyi
 */
@Component
public class EmbeddingModelFactory
{
    private static final Logger log = LoggerFactory.getLogger(EmbeddingModelFactory.class);

    @Autowired
    private IAiModelSupplyService aiModelSupplyService;

    @Autowired
    private IAiChannelService aiChannelService;

    @Autowired
    private EncryptUtils encryptUtils;

    /** 缓存:key = channelId:modelName */
    private final ConcurrentMap<String, EmbeddingModel> cache = new ConcurrentHashMap<>();

    /**
     * 连接超时秒数。字段默认值与 @Value 兜底保持一致(沿用 ContextCleaner 那条既有约定),
     * 直接 new 出来时不会静默退回「无超时」。
     */
    @Value("${ai.embedding.connect-timeout-seconds:5}")
    private int connectTimeoutSeconds = 5;

    /**
     * 读取超时秒数。基准是知识库摄入:一批 {@code KbConstants.EMBED_BATCH_SIZE}=16 个分块,
     * 本地 CPU 上的 Ollama 也够跑完;记忆检索那种单条短查询用不到零头。
     */
    @Value("${ai.embedding.read-timeout-seconds:60}")
    private int readTimeoutSeconds = 60;

    /** 单次 embed 的总尝试次数(含首次)。<=1 即不重试。 */
    @Value("${ai.embedding.max-attempts:2}")
    private int maxAttempts = 2;

    /**
     * 根据模型 ID 获取可用 EmbeddingModel。
     *
     * @param modelId 模型ID
     * @return EmbeddingModel
     */
    public EmbeddingModel get(Long modelId)
    {
        if (modelId == null)
        {
            throw new ServiceException("请选择嵌入模型");
        }
        List<AiModelChannel> supplies = aiModelSupplyService.listByModelId(modelId);
        if (supplies == null || supplies.isEmpty())
        {
            throw new ServiceException("该嵌入模型暂无可用渠道,请先在模型管理中配置供应");
        }
        AiModelChannel supply = supplies.stream()
            .filter(s -> "0".equals(s.getStatus()))
            .sorted(this::compareWeightDesc)
            .filter(this::isChannelActive)
            .findFirst()
            .orElseThrow(() -> new ServiceException("该嵌入模型暂无可用渠道,请检查渠道与供应状态"));

        AiChannel channel = aiChannelService.selectAiChannelById(supply.getChannelId());
        String modelName = supply.getModelName();
        if (modelName == null || modelName.trim().isEmpty())
        {
            throw new ServiceException("该嵌入模型供应未配置调用标识(modelName)");
        }
        if (channel.getBaseUrl() == null || channel.getBaseUrl().trim().isEmpty())
        {
            throw new ServiceException("渠道[" + channel.getChannelName() + "]未配置 baseUrl");
        }
        String apiKey = encryptUtils.decrypt(channel.getApiKey());

        String cacheKey = channel.getChannelId() + ":" + modelName;
        EmbeddingModel embeddingModel = cache.get(cacheKey);
        if (embeddingModel == null)
        {
            embeddingModel = buildEmbeddingModel(channel, modelName, apiKey);
            cache.put(cacheKey, embeddingModel);
            log.info("构造 EmbeddingModel:渠道[{}],模型[{}]", channel.getChannelName(), modelName);
        }
        return embeddingModel;
    }

    @EventListener
    public void onChannelChanged(AiChannelChangedEvent event)
    {
        evict(event.channelId());
    }

    public void evict(Long channelId)
    {
        if (channelId == null)
        {
            int size = cache.size();
            cache.clear();
            log.info("清空 EmbeddingModel 缓存,共 {} 个实例", size);
            return;
        }
        String prefix = channelId + ":";
        boolean removed = cache.keySet().removeIf(key -> key.startsWith(prefix));
        if (removed)
        {
            log.info("渠道[{}]配置已变更,相关 EmbeddingModel 缓存已失效,下次调用按新配置重建", channelId);
        }
    }

    private EmbeddingModel buildEmbeddingModel(AiChannel channel, String modelName, String apiKey)
    {
        // 与 ChatModelFactory 同理:completionsPath/embeddingsPath 由 OpenAiCompatibleEndpoint
        // 按 baseUrl 末段是否已是 vN 版本段决定要不要带 /v1,避免 …/v2/v1/embeddings 双版本。
        // RestClient 默认无读超时(照 ImageModelFactory 那条注释):渠道接受连接后不响应会永久挂起。
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(connectTimeoutSeconds)).build());
        requestFactory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        OpenAiApi api = OpenAiApi.builder()
            .baseUrl(OpenAiCompatibleEndpoint.baseUrl(channel.getBaseUrl()))
            .embeddingsPath(OpenAiCompatibleEndpoint.embeddingsPath(channel.getBaseUrl()))
            .apiKey(apiKey)
            .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
            .build();
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
            .model(modelName)
            .build();
        return new OpenAiEmbeddingModel(api, MetadataMode.EMBED, options, retryTemplate());
    }

    /**
     * 嵌入调用重试:次数有界、退避固定。
     *
     * <p><b>不能用三参构造默认挂上的 {@code RetryUtils.DEFAULT_RETRY_TEMPLATE}</b> ——
     * 它是 maxAttempts=10 + 指数退避(2s 起、×5、封顶 180s),而且 retryOn 里带着
     * {@code ResourceAccessException},连"连接被拒"这种毫秒级失败也照样按满退避重来:
     * 一次 embed 要阻塞 2+10+50+180×6 ≈ <b>19 分钟</b>。
     *
     * <p>而 embed 就跑在对话主链路上({@code MemoryRetriever} 每轮检索,在用户消息落库之前),
     * 那段是同步跑在 chatRunTaskExecutor 的线程上的、core 只有 4 —— 向量渠道一挂,
     * 四轮对话就能把整个实例的对话能力堵死,且取消也救不回来(线程根本没在等信号)。
     * 实测过一次:插件侧表现为发消息完全没反应,连新会话的"你好"都排不上号。
     *
     * <p>包级可见:单测直接断言"重试次数有界"这条线(沿用 ContextCleaner#triggerThreshold 的做法)。
     */
    RetryTemplate retryTemplate()
    {
        return RetryTemplate.builder()
            .maxAttempts(Math.max(1, maxAttempts))
            .fixedBackoff(Duration.ofMillis(500))
            .retryOn(TransientAiException.class)
            .retryOn(ResourceAccessException.class)
            .build();
    }

    private boolean isChannelActive(AiModelChannel supply)
    {
        if (supply.getChannelId() == null)
        {
            return false;
        }
        AiChannel channel = aiChannelService.selectAiChannelById(supply.getChannelId());
        return channel != null && "0".equals(channel.getStatus());
    }

    private int compareWeightDesc(AiModelChannel a, AiModelChannel b)
    {
        int wa = a.getWeight() == null ? 0 : a.getWeight();
        int wb = b.getWeight() == null ? 0 : b.getWeight();
        return Integer.compare(wb, wa);
    }
}
