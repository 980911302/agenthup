package com.ruoyi.ai.contract.kb;

import java.net.URI;
import java.util.Map;

public record DocumentSource(String documentId, String fileName, String mediaType,
                             URI contentUri, String sha256, Map<String, Object> metadata)
{
    public DocumentSource
    {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
