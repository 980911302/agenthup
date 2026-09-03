package com.ruoyi.ai.contract.model;

import java.util.List;

public record EmbeddingRequest(String model, List<String> inputs)
{
    public EmbeddingRequest
    {
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
    }
}
