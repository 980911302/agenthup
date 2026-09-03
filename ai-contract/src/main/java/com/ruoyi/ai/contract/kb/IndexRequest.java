package com.ruoyi.ai.contract.kb;

import java.util.List;
import java.util.Map;

public record IndexRequest(String knowledgeBaseId, List<DocumentSource> documents,
                           Map<String, Object> options)
{
    public IndexRequest
    {
        documents = documents == null ? List.of() : List.copyOf(documents);
        options = options == null ? Map.of() : Map.copyOf(options);
    }
}
