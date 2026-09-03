package com.ruoyi.ai.contract.kb;

import java.util.List;

public record IndexSelector(String knowledgeBaseId, List<String> documentIds)
{
    public IndexSelector
    {
        documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
    }
}
