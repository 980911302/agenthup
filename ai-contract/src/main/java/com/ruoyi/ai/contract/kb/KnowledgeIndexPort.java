package com.ruoyi.ai.contract.kb;

import com.ruoyi.ai.contract.core.InvocationContext;

public interface KnowledgeIndexPort
{
    IndexOperation start(IndexRequest request, InvocationContext context);

    IndexStatus status(String operationId, InvocationContext context);

    void delete(IndexSelector selector, InvocationContext context);
}
