package com.ruoyi.system.ai.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeechGenerationToolCallbackTest
{
    @Test
    void definitionStaysShortAndPointsToSkill()
    {
        SpeechGenerationToolCallback tool = new SpeechGenerationToolCallback("qwen-audio-3.0-tts-plus", null, null, null);
        String desc = tool.getToolDefinition().description();
        String schema = tool.getToolDefinition().inputSchema();
        assertTrue(desc.contains("语音合成"), desc);
        assertTrue(desc.contains("loadSkill"), desc);
        assertTrue(schema.contains("语音合成"), schema);
    }
}
