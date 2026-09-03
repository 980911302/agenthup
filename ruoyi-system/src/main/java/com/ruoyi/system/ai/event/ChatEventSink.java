package com.ruoyi.system.ai.event;

/**
 * 对话过程事件出口。
 *
 * <p>Agent 与工具只依赖这个接口，不再感知浏览器连接类型。
 * 持久化运行(Run)适配 Redis Stream + WebSocket 广播。</p>
 */
@FunctionalInterface
public interface ChatEventSink
{
    ChatEventSink NOOP = eventJson -> { };

    void emit(String eventJson);

    static ChatEventSink noop()
    {
        return NOOP;
    }
}
