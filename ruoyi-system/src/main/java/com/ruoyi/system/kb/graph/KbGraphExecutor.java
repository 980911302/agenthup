package com.ruoyi.system.kb.graph;

import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * 异步提交图谱抽取任务。
 */
@Component
public class KbGraphExecutor
{
    private static final Logger log = LoggerFactory.getLogger(KbGraphExecutor.class);

    @Autowired
    private KbGraphPipeline pipeline;

    @Autowired
    @Qualifier("kbGraphTaskExecutor")
    private ThreadPoolTaskExecutor executor;

    public void submit(Long docId)
    {
        if (docId == null)
        {
            return;
        }
        try
        {
            executor.execute(() -> {
                try
                {
                    pipeline.process(docId);
                }
                catch (Exception e)
                {
                    log.error("图谱抽取未捕获异常 docId={}", docId, e);
                }
            });
        }
        catch (RejectedExecutionException e)
        {
            log.error("图谱线程池已满,同步兜底 docId={}", docId, e);
            pipeline.process(docId);
        }
    }
}
