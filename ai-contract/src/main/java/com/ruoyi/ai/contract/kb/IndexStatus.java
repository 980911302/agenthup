package com.ruoyi.ai.contract.kb;

import java.util.List;

public record IndexStatus(String operationId, State state, int progress,
                          List<String> errors)
{
    public enum State { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }

    public IndexStatus
    {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
