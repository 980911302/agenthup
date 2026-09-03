package com.ruoyi.system.ai.run;

import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.serializer.RedisSerializer;

/** Redis Pub/Sub 到本实例运行事件总线的桥接器。 */
public class ChatRunRedisSubscriber implements MessageListener
{
    private final ChatRunEventBroker eventBroker;

    public ChatRunRedisSubscriber(ChatRunEventBroker eventBroker)
    {
        this.eventBroker = eventBroker;
    }

    @Override
    public void onMessage(Message message, byte[] pattern)
    {
        String payload = RedisSerializer.string().deserialize(message.getBody());
        if (payload != null)
        {
            eventBroker.acceptRemote(payload);
        }
    }
}

