package com.ruoyi.system.ai.agent;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ruoyi.system.ai.AiConfigChangedEvent;
import com.ruoyi.system.domain.AiAgent;
import com.ruoyi.system.domain.AiModel;
import com.ruoyi.system.domain.AiTool;
import com.ruoyi.system.service.IAiAgentService;
import com.ruoyi.system.service.IAiModelService;
import com.ruoyi.system.service.IAiSkillService;
import com.ruoyi.system.service.IAiToolService;

/** 装配行缓存的三条不变量:TTL 内零重查、事件即时失效、查不到不缓存。 */
class AgentAssemblyCacheTest
{
    private IAiAgentService agentService;
    private IAiToolService toolService;
    private IAiSkillService skillService;
    private IAiModelService modelService;
    private AgentAssemblyCache cache;

    @BeforeEach
    void setUp()
    {
        agentService = mock(IAiAgentService.class);
        toolService = mock(IAiToolService.class);
        skillService = mock(IAiSkillService.class);
        modelService = mock(IAiModelService.class);
        cache = new AgentAssemblyCache(agentService, toolService, skillService, modelService);
    }

    @AfterEach
    void restoreTtl()
    {
        AgentAssemblyCache.TTL_MS = 30_000L;
    }

    @Test
    void withinTtl_secondLookupHitsCacheWithoutRequery()
    {
        AiAgent agent = new AiAgent();
        when(agentService.selectAiAgentById(1L)).thenReturn(agent);

        assertSame(agent, cache.agent(1L));
        assertSame(agent, cache.agent(1L));
        verify(agentService, times(1)).selectAiAgentById(1L);
    }

    @Test
    void afterTtl_requeries()
    {
        AgentAssemblyCache.TTL_MS = 1L;
        AiTool tool = new AiTool();
        when(toolService.selectAiToolById(5L)).thenReturn(tool);

        assertSame(tool, cache.tool(5L));
        sleepQuietly(20L);
        assertSame(tool, cache.tool(5L));
        verify(toolService, times(2)).selectAiToolById(5L);
    }

    /** 事件失效按类别精准路由:失效工具不影响已缓存的智能体。 */
    @Test
    void configChangedEvent_invalidatesOnlyItsKind()
    {
        AiAgent agent = new AiAgent();
        AiTool tool = new AiTool();
        when(agentService.selectAiAgentById(1L)).thenReturn(agent);
        when(toolService.selectAiToolById(5L)).thenReturn(tool);

        cache.agent(1L);
        cache.tool(5L);

        cache.onConfigChanged(new AiConfigChangedEvent(AiConfigChangedEvent.Kind.TOOL, 5L));

        assertSame(tool, cache.tool(5L));
        verify(toolService, times(2)).selectAiToolById(5L);
        verify(agentService, times(1)).selectAiAgentById(1L);
    }

    /** MODEL 事件整表失效:modelCode 本身可能被改,按 id 失效会漏旧 code 条目。 */
    @Test
    void modelEvent_clearsAllModelEntries()
    {
        AiModel a = new AiModel();
        AiModel b = new AiModel();
        when(modelService.selectByModelCode("m-a")).thenReturn(a);
        when(modelService.selectByModelCode("m-b")).thenReturn(b);

        cache.modelByCode("m-a");
        cache.modelByCode("m-b");

        cache.onConfigChanged(new AiConfigChangedEvent(AiConfigChangedEvent.Kind.MODEL, 77L));

        assertSame(a, cache.modelByCode("m-a"));
        assertSame(b, cache.modelByCode("m-b"));
        verify(modelService, times(2)).selectByModelCode("m-a");
        verify(modelService, times(2)).selectByModelCode("m-b");
    }

    /** 查不到不缓存:删行后的穿透查询在 TTL 内重查,恢复后立即可见。 */
    @Test
    void missingRow_isNotCached()
    {
        AiTool tool = new AiTool();
        when(toolService.selectAiToolById(9L)).thenReturn(null, tool);

        assertNull(cache.tool(9L));
        assertSame(tool, cache.tool(9L));
        verify(toolService, times(2)).selectAiToolById(9L);
    }

    @Test
    void blankKeys_shortCircuitWithoutQuery()
    {
        assertNull(cache.modelByCode(" "));
        assertNull(cache.agent(null));
        assertNull(cache.tool(null));
        assertNull(cache.skill(null));
        verifyNoMoreInteractionsOnAll();
    }

    private void verifyNoMoreInteractionsOnAll()
    {
        verify(agentService, times(0)).selectAiAgentById(org.mockito.ArgumentMatchers.any());
        verify(toolService, times(0)).selectAiToolById(org.mockito.ArgumentMatchers.any());
        verify(skillService, times(0)).selectAiSkillById(org.mockito.ArgumentMatchers.any());
        verify(modelService, times(0)).selectByModelCode(org.mockito.ArgumentMatchers.any());
    }

    private static void sleepQuietly(long ms)
    {
        try
        {
            Thread.sleep(ms);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }
}
