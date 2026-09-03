package com.ruoyi.ai.contract.event;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/** WebSocket、Redis 和 SSE 共用的 v1 运行事件信封。 */
public record RunEventEnvelope(
        String specversion,
        String type,
        String id,
        Instant time,
        String subject,
        long sequence,
        JsonNode data)
{
    public static final String SPEC_VERSION = "1.0";

    public RunEventEnvelope
    {
        if (!SPEC_VERSION.equals(specversion))
        {
            throw new IllegalArgumentException("不支持的事件协议版本: " + specversion);
        }
        if (type == null || type.isBlank() || id == null || id.isBlank()
                || subject == null || subject.isBlank() || sequence < 1)
        {
            throw new IllegalArgumentException("运行事件缺少必填字段");
        }
        time = time == null ? Instant.now() : time;
    }
}
