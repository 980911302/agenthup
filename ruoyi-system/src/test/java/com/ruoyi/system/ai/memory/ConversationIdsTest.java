package com.ruoyi.system.ai.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * conversationId 颗粒度测试(§4.2)。
 * 规则:agentId 为空时退化为 sessionId;两者都为空返回 null;否则 sessionId:agentId。
 */
class ConversationIdsTest {

    @Test
    void agentIdNull_returnsBareSessionId() {
        assertEquals("s1", ConversationIds.of("s1", null));
    }

    @Test
    void agentIdNonNull_returnsCompound() {
        assertEquals("s1:25", ConversationIds.of("s1", 25L));
    }

    @Test
    void sessionIdNull_agentIdNull_returnsNull() {
        assertNull(ConversationIds.of(null, null));
    }

    @Test
    void agentIdOf_parsesCompoundAndRejectsBare() {
        assertEquals(25L, ConversationIds.agentIdOf("s1:25"));
        assertNull(ConversationIds.agentIdOf("s1"));
        assertNull(ConversationIds.agentIdOf(null));
        assertNull(ConversationIds.agentIdOf("s1:x"));
    }
}
