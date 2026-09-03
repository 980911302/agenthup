package com.ruoyi.ai.contract.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

public record ToolDescriptor(String name, String version, String description,
                             JsonNode inputSchema, JsonNode outputSchema,
                             ToolSafety safety, Set<String> capabilities)
{
    public ToolDescriptor
    {
        if (name == null || name.isBlank() || version == null || version.isBlank())
        {
            throw new IllegalArgumentException("工具 name/version 不能为空");
        }
        safety = safety == null ? ToolSafety.readOnly() : safety;
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
    }
}
