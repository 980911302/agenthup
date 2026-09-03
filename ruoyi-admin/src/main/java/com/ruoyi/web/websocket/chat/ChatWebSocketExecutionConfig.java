package com.ruoyi.web.websocket.chat;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/** WebSocket 出站使用独立有界线程池，慢客户端不能占用 Agent worker。 */
@Configuration
public class ChatWebSocketExecutionConfig
{
    @Bean(name = "chatWebSocketSendExecutor")
    public ThreadPoolTaskExecutor chatWebSocketSendExecutor()
    {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("chat-ws-send-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(1000);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }
}
