package com.ruoyi.system.ai.run;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
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
 * 取消必须把 worker 线程还回线程池 —— 哪怕它卡在<b>同步前奏</b>里。
 *
 * <p>{@code ChatTurnRunner.run()} 返回 Flux 之前那一段(装配 / 上下文压缩 / 长期记忆检索
 * embedding)是同步跑在 worker 上的。卡在那里时 {@code disposable} 还是 null,
 * {@code dispose()} 什么也断不掉,{@code signalTerminal()} 又是冲着一个还没人在等的 latch 打的:
 * 库里改成了 CANCELLED、界面解了锁,worker 却一直占着 {@code chatRunTaskExecutor} 的核心线程。
 * 而它此时已被移出 {@code activeRuns},连 {@code enforceMaxDuration} 的兜底也扫不到。
 *
 * <p>线上就是这么死的:core-pool-size=4,同一句话取消重发四次,四个核心线程全漏光,
 * 之后任何会话发任何消息都只能排在 QUEUED 里,重启前不会好。
 */
class ChatRunExecutorCancelWakeupTest
{
    /** 取消卡在同步前奏里的 run:必须中断 worker,否则线程永远回不来。 */
    @Test
    void cancelLocal_wakesWorkerBlockedBeforeAwait() throws Exception
    {
        ChatRunExecutor executor = executor();
        Object active = newActive("run-1", "sess-1");

        // worker 卡在前奏里:还没 setDisposable、更没进 awaitTerminal
        AtomicBoolean interrupted = new AtomicBoolean(false);
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            parked.countDown();
            try
            {
                // 模拟无超时的 embedding 调用:除了中断没有别的出路
                Thread.sleep(60_000L);
            }
            catch (InterruptedException e)
            {
                interrupted.set(true);
            }
            released.countDown();
        }, "fake-chat-run");
        worker.setDaemon(true);
        worker.start();
        assertTrue(parked.await(5, TimeUnit.SECONDS), "worker 未能进入阻塞态");

        invoke(active, "markWorker", Thread.class, worker);
        Map<String, Object> runs = new ConcurrentHashMap<>();
        runs.put("run-1", active);
        ChatMessageMapperTestSupport.setField(executor, "activeRuns", runs);

        executor.cancelLocal("run-1");

        assertTrue(released.await(5, TimeUnit.SECONDS),
                "取消后 worker 必须被唤醒并退出,否则核心线程被永久占住");
        assertTrue(interrupted.get(), "唤醒手段是中断:dispose 断不掉同步前奏");
        assertTrue((Boolean) invoke(active, "consumeWakeupInterrupt"),
                "唤醒用的中断要打上标记,收尾时好把中断位清掉,不能带回线程池");
    }

    /** 已经进入 awaitTerminal 的 worker 不该被中断:那时 signalTerminal 就唤得动它。 */
    @Test
    void wakeBlockedWorker_skipsWorkerAlreadyAwaiting() throws Exception
    {
        Object active = newActive("run-2", "sess-2");
        AtomicBoolean interrupted = new AtomicBoolean(false);
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            parked.countDown();
            try
            {
                Thread.sleep(1_500L);
            }
            catch (InterruptedException e)
            {
                interrupted.set(true);
            }
            done.countDown();
        }, "awaiting-worker");
        worker.setDaemon(true);
        worker.start();
        assertTrue(parked.await(5, TimeUnit.SECONDS));

        invoke(active, "markWorker", Thread.class, worker);
        invoke(active, "beginAwait");
        invoke(active, "wakeBlockedWorker");

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertFalse(interrupted.get(), "已在 awaitTerminal 的 worker 不该挨中断");
        assertFalse((Boolean) invoke(active, "consumeWakeupInterrupt"), "没中断就不该留标记");
    }

    /** 绝不自断:终态是在 worker 自己线程上取得的(execute 的 catch、interruptWorker)。 */
    @Test
    void wakeBlockedWorker_neverInterruptsCaller() throws Exception
    {
        Object active = newActive("run-3", "sess-3");
        invoke(active, "markWorker", Thread.class, Thread.currentThread());
        invoke(active, "wakeBlockedWorker");
        assertFalse(Thread.currentThread().isInterrupted(), "不能中断调用者自己");
        assertFalse((Boolean) invoke(active, "consumeWakeupInterrupt"));
    }

    /** 标记只会被取走一次,避免重复清中断位。 */
    @Test
    void consumeWakeupInterrupt_isOneShot() throws Exception
    {
        Object active = newActive("run-4", "sess-4");
        Thread idle = new Thread(() -> { }, "idle");
        invoke(active, "markWorker", Thread.class, idle);
        invoke(active, "wakeBlockedWorker");
        assertTrue((Boolean) invoke(active, "consumeWakeupInterrupt"));
        assertFalse((Boolean) invoke(active, "consumeWakeupInterrupt"));
    }

    // ---------- helpers ----------

    private ChatRunExecutor executor()
    {
        AiChatRunMapper runMapper = mock(AiChatRunMapper.class);
        AiChatRun run = new AiChatRun();
        run.setRunId("run-1");
        run.setSessionId("sess-1");
        run.setStatus(ChatRunStatus.RUNNING);
        when(runMapper.selectAiChatRunById(any())).thenReturn(run);
        when(runMapper.completeRun(any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        return new ChatRunExecutor(
                mock(ThreadPoolTaskExecutor.class),
                mock(ScheduledExecutorService.class),
                120_000L,
                1_800L,
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
                mock(com.ruoyi.system.tool.WorkspaceScopeService.class));
    }

    private static Object newActive(String runId, String sessionId) throws Exception
    {
        Constructor<?> ctor = ChatRunExecutor.ActiveRun.class
                .getDeclaredConstructor(String.class, String.class);
        ctor.setAccessible(true);
        Object active = ctor.newInstance(runId, sessionId);
        // 前奏卡死的本质:Flux 还没订阅,disposable 恒为 null
        ChatMessageMapperTestSupport.setField(active, "disposable", (Disposable) null);
        return active;
    }

    private static Object invoke(Object target, String name) throws Exception
    {
        Method m = target.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(target);
    }

    private static Object invoke(Object target, String name, Class<?> argType, Object arg)
            throws Exception
    {
        Method m = target.getClass().getDeclaredMethod(name, argType);
        m.setAccessible(true);
        return m.invoke(target, arg);
    }
}
