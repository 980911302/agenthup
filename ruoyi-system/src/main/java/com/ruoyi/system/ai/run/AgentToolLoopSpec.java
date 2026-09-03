package com.ruoyi.system.ai.run;

import java.util.List;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;
import com.ruoyi.system.ai.agent.ModelInputModalities;
import com.ruoyi.system.ai.event.ChatEventSink;
import com.ruoyi.system.ai.metering.LlmCallCollector;

/**
 * {@link AgentToolLoop} 的入参。
 *
 * <p>{@code options} 必须 {@code internalToolExecutionEnabled=false},否则循环不会执行工具。
 * {@code conversationId == null} 时跳过 {@code markPruned},ContextCleaner 仍会跑。
 * {@code ownerAgentCode == null} 时事件不带 {@code owner},stepId 保持现口径。
 */
public record AgentToolLoopSpec(
        ChatModel chatModel,
        ChatOptions options,
        List<Message> messages,
        List<ToolCallback> tools,
        int inputBudget,
        LlmCallCollector collector,
        ChatEventSink eventSink,
        ChatTurnCallbacks callbacks,
        String runId,
        String sessionId,
        String conversationId,
        Long agentId,
        Long turnSpanId,
        String ownerAgentCode,
        StringBuilder reply,
        StringBuilder reasoning,
        Runnable flushThinking,
        /** 当前模型是否开启思考；false 时丢弃上游 reasoning 内容，不落库也不推送前端。 */
        boolean reasoningEnabled,
        /**
         * 当前模型支持的输入模态。工具产出的媒体逐份按它判定能否挂进请求
         * (见 {@code AgentToolLoop.appendPromptMedia});送不出去的会转成文字说明。
         */
        ModelInputModalities inputModalities)
{
}
