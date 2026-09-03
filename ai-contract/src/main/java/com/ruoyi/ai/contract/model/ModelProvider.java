package com.ruoyi.ai.contract.model;

import com.ruoyi.ai.contract.core.InvocationContext;
import java.util.concurrent.Flow;

public interface ModelProvider
{
    String id();

    ModelCapabilities capabilities();

    default Flow.Publisher<ModelChunk> chat(ChatRequest request, InvocationContext context)
    {
        throw new UnsupportedOperationException("provider 不支持 chat");
    }

    default EmbeddingResponse embed(EmbeddingRequest request, InvocationContext context)
    {
        throw new UnsupportedOperationException("provider 不支持 embedding");
    }
}
