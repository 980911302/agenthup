package com.ruoyi.system.ai.run;

import java.util.List;

/** API 层提交给运行服务的创建命令。 */
public record ChatRunCreateCommand(
        String sessionId,
        Long agentId,
        String message,
        List<ChatRunAttachment> attachments,
        String clientRequestId,
        Long userId,
        String username,
        boolean admin,
        List<Long> kbIds,
        Long modelId,
        List<Long> skillIds,
        Long projectId,
        // 首轮声明:新会话在 declare 之前就发了消息,这里补写客户端工具清单。
        // 不带则沿用会话已存的清单。见 ChatRunService.create 的 declareIfFirstTurn。
        String clientType,
        String capabilitiesVersion,
        String clientToolsJson)
{
    public ChatRunCreateCommand
    {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        // null 表示前端未传(不动会话已有知识库)；空列表表示清空；其余防可变。
        kbIds = kbIds == null ? null : List.copyOf(kbIds);
        skillIds = skillIds == null ? List.of() : List.copyOf(skillIds);
    }
}
