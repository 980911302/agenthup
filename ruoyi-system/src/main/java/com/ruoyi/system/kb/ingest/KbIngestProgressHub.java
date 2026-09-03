package com.ruoyi.system.kb.ingest;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 文档处理进度 SSE 广播。按 kbId 订阅,处理管线推事件。
 */
@Component
public class KbIngestProgressHub
{
    private static final Logger log = LoggerFactory.getLogger(KbIngestProgressHub.class);

    /** 超时 30 分钟,长文档嵌入可能较慢 */
    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;

    private final Map<Long, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long kbId)
    {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        subscribers.computeIfAbsent(kbId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable cleanup = () -> remove(kbId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());
        try
        {
            emitter.send(SseEmitter.event().name("connected").data("{\"type\":\"connected\",\"kbId\":" + kbId + "}"));
        }
        catch (IOException e)
        {
            remove(kbId, emitter);
        }
        return emitter;
    }

    public void publish(Long kbId, String json)
    {
        List<SseEmitter> list = subscribers.get(kbId);
        if (list == null || list.isEmpty())
        {
            return;
        }
        for (SseEmitter emitter : list)
        {
            try
            {
                emitter.send(SseEmitter.event().name("kb").data(json));
            }
            catch (Exception e)
            {
                remove(kbId, emitter);
            }
        }
    }

    private void remove(Long kbId, SseEmitter emitter)
    {
        List<SseEmitter> list = subscribers.get(kbId);
        if (list != null)
        {
            list.remove(emitter);
            if (list.isEmpty())
            {
                subscribers.remove(kbId, list);
            }
        }
        try
        {
            emitter.complete();
        }
        catch (Exception ignored)
        {
        }
    }
}
