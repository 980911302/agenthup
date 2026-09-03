package com.ruoyi.system.ai;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.reactive.function.client.WebClient;

import com.ruoyi.common.utils.EncryptUtils;
import com.ruoyi.system.ai.metering.CacheUsageProbe;
import com.ruoyi.system.domain.AiChannel;
import com.ruoyi.system.domain.AiModelChannel;
import com.ruoyi.system.service.IAiChannelService;
import com.ruoyi.system.service.IAiModelSupplyService;

/**
 * 路由缓存的三条不变量:二次 get() 零路由 SQL 且零重复解密;
 * 渠道变更事件即时失效并按新配置重建。该类此前零测试。
 */
class ChatModelFactoryTest
{
    private IAiModelSupplyService supplyService;
    private IAiChannelService channelService;
    private EncryptUtils encryptUtils;
    private ChatModelFactory factory;

    @BeforeEach
    void setUp() throws Exception
    {
        supplyService = mock(IAiModelSupplyService.class);
        channelService = mock(IAiChannelService.class);
        encryptUtils = mock(EncryptUtils.class);
        CacheUsageProbe probe = mock(CacheUsageProbe.class);
        when(probe.decorate(any(WebClient.Builder.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        factory = new ChatModelFactory();
        com.ruoyi.system.ai.memory.ChatMessageMapperTestSupport.setField(factory, "aiModelSupplyService", supplyService);
        com.ruoyi.system.ai.memory.ChatMessageMapperTestSupport.setField(factory, "aiChannelService", channelService);
        com.ruoyi.system.ai.memory.ChatMessageMapperTestSupport.setField(factory, "encryptUtils", encryptUtils);
        com.ruoyi.system.ai.memory.ChatMessageMapperTestSupport.setField(factory, "cacheUsageProbe", probe);
    }

    /** 路由与渠道行进 TTL 缓存,apiKey 只在构造新实例时解密一次。 */
    @Test
    void repeatedGet_reusesRouteAndDecryptsOnce()
    {
        stubSingleSupply(5L, "qwen-max", channel(5L, "http://old:8317", "enc-old"));

        ChatModel first = factory.get(9L);
        ChatModel second = factory.get(9L);

        assertSame(first, second, "同渠道同模型应复用同一实例");
        verify(supplyService, times(1)).listByModelId(9L);
        verify(channelService, times(1)).selectAiChannelById(5L);
        verify(encryptUtils, times(1)).decrypt("enc-old");
    }

    /** 渠道变更事件:模型实例、渠道行、路由三层全部失效,下次按新配置重建。 */
    @Test
    void channelChangedEvent_rebuildsWithNewConfig()
    {
        stubSingleSupply(5L, "qwen-max", channel(5L, "http://old:8317", "enc-old"));
        ChatModel first = factory.get(9L);

        factory.onChannelChanged(new AiChannelChangedEvent(5L));
        when(channelService.selectAiChannelById(5L)).thenReturn(channel(5L, "http://new:9000", "enc-new"));
        when(encryptUtils.decrypt("enc-new")).thenReturn("plain-new");

        ChatModel rebuilt = factory.get(9L);

        assertNotSame(first, rebuilt, "渠道配置已变,必须重建实例");
        verify(supplyService, times(2)).listByModelId(9L);
        verify(encryptUtils, times(1)).decrypt("enc-new");
    }

    private void stubSingleSupply(Long channelId, String modelName, AiChannel channel)
    {
        AiModelChannel supply = new AiModelChannel();
        supply.setChannelId(channelId);
        supply.setModelName(modelName);
        supply.setStatus("0");
        supply.setWeight(10);
        when(supplyService.listByModelId(9L)).thenReturn(List.of(supply));
        when(channelService.selectAiChannelById(channelId)).thenReturn(channel);
        when(encryptUtils.decrypt(channel.getApiKey())).thenReturn("plain-" + channel.getApiKey());
    }

    private static AiChannel channel(Long id, String baseUrl, String apiKey)
    {
        AiChannel channel = new AiChannel();
        channel.setChannelId(id);
        channel.setChannelName("测试渠道" + id);
        channel.setStatus("0");
        channel.setBaseUrl(baseUrl);
        channel.setApiKey(apiKey);
        return channel;
    }
}
