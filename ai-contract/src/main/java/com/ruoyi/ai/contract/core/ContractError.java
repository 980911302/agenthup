package com.ruoyi.ai.contract.core;

import java.util.Map;

/** 可跨 HTTP、JSON-RPC 和事件边界传输的统一错误。 */
public record ContractError(ErrorCode code, String message, boolean retryable,
                            Map<String, Object> details)
{
    public ContractError
    {
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
