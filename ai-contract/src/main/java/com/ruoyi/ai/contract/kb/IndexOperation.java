package com.ruoyi.ai.contract.kb;

public record IndexOperation(String operationId, String knowledgeBaseId, IndexStatus.State state)
{
}
