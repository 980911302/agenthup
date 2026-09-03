package com.ruoyi.system.ai.agent;

import com.ruoyi.system.domain.AiAgent;

/**
 * 智能体可见性策略。
 * <p>私有智能体仅创建者可见；标记为公共的智能体对所有登录用户可见。
 */
public final class AiAgentAccessPolicy
{
    private AiAgentAccessPolicy()
    {
    }

    public static boolean canRead(AiAgent agent, String username)
    {
        if (agent == null)
        {
            return false;
        }
        if ("1".equals(agent.getIsPublic()))
        {
            return true;
        }
        return username != null && username.equals(agent.getCreateBy());
    }

    /** 给 Mapper 设置当前登录用户的列表范围，调用方传入值不可信。 */
    public static void applyListScope(AiAgent query, String username)
    {
        if (query != null)
        {
            query.setAccessUserName(username);
        }
    }
}
