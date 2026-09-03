package com.ruoyi.ai.contract.kb;

import java.util.List;

public record SearchRequest(List<String> knowledgeBaseIds, String query, SearchOptions options)
{
    public SearchRequest
    {
        knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
        if (query == null || query.isBlank())
        {
            throw new IllegalArgumentException("query 不能为空");
        }
        options = options == null ? new SearchOptions(SearchMode.BASIC, 10, 0, false, null) : options;
    }
}
