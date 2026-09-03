package com.ruoyi.system.service.impl;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.system.mapper.AiChatMessageMapper;
import com.ruoyi.system.mapper.AiChatRunMapper;
import com.ruoyi.system.mapper.AiChatRunStepMapper;
import com.ruoyi.system.mapper.AiChatSessionKbMapper;
import com.ruoyi.system.mapper.AiChatSessionMapper;
import com.ruoyi.system.mapper.AiLlmCallMapper;

/** 删除对话只能清理内容，已发生的模型计量必须保留。 */
class AiChatSessionUsageRetentionTest
{
    @Test
    void deletingSessionKeepsLlmCallMetering()
    {
        AiChatSessionServiceImpl service = new AiChatSessionServiceImpl();
        AiChatSessionMapper sessionMapper = mock(AiChatSessionMapper.class);
        AiChatMessageMapper messageMapper = mock(AiChatMessageMapper.class);
        AiChatRunMapper runMapper = mock(AiChatRunMapper.class);
        AiChatRunStepMapper stepMapper = mock(AiChatRunStepMapper.class);
        AiChatSessionKbMapper sessionKbMapper = mock(AiChatSessionKbMapper.class);
        AiLlmCallMapper llmCallMapper = mock(AiLlmCallMapper.class);

        ReflectionTestUtils.setField(service, "aiChatSessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "aiChatMessageMapper", messageMapper);
        ReflectionTestUtils.setField(service, "aiChatRunMapper", runMapper);
        ReflectionTestUtils.setField(service, "aiChatRunStepMapper", stepMapper);
        ReflectionTestUtils.setField(service, "aiChatSessionKbMapper", sessionKbMapper);
        ReflectionTestUtils.setField(service, "aiLlmCallMapper", llmCallMapper);
        when(sessionMapper.deleteAiChatSessionById("session-usage-1")).thenReturn(1);
        when(sessionMapper.deleteAiChatSessionByIds(new String[] { "session-usage-2", "session-usage-3" })).thenReturn(2);

        service.deleteAiChatSessionById("session-usage-1");
        service.deleteAiChatSessionByIds(new String[] { "session-usage-2", "session-usage-3" });

        verify(sessionMapper).deleteAiChatSessionById("session-usage-1");
        verify(messageMapper).deleteBySessionId("session-usage-1");
        verify(messageMapper).deleteBySessionId("session-usage-2");
        verify(messageMapper).deleteBySessionId("session-usage-3");
        verify(llmCallMapper, never()).deleteBySessionId(anyString());
    }
}
