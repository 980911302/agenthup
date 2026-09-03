package com.ruoyi.system.ai.run;

import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把本批次 {@code tool_calls} 的 id 分发给正在执行的 {@code RecordingToolCallback}。
 *
 * <p><b>为什么需要它</b>:Spring AI 的 {@code ToolCallback.call(String toolInput)} 只给入参,
 * 不给 {@code tool_call_id}。而重建「assistant(tool_calls) + tool 结果」的配对必须靠这个 id ——
 * OpenAI 协议要求每个 tool 消息的 id 都能对上前面 assistant 里的某个 tool_call,配不上直接 400。
 *
 * <p><b>匹配方式</b>:按 (name, arguments) 建索引。{@code RecordingToolCallback} 收到的 toolInput
 * 就是 Spring AI 从 {@code ToolCall.arguments()} 原样传来的,两边字符串完全一致,匹配可靠。
 *
 * <p><b>消费式</b>:每匹配一次就把 id 弹出。同一批次里同名同参的两次调用会各自拿到自己的 id ——
 * 若循环发放同一个,重建时就会少一条 tool 响应,照样 400。
 *
 * <p><b>线程安全</b>:并行工具由 {@code ParallelToolCallingManager} 把调用线程的 Reactor 上下文
 * 复制进每个子线程,多个线程会同时来 take,故 take 加锁。
 *
 * @author ruoyi
 */
public final class ToolCallIdMatcher
{
    /** 放进 Reactor 上下文的键 */
    public static final String CTX_KEY = "ai.chat.toolCallIdMatcher";

    private final Map<String, Deque<String>> idsByKey = new HashMap<>();

    public ToolCallIdMatcher(List<AssistantMessage.ToolCall> calls)
    {
        if (calls == null)
        {
            return;
        }
        for (AssistantMessage.ToolCall tc : calls)
        {
            if (tc == null || tc.id() == null)
            {
                continue;
            }
            idsByKey.computeIfAbsent(key(tc.name(), tc.arguments()), k -> new ArrayDeque<>())
                    .addLast(tc.id());
        }
    }

    /** 取一个 id 并消费掉;没匹配上返回 null(调用方按「没有 id」处理,不要编一个)。 */
    public synchronized String take(String name, String arguments)
    {
        Deque<String> q = idsByKey.get(key(name, arguments));
        return (q == null || q.isEmpty()) ? null : q.pollFirst();
    }

    private static String key(String name, String arguments)
    {
        // \u0000 做分隔符:工具名与 JSON 入参里都不会出现,不可能拼出歧义键
        return (name == null ? "" : name) + '\u0000' + (arguments == null ? "" : arguments);
    }
}
