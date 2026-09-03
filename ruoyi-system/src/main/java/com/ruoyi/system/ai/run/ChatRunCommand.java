package com.ruoyi.system.ai.run;

import java.util.List;

/**
 * 已完成鉴权、可跨线程执行的对话运行命令。
 *
 * <p>{@code modelCode} / {@code skillIds} 是客户端本轮的覆盖项快照,在 {@code ChatRunService.create}
 * 里算好后随命令跨线程传递,异步执行期间不受智能体配置被改动影响。其中 {@code skillIds}
 * <b>只用于注入当轮 user 消息</b>,不参与装配 ——
 * 理由见 {@code AgentContextFactory#buildTurnSkillSection}。
 */
public record ChatRunCommand(
        String runId,
        String sessionId,
        Long agentId,
        String message,
        List<ChatRunAttachment> attachments,
        Long userId,
        String username,
        boolean admin,
        String modelCode,
        Long[] skillIds)
{
    public ChatRunCommand
    {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        skillIds = skillIds == null ? new Long[0] : java.util.Arrays.stream(skillIds)
                .filter(java.util.Objects::nonNull).distinct().toArray(Long[]::new);
    }

    /** 无客户端覆盖项的简化构造:模型与技能都跟随智能体配置。 */
    public ChatRunCommand(String runId, String sessionId, Long agentId, String message,
                          List<ChatRunAttachment> attachments, Long userId, String username,
                          boolean admin)
    {
        this(runId, sessionId, agentId, message, attachments, userId, username, admin, null, null);
    }
}
