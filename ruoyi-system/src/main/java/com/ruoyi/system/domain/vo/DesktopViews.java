package com.ruoyi.system.domain.vo;

import com.ruoyi.system.ai.run.ChatRunState;
import com.ruoyi.system.domain.AiAgent;
import com.ruoyi.system.domain.AiChatMessage;
import com.ruoyi.system.domain.AiChatRun;
import com.ruoyi.system.domain.AiChatRunStep;
import com.ruoyi.system.domain.AiChatSession;
import com.ruoyi.system.domain.AiModel;
import com.ruoyi.system.domain.AiSkill;
import com.ruoyi.system.domain.KbKnowledge;
import com.ruoyi.system.domain.KbDocument;
import com.ruoyi.system.domain.KbDocGraph;
import java.util.List;

/** desktop 使用端响应的字段白名单，禁止直接序列化后台管理实体。 */
public final class DesktopViews
{
    private DesktopViews() { }

    public record AgentOption(Long agentId, String agentName, String agentDesc,
                              String agentCode, String icon, String theme) { }
    public record KnowledgeOption(Long kbId, String kbName, String description,
                                  String status, String visibility) { }
    /** 客户端白名单：不返回技能 promptTemplate，模板只在服务端运行时读取。 */
    public record SkillOption(Long skillId, String skillCode, String skillName,
                              String category, String description, String visibility) { }
    public record ModelOption(Long modelId, String modelCode, String displayName,
                              Integer contextWindow, Integer maxOutputTokens,
                              String reasoningEnabled, String visionEnabled, String inputModalities) { }
    public record DocumentItem(Long docId, String docName, Long fileSize, String fileType,
                               String parseStatus, String parseStep, Integer progress,
                               Integer chunkCount, String errorMsg, String status,
                               String productStatus, Boolean downloadable,
                               String createBy,
                               java.util.Date createTime, java.util.Date updateTime) { }
    public record DocumentGraph(Long docId, String graphStatus, String graphStep,
                                Integer progress, Integer chunkTotal, Integer chunkDone,
                                Integer entityCount, Integer relationCount, String errorMsg) { }
    public record ChatSessionItem(String sessionId, String title, String status,
                                  Long supervisorAgentId, String supervisorAgentName,
                                  String kbIds, java.util.Date createTime, java.util.Date updateTime) { }
    public record UserMessageNav(Long messageId, String runId, String content,
                                 java.util.Date createTime) { }
    /** 不暴露 userId、workerId、activeKey、幂等键和内部消息主键。 */
    public record ChatRunView(String runId, String sessionId, Long agentId, String status,
                              String inputText, String attachments, Long snapshotSeq,
                              String errorCode, String errorMessage, java.util.Date startedTime,
                              java.util.Date finishedTime, java.util.Date createTime,
                              List<Long> skippedSkillIds) { }
    /** 不暴露会话内部记忆键、LLM 可见标记和服务端文件路径。 */
    public record ChatMessageView(Long messageId, String sessionId, String runId, String messageKind,
                                  String stepId, String parentStepId, Long agentId, Long subAgentId,
                                  String messageType, String content, String attachments,
                                  String toolCalls, String toolCallId, String toolName,
                                  String toolArgs, Integer toolArgsLength, String toolResult,
                                  Integer toolResultLength, boolean hasFullToolResult,
                                  String toolSource, Long toolDurationMs, String toolSuccess,
                                  String pruned, Integer tokens, Integer promptTokens,
                                  Integer completionTokens, String modelName, String usageSource,
                                  java.util.Date createTime) { }
    /** 不暴露 run/session ID 与外置结果文件路径。 */
    public record ChatRunStepView(String stepId, String parentStepId, String stepType,
                                  String status, Long sortNo, String name, String source,
                                  String confirmId, String inputData, String outputData,
                                  String attachments, String success, Long durationMs,
                                  Long lastEventSeq, Long messageId, java.util.Date startedTime,
                                  java.util.Date finishedTime) { }
    public record ChatRunStateView(ChatRunView run, ChatMessageView userMessage,
                                   ChatMessageView finalMessage, List<ChatMessageView> messages,
                                   List<ChatRunStepView> steps, long snapshotSeq) { }

    public static AgentOption agentOption(AiAgent source)
    {
        return new AgentOption(source.getAgentId(), source.getAgentName(), source.getAgentDesc(),
            source.getAgentCode(), source.getIcon(), source.getTheme());
    }

    public static KnowledgeOption knowledgeOption(KbKnowledge source)
    {
        return new KnowledgeOption(source.getKbId(), source.getKbName(), source.getDescription(),
            source.getStatus(), source.getVisibility());
    }

    public static SkillOption skillOption(AiSkill source)
    {
        return new SkillOption(source.getSkillId(), source.getSkillCode(), source.getSkillName(),
            source.getCategory(), source.getDescription(), source.getVisibility());
    }

    public static ModelOption modelOption(AiModel source)
    {
        return new ModelOption(source.getModelId(), source.getModelCode(), source.getDisplayName(),
            source.getContextWindow(), source.getMaxOutputTokens(), source.getReasoningEnabled(),
            source.getVisionEnabled(), source.getInputModalities());
    }

    public static ChatSessionItem session(AiChatSession source)
    {
        return new ChatSessionItem(source.getSessionId(), source.getTitle(), source.getStatus(),
            source.getSupervisorAgentId(), source.getSupervisorAgentName(), source.getKbIds(),
            source.getCreateTime(), source.getUpdateTime());
    }

    public static DocumentItem document(KbDocument source)
    {
        return new DocumentItem(source.getDocId(), source.getDocName(), source.getFileSize(),
            source.getFileType(), source.getParseStatus(), source.getParseStep(), source.getProgress(),
            source.getChunkCount(), source.getErrorMsg(), source.getStatus(), source.getProductStatus(),
            source.getDownloadable(), source.getCreateBy(), source.getCreateTime(), source.getUpdateTime());
    }

    public static DocumentGraph documentGraph(KbDocGraph source)
    {
        return new DocumentGraph(source.getDocId(), source.getGraphStatus(), source.getGraphStep(),
            source.getProgress(), source.getChunkTotal(), source.getChunkDone(), source.getEntityCount(),
            source.getRelationCount(), source.getErrorMsg());
    }

    public static ChatRunView run(AiChatRun source)
    {
        if (source == null) return null;
        return new ChatRunView(source.getRunId(), source.getSessionId(), source.getAgentId(),
            source.getStatus(), source.getInputText(), source.getAttachments(), source.getSnapshotSeq(),
            source.getErrorCode(), source.getErrorMessage(), source.getStartedTime(),
            source.getFinishedTime(), source.getCreateTime(),
            source.getSkippedSkillIds() == null ? List.of() : source.getSkippedSkillIds());
    }

    public static UserMessageNav userMessageNav(AiChatMessage source)
    {
        return new UserMessageNav(source.getMessageId(), source.getRunId(), source.getContent(),
            source.getCreateTime());
    }

    public static ChatRunStateView state(ChatRunState source)
    {
        return new ChatRunStateView(run(source.run()), message(source.userMessage()),
            message(source.finalMessage()), source.messages().stream().map(DesktopViews::message).toList(),
            source.steps().stream().map(DesktopViews::step).toList(), source.snapshotSeq());
    }

    private static ChatMessageView message(AiChatMessage source)
    {
        if (source == null) return null;
        int argsLength = source.getToolArgs() == null ? 0 : source.getToolArgs().length();
        int resultLength = source.getToolResult() == null ? 0 : source.getToolResult().length();
        return new ChatMessageView(source.getMessageId(), source.getSessionId(), source.getRunId(), source.getMessageKind(),
            source.getStepId(), source.getParentStepId(), source.getAgentId(), source.getSubAgentId(),
            source.getMessageType(), source.getContent(), source.getAttachments(), source.getToolCalls(),
            source.getToolCallId(), source.getToolName(), preview(source.getToolArgs(), 500), argsLength,
            preview(source.getToolResult(), 300), resultLength,
            source.getToolResultPath() != null || resultLength > 300, source.getToolSource(),
            source.getToolDurationMs(), source.getToolSuccess(), source.getPruned(), source.getTokens(),
            source.getPromptTokens(), source.getCompletionTokens(), source.getModelName(),
            source.getUsageSource(), source.getCreateTime());
    }

    private static ChatRunStepView step(AiChatRunStep source)
    {
        return new ChatRunStepView(source.getStepId(), source.getParentStepId(), source.getStepType(),
            source.getStatus(), source.getSortNo(), source.getName(), source.getSource(),
            source.getConfirmId(), source.getInputData(), source.getOutputData(), source.getAttachments(),
            source.getSuccess(), source.getDurationMs(), source.getLastEventSeq(), source.getMessageId(),
            source.getStartedTime(), source.getFinishedTime());
    }

    private static String preview(String value, int max)
    {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max) + "\n…[内容较长，完整内容点击查看]";
    }
}
