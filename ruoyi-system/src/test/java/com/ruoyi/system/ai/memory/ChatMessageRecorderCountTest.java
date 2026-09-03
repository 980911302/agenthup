package com.ruoyi.system.ai.memory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.system.domain.AiChatMessage;
import com.ruoyi.system.mapper.AiChatMessageMapper;
import com.ruoyi.system.mapper.AiChatSessionMapper;

/**
 * ai_chat_session.message_count 的维护。
 *
 * <p>该列自建表起恒为 0 —— 随 context_length 一起加进表,却从未进入 ORM 层,
 * 全代码零引用。计数收敛到 {@link ChatMessageRecorder} 的落库出口后,由本测试锁住:
 * 每条落表消息都必须计一次,且计数失败不得连累消息本身。
 */
class ChatMessageRecorderCountTest
{
    private AiChatMessageMapper mapper;
    private AiChatSessionMapper sessionMapper;
    private ChatMessageRecorder recorder;

    @BeforeEach
    void setUp()
    {
        mapper = mock(AiChatMessageMapper.class);
        sessionMapper = mock(AiChatSessionMapper.class);
        TokenEstimator estimator = mock(TokenEstimator.class);
        when(estimator.estimate(any())).thenReturn(7);
        when(mapper.insertMessage(any())).thenAnswer(inv -> {
            AiChatMessage m = inv.getArgument(0);
            m.setMessageId(1L);
            return 1;
        });

        recorder = new ChatMessageRecorder();
        ReflectionTestUtils.setField(recorder, "mapper", mapper);
        ReflectionTestUtils.setField(recorder, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(recorder, "tokenEstimator", estimator);
        ReflectionTestUtils.setField(recorder, "inlineLimit", 2048);
    }

    @Test
    void 每条消息都累加一次计数()
    {
        recorder.insert("s:1", "sess-a", 1L, "USER", "hi", "0", null);
        recorder.insert("s:1", "sess-a", 1L, "ASSISTANT", "hello", "0", null);
        recorder.insert("s:1", "sess-a", 1L, "THINKING", "think", "1", null);

        verify(sessionMapper, times(3)).addSessionMessageCount(eq("sess-a"), eq(1));
    }

    @Test
    void 计数按会话隔离()
    {
        recorder.insert("s:1", "sess-a", 1L, "USER", "hi", "0", null);
        recorder.insert("s:2", "sess-b", 1L, "USER", "hi", "0", null);

        verify(sessionMapper).addSessionMessageCount(eq("sess-a"), eq(1));
        verify(sessionMapper).addSessionMessageCount(eq("sess-b"), eq(1));
    }

    @Test
    void 没有会话号时不计数()
    {
        recorder.insert("s:1", null, 1L, "USER", "hi", "0", null);
        recorder.insert("s:1", "  ", 1L, "USER", "hi", "0", null);

        verify(sessionMapper, never()).addSessionMessageCount(anyString(), anyInt());
        // 消息本身照常落库,计数缺位不影响审计流
        verify(mapper, times(2)).insertMessage(any());
    }

    @Test
    void 计数失败不影响消息落库()
    {
        when(sessionMapper.addSessionMessageCount(anyString(), anyInt()))
                .thenThrow(new RuntimeException("session row gone"));

        long id = assertDoesNotThrow(
                () -> recorder.insert("s:1", "sess-a", 1L, "USER", "hi", "0", null));

        assertEquals(1L, id, "计数是统计值,消息是审计流,后者不能被前者拖垮");
        verify(mapper).insertMessage(any());
    }

    @Test
    void 未注入会话mapper时不报错()
    {
        ReflectionTestUtils.setField(recorder, "sessionMapper", null);

        long id = assertDoesNotThrow(
                () -> recorder.insert("s:1", "sess-a", 1L, "USER", "hi", "0", null));

        assertEquals(1L, id);
        verify(mapper).insertMessage(any());
    }
}
