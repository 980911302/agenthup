package com.ruoyi.system.ai;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.EncryptUtils;
import com.ruoyi.system.domain.AiChannel;
import com.ruoyi.system.domain.AiModelChannel;
import com.ruoyi.system.service.IAiChannelService;
import com.ruoyi.system.service.IAiModelSupplyService;

/**
 * 语音合成工厂,与 {@link VideoModelFactory} 平行,不碰生图/视频缓存。
 */
@Component
public class TtsModelFactory
{
    private static final Logger log = LoggerFactory.getLogger(TtsModelFactory.class);

    @Autowired
    private IAiModelSupplyService aiModelSupplyService;

    @Autowired
    private IAiChannelService aiChannelService;

    @Autowired
    private EncryptUtils encryptUtils;

    private final ConcurrentMap<String, OpenAiCompatibleTtsClient> cache = new ConcurrentHashMap<>();

    public OpenAiCompatibleTtsClient get(Long modelId)
    {
        if (modelId == null)
        {
            throw new ServiceException("请选择语音合成模型");
        }
        List<AiModelChannel> supplies = aiModelSupplyService.listByModelId(modelId);
        if (supplies == null || supplies.isEmpty())
        {
            throw new ServiceException("该语音模型暂无可用渠道,请先在模型管理中配置供应");
        }
        AiModelChannel supply = supplies.stream()
                .filter(s -> "0".equals(s.getStatus()))
                .sorted(this::compareWeightDesc)
                .filter(this::isChannelActive)
                .findFirst()
                .orElseThrow(() -> new ServiceException("该语音模型暂无可用渠道,请检查渠道与供应状态"));

        AiChannel channel = aiChannelService.selectAiChannelById(supply.getChannelId());
        String modelName = supply.getModelName();
        if (modelName == null || modelName.trim().isEmpty())
        {
            throw new ServiceException("该语音模型供应未配置调用标识(modelName)");
        }
        if (channel.getBaseUrl() == null || channel.getBaseUrl().trim().isEmpty())
        {
            throw new ServiceException("渠道[" + channel.getChannelName() + "]未配置 baseUrl");
        }
        String apiKey = encryptUtils.decrypt(channel.getApiKey());
        String cacheKey = channel.getChannelId() + ":" + modelName;
        return cache.computeIfAbsent(cacheKey, ignored -> {
            log.info("构造 TtsClient:渠道[{}],模型[{}]", channel.getChannelName(), modelName);
            return new OpenAiCompatibleTtsClient(channel.getBaseUrl(), apiKey, modelName);
        });
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
            cache.clear();
            return;
        }
        String prefix = channelId + ":";
        cache.keySet().removeIf(key -> key.startsWith(prefix));
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
