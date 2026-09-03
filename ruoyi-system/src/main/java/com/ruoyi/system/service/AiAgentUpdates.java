package com.ruoyi.system.service;

import com.ruoyi.system.domain.AiAgent;

/**
 * 智能体 REST PUT 的空值约定。
 * <p>
 * 管理端是整单保存:下拉清空后 JSON 里是 {@code null}。Mapper 的
 * {@code <if test="modelCode != null">} 会跳过 SET,旧绑定残留。
 * 把 null 收成空串,让 UPDATE 真正写出清空。
 * <p>
 * AgentMetaTools 走局部更新,只填要改的字段,不能走这里。
 */
public final class AiAgentUpdates
{
    private AiAgentUpdates()
    {
    }

    public static void applyRestPutNulls(AiAgent agent)
    {
        if (agent == null)
        {
            return;
        }
        if (agent.getModelCode() == null)
        {
            agent.setModelCode("");
        }
        if (agent.getImageModelCode() == null)
        {
            agent.setImageModelCode("");
        }
        if (agent.getVideoModelCode() == null)
        {
            agent.setVideoModelCode("");
        }
        if (agent.getTtsModelCode() == null)
        {
            agent.setTtsModelCode("");
        }
    }
}
