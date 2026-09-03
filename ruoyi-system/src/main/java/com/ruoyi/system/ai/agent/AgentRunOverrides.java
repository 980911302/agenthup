package com.ruoyi.system.ai.agent;

import java.util.Arrays;
import java.util.List;
import com.ruoyi.system.tool.channel.ChannelToolDef;

/**
 * 客户端本轮对默认智能体的可控覆盖项。
 *
 * <p>工具、角色和子智能体仍由智能体本身决定；这里只允许经过服务端校验的聊天模型
 * 与技能快照、以及会话行上的渠道工具清单进入执行链路。</p>
 */
public record AgentRunOverrides(String modelCode, Long[] skillIds, List<ChannelToolDef> clientTools)
{
    public AgentRunOverrides
    {
        modelCode = modelCode == null || modelCode.isBlank() ? null : modelCode.trim();
        skillIds = skillIds == null ? null : Arrays.stream(skillIds)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toArray(Long[]::new);
        clientTools = clientTools == null ? List.of() : List.copyOf(clientTools);
    }

    public AgentRunOverrides(String modelCode, Long[] skillIds)
    {
        this(modelCode, skillIds, List.of());
    }

    public static AgentRunOverrides defaults()
    {
        return new AgentRunOverrides(null, null, List.of());
    }
}
