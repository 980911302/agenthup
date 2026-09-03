package com.ruoyi.system.ai.run;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.ruoyi.system.ai.memory.ChatMessageMapperTestSupport;
import com.ruoyi.system.ai.memory.ContextBudget;
import com.ruoyi.system.mapper.AiChatRunMapper;
import com.ruoyi.system.service.IAiChatSessionService;
import com.ruoyi.system.tool.AiToolProperties;
import com.ruoyi.system.tool.ToolBudgetRegistry;
import com.ruoyi.system.tool.ToolConfirmBroker;

import reactor.core.Disposable;

/**
 * Run 总时长兜底:心跳只覆盖「进程死亡」,stale 扫描永远不触发「线程活着但永久阻塞」的 Run。
 * 本兜底按 ActiveRun 的本地起始时间强制收敛,防会话活动锁(uk_ai_chat_run_active)被永久占用。
 */
class ChatRunExecutorMaxDurationTest
{
    @Test
    void runExceedingMaxDuration_isForceTerminated() throws Exception
    {
        AiChatRunMapper runMapper = mock(AiChatRunMapper.class);
        when(runMapper.completeRun(any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        ChatRunEventBroker broker = mock(ChatRunEventBroker.class);
        ToolBudgetRegistry budgetRegistry = mock(ToolBudgetRegistry.class);
        ChatRunExecutor executor = executor(runMapper, broker, budgetRegistry, 1L);

        Disposable stuck = mock(Disposable.class);
        Object active = backdatedActive("run-1", "sess-1", stuck);
        Map<String, Object> runs = new ConcurrentHashMap<>();
        runs.put("run-1", active);
        ChatMessageMapperTestSupport.setField(executor, "activeRuns", runs);

        executor.enforceMaxDuration();

        verify(stuck).dispose();
        verify(budgetRegistry).close("sess-1");
        verify(runMapper).completeRun(eq("run-1"), eq(ChatRunStatus.INTERRUPTED),
                eq("RUN_DURATION_EXCEEDED"), contains("最大时长"), any(), any(), any());
        verify(broker).publish(eq("run-1"), eq("sess-1"), anyString());
        assertTrue(runs.isEmpty(), "超期 Run 应从活动表移除,释放会话活动锁");
    }

    /** 未超期的 Run 不受影响;关掉兜底(<=0)时什么都不做。 */
    @Test
    void runWithinMaxDuration_orDisabled_staysUntouched() throws Exception
    {
        AiChatRunMapper runMapper = mock(AiChatRunMapper.class);
        ChatRunEventBroker broker = mock(ChatRunEventBroker.class);
        ToolBudgetRegistry budgetRegistry = mock(ToolBudgetRegistry.class);

        ChatRunExecutor executor = executor(runMapper, broker, budgetRegistry, 3_600L);
        Map<String, Object> runs = new ConcurrentHashMap<>();
        runs.put("run-ok", freshActive("run-ok", "sess-ok"));
        ChatMessageMapperTestSupport.setField(executor, "activeRuns", runs);
        executor.enforceMaxDuration();
        assertTrue(runs.containsKey("run-ok"));
        verifyNoInteractions(runMapper, broker, budgetRegistry);

        // <=0 关闭兜底:即使已超期也不动
        ChatRunExecutor off = executor(runMapper, broker, budgetRegistry, 0L);
        Map<String, Object> stale = new ConcurrentHashMap<>();
        stale.put("run-off", backdatedActive("run-off", "sess-off", mock(Disposable.class)));
        ChatMessageMapperTestSupport.setField(off, "activeRuns", stale);
        off.enforceMaxDuration();
        assertTrue(stale.containsKey("run-off"));
        verifyNoInteractions(runMapper, broker, budgetRegistry);
    }

    // ---------- helpers ----------

    private ChatRunExecutor executor(AiChatRunMapper runMapper, ChatRunEventBroker broker,
                                     ToolBudgetRegistry budgetRegistry, long maxDurationSeconds)
    {
        return new ChatRunExecutor(
                mock(ThreadPoolTaskExecutor.class),
                mock(ScheduledExecutorService.class),
                120_000L,
                maxDurationSeconds,
                mock(ChatTurnRunner.class),
                runMapper,
                broker,
                mock(ChatRunInstance.class),
                mock(IAiChatSessionService.class),
                mock(com.ruoyi.system.ai.agent.AgentAssemblyCache.class),
                mock(ContextBudget.class),
                mock(AiToolProperties.class),
                budgetRegistry,
                mock(ToolConfirmBroker.class),
                // ChatRunExecutor 新增依赖:ensureSessionArtifacts 里只调 resolveRoot 且返回值不用、
                // 外层还包了 try/catch,裸 mock 足够,不需要打桩
                mock(com.ruoyi.system.tool.WorkspaceScopeService.class));
    }

    /** 构造 startedAtMs 已回拨 60s 的 ActiveRun,模拟超期未收敛的活动运行。 */
    private Object backdatedActive(String runId, String sessionId, Disposable disposable)
            throws Exception
    {
        Object active = newActive(runId, sessionId);
        ChatMessageMapperTestSupport.setField(active, "startedAtMs",
                System.currentTimeMillis() - 60_000L);
        ChatMessageMapperTestSupport.setField(active, "disposable", disposable);
        return active;
    }

    private Object freshActive(String runId, String sessionId) throws Exception
    {
        return newActive(runId, sessionId);
    }

    private static Object newActive(String runId, String sessionId) throws Exception
    {
        Constructor<?> ctor = ChatRunExecutor.ActiveRun.class
                .getDeclaredConstructor(String.class, String.class);
        ctor.setAccessible(true);
        return ctor.newInstance(runId, sessionId);
    }
}
