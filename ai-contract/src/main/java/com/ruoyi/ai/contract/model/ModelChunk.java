package com.ruoyi.ai.contract.model;

import com.fasterxml.jackson.databind.JsonNode;

public record ModelChunk(String type, String text, JsonNode data, boolean terminal)
{
}
