package com.ruoyi.system.ai;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.openai.OpenAiImageModel;
import org.springframework.ai.openai.OpenAiImageOptions;
import org.springframework.ai.openai.api.OpenAiImageApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.EncryptUtils;
import com.ruoyi.system.domain.AiChannel;
import com.ruoyi.system.domain.AiModelChannel;
import com.ruoyi.system.service.IAiChannelService;
import com.ruoyi.system.service.IAiModelSupplyService;

/**
 * 动态 ImageModel 工厂
 *
 * <p>与 {@link ChatModelFactory} 同构:按模型 ID 路由到一条启用的渠道供应,解密 apiKey 后
 * 按 OpenAI 兼容协议构造 Spring AI 的 ImageModel({@link OpenAiImageModel})。
 *
 * <p>缓存策略与 ChatModelFactory 完全一致:key = {@code channelId:modelName},
 * 渠道变更时由 {@link #onChannelChanged} 按 channelId 失效。
 *
 * @author ruoyi
 */
@Component
public class ImageModelFactory
{
    private static final Logger log = LoggerFactory.getLogger(ImageModelFactory.class);

    @Autowired
    private IAiModelSupplyService aiModelSupplyService;

    @Autowired
    private IAiChannelService aiChannelService;

    @Autowired
    private EncryptUtils encryptUtils;

    /** 缓存:key = channelId:modelName */
    private final ConcurrentMap<String, ImageModel> cache = new ConcurrentHashMap<>();
    /** 图生图客户端缓存,与 cache 同 key 规则(channelId:modelName),渠道变更时一并失效 */
    private final ConcurrentMap<String, ImageEditClient> editCache = new ConcurrentHashMap<>();

    /** 生图可能较慢但绝不能无限挂起:连接 10s,读取 180s(出图本身耗时,读超时留足) */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(180);

    /**
     * 生图不自动重试(maxAttempts=1):单次出图本身就慢,渠道不通时重试只会成倍放大等待;
     * 失败由工具结果告知 LLM,由它决定是否再试。
     * <p>不用框架默认 DEFAULT_RETRY_TEMPLATE 还有个原因:用户取消运行会中断在途请求,
     * 中断抛出的 ResourceAccessException 在默认策略下被当成网络抖动,取消后还会再发一次请求。
     */
    private static final RetryTemplate IMAGE_RETRY_TEMPLATE =
            RetryTemplate.builder().maxAttempts(1).build();

    /**
     * 根据模型 ID 获取一个可用的 ImageModel
     *
     * @param modelId 模型ID
     * @return ImageModel
     */
    /**
     * 带参考图的图生图客户端(multipart {@code /v1/images/edits})。
     *
     * <p>与 {@link #get} 走同一条渠道选路与缓存失效逻辑,只是终点不同:文生图交给
     * Spring AI 的 {@code OpenAiImageModel},图生图它塞不进参考图(请求 record 字段固定),
     * 只能自己发。
     */
    public ImageEditClient getEditClient(Long modelId)
    {
        Resolved r = resolve(modelId);
        String cacheKey = r.channel().getChannelId() + ":" + r.modelName();
        ImageEditClient client = editCache.get(cacheKey);
        if (client == null)
        {
            client = new ImageEditClient(r.channel().getBaseUrl(), r.apiKey(), r.modelName());
            editCache.put(cacheKey, client);
            log.info("构造 ImageEditClient:渠道[{}],模型[{}]",
                    r.channel().getChannelName(), r.modelName());
        }
        return client;
    }

    /** 渠道选路结果:两个入口共用,避免选路逻辑分叉 */
    private record Resolved(AiChannel channel, String modelName, String apiKey) {}

    private Resolved resolve(Long modelId)
    {
        if (modelId == null)
        {
            throw new ServiceException("请选择生图模型");
        }
        List<AiModelChannel> supplies = aiModelSupplyService.listByModelId(modelId);
        if (supplies == null || supplies.isEmpty())
        {
            throw new ServiceException("该生图模型暂无可用渠道,请先在模型管理中配置供应");
        }
        AiModelChannel supply = supplies.stream()
            .filter(s -> "0".equals(s.getStatus()))
            .sorted(this::compareWeightDesc)
            .filter(this::isChannelActive)
            .findFirst()
            .orElseThrow(() -> new ServiceException("该生图模型暂无可用渠道,请检查渠道与供应状态"));
        AiChannel channel = aiChannelService.selectAiChannelById(supply.getChannelId());
        String modelName = supply.getModelName();
        if (modelName == null || modelName.trim().isEmpty())
        {
            throw new ServiceException("该生图模型供应未配置调用标识(modelName)");
        }
        if (channel.getBaseUrl() == null || channel.getBaseUrl().trim().isEmpty())
        {
            throw new ServiceException("渠道[" + channel.getChannelName() + "]未配置 baseUrl");
        }
        return new Resolved(channel, modelName, encryptUtils.decrypt(channel.getApiKey()));
    }

    public ImageModel get(Long modelId)
    {
        // 选路与 getEditClient 共用 resolve:两个入口必须落到同一条渠道,
        // 否则文生图和图生图可能跑在不同渠道上,风格对不齐还难排查
        Resolved r = resolve(modelId);
        String cacheKey = r.channel().getChannelId() + ":" + r.modelName();
        ImageModel imageModel = cache.get(cacheKey);
        if (imageModel == null)
        {
            imageModel = buildImageModel(r.channel(), r.modelName(), r.apiKey());
            cache.put(cacheKey, imageModel);
            log.info("构造 ImageModel:渠道[{}],模型[{}]",
                    r.channel().getChannelName(), r.modelName());
        }
        return imageModel;
    }

    /**
     * 渠道变更后丢弃其缓存实例,下次调用按新配置重建。
     * <p>复用 {@link AiChannelChangedEvent} -- ChatModelFactory 监听同一个事件,
     * 这里再挂一个监听器,两个工厂各自失效自己的缓存,互不干扰。
     */
    @EventListener
    public void onChannelChanged(AiChannelChangedEvent event)
    {
        evict(event.channelId());
    }

    /**
     * 清理指定渠道的全部模型实例;channelId 为 null 时清空整个缓存。
     * <p>键是 {@code channelId + ":" + modelName},按 {@code "渠道号:"} 前缀匹配 --
     * 带上冒号才不会让渠道 1 的失效顺手清掉渠道 10、100。
     */
    public void evict(Long channelId)
    {
        if (channelId == null)
        {
            int size = cache.size();
            cache.clear();
            editCache.clear();
            log.info("清空 ImageModel 缓存,共 {} 个实例", size);
            return;
        }
        String prefix = channelId + ":";
        boolean removed = cache.keySet().removeIf(key -> key.startsWith(prefix));
        removed |= editCache.keySet().removeIf(key -> key.startsWith(prefix));
        if (removed)
        {
            log.info("渠道[{}]配置已变更,相关 ImageModel 缓存已失效,下次调用按新配置重建", channelId);
        }
    }

    /**
     * 构造 ImageModel(OpenAI 兼容协议)
     */
    private ImageModel buildImageModel(AiChannel channel, String modelName, String apiKey)
    {
        // OpenAiImageModel 没有 builder,用构造函数;imagesPath 由 OpenAiCompatibleEndpoint
        // 按 baseUrl 末段是否已是 vN 版本段决定要不要带 /v1,与 ChatModelFactory 口径一致。
        // RestClient 默认无读超时,中转站若接受连接后不响应会永久挂起(前端一直"执行中"),必须显式设置。
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        requestFactory.setReadTimeout(READ_TIMEOUT);
        OpenAiImageApi api = OpenAiImageApi.builder()
            .baseUrl(OpenAiCompatibleEndpoint.baseUrl(channel.getBaseUrl()))
            .imagesPath(OpenAiCompatibleEndpoint.imagesPath(channel.getBaseUrl()))
            .apiKey(apiKey)
            .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
            .build();
        OpenAiImageOptions options = OpenAiImageOptions.builder()
            .model(modelName)
            .build();
        return new OpenAiImageModel(api, options, IMAGE_RETRY_TEMPLATE);
    }

    /**
     * 校验供应对应渠道是否启用
     */
    private boolean isChannelActive(AiModelChannel supply)
    {
        if (supply.getChannelId() == null)
        {
            return false;
        }
        AiChannel channel = aiChannelService.selectAiChannelById(supply.getChannelId());
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
