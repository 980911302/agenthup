package com.ruoyi.system.ai.agent;

import org.junit.jupiter.api.Test;
import com.ruoyi.system.domain.AiAgent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiAgentAccessPolicyTest
{
    @Test
    void privateAgentIsVisibleOnlyToItsCreator()
    {
        AiAgent agent = agent("alice", "0");

        assertTrue(AiAgentAccessPolicy.canRead(agent, "alice"));
        assertFalse(AiAgentAccessPolicy.canRead(agent, "bob"));
        assertFalse(AiAgentAccessPolicy.canRead(agent, null));
    }

    @Test
    void publicAgentIsVisibleToEveryLoggedInUser()
    {
        assertTrue(AiAgentAccessPolicy.canRead(agent("alice", "1"), "bob"));
    }

    private static AiAgent agent(String createBy, String isPublic)
    {
        AiAgent agent = new AiAgent();
        agent.setCreateBy(createBy);
        agent.setIsPublic(isPublic);
        return agent;
    }
}
