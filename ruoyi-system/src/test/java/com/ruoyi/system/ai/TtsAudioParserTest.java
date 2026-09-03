package com.ruoyi.system.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TtsAudioParserTest
{
    @Test
    void readsDashscopeOutputAudioUrl()
    {
        assertEquals("https://cdn/a.mp3",
                TtsAudioParser.audioUrl("{\"output\":{\"audio\":{\"url\":\"https://cdn/a.mp3\"}}}"));
        assertEquals("https://cdn/b.wav", TtsAudioParser.audioUrl("{\"url\":\"https://cdn/b.wav\"}"));
        assertNull(TtsAudioParser.audioUrl("{\"output\":{}}"));
    }

    @Test
    void stripsCompatibleModeForDashscopeOrigin()
    {
        assertEquals("https://token-plan.cn-beijing.maas.aliyuncs.com",
                OpenAiCompatibleTtsClient.normalizeDashscopeOrigin(
                        "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"));
        assertEquals("https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode",
                OpenAiCompatibleTtsClient.normalizeOpenAiBase(
                        "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"));
    }
}
