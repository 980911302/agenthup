package com.ruoyi.system.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * OpenAiCompatibleEndpoint 端点推导:
 * baseUrl 末段已是 vN 版本段的渠道,依赖路径不能再拼 /v1,否则 …/v3/v1/chat/completions 404。
 * 此处既断言版本段判定,也断言「baseUrl + 依赖路径」拼出的最终端点(线上回归点:火山 ark)。
 */
class OpenAiCompatibleEndpointTest
{
    @Test
    void baseUrl_trimsOnlyTrailingSlashes_keepsVersionSegment()
    {
        assertEquals("https://ark.cn-beijing.volces.com/api/plan/v3",
                OpenAiCompatibleEndpoint.baseUrl("https://ark.cn-beijing.volces.com/api/plan/v3/"));
        assertEquals("https://host:8317",
                OpenAiCompatibleEndpoint.baseUrl("https://host:8317///"));
        assertEquals("", OpenAiCompatibleEndpoint.baseUrl(null));
    }

    @Test
    void versionInBase_detectsVersionSuffix()
    {
        assertTrue(OpenAiCompatibleEndpoint.versionInBase("https://ark.cn-beijing.volces.com/api/plan/v3"));
        assertTrue(OpenAiCompatibleEndpoint.versionInBase("https://api.deepseek.com/v1"));
        assertTrue(OpenAiCompatibleEndpoint.versionInBase("https://qianfan.baidubce.com/v2"));
        assertTrue(OpenAiCompatibleEndpoint.versionInBase("http://192.168.0.102:11434/v1"));

        assertFalse(OpenAiCompatibleEndpoint.versionInBase("https://api.openai.com"));
        assertFalse(OpenAiCompatibleEndpoint.versionInBase("https://opencode.ai/zen/go"));
        assertFalse(OpenAiCompatibleEndpoint.versionInBase("https://host:8317"));
    }

    @Test
    void completionsPath_noVersionInBase_keepsSpringAiDefaultV1()
    {
        assertEquals("/v1/chat/completions",
                OpenAiCompatibleEndpoint.completionsPath("https://api.openai.com"));
    }

    /** 最终端点 = baseUrl + 依赖路径;该表即各渠道线下的真实请求地址 */
    @Test
    void finalChatEndpoint_regressionTable()
    {
        assertFinalChat("https://ark.cn-beijing.volces.com/api/plan/v3",
                "https://ark.cn-beijing.volces.com/api/plan/v3/chat/completions");
        assertFinalChat("https://api.deepseek.com/v1",
                "https://api.deepseek.com/v1/chat/completions");
        assertFinalChat("https://qianfan.baidubce.com/v2",
                "https://qianfan.baidubce.com/v2/chat/completions");
        assertFinalChat("https://dashscope.aliyuncs.com/compatible-mode/v1",
                "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions");
        // 官方 OpenAI 风格:base 不带版本,依赖路径带 /v1
        assertFinalChat("https://api.openai.com",
                "https://api.openai.com/v1/chat/completions");
        assertFinalChat("https://opencode.ai/zen/go/v1",
                "https://opencode.ai/zen/go/v1/chat/completions");
    }

    private static void assertFinalChat(String storedBase, String expected)
    {
        assertEquals(expected,
                OpenAiCompatibleEndpoint.baseUrl(storedBase)
                        + OpenAiCompatibleEndpoint.completionsPath(storedBase));
    }
}