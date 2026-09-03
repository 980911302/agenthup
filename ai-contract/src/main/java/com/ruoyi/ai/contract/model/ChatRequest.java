package com.ruoyi.ai.contract.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

public record ChatRequest(String model, List<Message> messages, Map<String, Object> options)
{
    public ChatRequest
    {
        messages = messages == null ? List.of() : List.copyOf(messages);
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    public record Message(String role, String content, JsonNode metadata) { }
}
