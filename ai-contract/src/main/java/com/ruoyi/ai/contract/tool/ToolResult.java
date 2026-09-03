package com.ruoyi.ai.contract.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.ruoyi.ai.contract.artifact.ArtifactRef;
import com.ruoyi.ai.contract.artifact.UiArtifact;
import com.ruoyi.ai.contract.core.Usage;
import java.util.List;

public record ToolResult(boolean success, JsonNode output, List<ArtifactRef> artifacts,
                         List<UiArtifact> uiArtifacts, ToolError error, Usage usage)
{
    public ToolResult
    {
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        uiArtifacts = uiArtifacts == null ? List.of() : List.copyOf(uiArtifacts);
        usage = usage == null ? Usage.EMPTY : usage;
        if (success && error != null)
        {
            throw new IllegalArgumentException("成功的工具结果不能包含 error");
        }
    }
}
