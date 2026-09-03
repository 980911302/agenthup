package com.ruoyi.system.ai.memory;

/**
 * 消息的业务语义。messageType 只描述协议角色，messageKind 描述这行在一次 Run 中的用途。
 */
public final class ChatMessageKind
{
    public static final String USER_INPUT = "USER_INPUT";
    public static final String ASSISTANT_TOOL_CALL = "ASSISTANT_TOOL_CALL";
    public static final String TOOL_RESULT = "TOOL_RESULT";
    public static final String ASSISTANT_FINAL = "ASSISTANT_FINAL";
    public static final String REASONING = "REASONING";
    public static final String SUMMARY = "SUMMARY";
    public static final String SYSTEM = "SYSTEM";

    private ChatMessageKind() { }
}
