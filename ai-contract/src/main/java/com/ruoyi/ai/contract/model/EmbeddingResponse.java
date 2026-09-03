package com.ruoyi.ai.contract.model;

import com.ruoyi.ai.contract.core.Usage;
import java.util.List;

public record EmbeddingResponse(List<float[]> vectors, Usage usage)
{
    public EmbeddingResponse
    {
        vectors = vectors == null ? List.of() : List.copyOf(vectors);
        usage = usage == null ? Usage.EMPTY : usage;
    }
}
