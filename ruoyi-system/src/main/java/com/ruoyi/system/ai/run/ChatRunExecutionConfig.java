package com.ruoyi.system.ai.run;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/** 对话运行使用独立有界线程池，避免长耗时 Agent 占满通用异步任务池。 */
@Configuration
public class ChatRunExecutionConfig
{
    @Bean(name = "chatRunTaskExecutor")
    public ThreadPoolTaskExecutor chatRunTaskExecutor(
            @Value("${ai.chat.run.core-pool-size:4}") int corePoolSize,
            @Value("${ai.chat.run.max-pool-size:16}") int maxPoolSize,
            @Value("${ai.chat.run.queue-capacity:200}") int queueCapacity)
    {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("chat-run-");
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }

    /**
     * 并行工具执行专用线程池（与 chatRunTaskExecutor 隔离）。
     *
     * <p>不能复用 chatRunTaskExecutor：并行工具在 chat-run 线程内等待子任务完成，
     * 若子任务也排同一个池，父线程占着槽位等子任务、子任务排队等槽位，会互相死锁。
     */
    @Bean(name = "parallelToolTaskExecutor")
    public ThreadPoolTaskExecutor parallelToolTaskExecutor(
            @Value("${ai.chat.run.parallel-core-pool-size:4}") int corePoolSize,
            @Value("${ai.chat.run.parallel-max-pool-size:16}") int maxPoolSize,
            @Value("${ai.chat.run.parallel-queue-capacity:64}") int queueCapacity)
    {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("parallel-tool-");
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        return executor;
    }
}

