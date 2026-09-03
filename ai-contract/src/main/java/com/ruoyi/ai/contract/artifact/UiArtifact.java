package com.ruoyi.ai.contract.artifact;

import com.fasterxml.jackson.databind.JsonNode;

public record UiArtifact(String artifactType, int schemaVersion, JsonNode payload)
{
    public UiArtifact
    {
        if (artifactType == null || artifactType.isBlank())
        {
            throw new IllegalArgumentException("artifactType 不能为空");
        }
        if (schemaVersion < 1)
        {
            throw new IllegalArgumentException("schemaVersion 必须大于 0");
        }
    }
}
