package com.ruoyi.system.kb.graph;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 图谱抽取独立线程池,勿与摄入池共用(长任务会饿死上传)。
 */
@Configuration
public class KbGraphExecutionConfig
{
    @Bean(name = "kbGraphTaskExecutor")
    public ThreadPoolTaskExecutor kbGraphTaskExecutor(
            @Value("${ai.kb.graph.core-pool-size:2}") int corePoolSize,
            @Value("${ai.kb.graph.max-pool-size:6}") int maxPoolSize,
            @Value("${ai.kb.graph.queue-capacity:50}") int queueCapacity)
    {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("kb-graph-");
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        return executor;
    }
}
