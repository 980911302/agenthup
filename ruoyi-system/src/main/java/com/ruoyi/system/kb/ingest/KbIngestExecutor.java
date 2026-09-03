package com.ruoyi.system.kb.ingest;

import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * 异步提交文档摄入任务。
 */
@Component
public class KbIngestExecutor
{
    private static final Logger log = LoggerFactory.getLogger(KbIngestExecutor.class);

    @Autowired
    private KbIngestPipeline pipeline;

    @Autowired
    @Qualifier("kbIngestTaskExecutor")
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
                    log.error("摄入任务未捕获异常 docId={}", docId, e);
                }
            });
        }
        catch (RejectedExecutionException e)
        {
            log.error("摄入线程池已满,拒绝 docId={}", docId, e);
            // 同步兜底,保证任务不丢
            pipeline.process(docId);
        }
    }
}
