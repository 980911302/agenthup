package com.ruoyi.ai.contract.model;

import java.util.Set;

public record ModelCapabilities(Set<ModelCapability> capabilities, boolean streaming,
                                boolean toolCalling, boolean vision, int contextWindow)
{
    public ModelCapabilities
    {
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
    }
}
