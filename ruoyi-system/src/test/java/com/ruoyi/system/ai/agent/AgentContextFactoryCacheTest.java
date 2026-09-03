package com.ruoyi.system.ai.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ruoyi.system.domain.AiAgent;
import com.ruoyi.system.domain.AiSkill;
import com.ruoyi.system.domain.AiTool;
import com.ruoyi.system.service.IAiAgentService;
import com.ruoyi.system.service.IAiModelService;
import com.ruoyi.system.service.IAiSkillService;
import com.ruoyi.system.service.IAiToolService;

/**
 * 装配去重锁:此前同一轮里 resolveTools 与 buildWorkspaceToolsSection 各查一遍工具行、
 * buildSkillSection 与 collectBoundSkills 各查一遍技能行(2N+2S 点查)。
 * 接入 AgentAssemblyCache 后,同一 TTL 窗口内每个 id 只允许落库一次。
 */
class AgentContextFactoryCacheTest
{
    private IAiAgentService agentService;
    private IAiToolService toolService;
    private IAiSkillService skillService;
    private AgentAssemblyCache cache;
    private AgentContextFactory factory;

    @BeforeEach
    void setUp() throws Exception
    {
        agentService = mock(IAiAgentService.class);
        toolService = mock(IAiToolService.class);
        skillService = mock(IAiSkillService.class);
        cache = new AgentAssemblyCache(agentService, toolService, skillService,
                mock(IAiModelService.class));
        factory = new AgentContextFactory();
        com.ruoyi.system.ai.memory.ChatMessageMapperTestSupport.setField(factory, "aiToolService", toolService);
        com.ruoyi.system.ai.memory.ChatMessageMapperTestSupport.setField(factory, "aiSkillService", skillService);
        com.ruoyi.system.ai.memory.ChatMessageMapperTestSupport.setField(factory, "assemblyCache", cache);
    }

    /**
     * 同一 agent 的工具行在「工具段 + 工作区段」两个消费者之间只允许查库一次;
     * 技能行在「技能指引段 + loadSkill 绑定」之间同理。
     */
    @Test
    void toolAndSkillRows_queriedOncePerIdWithinTtl() throws Exception
    {
        AiAgent agent = new AiAgent();
        agent.setAgentId(7L);
        agent.setToolIds(new Long[] { 11L, 12L });
        agent.setSkillIds(new Long[] { 21L, 22L });
        when(agentService.selectAiAgentById(7L)).thenReturn(agent);

        AiTool toolA = tool(11L, "read", "0");
        AiTool toolB = tool(12L, "bash", "0");
        when(toolService.selectAiToolById(11L)).thenReturn(toolA);
        when(toolService.selectAiToolById(12L)).thenReturn(toolB);

        AiSkill skillA = skill(21L, "生图技能", "0");
        AiSkill skillB = skill(22L, "TTS技能", "0");
        when(skillService.selectAiSkillById(21L)).thenReturn(skillA);
        when(skillService.selectAiSkillById(22L)).thenReturn(skillB);

        // 两遍「agent 行 + 工具段 + 工作区段 + 技能段 + loadSkill 绑定」= 原本 2×(1+2N+2S) 次点查
        for (int round = 0; round < 2; round++)
        {
            cache.agent(7L);
            factory.buildWorkspaceToolsSection(agent);
            factory.buildSkillSection(agent);
            SkillLoadToolCallback.collectBoundSkills(agent.getSkillIds(), cache::skill);
            // resolveTools 的查询路径等价于逐 id 取行,这里直接经缓存触发同一入口
            cache.tool(11L);
            cache.tool(12L);
        }

        verify(toolService, times(1)).selectAiToolById(11L);
        verify(toolService, times(1)).selectAiToolById(12L);
        verify(skillService, times(1)).selectAiSkillById(21L);
        verify(skillService, times(1)).selectAiSkillById(22L);
        verify(agentService, times(1)).selectAiAgentById(7L);

        String skillSection = factory.buildSkillSection(agent);
        assertTrue(skillSection.contains("生图技能"), "技能段应含技能名: " + skillSection);
        String workspaceSection = factory.buildWorkspaceToolsSection(agent);
        assertTrue(workspaceSection.contains("read"), "工作区段应含工具 code: " + workspaceSection);
    }

    private static AiTool tool(Long id, String code, String status)
    {
        AiTool tool = new AiTool();
        tool.setToolId(id);
        tool.setToolCode(code);
        tool.setStatus(status);
        return tool;
    }

    private static AiSkill skill(Long id, String name, String status)
    {
        AiSkill skill = new AiSkill();
        skill.setSkillId(id);
        skill.setSkillName(name);
        skill.setDescription(name + " 的适用场景");
        skill.setStatus(status);
        return skill;
    }
}
