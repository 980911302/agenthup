package com.ruoyi.ai.contract.kb;

import com.ruoyi.ai.contract.core.InvocationContext;

public interface KnowledgeSearchPort
{
    SearchResponse search(SearchRequest request, InvocationContext context);
}
