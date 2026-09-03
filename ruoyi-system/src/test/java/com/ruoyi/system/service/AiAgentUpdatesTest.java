package com.ruoyi.system.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import com.ruoyi.system.domain.AiAgent;

/**
 * REST PUT 全量保存：前端 clearable 下拉清空后字段是 null，
 * MyBatis {@code <if test="xx != null">} 会跳过 SET，旧值残留。
 * 约定：null 归一成空串，让 mapper 写出清空。
 */
class AiAgentUpdatesTest
{
    @Test
    void restPutTurnsNullModelCodesIntoEmptyStringSoMapperClearsThem()
    {
        AiAgent agent = new AiAgent();
        agent.setAgentId(1L);
        agent.setAgentName("助手");
        agent.setModelCode(null);
        agent.setImageModelCode(null);
        agent.setVideoModelCode(null);
        agent.setTtsModelCode(null);

        AiAgentUpdates.applyRestPutNulls(agent);

        assertEquals("", agent.getModelCode());
        assertEquals("", agent.getImageModelCode());
        assertEquals("", agent.getVideoModelCode());
        assertEquals("", agent.getTtsModelCode());
    }

    @Test
    void restPutKeepsExplicitModelCodes()
    {
        AiAgent agent = new AiAgent();
        agent.setModelCode("gpt-4o");
        agent.setImageModelCode("dall-e-3");
        agent.setVideoModelCode("sora");
        agent.setTtsModelCode("tts-1");

        AiAgentUpdates.applyRestPutNulls(agent);

        assertEquals("gpt-4o", agent.getModelCode());
        assertEquals("dall-e-3", agent.getImageModelCode());
        assertEquals("sora", agent.getVideoModelCode());
        assertEquals("tts-1", agent.getTtsModelCode());
    }

    @Test
    void restPutIgnoresNullAgent()
    {
        AiAgentUpdates.applyRestPutNulls(null);
    }
}
