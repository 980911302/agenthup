package com.ruoyi.ai.contract.model;

import java.util.Map;

public record ModelRequest(String modelRef, ModelCapability capability,
                           Map<String, Object> requirements)
{
    public ModelRequest
    {
        if (modelRef == null || modelRef.isBlank() || capability == null)
        {
            throw new IllegalArgumentException("modelRef 和 capability 不能为空");
        }
        requirements = requirements == null ? Map.of() : Map.copyOf(requirements);
    }
}
