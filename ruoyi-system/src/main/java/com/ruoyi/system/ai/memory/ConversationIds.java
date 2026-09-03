package com.ruoyi.system.ai.memory;

/**
 * conversationId 颗粒度拼装(§4.2)。
 * <p>规则:agentId 为空时退化为 sessionId(兼容旧会话路径);两者都为空返回 null;
 * 否则返回 {@code sessionId:agentId}。
 * <p>这是 ChatMemory 的 key,框架不关心它代表什么,但用复合 key 才能让同一会话里
 * 不同 agent 的上下文彼此隔离。
 */
public final class ConversationIds {

    private ConversationIds() {
    }

    public static String of(String sessionId, Long agentId) {
        if (sessionId == null && agentId == null) {
            return null;
        }
        if (agentId == null) {
            return sessionId;
        }
        return sessionId + ":" + agentId;
    }

    /**
     * 从 {@code sessionId:agentId} 解析 agentId。
     * 旧路径裸 sessionId、缺省或非数字后缀时返回 null。
     */
    public static Long agentIdOf(String conversationId)
    {
        if (conversationId == null)
        {
            return null;
        }
        int colon = conversationId.indexOf(':');
        if (colon <= 0 || colon == conversationId.length() - 1)
        {
            return null;
        }
        try
        {
            return Long.valueOf(conversationId.substring(colon + 1));
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }
}
