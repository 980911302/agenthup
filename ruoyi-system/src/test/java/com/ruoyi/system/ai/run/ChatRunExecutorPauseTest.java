package com.ruoyi.system.ai.run;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.ruoyi.system.ai.memory.ChatMessageMapperTestSupport;
import com.ruoyi.system.ai.memory.ContextBudget;
import com.ruoyi.system.domain.AiChatRun;
import com.ruoyi.system.mapper.AiChatRunMapper;
import com.ruoyi.system.service.IAiChatSessionService;
import com.ruoyi.system.tool.AiToolProperties;
import com.ruoyi.system.tool.ToolBudgetRegistry;
import com.ruoyi.system.tool.ToolConfirmBroker;

import reactor.core.Disposable;

/**
 * 暂停走 shouldContinue 闸门,不 cancel、不 dispose。
 */
class ChatRunExecutorPauseTest
{
    @Test
    void pauseSetsFlagAndShouldContinueBecomesFalseWithoutCancel() throws Exception
    {
        AiChatRunMapper runMapper = mock(AiChatRunMapper.class);
        ChatRunExecutor executor = executor(runMapper);
        Disposable disposable = mock(Disposable.class);
        ChatRunExecutor.ActiveRun active = newActive("run-1", "sess-1");
        ChatMessageMapperTestSupport.setField(active, "disposable", disposable);

        Map<String, Object> runs = new ConcurrentHashMap<String, Object>();
        runs.put("run-1", active);
        ChatMessageMapperTestSupport.setField(executor, "activeRuns", runs);

        assertTrue(ChatRunExecutor.shouldContinue(active));
        executor.pauseLocal("run-1");

        assertFalse(ChatRunExecutor.shouldContinue(active));
        assertFalse(isTerminal(active));
        verify(disposable, never()).dispose();
        verify(runMapper, never()).completeRun(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void pauseWithoutLocalActiveRemembersQueuedRun() throws Exception
    {
        AiChatRunMapper runMapper = mock(AiChatRunMapper.class);
        AiChatRun queued = new AiChatRun();
        queued.setRunId("run-q");
        queued.setStatus(ChatRunStatus.QUEUED);
        when(runMapper.selectAiChatRunById("run-q")).thenReturn(queued);
        ChatRunExecutor executor = executor(runMapper);

        executor.pauseLocal("run-q");

        @SuppressWarnings("unchecked")
        Map<String, Boolean> pending = (Map<String, Boolean>) field(executor, "pauseRequested");
        assertTrue(Boolean.TRUE.equals(pending.get("run-q")));
        verify(runMapper, never()).completeRun(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void controlBrokerPauseDoesNotCancel()
    {
        ChatRunExecutor executor = mock(ChatRunExecutor.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ChatRunInstance instance = mock(ChatRunInstance.class);
        when(instance.id()).thenReturn("i1");
        ChatRunControlBroker broker = new ChatRunControlBroker(redis, executor, instance);

        broker.pause("run-1");

        verify(executor).pauseLocal("run-1");
        verify(executor, never()).cancelLocal(any());
        verify(redis).convertAndSend(org.mockito.ArgumentMatchers.eq(ChatRunControlBroker.CHANNEL),
                org.mockito.ArgumentMatchers.contains("\"pause\""));
    }

    @Test
    void acceptRemotePauseSkipsSelfAndHandlesForeign()
    {
        ChatRunExecutor executor = mock(ChatRunExecutor.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ChatRunInstance instance = mock(ChatRunInstance.class);
        when(instance.id()).thenReturn("i1");
        ChatRunControlBroker broker = new ChatRunControlBroker(redis, executor, instance);

        broker.acceptRemote("{\"action\":\"pause\",\"runId\":\"run-1\",\"originInstanceId\":\"i1\"}");
        verifyNoInteractions(executor);

        broker.acceptRemote("{\"action\":\"pause\",\"runId\":\"run-1\",\"originInstanceId\":\"other\"}");
        verify(executor).pauseLocal("run-1");
        verify(executor, never()).cancelLocal(any());
    }

    @Test
    void pauseExitDoesNotSucceedAndWritesPausedByUser()
    {
        AiChatRunMapper runMapper = mock(AiChatRunMapper.class);
        ChatTurnRunner runner = mock(ChatTurnRunner.class);
        ChatRunEventBroker broker = mock(ChatRunEventBroker.class);
        AiChatRun persisted = new AiChatRun();
        persisted.setRunId("run-1");
        persisted.setStatus(ChatRunStatus.QUEUED);
        when(runMapper.selectAiChatRunById("run-1")).thenReturn(persisted);
        when(runMapper.markRunning(eq("run-1"), any(), any())).thenReturn(1);
        when(runMapper.completeRun(any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(runner.run(any(), any(), any())).thenAnswer(inv -> {
            ChatTurnCallbacks cb = inv.getArgument(2);
            cb.onSucceeded("ok", null, null, 8L, 9L);
            return mock(Disposable.class);
        });

        ChatRunExecutor executor = executor(runMapper, runner, broker, inlinePool());
        executor.pauseLocal("run-1");
        executor.start(new ChatRunCommand("run-1", "sess-1", 1L, "hi", List.of(), 1L, "u", false));

        verify(runMapper).completeRun(eq("run-1"), eq(ChatRunStatus.CANCELLED),
                eq("PAUSED_BY_USER"), eq("已按用户请求暂停"), eq(8L), eq(9L), any());
        verify(runMapper, never()).markFinalizing(anyString(), any(), any());
        verify(runMapper, never()).completeRun(eq("run-1"), eq(ChatRunStatus.SUCCEEDED),
                any(), any(), any(), any(), any());
    }

    @Test
    void failAfterPauseWritesPausedByUserNotModelFailure()
    {
        AiChatRunMapper runMapper = mock(AiChatRunMapper.class);
        ChatTurnRunner runner = mock(ChatTurnRunner.class);
        AiChatRun persisted = new AiChatRun();
        persisted.setRunId("run-1");
        persisted.setStatus(ChatRunStatus.QUEUED);
        when(runMapper.selectAiChatRunById("run-1")).thenReturn(persisted);
        when(runMapper.markRunning(eq("run-1"), any(), any())).thenReturn(1);
        when(runMapper.completeRun(any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(runner.run(any(), any(), any())).thenAnswer(inv -> {
            ChatTurnCallbacks cb = inv.getArgument(2);
            cb.onFailed(new RuntimeException("idle timeout"));
            return mock(Disposable.class);
        });

        ChatRunExecutor executor = executor(runMapper, runner, mock(ChatRunEventBroker.class), inlinePool());
        executor.pauseLocal("run-1");
        executor.start(new ChatRunCommand("run-1", "sess-1", 1L, "hi", List.of(), 1L, "u", false));

        verify(runMapper).completeRun(eq("run-1"), eq(ChatRunStatus.CANCELLED),
                eq("PAUSED_BY_USER"), eq("已按用户请求暂停"), isNull(), isNull(), any());
        verify(runMapper, never()).completeRun(eq("run-1"), eq(ChatRunStatus.FAILED),
                eq("MODEL_EXECUTION_FAILED"), any(), any(), any(), any());
    }

    @Test
    void unpausedOnSucceededStillSucceeds()
    {
        AiChatRunMapper runMapper = mock(AiChatRunMapper.class);
        ChatTurnRunner runner = mock(ChatTurnRunner.class);
        AiChatRun persisted = new AiChatRun();
        persisted.setRunId("run-1");
        persisted.setStatus(ChatRunStatus.QUEUED);
        when(runMapper.selectAiChatRunById("run-1")).thenReturn(persisted);
        when(runMapper.markRunning(eq("run-1"), any(), any())).thenReturn(1);
        when(runMapper.markFinalizing(eq("run-1"), any(), any())).thenReturn(1);
        when(runMapper.completeRun(any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(runner.run(any(), any(), any())).thenAnswer(inv -> {
            ChatTurnCallbacks cb = inv.getArgument(2);
            cb.onSucceeded("ok", null, null, 8L, 9L);
            return mock(Disposable.class);
        });

        ChatRunExecutor executor = executor(runMapper, runner, mock(ChatRunEventBroker.class), inlinePool());
        executor.start(new ChatRunCommand("run-1", "sess-1", 1L, "hi", List.of(), 1L, "u", false));

        verify(runMapper).markFinalizing(eq("run-1"), any(), any());
        verify(runMapper).completeRun(eq("run-1"), eq(ChatRunStatus.SUCCEEDED),
                isNull(), isNull(), eq(8L), eq(9L), any());
    }

    @Test
    void cancelStillCancelledByUser()
    {
        AiChatRunMapper runMapper = mock(AiChatRunMapper.class);
        AiChatRun run = new AiChatRun();
        run.setRunId("run-1");
        run.setSessionId("sess-1");
        run.setStatus(ChatRunStatus.RUNNING);
        when(runMapper.selectAiChatRunById("run-1")).thenReturn(run);
        when(runMapper.completeRun(any(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        ChatRunExecutor executor = executor(runMapper);
        executor.cancelLocal("run-1");

        verify(runMapper).completeRun(eq("run-1"), eq(ChatRunStatus.CANCELLED),
                eq("CANCELLED_BY_USER"), eq("运行已取消"), any(), any(), any());
    }

    private static ThreadPoolTaskExecutor inlinePool()
    {
        ThreadPoolTaskExecutor pool = mock(ThreadPoolTaskExecutor.class);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(pool).execute(any(Runnable.class));
        return pool;
    }

    private static ChatRunExecutor executor(AiChatRunMapper runMapper,
            ChatTurnRunner runner, ChatRunEventBroker broker, ThreadPoolTaskExecutor pool)
    {
        return new ChatRunExecutor(
                pool,
                mock(ScheduledExecutorService.class),
                120_000L,
                1800L,
                runner,
                runMapper,
                broker,
                mock(ChatRunInstance.class),
                mock(IAiChatSessionService.class),
                mock(com.ruoyi.system.ai.agent.AgentAssemblyCache.class),
                mock(ContextBudget.class),
                mock(AiToolProperties.class),
                mock(ToolBudgetRegistry.class),
                mock(ToolConfirmBroker.class),
                // ChatRunExecutor 新增依赖:ensureSessionArtifacts 里只调 resolveRoot 且返回值不用、
                // 外层还包了 try/catch,裸 mock 足够,不需要打桩
                mock(com.ruoyi.system.tool.WorkspaceScopeService.class));
    }

    private static ChatRunExecutor executor(AiChatRunMapper runMapper)
    {
        return new ChatRunExecutor(
                mock(ThreadPoolTaskExecutor.class),
                mock(ScheduledExecutorService.class),
                120_000L,
                1800L,
                mock(ChatTurnRunner.class),
                runMapper,
                mock(ChatRunEventBroker.class),
                mock(ChatRunInstance.class),
                mock(IAiChatSessionService.class),
                mock(com.ruoyi.system.ai.agent.AgentAssemblyCache.class),
                mock(ContextBudget.class),
                mock(AiToolProperties.class),
                mock(ToolBudgetRegistry.class),
                mock(ToolConfirmBroker.class),
                // ChatRunExecutor 新增依赖:ensureSessionArtifacts 里只调 resolveRoot 且返回值不用、
                // 外层还包了 try/catch,裸 mock 足够,不需要打桩
                mock(com.ruoyi.system.tool.WorkspaceScopeService.class));
    }

    private static ChatRunExecutor.ActiveRun newActive(String runId, String sessionId) throws Exception
    {
        Constructor<?> ctor = ChatRunExecutor.ActiveRun.class
                .getDeclaredConstructor(String.class, String.class);
        ctor.setAccessible(true);
        return (ChatRunExecutor.ActiveRun) ctor.newInstance(runId, sessionId);
    }

    private static boolean isTerminal(Object active) throws Exception
    {
        java.lang.reflect.Method m = ChatRunExecutor.ActiveRun.class.getDeclaredMethod("isTerminal");
        m.setAccessible(true);
        return (Boolean) m.invoke(active);
    }

    private static Object field(Object target, String name) throws Exception
    {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
}
