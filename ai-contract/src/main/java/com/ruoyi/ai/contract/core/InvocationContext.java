package com.ruoyi.ai.contract.core;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** 跨模块显式传递的调用身份、幂等和截止时间，替代 ThreadLocal 隐式读取。 */
public record InvocationContext(
        String tenantId,
        String principalId,
        Set<String> roles,
        String traceId,
        Instant deadline,
        String idempotencyKey,
        Map<String, String> attributes)
{
    public InvocationContext
    {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static InvocationContext system(String traceId)
    {
        return new InvocationContext(null, "system", Set.of("SYSTEM"), traceId,
                null, null, Map.of());
    }
}
