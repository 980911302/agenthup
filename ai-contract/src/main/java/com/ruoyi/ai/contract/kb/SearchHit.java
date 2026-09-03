package com.ruoyi.ai.contract.kb;

import java.util.List;
import java.util.Map;

public record SearchHit(String knowledgeBaseId, String documentId, String chunkId,
                        String content, double score, List<Citation> citations,
                        Map<String, Object> metadata)
{
    public SearchHit
    {
        citations = citations == null ? List.of() : List.copyOf(citations);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
