package com.ruoyi.system.ai.run;

import java.util.List;
import com.ruoyi.system.ai.agent.AgentContextFactory;
import com.ruoyi.system.tool.channel.ChannelToolDef;

/**
 * 一轮对话的统一入参。A 轨与持久化轨各自转换后交给 {@link ChatTurnRunner}。
 *
 * @param runId       本轮运行 id(ai_chat_run.run_id);链路追踪的根,可为 null(非持久化调用)
 * @param sessionId   会话 ID
 * @param agentId     智能体 ID
 * @param message     用户原文
 * @param attachments 附件元数据（可空）
 * @param operator    操作者；A 轨可传 null（由 AgentContextFactory 从请求线程捕获）
 * @param modelCode   本轮实际模型编码快照；null=跟随智能体配置
 * @param skillIds    本轮生效技能（智能体自带 ∪ 用户 @ 选择）。<b>只用于注入当轮 user 消息</b>，
 *                    不参与装配——理由见 {@code AgentContextFactory#buildTurnSkillSection}
 * @param clientTools 会话行上的渠道工具清单；子 agent 传空列表
 */
public record ChatTurnRequest(
        String runId,
        String sessionId,
        Long agentId,
        String message,
        List<ChatTurnAttachment> attachments,
        AgentContextFactory.OperatorHolder operator,
        String modelCode,
        Long[] skillIds,
        List<ChannelToolDef> clientTools)
{
    public ChatTurnRequest
    {
        if (attachments == null)
        {
            attachments = List.of();
        }
        skillIds = skillIds == null ? new Long[0] : java.util.Arrays.stream(skillIds)
                .filter(java.util.Objects::nonNull).distinct().toArray(Long[]::new);
        clientTools = clientTools == null ? List.of() : List.copyOf(clientTools);
    }

    /** 无客户端覆盖项的简化构造:子 agent 与非持久化调用走这条,模型与技能都跟随智能体配置。 */
    public ChatTurnRequest(String runId, String sessionId, Long agentId, String message,
                           List<ChatTurnAttachment> attachments,
                           AgentContextFactory.OperatorHolder operator)
    {
        this(runId, sessionId, agentId, message, attachments, operator, null, null, List.of());
    }

    /** 带模型/技能、无渠道工具。 */
    public ChatTurnRequest(String runId, String sessionId, Long agentId, String message,
                           List<ChatTurnAttachment> attachments,
                           AgentContextFactory.OperatorHolder operator,
                           String modelCode, Long[] skillIds)
    {
        this(runId, sessionId, agentId, message, attachments, operator, modelCode, skillIds, List.of());
    }
}
