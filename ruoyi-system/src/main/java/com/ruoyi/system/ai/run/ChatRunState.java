package com.ruoyi.system.ai.run;

import com.ruoyi.system.domain.AiChatMessage;
import com.ruoyi.system.domain.AiChatRun;
import com.ruoyi.system.domain.AiChatRunStep;

import java.util.List;

/** 可恢复 Run 快照；snapshotSeq 之后的事件由 WebSocket 增量补齐。 */
public record ChatRunState(
        AiChatRun run,
        AiChatMessage userMessage,
        AiChatMessage finalMessage,
        List<AiChatMessage> messages,
        List<AiChatRunStep> steps,
        long snapshotSeq)
{
    public ChatRunState
    {
        messages = messages == null ? List.of() : List.copyOf(messages);
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
