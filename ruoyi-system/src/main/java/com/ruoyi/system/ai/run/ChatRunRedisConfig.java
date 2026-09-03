package com.ruoyi.system.ai.run;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** 对话实时事件的 Redis 广播订阅配置。 */
@Configuration
public class ChatRunRedisConfig
{
    @Bean(name = "chatRunRedisMessageListenerContainer")
    public RedisMessageListenerContainer chatRunRedisMessageListenerContainer(
            RedisConnectionFactory connectionFactory, ChatRunEventBroker eventBroker,
            ChatRunControlBroker controlBroker)
    {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(new ChatRunRedisSubscriber(eventBroker),
                new ChannelTopic(ChatRunEventBroker.CHANNEL));
        container.addMessageListener((message, pattern) -> {
            String payload = org.springframework.data.redis.serializer.RedisSerializer.string()
                    .deserialize(message.getBody());
            if (payload != null)
            {
                controlBroker.acceptRemote(payload);
            }
        }, new ChannelTopic(ChatRunControlBroker.CHANNEL));
        return container;
    }
}
