package com.ruoyi.system.ai.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.system.domain.AiChatMessage;
import com.ruoyi.system.mapper.AiChatMessageMapper;

/**
 * tokens 列统一为估算口径:insert 写估算,updateAttribution 不改 tokens。
 */
class ChatMessageRecorderAttributionTest
{
    private AiChatMessageMapper mapper;
    private TokenEstimator estimator;
    private ChatMessageRecorder recorder;

    @BeforeEach
    void setUp()
    {
        mapper = mock(AiChatMessageMapper.class);
        estimator = mock(TokenEstimator.class);
        when(estimator.estimate(any())).thenReturn(42);
        when(mapper.insertMessage(any())).thenAnswer(inv -> {
            AiChatMessage m = inv.getArgument(0);
            m.setMessageId(100L);
            return 1;
        });

        recorder = new ChatMessageRecorder();
        ReflectionTestUtils.setField(recorder, "mapper", mapper);
        ReflectionTestUtils.setField(recorder, "tokenEstimator", estimator);
        ReflectionTestUtils.setField(recorder, "inlineLimit", 2048);
    }

    @Test
    void insert_writesEstimatedTokens()
    {
        long id = recorder.insert("s:1", "s", 1L, "ASSISTANT", "hello world", "0", null);
        assertEquals(100L, id);

        ArgumentCaptor<AiChatMessage> cap = ArgumentCaptor.forClass(AiChatMessage.class);
        verify(mapper).insertMessage(cap.capture());
        assertEquals(42, cap.getValue().getTokens());
        assertEquals("1", cap.getValue().getUsageSource());
    }

    @Test
    void updateAttribution_doesNotTouchTokensColumn()
    {
        recorder.updateAttribution(100L, 1000, 200, "test-model", "0");

        // 只走归因更新(mapper 方法本身不再包含 tokens 参数)
        verify(mapper).updateAttributionById(eq(100L), eq(1000), eq(200), eq("test-model"), eq("0"));
    }
}
