package com.ruoyi.system.ai.event;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.system.ai.sse.ChatEventJson;
import com.ruoyi.system.domain.AiChatSpecialEvent;
import com.ruoyi.system.kb.search.KbReferencesUiPayload;
import com.ruoyi.system.mapper.AiChatSpecialEventMapper;
import com.ruoyi.system.tool.UiArtifact;
import com.ruoyi.system.tool.UiArtifactNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 特殊事件发射器:白名单、体积上限、幂等键,再推 {@code type=ui}。
 * 观察者故障只打日志;{@link UiArtifactNames.Scope#GLOBAL} 明确拒绝。
 */
@Component
public class UiArtifactEmitter
{
    private static final Logger log = LoggerFactory.getLogger(UiArtifactEmitter.class);

    private static final int MERGE_RETRIES = 3;

    private final AtomicLong seq = new AtomicLong();
    private final ConcurrentHashMap<String, Long> turnMessageIds = new ConcurrentHashMap<>();
    private AiChatSpecialEventMapper specialEventMapper;

    public UiArtifactEmitter()
    {
    }

    public UiArtifactEmitter(AiChatSpecialEventMapper specialEventMapper)
    {
        this.specialEventMapper = specialEventMapper;
    }

    @Autowired(required = false)
    public void setSpecialEventMapper(AiChatSpecialEventMapper specialEventMapper)
    {
        this.specialEventMapper = specialEventMapper;
    }

    /** 把本轮 user 消息锚到 run,工具线程按 runId 取 messageId。 */
    public void bindTurn(String runId, Long messageId)
    {
        if (runId != null && !runId.isBlank() && messageId != null)
        {
            turnMessageIds.put(runId, messageId);
        }
    }

    public void unbindTurn(String runId)
    {
        if (runId != null)
        {
            turnMessageIds.remove(runId);
        }
    }

    public void emit(UiArtifactContext ctx, UiArtifact artifact)
    {
        if (ctx == null || artifact == null)
        {
            return;
        }
        try
        {
            doEmit(ctx, artifact);
        }
        catch (UnsupportedOperationException ex)
        {
            throw ex;
        }
        catch (Exception ex)
        {
            log.warn("发送 UI 产物失败,不影响主流程: name={}", artifact.name(), ex);
        }
    }

    private void doEmit(UiArtifactContext ctx, UiArtifact artifact)
    {
        UiArtifactNames.Spec spec = lookup(artifact.name());
        if (spec == null)
        {
            return;
        }
        requireSupportedScope(spec);
        if (artifact.schemaVersion() > spec.schemaVersion())
        {
            log.warn("UI 产物 schema 过新,丢弃: name={} v={} max={}",
                    artifact.name(), artifact.schemaVersion(), spec.schemaVersion());
            return;
        }
        Object payload = artifact.payload();
        String eventId = eventId(ctx, spec, artifact.name());
        if (spec.mergePolicy() == UiArtifactNames.MergePolicy.MERGE)
        {
            payload = persistMerged(ctx, spec, eventId, artifact, asMap(payload));
        }
        int chars = payloadChars(payload);
        if (chars > spec.maxPayloadChars())
        {
            log.warn("UI 产物超限,丢弃: name={} chars={} max={}",
                    artifact.name(), chars, spec.maxPayloadChars());
            return;
        }
        ChatEventSink sink = ctx.sink() != null ? ctx.sink() : ChatEventSink.noop();
        sink.emit(ChatEventJson.ui(artifact.name(), artifact.schemaVersion(), eventId,
                payload, ctx.ownerAgentCode(), eventId, ctx.producerStepId()));
        if (spec.mergePolicy() != UiArtifactNames.MergePolicy.MERGE
                && spec.persistence() == UiArtifactNames.Persistence.SESSION)
        {
            persist(ctx, artifact, spec, eventId);
        }
    }

    private void persist(UiArtifactContext ctx, UiArtifact artifact,
                         UiArtifactNames.Spec spec, String eventId)
    {
        if (specialEventMapper == null || ctx.sessionId() == null || ctx.sessionId().isBlank())
        {
            return;
        }
        AiChatSpecialEvent row = new AiChatSpecialEvent();
        row.setSessionId(ctx.sessionId());
        row.setRunId(ctx.runId());
        row.setMessageId(resolveMessageId(ctx));
        row.setAgentId(ctx.agentId());
        row.setOwnerAgentCode(ctx.ownerAgentCode());
        row.setName(artifact.name());
        row.setSchemaVersion(artifact.schemaVersion() > 0 ? artifact.schemaVersion() : spec.schemaVersion());
        row.setEventId(eventId);
        row.setPayload(artifact.payload() == null ? null : JSON.toJSONString(artifact.payload()));
        row.setCreateTime(new Date());
        specialEventMapper.upsert(row);
    }

    private Long resolveMessageId(UiArtifactContext ctx)
    {
        if (ctx.messageId() != null)
        {
            return ctx.messageId();
        }
        if (ctx.runId() != null)
        {
            return turnMessageIds.get(ctx.runId());
        }
        return null;
    }

    UiArtifactNames.Spec lookup(String name)
    {
        return UiArtifactNames.spec(name);
    }

    static void requireSupportedScope(UiArtifactNames.Spec spec)
    {
        if (spec != null && spec.scope() == UiArtifactNames.Scope.GLOBAL)
        {
            throw new UnsupportedOperationException("Scope.GLOBAL 投递本期不实现");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> persistMerged(UiArtifactContext ctx, UiArtifactNames.Spec spec,
                                              String eventId, UiArtifact artifact,
                                              Map<String, Object> incoming)
    {
        Map<String, Object> last = incoming;
        if (specialEventMapper == null || ctx.sessionId() == null || ctx.sessionId().isBlank())
        {
            return incoming;
        }
        for (int attempt = 1; attempt <= MERGE_RETRIES; attempt++)
        {
            try
            {
                AiChatSpecialEvent existing = specialEventMapper.selectBySessionEvent(ctx.sessionId(), eventId);
                if (existing == null)
                {
                    Map<String, Object> merged = KbReferencesUiPayload.merge(null, incoming);
                    last = merged;
                    AiChatSpecialEvent row = newRow(ctx, artifact, spec, eventId, merged);
                    row.setVersion(0);
                    specialEventMapper.insert(row);
                    return merged;
                }
                Map<String, Object> previous = asMap(JSON.parse(existing.getPayload()));
                Map<String, Object> merged = KbReferencesUiPayload.merge(previous, incoming);
                last = merged;
                existing.setPayload(JSON.toJSONString(merged));
                existing.setSchemaVersion(spec.schemaVersion());
                existing.setRunId(ctx.runId());
                existing.setAgentId(ctx.agentId());
                existing.setOwnerAgentCode(ctx.ownerAgentCode());
                int updated = specialEventMapper.updateIfVersion(existing);
                if (updated == 1)
                {
                    return merged;
                }
            }
            catch (Exception ex)
            {
                log.warn("归并写入冲突,重试 {}/{}: name={}", attempt, MERGE_RETRIES, artifact.name(), ex);
            }
        }
        log.warn("归并重试超限,放弃落库: session={} eventId={}", ctx.sessionId(), eventId);
        return last;
    }

    private AiChatSpecialEvent newRow(UiArtifactContext ctx, UiArtifact artifact,
                                      UiArtifactNames.Spec spec, String eventId, Object payload)
    {
        AiChatSpecialEvent row = new AiChatSpecialEvent();
        row.setSessionId(ctx.sessionId());
        row.setRunId(ctx.runId());
        row.setMessageId(resolveMessageId(ctx));
        row.setAgentId(ctx.agentId());
        row.setOwnerAgentCode(ctx.ownerAgentCode());
        row.setName(artifact.name());
        row.setSchemaVersion(artifact.schemaVersion() > 0 ? artifact.schemaVersion() : spec.schemaVersion());
        row.setEventId(eventId);
        row.setPayload(payload == null ? null : JSON.toJSONString(payload));
        row.setCreateTime(new Date());
        return row;
    }

    private String eventId(UiArtifactContext ctx, UiArtifactNames.Spec spec, String name)
    {
        if (spec.mergePolicy() == UiArtifactNames.MergePolicy.MERGE)
        {
            Long messageId = resolveMessageId(ctx);
            if (messageId != null)
            {
                return messageId + ":" + name;
            }
        }
        String producer = ctx.producerStepId();
        if (producer != null && !producer.isBlank())
        {
            return producer + ":" + name;
        }
        String run = ctx.runId() != null && !ctx.runId().isBlank() ? ctx.runId() : "na";
        return run + ":" + name + ":" + seq.incrementAndGet();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object payload)
    {
        if (payload instanceof Map<?, ?> map)
        {
            return (Map<String, Object>) map;
        }
        if (payload == null)
        {
            return Map.of();
        }
        Object parsed = payload instanceof String s ? JSON.parse(s) : JSON.parse(JSON.toJSONString(payload));
        return parsed instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static int payloadChars(Object payload)
    {
        if (payload == null)
        {
            return 0;
        }
        try
        {
            return JSON.toJSONString(payload).length();
        }
        catch (Exception ex)
        {
            return Integer.MAX_VALUE;
        }
    }
}
