package com.ruoyi.ai.contract.tool;

import com.fasterxml.jackson.databind.JsonNode;

public record ToolCall(String id, String name, JsonNode arguments)
{
}
