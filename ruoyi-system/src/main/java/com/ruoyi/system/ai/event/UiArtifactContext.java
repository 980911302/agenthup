package com.ruoyi.system.ai.event;

/**
 * UI 产物发射上下文。工具与非工具来源共用。
 */
public record UiArtifactContext(
        ChatEventSink sink,
        String sessionId,
        String runId,
        Long messageId,
        Long agentId,
        String ownerAgentCode,
        String producerStepId
)
{
}
