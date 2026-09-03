package com.ruoyi.system.kb.ingest;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 知识库摄入独立线程池,避免占满通用异步池与对话运行池。
 */
@Configuration
public class KbIngestExecutionConfig
{
    @Bean(name = "kbIngestTaskExecutor")
    public ThreadPoolTaskExecutor kbIngestTaskExecutor(
            @Value("${ai.kb.ingest.core-pool-size:2}") int corePoolSize,
            @Value("${ai.kb.ingest.max-pool-size:8}") int maxPoolSize,
            @Value("${ai.kb.ingest.queue-capacity:100}") int queueCapacity)
    {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("kb-ingest-");
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        return executor;
    }
}
