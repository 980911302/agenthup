package com.ruoyi.system.ai.run;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.system.ai.agent.AgentContextFactory;
import com.ruoyi.system.ai.event.ChatEventSink;
import com.ruoyi.system.ai.memory.ContextBudget;
import com.ruoyi.system.ai.metering.LlmCallCollector;
import com.ruoyi.system.ai.sse.ChatEventJson;
import com.ruoyi.system.domain.AiAgent;
import com.ruoyi.system.domain.AiChatRun;
import com.ruoyi.system.domain.AiModel;
import com.ruoyi.system.mapper.AiChatRunMapper;
import com.ruoyi.system.service.IAiChatSessionService;
import com.ruoyi.system.tool.AiToolProperties;
import com.ruoyi.system.tool.ToolBudgetRegistry;
import com.ruoyi.system.tool.ToolConfirmBroker;
import com.ruoyi.system.tool.WorkspaceScopeService;
import com.ruoyi.system.tool.WorkspaceSandbox;
import com.ruoyi.system.tool.channel.ChannelToolBroker;
import com.ruoyi.system.tool.channel.ChannelToolDef;
import com.ruoyi.system.tool.channel.ChannelToolSchemas;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 独立于 HTTP/WebSocket 连接的 Agent 运行器。
 *
 * <p>浏览器断开只会失去实时订阅；本类持有模型订阅并持续落库、发事件，直到状态机进入终态。</p>
 */
@Component
public class ChatRunExecutor
{
    private static final Logger log = LoggerFactory.getLogger(ChatRunExecutor.class);

    private final ThreadPoolTaskExecutor taskExecutor;
    private final ScheduledExecutorService scheduler;
    private final long staleTimeoutMs;
    private final long maxDurationSeconds;
    private final ChatTurnRunner chatTurnRunner;
    private final AiChatRunMapper runMapper;
    private final ChatRunEventBroker eventBroker;
    private final ChatRunInstance instance;
    private final IAiChatSessionService sessionService;
    private final com.ruoyi.system.ai.agent.AgentAssemblyCache assemblyCache;
    private final ContextBudget contextBudget;
    private final AiToolProperties toolProperties;
    private final ToolBudgetRegistry budgetRegistry;
    private final ToolConfirmBroker confirmBroker;
    private final WorkspaceScopeService workspaceScopeService;
    private final ChannelToolBroker channelToolBroker;

    private final ConcurrentHashMap<String, ActiveRun> activeRuns = new ConcurrentHashMap<>();
    /** 本实例尚未持有 ActiveRun 时记下的暂停请求(QUEUED / 跨实例广播窗口)。 */
    private final ConcurrentHashMap<String, Boolean> pauseRequested = new ConcurrentHashMap<>();
    private ScheduledFuture<?> heartbeatTask;
    private ScheduledFuture<?> staleTask;

    /** 单测用：无渠道工具 broker。 */
    public ChatRunExecutor(
            ThreadPoolTaskExecutor taskExecutor,
            ScheduledExecutorService scheduler,
            long staleTimeoutMs,
            long maxDurationSeconds,
            ChatTurnRunner chatTurnRunner,
            AiChatRunMapper runMapper,
            ChatRunEventBroker eventBroker,
            ChatRunInstance instance,
            IAiChatSessionService sessionService,
            com.ruoyi.system.ai.agent.AgentAssemblyCache assemblyCache,
            ContextBudget contextBudget,
            AiToolProperties toolProperties,
            ToolBudgetRegistry budgetRegistry,
            ToolConfirmBroker confirmBroker,
            WorkspaceScopeService workspaceScopeService)
    {
        this(taskExecutor, scheduler, staleTimeoutMs, maxDurationSeconds, chatTurnRunner, runMapper,
                eventBroker, instance, sessionService, assemblyCache, contextBudget, toolProperties,
                budgetRegistry, confirmBroker, workspaceScopeService, null);
    }

    @Autowired
    public ChatRunExecutor(
            @Qualifier("chatRunTaskExecutor") ThreadPoolTaskExecutor taskExecutor,
            @Qualifier("scheduledExecutorService") ScheduledExecutorService scheduler,
            @Value("${ai.chat.run.stale-timeout-ms:120000}") long staleTimeoutMs,
            @Value("${ai.chat.run.max-duration-seconds:1800}") long maxDurationSeconds,
            ChatTurnRunner chatTurnRunner,
            AiChatRunMapper runMapper,
            ChatRunEventBroker eventBroker,
            ChatRunInstance instance,
            IAiChatSessionService sessionService,
            com.ruoyi.system.ai.agent.AgentAssemblyCache assemblyCache,
            ContextBudget contextBudget,
            AiToolProperties toolProperties,
            ToolBudgetRegistry budgetRegistry,
            ToolConfirmBroker confirmBroker,
            WorkspaceScopeService workspaceScopeService,
            ChannelToolBroker channelToolBroker)
    {
        this.taskExecutor = taskExecutor;
        this.scheduler = scheduler;
        this.staleTimeoutMs = Math.max(staleTimeoutMs, 30000L);
        this.maxDurationSeconds = Math.max(maxDurationSeconds, 0L);
        this.chatTurnRunner = chatTurnRunner;
        this.runMapper = runMapper;
        this.eventBroker = eventBroker;
        this.instance = instance;
        this.sessionService = sessionService;
        this.assemblyCache = assemblyCache;
        this.contextBudget = contextBudget;
        this.toolProperties = toolProperties;
        this.budgetRegistry = budgetRegistry;
        this.confirmBroker = confirmBroker;
        this.workspaceScopeService = workspaceScopeService;
        this.channelToolBroker = channelToolBroker;
    }

    /** 兼容不启动 Spring 容器的单元测试构造。 */
    public ChatRunExecutor(
            ThreadPoolTaskExecutor taskExecutor,
            ScheduledExecutorService scheduler,
            long staleTimeoutMs,
            long maxDurationSeconds,
            ChatTurnRunner chatTurnRunner,
            AiChatRunMapper runMapper,
            ChatRunEventBroker eventBroker,
            ChatRunInstance instance,
            IAiChatSessionService sessionService,
            com.ruoyi.system.ai.agent.AgentAssemblyCache assemblyCache,
            ContextBudget contextBudget,
            AiToolProperties toolProperties,
            ToolBudgetRegistry budgetRegistry,
            ToolConfirmBroker confirmBroker)
    {
        this(taskExecutor, scheduler, staleTimeoutMs, maxDurationSeconds, chatTurnRunner, runMapper,
                eventBroker, instance, sessionService, assemblyCache, contextBudget, toolProperties,
                budgetRegistry, confirmBroker, null);
    }

    @PostConstruct
    public void startWatchdogs()
    {
        heartbeatTask = scheduler.scheduleAtFixedRate(this::heartbeatSafely, 10, 10, TimeUnit.SECONDS);
        staleTask = scheduler.scheduleAtFixedRate(this::recoverStaleSafely, 30, 30, TimeUnit.SECONDS);
    }

    /** 把已提交的运行放入独立工作池。 */
    public void start(ChatRunCommand command)
    {
        try
        {
            taskExecutor.execute(() -> execute(command));
        }
        catch (RuntimeException e)
        {
            log.error("对话运行队列已满: runId={}", command.runId(), e);
            terminalize(command.runId(), command.sessionId(), ChatRunStatus.FAILED,
                    "QUEUE_REJECTED", "当前对话任务过多，请稍后重试", null, null, "error");
        }
    }

    private void execute(ChatRunCommand command)
    {
        try
        {
            executeInternal(command);
        }
        catch (Throwable e)
        {
            forgetPause(command.runId());
            ActiveRun active = activeRuns.remove(command.runId());
            if (active != null)
            {
                active.beginTerminal();
                active.dispose();
                active.signalTerminal();
                // worker 在 await 之前崩溃时 executeInternal 的 finally 可能未跑到,此处兜底释放
                cancelChannelTools(command.sessionId());
                budgetRegistry.close(command.sessionId());
            }
            log.error("对话 worker 启动或基础设施访问失败: runId={}", command.runId(), e);
            try
            {
                terminalize(command.runId(), command.sessionId(), ChatRunStatus.FAILED,
                        "WORKER_EXECUTION_FAILED", safeMessage(e), null, null, "error");
            }
            catch (Throwable terminalError)
            {
                // 数据库不可用时无法强行伪造终态；恢复后由 stale watchdog 收敛。
                log.error("对话运行终态写入失败，将等待心跳超时恢复: runId={}",
                        command.runId(), terminalError);
            }
        }
    }

    private void executeInternal(ChatRunCommand command)
    {
        AiChatRun persisted = runMapper.selectAiChatRunById(command.runId());
        if (persisted == null || ChatRunStatus.isTerminal(persisted.getStatus()))
        {
            return;
        }
        if ("1".equals(persisted.getCancelRequested()))
        {
            terminalize(command.runId(), command.sessionId(), ChatRunStatus.CANCELLED,
                    "CANCELLED_BY_USER", "运行已取消", null, null, "cancelled");
            return;
        }
        if (runMapper.markRunning(command.runId(), instance.id(), new java.util.Date()) == 0)
        {
            persisted = runMapper.selectAiChatRunById(command.runId());
            if (persisted != null && "1".equals(persisted.getCancelRequested()))
            {
                terminalize(command.runId(), command.sessionId(), ChatRunStatus.CANCELLED,
                        "CANCELLED_BY_USER", "运行已取消", null, null, "cancelled");
            }
            return;
        }

        ActiveRun active = new ActiveRun(command.runId(), command.sessionId());
        // 认领 worker 线程:取消要唤醒的是它。必须在入表之前设好 ——
        // 入表之后 cancelLocal 就可能拿到这个 ActiveRun,那时 worker 为空就白唤醒一次。
        active.markWorker(Thread.currentThread());
        // 先入表,pauseLocal 的 QUEUED 窗口才能打到 ActiveRun;再消化握手期 pending。
        activeRuns.put(command.runId(), active);
        if (Boolean.TRUE.equals(pauseRequested.remove(command.runId())))
        {
            active.requestPause();
        }
        // 与 activeRuns 同步开预算:RecordingToolCallback 只持 sessionId,按会话共享才能罩住子 agent。
        // inputBudget 按模型窗口比例卡 prompt_tokens(见 ai-context-compactor-design.md §3.1)。
        budgetRegistry.open(command.sessionId(), resolveInputBudget(command.agentId(), command.modelCode()));

        try
        {
            eventBroker.publish(command.runId(), command.sessionId(), statusEvent(ChatRunStatus.RUNNING));
            ensureSessionArtifacts(command);
            com.ruoyi.system.domain.AiChatSession session = loadSessionQuietly(command.sessionId());
            boolean unattended = session != null && "job".equalsIgnoreCase(session.getSessionType());
            List<ChannelToolDef> clientTools = ChannelToolSchemas.parseQuietly(
                    session != null ? session.getClientTools() : null);
            AgentContextFactory.OperatorHolder operator = new AgentContextFactory.OperatorHolder(
                    command.username(), command.userId(), command.admin(), unattended);
            ChatEventSink sink = eventJson -> {
                if (!active.isTerminal())
                {
                    eventBroker.publish(command.runId(), command.sessionId(), eventJson);
                }
            };
            List<ChatTurnAttachment> attachments = toTurnAttachments(command.attachments());
            ChatTurnRequest turnRequest = new ChatTurnRequest(
                    command.runId(), command.sessionId(), command.agentId(), command.message(),
                    attachments, operator, command.modelCode(), command.skillIds(), clientTools);

            Disposable disposable = chatTurnRunner.run(turnRequest, sink, new ChatTurnCallbacks()
            {
                @Override
                public boolean shouldContinue()
                {
                    return ChatRunExecutor.shouldContinue(active);
                }

                @Override
                public boolean shouldPersistFinalAnswer()
                {
                    return active != null && !active.isTerminal();
                }

                @Override
                public void onRequestMessageRecorded(Long requestMessageId)
                {
                    if (requestMessageId != null)
                    {
                        runMapper.bindRequestMessage(command.runId(), requestMessageId);
                    }
                }

                @Override
                public void onSucceeded(String reply, LlmCallCollector.TurnUsage usage,
                                        Map<String, Object> contextUsage,
                                        Long requestMessageId, Long responseMessageId)
                {
                    if (active.isPauseRequested())
                    {
                        pauseByUser(active, command, requestMessageId, responseMessageId);
                        return;
                    }
                    succeed(active, command, reply, usage, contextUsage,
                            requestMessageId, responseMessageId);
                }

                @Override
                public void onFailed(Throwable error)
                {
                    fail(active, command, error);
                }
            });
            active.setDisposable(disposable);
            // Reactor 的 subscribe() 会立即返回。这里持续占用独立 worker 槽位直到终态，
            // 才能让线程池的 core/max/queue 真正约束“并发运行数”，而不只是启动速度。
            try
            {
                // 进入等待后 signalTerminal 就唤得动,不必再挨中断(见 ActiveRun#wakeBlockedWorker)
                active.beginAwait();
                active.awaitTerminal();
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                interruptWorker(active, command);
            }
        }
        catch (Throwable e)
        {
            fail(active, command, e);
        }
        finally
        {
            // 唤醒用的中断到此为止,不能带着中断位回线程池 —— 下一个 run 会被它误伤。
            // 容器自己发的中断不清:那是真的要停这个线程,上面已按 INTERRUPTED 收敛。
            if (active.consumeWakeupInterrupt())
            {
                Thread.interrupted();
            }
            // 覆盖 succeed/fail/interrupt 后 await 返回,以及 cancel 触发的 signal;close 幂等
            if (confirmBroker != null)
            {
                confirmBroker.cancelAll(command.sessionId());
            }
            cancelChannelTools(command.sessionId());
            budgetRegistry.close(command.sessionId());
        }
    }

    private void succeed(ActiveRun active, ChatRunCommand command, String reply,
                         LlmCallCollector.TurnUsage usage, Map<String, Object> contextUsage,
                         Long requestMessageId, Long responseMessageId)
    {
        if (!active.beginTerminal())
        {
            return;
        }
        try
        {
            int claimed = runMapper.markFinalizing(command.runId(), instance.id(), new java.util.Date());
            if (claimed == 0)
            {
                settleUnclaimedCompletion(command);
                return;
            }
            eventBroker.publish(command.runId(), command.sessionId(),
                    statusEvent(ChatRunStatus.FINALIZING));
            // 消息 ID 由 Runner 写入时直接返回，不再用“最新消息”猜本轮归属。
            int changed = runMapper.completeRun(command.runId(), ChatRunStatus.SUCCEEDED,
                    null, null, requestMessageId, responseMessageId, new java.util.Date());
            if (changed > 0)
            {
                JSONObject usageJson = toUsageJson(usage);
                JSONObject contextJson = contextUsage != null ? new JSONObject(contextUsage) : null;
                JSONObject done = JSON.parseObject(ChatEventJson.done(usageJson, contextJson));
                done.put("status", ChatRunStatus.SUCCEEDED);
                // 终态事件携带最终正文，使 Stream 截断或中途重连后仍能校准完整答案。
                done.put("text", reply);
                eventBroker.publish(command.runId(), command.sessionId(), done.toJSONString());
            }
        }
        catch (Throwable e)
        {
            // 已抢到收尾权但处理失败，仍必须把数据库状态从 FINALIZING 释放出来。
            log.error("对话运行收尾失败: runId={}", command.runId(), e);
            terminalize(command.runId(), command.sessionId(), ChatRunStatus.FAILED,
                    "FINALIZE_FAILED", safeMessage(e), null, null, "error");
        }
        finally
        {
            forgetPause(command.runId());
            activeRuns.remove(command.runId(), active);
            active.signalTerminal();
        }
    }

    /** 完成与取消同时发生时，数据库条件更新决定赢家；取消已登记则在这里收敛终态。 */
    private void settleUnclaimedCompletion(ChatRunCommand command)
    {
        AiChatRun run = runMapper.selectAiChatRunById(command.runId());
        if (run != null && !ChatRunStatus.isTerminal(run.getStatus())
                && "1".equals(run.getCancelRequested()))
        {
            terminalize(command.runId(), command.sessionId(), ChatRunStatus.CANCELLED,
                    "CANCELLED_BY_USER", "运行已取消", run.getRequestMessageId(),
                    run.getResponseMessageId(), "cancelled");
        }
    }

    private void fail(ActiveRun active, ChatRunCommand command, Throwable error)
    {
        // idle-timeout 等 onFailed 仍可能在暂停之后到达;不能写成 MODEL_EXECUTION_FAILED。
        if (active.isPauseRequested())
        {
            pauseByUser(active, command, null, null);
            return;
        }
        if (!active.beginTerminal())
        {
            return;
        }
        log.error("AI 对话运行失败: runId={}, sessionId={}", command.runId(), command.sessionId(), error);
        try
        {
            terminalize(command.runId(), command.sessionId(), ChatRunStatus.FAILED,
                    "MODEL_EXECUTION_FAILED", safeMessage(error), null, null, "error");
        }
        finally
        {
            forgetPause(command.runId());
            activeRuns.remove(command.runId(), active);
            active.signalTerminal();
        }
    }

    /** worker 线程被容器中断时，释放模型订阅并把运行收敛为可恢复终态。 */
    private void interruptWorker(ActiveRun active, ChatRunCommand command)
    {
        if (!active.beginTerminal())
        {
            // 完成/取消回调已经取得终态权，它会负责发信号；这里只等待其自然收尾。
            return;
        }
        try
        {
            active.dispose();
            terminalize(command.runId(), command.sessionId(), ChatRunStatus.INTERRUPTED,
                    "WORKER_INTERRUPTED", "执行线程已中断，可重新发起", null, null, "interrupted");
        }
        finally
        {
            forgetPause(command.runId());
            activeRuns.remove(command.runId(), active);
            active.signalTerminal();
        }
    }

    /**
     * 请求暂停。只立闸门,不 cancel、不 dispose,当前工具批次跑完。
     * 循环因暂停退出后由 onSucceeded / onFailed 落 CANCELLED / PAUSED_BY_USER,不走 succeed / MODEL_EXECUTION_FAILED。
     */
    public void pauseLocal(String runId)
    {
        if (runId == null || runId.isBlank())
        {
            return;
        }
        ActiveRun active = activeRuns.get(runId);
        if (active != null)
        {
            active.requestPause();
            return;
        }
        AiChatRun run = runMapper.selectAiChatRunById(runId);
        if (run != null && !ChatRunStatus.isTerminal(run.getStatus()))
        {
            pauseRequested.put(runId, Boolean.TRUE);
        }
    }

    /** 循环因暂停退出:落 CANCELLED + PAUSED_BY_USER。不 dispose —— 工具批次已在闸门前跑完。 */
    private void pauseByUser(ActiveRun active, ChatRunCommand command,
                             Long requestMessageId, Long responseMessageId)
    {
        if (!active.beginTerminal())
        {
            return;
        }
        try
        {
            terminalize(command.runId(), command.sessionId(), ChatRunStatus.CANCELLED,
                    "PAUSED_BY_USER", "已按用户请求暂停", requestMessageId, responseMessageId,
                    "cancelled");
        }
        finally
        {
            forgetPause(command.runId());
            activeRuns.remove(command.runId(), active);
            active.signalTerminal();
        }
    }

    /** 闸门:未终态且未被请求暂停。暂停不是 cancel。 */
    static boolean shouldContinue(ActiveRun active)
    {
        return active != null && !active.isTerminal() && !active.isPauseRequested();
    }

    private void forgetPause(String runId)
    {
        if (runId != null)
        {
            pauseRequested.remove(runId);
        }
    }

    /** 本实例收到取消指令；若运行属于其他实例则由 Redis 控制广播在对应实例处理。 */
    public void cancelLocal(String runId)
    {
        ActiveRun active = activeRuns.get(runId);
        if (active != null)
        {
            if (!active.beginTerminal())
            {
                return;
            }
            active.dispose();
            // dispose 只断得掉已订阅的 Flux;卡在同步前奏里的 worker 得靠中断唤醒,
            // 否则取消只改了库和界面,线程永远留在池子里(详见 ActiveRun#wakeBlockedWorker)
            active.wakeBlockedWorker();
            forgetPause(runId);
            activeRuns.remove(runId, active);
            // worker 可能已挂死不会走到 executeInternal finally,取消路径必须自己 close
            cancelChannelTools(active.sessionId);
            budgetRegistry.close(active.sessionId);
        }
        try
        {
            // 其他实例可能已先把数据库状态改成 CANCELLED；本实例若正持有模型订阅，
            // 仍必须先 dispose，再根据数据库状态决定是否需要重复终态转换。
            AiChatRun run = runMapper.selectAiChatRunById(runId);
            if (run == null || ChatRunStatus.isTerminal(run.getStatus())
                    || ChatRunStatus.FINALIZING.equals(run.getStatus()))
            {
                return;
            }
            terminalize(runId, run.getSessionId(), ChatRunStatus.CANCELLED,
                    "CANCELLED_BY_USER", "运行已取消", run.getRequestMessageId(),
                    run.getResponseMessageId(), "cancelled");
        }
        finally
        {
            if (active != null)
            {
                active.signalTerminal();
            }
        }
    }

    private void terminalize(String runId, String sessionId, String status,
                             String errorCode, String message,
                             Long requestMessageId, Long responseMessageId, String eventType)
    {
        try
        {
            int changed = runMapper.completeRun(runId, status, errorCode, truncate(message, 2000),
                    requestMessageId, responseMessageId, new java.util.Date());
            if (changed > 0)
            {
                JSONObject event = new JSONObject();
                event.put("type", eventType);
                event.put("status", status);
                if (errorCode != null)
                {
                    event.put("code", errorCode);
                }
                if (message != null)
                {
                    event.put("message", message);
                }
                eventBroker.publish(runId, sessionId, event.toJSONString());
            }
        }
        catch (RuntimeException e)
        {
            log.error("写入对话运行终态失败，将由心跳恢复机制继续收敛: runId={}, status={}",
                    runId, status, e);
        }
    }

    private void cancelChannelTools(String sessionId)
    {
        if (channelToolBroker != null && sessionId != null)
        {
            channelToolBroker.cancelBySession(sessionId);
        }
    }

    private com.ruoyi.system.domain.AiChatSession loadSessionQuietly(String sessionId)
    {
        if (sessionId == null || sessionService == null)
        {
            return null;
        }
        try
        {
            return sessionService.selectAiChatSessionById(sessionId);
        }
        catch (RuntimeException e)
        {
            log.debug("读取会话失败 sessionId={}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    private void ensureSessionArtifacts(ChatRunCommand command)
    {
        sessionService.ensureAgentJoined(command.sessionId(), command.agentId(), "supervisor");
        try
        {
            if (workspaceScopeService != null)
            {
                workspaceScopeService.resolveRoot(toolProperties, command.sessionId(), true);
            }
            else
            {
                WorkspaceSandbox.resolveRoot(toolProperties, command.sessionId());
            }
        }
        catch (Exception e)
        {
            log.warn("创建会话工作区失败: sessionId={}", command.sessionId(), e);
        }
    }

    private static List<ChatTurnAttachment> toTurnAttachments(List<ChatRunAttachment> source)
    {
        if (source == null || source.isEmpty())
        {
            return List.of();
        }
        List<ChatTurnAttachment> list = new ArrayList<>(source.size());
        for (ChatRunAttachment a : source)
        {
            if (a == null)
            {
                continue;
            }
            list.add(new ChatTurnAttachment(a.getName(), a.getPath(), a.getMime(), a.getSize()));
        }
        return list;
    }

    private void heartbeatSafely()
    {
        enforceMaxDurationSafely();
        try
        {
            for (ActiveRun active : activeRuns.values())
            {
                runMapper.updateHeartbeat(active.runId, instance.id(), new java.util.Date());
                AiChatRun run = runMapper.selectAiChatRunById(active.runId);
                if (run != null && "1".equals(run.getCancelRequested()))
                {
                    cancelLocal(active.runId);
                }
            }
        }
        catch (Throwable e)
        {
            log.warn("更新对话运行心跳失败", e);
        }
    }

    /**
     * Run 总时长兜底:心跳/stale 只覆盖「进程死亡」,本兜底按本地起始时间收敛
     * 「线程活着但永久阻塞」的 Run(上游半开、工具挂死、未知阻塞),
     * 防止会话活动锁(uk_ai_chat_run_active)被永久占用。须大于各分段超时之和。
     */
    private void enforceMaxDurationSafely()
    {
        try
        {
            enforceMaxDuration();
        }
        catch (Throwable e)
        {
            log.warn("Run 总时长兜底执行失败", e);
        }
    }

    /** 包级可见供单测;只处理本实例 activeRuns 里的运行,进程死亡场景由 stale 扫描覆盖。 */
    void enforceMaxDuration()
    {
        if (maxDurationSeconds <= 0)
        {
            return;
        }
        long limitMs = maxDurationSeconds * 1000L;
        long now = System.currentTimeMillis();
        for (ActiveRun active : activeRuns.values())
        {
            if (now - active.startedAtMs() <= limitMs)
            {
                continue;
            }
            if (!active.beginTerminal())
            {
                continue;
            }
            active.dispose();
            active.wakeBlockedWorker();
            forgetPause(active.runId);
            activeRuns.remove(active.runId, active);
            // worker 可能正卡死,不会走 executeInternal finally,必须显式释放
            cancelChannelTools(active.sessionId);
            budgetRegistry.close(active.sessionId);
            log.warn("对话运行超过最大时长({}s),强制结束: runId={} sessionId={}",
                    maxDurationSeconds, active.runId, active.sessionId);
            terminalize(active.runId, active.sessionId, ChatRunStatus.INTERRUPTED,
                    "RUN_DURATION_EXCEEDED",
                    "运行超过最大时长(" + maxDurationSeconds / 60 + "分钟),已强制结束,可重新发起",
                    null, null, "interrupted");
            active.signalTerminal();
        }
    }

    private void recoverStaleSafely()
    {
        try
        {
            // staleBefore 用应用时钟算:无心跳的 run,create_time 也是应用写的,基准才对得上
            java.util.Date staleBefore = new java.util.Date(System.currentTimeMillis() - staleTimeoutMs);
            for (AiChatRun run : runMapper.selectStaleActive(staleBefore))
            {
                ActiveRun local = activeRuns.get(run.getRunId());
                if (local != null)
                {
                    local.beginTerminal();
                    local.dispose();
                    local.wakeBlockedWorker();
                    forgetPause(run.getRunId());
                    activeRuns.remove(run.getRunId(), local);
                }
                // stale 时 worker 往往已卡死,无法依赖 executeInternal finally,必须显式释放
                cancelChannelTools(run.getSessionId());
                budgetRegistry.close(run.getSessionId());
                terminalize(run.getRunId(), run.getSessionId(), ChatRunStatus.INTERRUPTED,
                        "WORKER_STALE", "执行节点心跳超时，可重新发起", run.getRequestMessageId(),
                        run.getResponseMessageId(), "interrupted");
                if (local != null)
                {
                    local.signalTerminal();
                }
            }
        }
        catch (Throwable e)
        {
            log.warn("扫描超时对话运行失败", e);
        }
    }

    @PreDestroy
    public void shutdown()
    {
        if (heartbeatTask != null) heartbeatTask.cancel(false);
        if (staleTask != null) staleTask.cancel(false);
        for (ActiveRun active : activeRuns.values())
        {
            active.beginTerminal();
            active.dispose();
            active.wakeBlockedWorker();
            cancelChannelTools(active.sessionId);
            budgetRegistry.close(active.sessionId);
            terminalize(active.runId, active.sessionId, ChatRunStatus.INTERRUPTED,
                    "WORKER_SHUTDOWN", "执行节点已停止，可重新发起", null, null, "interrupted");
            active.signalTerminal();
        }
        activeRuns.clear();
    }

    /**
     * 按智能体绑定模型算输入 token 预算,供 ToolBudget 做 prompt 比例硬约束。
     * <p>解析失败时返回 0:ToolBudget 会跳过 token 判定,仅保留轮次/字符上限,不影响运行。
     */
    private int resolveInputBudget(Long agentId, String modelCode)
    {
        if (agentId == null || contextBudget == null)
        {
            return 0;
        }
        try
        {
            String effectiveModelCode = modelCode;
            if (effectiveModelCode == null || effectiveModelCode.isEmpty())
            {
                AiAgent agent = assemblyCache.agent(agentId);
                effectiveModelCode = agent != null ? agent.getModelCode() : null;
            }
            if (effectiveModelCode == null || effectiveModelCode.isEmpty())
            {
                return contextBudget.inputBudget(null, null);
            }
            AiModel model = assemblyCache.modelByCode(effectiveModelCode);
            Integer window = model != null ? model.getContextWindow() : null;
            Integer maxOut = model != null ? model.getMaxOutputTokens() : null;
            return contextBudget.inputBudget(window, maxOut);
        }
        catch (Exception e)
        {
            log.debug("解析输入 token 预算失败 agent={}: {}", agentId, e.getMessage());
            return 0;
        }
    }

    private static JSONObject toUsageJson(LlmCallCollector.TurnUsage usage)
    {
        if (usage == null) return null;
        JSONObject json = new JSONObject();
        json.put("promptTokens", usage.promptTokens());
        json.put("completionTokens", usage.completionTokens());
        json.put("totalTokens", usage.totalTokens());
        json.put("callCount", usage.callCount());
        return json;
    }

    private static String statusEvent(String status)
    {
        JSONObject event = new JSONObject();
        event.put("type", "run_status");
        event.put("status", status);
        return event.toJSONString();
    }

    private static String safeMessage(Throwable error)
    {
        Throwable current = error;
        String message = null;
        int depth = 0;
        while (current != null && depth++ < 5)
        {
            if (current.getMessage() != null && !current.getMessage().isBlank())
            {
                message = current.getMessage();
            }
            current = current.getCause();
        }
        return truncate(message != null ? message : error.getClass().getSimpleName(), 2000);
    }

    private static String truncate(String value, int max)
    {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max) + "…";
    }

    /** 包级可见:同包单测需要直接构造并回拨 startedAtMs(见 ChatRunExecutorMaxDurationTest)。 */
    static final class ActiveRun
    {
        private final String runId;
        private final String sessionId;
        /** 本实例观察到 Run 开始执行的时间,总时长兜底以此判定;非 final 供测试回拨 */
        private volatile long startedAtMs;
        private final AtomicBoolean terminal = new AtomicBoolean(false);
        private final AtomicBoolean pauseRequested = new AtomicBoolean(false);
        private final CountDownLatch terminalSignal = new CountDownLatch(1);
        private volatile Disposable disposable;
        /** 执行本 run 的 worker 线程;卡在同步前奏里时只有中断唤得动它 */
        private volatile Thread worker;
        /** worker 是否已进入 awaitTerminal —— 进去之后 signalTerminal 就够,不必中断 */
        private final AtomicBoolean awaiting = new AtomicBoolean(false);
        /** 中断是我们为了唤醒发的(而非容器要停线程),收尾时要把中断位清掉 */
        private final AtomicBoolean wakeupInterrupt = new AtomicBoolean(false);

        private ActiveRun(String runId, String sessionId)
        {
            this.runId = runId;
            this.sessionId = sessionId;
            this.startedAtMs = System.currentTimeMillis();
        }

        private boolean beginTerminal() { return terminal.compareAndSet(false, true); }
        private boolean isTerminal() { return terminal.get(); }
        private void requestPause() { pauseRequested.set(true); }
        private boolean isPauseRequested() { return pauseRequested.get(); }
        private long startedAtMs() { return startedAtMs; }
        private void awaitTerminal() throws InterruptedException { terminalSignal.await(); }
        private void signalTerminal() { terminalSignal.countDown(); }

        private void setDisposable(Disposable disposable)
        {
            this.disposable = disposable;
            if (terminal.get() && disposable != null)
            {
                disposable.dispose();
            }
        }

        private void dispose()
        {
            Disposable current = disposable;
            if (current != null) current.dispose();
        }

        private void markWorker(Thread thread) { this.worker = thread; }

        private void beginAwait() { awaiting.set(true); }

        /**
         * 唤醒卡在<b>同步前奏</b>里的 worker。
         *
         * <p>{@link #dispose()} 只能取消已经拿到手的 Flux 订阅,而
         * {@code ChatTurnRunner.run()} 在返回 Flux 之前那一段(装配 / 上下文压缩 /
         * 长期记忆检索 embedding)是<b>同步跑在 worker 线程上</b>的:卡在那里时
         * {@code disposable} 还是 null,取消什么也断不掉,{@code signalTerminal} 又是
         * 冲着一个还没人在等的 latch 打的 —— 库里状态改成了 CANCELLED、界面也解了锁,
         * worker 却一直占着 {@code chatRunTaskExecutor} 的核心线程(core=4)。
         * 而且此时它已被移出 {@code activeRuns},连 {@code enforceMaxDuration} 那道
         * 「线程活着但永久阻塞」的兜底也扫不到它了。四次取消就能把整个实例的对话能力打光。
         *
         * <p>中断是唯一能穿透退避 sleep 与 JDK HttpClient 阻塞读的手段。尽力而为:
         * 传统 Socket 读不响应中断,所以这不是替代品,而是各层调用超时之外的第二道闸。
         *
         * <p>两种情况不发:已经进入 {@link #awaitTerminal()}(那时 signal 就够),
         * 以及调用者就是 worker 自己(不能自断)。
         */
        private void wakeBlockedWorker()
        {
            Thread thread = worker;
            if (thread == null || thread == Thread.currentThread() || awaiting.get())
            {
                return;
            }
            wakeupInterrupt.set(true);
            thread.interrupt();
        }

        /** 取走「这次中断是唤醒用的」标记,只会为真一次。 */
        private boolean consumeWakeupInterrupt()
        {
            return wakeupInterrupt.compareAndSet(true, false);
        }
    }
}
