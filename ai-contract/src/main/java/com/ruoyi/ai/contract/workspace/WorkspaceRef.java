package com.ruoyi.ai.contract.workspace;

public record WorkspaceRef(String id)
{
    public WorkspaceRef
    {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("workspace id 不能为空");
    }
}
