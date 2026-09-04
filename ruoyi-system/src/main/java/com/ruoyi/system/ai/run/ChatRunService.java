package com.ruoyi.system.ai.run;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.AiAgent;
import com.ruoyi.system.domain.AiModel;
import com.ruoyi.system.domain.AiSkill;
import com.ruoyi.system.domain.AiChatRun;
import com.ruoyi.system.domain.AiChatSession;
import com.ruoyi.system.ai.session.SessionAccessGuard;
import com.ruoyi.system.ai.session.SessionIds;
import com.ruoyi.system.mapper.AiChatRunMapper;
import com.ruoyi.system.mapper.AiChatMessageMapper;
import com.ruoyi.system.mapper.AiChatRunStepMapper;
import com.ruoyi.system.ai.memory.ChatMessageKind;
import com.ruoyi.system.domain.AiChatMessage;
import com.ruoyi.system.service.IAiAgentService;
import com.ruoyi.system.service.IAiChatSessionService;
import com.ruoyi.system.service.IAiModelService;
import com.ruoyi.system.service.IAiSkillService;
import com.ruoyi.system.tool.ToolConfirmBroker;
import com.ruoyi.system.tool.channel.ChannelToolBroker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** 对话运行的创建、查询、鉴权与取消入口。 */
@Service
public class ChatRunService
{
    private static final Logger log = LoggerFactory.getLogger(ChatRunService.class);

    /** clientRequestId 的字符集约束;sessionId 的规则见 {@link SessionIds}(那里区分新建/存量两级)。 */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final int MAX_MESSAGE_CHARS = 100_000;
    private static final int MAX_ATTACHMENTS = 20;

    private final AiChatRunMapper runMapper;
    private final IAiChatSessionService sessionService;
    private final IAiAgentService agentService;
    private final IAiModelService modelService;
    private final IAiSkillService skillService;
    private final ChatRunExecutor executor;
    private final ChatRunEventBroker eventBroker;
    private final ChatRunControlBroker controlBroker;
    private final ToolConfirmBroker confirmBroker;
    private final ChannelToolBroker channelToolBroker;
    private final SessionAccessGuard sessionGuard;
    private final AiChatMessageMapper messageMapper;
    private final AiChatRunStepMapper stepMapper;
    private final ChatRunRateLimiter rateLimiter;

    public ChatRunService(AiChatRunMapper runMapper,
                          IAiChatSessionService sessionService,
                          IAiAgentService agentService,
                          IAiModelService modelService,
                          IAiSkillService skillService,
                          ChatRunExecutor executor,
                          ChatRunEventBroker eventBroker,
                          ChatRunControlBroker controlBroker,
                          ToolConfirmBroker confirmBroker,
                          ChannelToolBroker channelToolBroker,
                          SessionAccessGuard sessionGuard,
                          AiChatMessageMapper messageMapper,
                          AiChatRunStepMapper stepMapper,
                          ChatRunRateLimiter rateLimiter)
    {
        this.runMapper = runMapper;
        this.sessionService = sessionService;
        this.agentService = agentService;
        this.modelService = modelService;
        this.skillService = skillService;
        this.confirmBroker = confirmBroker;
        this.channelToolBroker = channelToolBroker;
        this.executor = executor;
        this.eventBroker = eventBroker;
        this.controlBroker = controlBroker;
        this.sessionGuard = sessionGuard;
        this.messageMapper = messageMapper;
        this.stepMapper = stepMapper;
        this.rateLimiter = rateLimiter;
    }

    @Transactional
    public AiChatRun create(ChatRunCreateCommand request)
    {
        validate(request);

        String clientRequestId = StringUtils.hasText(request.clientRequestId())
                ? request.clientRequestId() : UUID.randomUUID().toString();
        AiChatRun idempotent = runMapper.selectByClientRequest(request.userId(), clientRequestId);
        if (idempotent != null)
        {
            return validateIdempotentRequest(idempotent, request);
        }
        rateLimiter.check(request.userId(), isJobSession(request.sessionId()));

        AiAgent agent = agentService.selectAccessibleAiAgentById(request.agentId());
        if (agent == null || !"0".equals(agent.getStatus()))
        {
            throw new ServiceException("智能体不存在、无权访问或已停用");
        }

        AiModel selectedModel = resolveSelectedModel(request.modelId());
        SkillResolution skillResolution = resolveEffectiveSkillIds(agent, request.skillIds(), request.userId());
        Long[] effectiveSkillIds = skillResolution.effective();
        // 无显式选择时也快照基座智能体的模型编码，异步执行不受随后修改默认模型影响。
        String effectiveModelCode = selectedModel != null ? selectedModel.getModelCode() : agent.getModelCode();

        ensureOwnedSession(request);

        // 会话级多选知识库：前端发消息携带 kbIds 时整组替换会话知识库(含权限校验)。
        // null=前端未传，不动已有选择；空列表=清空；job 会话不传，不受影响。
        if (request.kbIds() != null)
        {
            sessionService.saveSessionKbs(request.sessionId(), request.kbIds());
        }

        AiChatRun run = new AiChatRun();
        run.setRunId(UUID.randomUUID().toString());
        run.setSessionId(request.sessionId());
        run.setAgentId(request.agentId());
        run.setSelectedModelId(selectedModel != null ? selectedModel.getModelId() : null);
        run.setSelectedModelCode(effectiveModelCode);
        run.setEffectiveSkillIds(JSON.toJSONString(effectiveSkillIds));
        run.setUserId(request.userId());
        run.setClientRequestId(clientRequestId);
        run.setActiveKey(request.sessionId());
        run.setStatus(ChatRunStatus.QUEUED);
        run.setInputText(request.message() == null ? "" : request.message());
        run.setAttachments(request.attachments().isEmpty()
                ? null : JSON.toJSONString(request.attachments()));
        Date now = new Date();
        run.setCreateTime(now);
        run.setUpdateTime(now);

        int inserted = runMapper.insertAiChatRun(run);
        if (inserted == 0)
        {
            idempotent = runMapper.selectByClientRequest(request.userId(), clientRequestId);
            if (idempotent != null)
            {
                return validateIdempotentRequest(idempotent, request);
            }
            AiChatRun active = runMapper.selectActiveBySession(request.sessionId());
            if (active != null)
            {
                requireOwner(active, request.userId(), request.admin());
                throw new ActiveChatRunException(active);
            }
            throw new ServiceException("创建对话运行失败，请重试");
        }

        ChatRunCommand command = new ChatRunCommand(
                run.getRunId(), request.sessionId(), request.agentId(), run.getInputText(),
                request.attachments(), request.userId(), request.username(), request.admin(),
                run.getSelectedModelCode(), effectiveSkillIds);
        afterCommit(() -> {
            try
            {
                eventBroker.publish(run.getRunId(), run.getSessionId(), queuedEvent());
            }
            finally
            {
                // 实时事件链路故障不能阻止已持久化运行进入工作池。
                executor.start(command);
            }
        });
        AiChatRun created = runMapper.selectAiChatRunById(run.getRunId());
        if (created != null)
        {
            // 非持久化字段:这里补一次,给前端提示「哪几个技能已不可用、本轮没用上」
            created.setSkippedSkillIds(skillResolution.skipped());
        }
        return created;
    }

    /** 客户端随首轮消息带来的工具清单;版本与已存相同则不动。失败不阻断发送。 */
    private void declareIfFirstTurn(ChatRunCreateCommand request)
    {
        if (!StringUtils.hasText(request.clientToolsJson())
                || !StringUtils.hasText(request.capabilitiesVersion()))
        {
            return;
        }
        try
        {
            sessionService.declareClient(request.sessionId(), request.clientType(),
                    request.capabilitiesVersion(), request.clientToolsJson(),
                    request.userId(), request.admin());
        }
        catch (RuntimeException e)
        {
            // 声明失败只是这一轮没有客户端工具,不该让消息发不出去
            log.warn("首轮补声明客户端工具失败 sessionId={}: {}", request.sessionId(), e.getMessage());
        }
    }

    public AiChatRun get(String runId, Long userId, boolean admin)
    {
        AiChatRun run = runMapper.selectAiChatRunById(runId);
        if (run == null)
        {
            throw new ServiceException("运行不存在");
        }
        requireOwner(run, userId, admin);
        return run;
    }

    /** 一致性恢复快照；客户端从 snapshotSeq 之后继续订阅增量事件。 */
    @Transactional(readOnly = true)
    public ChatRunState state(String runId, Long userId, boolean admin)
    {
        AiChatRun run = get(runId, userId, admin);
        AiChatMessage user = run.getRequestMessageId() == null ? null
                : messageMapper.selectMessageById(run.getRequestMessageId());
        if (user == null) user = messageMapper.selectByRunAndKind(runId, ChatMessageKind.USER_INPUT);
        AiChatMessage answer = run.getResponseMessageId() == null ? null
                : messageMapper.selectMessageById(run.getResponseMessageId());
        if (answer == null) answer = messageMapper.selectByRunAndKind(runId, ChatMessageKind.ASSISTANT_FINAL);
        long snapshotSeq = run.getSnapshotSeq() == null ? 0L : run.getSnapshotSeq();
        return new ChatRunState(run, user, answer, messageMapper.selectByRunId(runId),
                stepMapper.selectByRunId(runId), snapshotSeq);
    }

    public AiChatRun activeBySession(String sessionId, Long userId, boolean admin)
    {
        AiChatRun run = runMapper.selectActiveBySession(sessionId);
        if (run != null)
        {
            requireOwner(run, userId, admin);
        }
        else
        {
            requireSessionOwner(sessionId, userId, admin);
        }
        return run;
    }

    /**
     * 会话级订阅的准入检查，返回当前活动运行(可能为 null)。
     *
     * <p>与 {@link #activeBySession} 的区别：允许监听尚未落库的新会话 —— 新对话的 sessionId
     * 由前端生成，首轮发送前数据库里并不存在，此时不能因“会话不存在”拒绝订阅，
     * 否则新开的标签页要等第一轮发完才能互相同步。会话一旦存在则必须是本人；
     * 真正的事件正文仍由 {@code chat.run.subscribe} 按 run 再校验一次归属。</p>
     */
    public AiChatRun watchableActiveRun(String sessionId, Long userId, boolean admin)
    {
        AiChatRun run = runMapper.selectActiveBySession(sessionId);
        if (run != null)
        {
            requireOwner(run, userId, admin);
            return run;
        }
        // 会话不存在时放行(新会话尚未落库)，存在则必须是本人。
        sessionGuard.requireOwnedIfExists(sessionId, userId, admin);
        return null;
    }

    public AiChatRun latestBySession(String sessionId, Long userId, boolean admin)
    {
        AiChatRun run = runMapper.selectLatestBySession(sessionId);
        if (run != null)
        {
            requireOwner(run, userId, admin);
        }
        else
        {
            requireSessionOwner(sessionId, userId, admin);
        }
        return run;
    }

    public AiChatRun cancel(String runId, Long userId, boolean admin)
    {
        AiChatRun run = get(runId, userId, admin);
        if (ChatRunStatus.isTerminal(run.getStatus()))
        {
            return run;
        }
        // 取消时一并拒绝挂起的危险工具确认,避免 worker 线程卡死
        if (confirmBroker != null)
        {
            confirmBroker.cancelAll(run.getSessionId());
        }
        if (runMapper.requestCancel(runId) > 0)
        {
            controlBroker.cancel(runId);
        }
        return runMapper.selectAiChatRunById(runId);
    }

    /**
     * 危险工具人工确认/拒绝。
     *
     * @return true 已投递到等待中的工具线程;false 确认已过期或不存在
     */
    public boolean confirmTool(String runId, String confirmId, boolean approved,
                               Long userId, boolean admin)
    {
        AiChatRun run = get(runId, userId, admin);
        if (ChatRunStatus.isTerminal(run.getStatus()))
        {
            return false;
        }
        if (!StringUtils.hasText(confirmId))
        {
            throw new ServiceException("confirmId 不能为空");
        }
        return confirmBroker != null && confirmBroker.resolve(confirmId, approved);
    }

    /**
     * 客户端回传渠道工具结果。
     *
     * @return true 已投递到等待中的工具线程;false 调用已过期或不存在
     */
    public boolean completeChannelTool(String runId, String callId, boolean ok,
                                       String result, String error, Long mediaFileId,
                                       Long userId, boolean admin)
    {
        return completeChannelTool(runId, callId, ok, result, error, mediaFileId, null,
                userId, admin);
    }

    public boolean completeChannelTool(String runId, String callId, boolean ok,
                                       String result, String error, Long mediaFileId,
                                       String workspacePath, Long userId, boolean admin)
    {
        AiChatRun run = get(runId, userId, admin);
        if (ChatRunStatus.isTerminal(run.getStatus()))
        {
            return false;
        }
        if (!StringUtils.hasText(callId))
        {
            throw new ServiceException("callId 不能为空");
        }
        return channelToolBroker != null
                && channelToolBroker.complete(callId, ok, result, error, mediaFileId, workspacePath);
    }

    /**
     * 客户端(重新)订阅这一轮后，把还没回传的渠道工具请求补发一遍。
     *
     * <p>续订阅的游标是 {@code snapshot_seq}，而 tool_call_request 不落库却会把它推过自己，
     * 断线重连/关掉侧边栏再打开之后那条请求就永远送不到客户端了(详见
     * {@link ChannelToolBroker#redeliver})。补发只针对挂起表里真正还在等的调用。</p>
     *
     * <p>归属校验由调用方在订阅时完成(subscribe 已经 {@link #get} 过一次)，这里不重复查库。</p>
     *
     * @return 实际补发的条数
     */
    public int redeliverChannelTools(String runId)
    {
        return channelToolBroker == null ? 0 : channelToolBroker.redeliver(runId);
    }

    public boolean canAccess(String runId, Long userId, boolean admin)
    {
        AiChatRun run = runMapper.selectAiChatRunById(runId);
        return run != null && Objects.equals(run.getUserId(), userId);
    }

    private boolean isJobSession(String sessionId)
    {
        if (sessionId == null || sessionService == null)
        {
            return false;
        }
        AiChatSession session = sessionService.selectAiChatSessionById(sessionId);
        return session != null && "job".equalsIgnoreCase(session.getSessionType());
    }

    private void ensureOwnedSession(ChatRunCreateCommand request)
    {
        // 建行 + 属主校验统一在 Guard 里(新建分支才要求 id 强度，存量短 id 不追溯)。
        sessionGuard.requireOrCreate(request.sessionId(), request.userId(), request.username(),
                request.admin(), truncateTitle(request.message()), null, request.projectId());

        // 与删除/清空/回滚锁同一会话主行，避免“检查后删除、同时又创建运行”的竞态。
        // 上面的 requireOrCreate 是无锁读，这里必须再取一次带锁的行。
        AiChatSession session = sessionGuard.requireOwnedForUpdate(request.sessionId(),
                request.userId(), request.admin());
        if (!StringUtils.hasText(session.getTitle()))
        {
            AiChatSession patch = new AiChatSession();
            patch.setSessionId(request.sessionId());
            patch.setTitle(truncateTitle(request.message()));
            sessionService.updateAiChatSession(patch);
        }
        // 首轮补声明:客户端的 declare 要等会话落库后才能发,而首轮 run 就在此刻装配 ——
        // 不在这里补,新会话的第一轮永远没有客户端工具(实测模型会回「当前环境没有浏览器工具」)。
        declareIfFirstTurn(request);

        // 主智能体关系也属于会话创建事实，必须在响应 runId 前提交；否则首轮发送后立即刷新
        // 可能先看到会话，却还原不出本轮使用的智能体。
        sessionService.ensureAgentJoined(request.sessionId(), request.agentId(), "supervisor");
    }

    private void requireSessionOwner(String sessionId, Long userId, boolean admin)
    {
        sessionGuard.requireOwned(sessionId, userId, admin);
    }

    private void requireOwner(AiChatRun run, Long userId, boolean admin)
    {
        sessionGuard.requireOwnedRun(run, userId, admin);
    }

    /** 同一个幂等键只能重放完全相同的业务请求，不能静默指向另一轮对话。 */
    private static AiChatRun validateIdempotentRequest(AiChatRun existing, ChatRunCreateCommand request)
    {
        String expectedInput = request.message() == null ? "" : request.message();
        String expectedAttachments = request.attachments().isEmpty()
                ? null : JSON.toJSONString(request.attachments());
        boolean same = Objects.equals(existing.getSessionId(), request.sessionId())
                && Objects.equals(existing.getAgentId(), request.agentId())
                && Objects.equals(existing.getInputText(), expectedInput)
                && Objects.equals(existing.getAttachments(), expectedAttachments)
                && Objects.equals(existing.getSelectedModelId(), request.modelId());
        if (!same)
        {
            throw new ServiceException("clientRequestId 已用于其他对话请求");
        }
        return existing;
    }

    private static void validate(ChatRunCreateCommand request)
    {
        if (request == null || request.userId() == null)
        {
            throw new ServiceException("未获取到登录用户");
        }
        // 这里只校验字符集(存量短 id 的老会话要能继续对话);
        // 新建会话的强度下限在 SessionAccessGuard.requireOrCreate 的新建分支上把关。
        SessionIds.requireWellFormed(request.sessionId());
        if (request.agentId() == null || request.agentId() <= 0)
        {
            throw new ServiceException("请选择智能体");
        }
        if (!StringUtils.hasText(request.message()) && request.attachments().isEmpty())
        {
            throw new ServiceException("消息内容不能为空");
        }
        if (request.message() != null && request.message().length() > MAX_MESSAGE_CHARS)
        {
            throw new ServiceException("消息内容过长");
        }
        if (request.attachments().size() > MAX_ATTACHMENTS)
        {
            throw new ServiceException("单次最多上传 " + MAX_ATTACHMENTS + " 个附件");
        }
        if (request.modelId() != null && request.modelId() <= 0)
        {
            throw new ServiceException("模型参数不正确");
        }
        if (request.skillIds().size() > 20)
        {
            throw new ServiceException("单次最多选择 20 个技能");
        }
        for (Long skillId : request.skillIds())
        {
            if (skillId == null || skillId <= 0)
            {
                throw new ServiceException("技能参数不正确");
            }
        }
        for (ChatRunAttachment attachment : request.attachments())
        {
            if (attachment == null || !StringUtils.hasText(attachment.getPath())
                    || attachment.getPath().length() > 512
                    || !isSafeRelativePath(attachment.getPath())
                    || (attachment.getSize() != null && attachment.getSize() < 0))
            {
                throw new ServiceException("附件参数不正确");
            }
        }
        if (StringUtils.hasText(request.clientRequestId())
                && !SAFE_ID.matcher(request.clientRequestId()).matches())
        {
            throw new ServiceException("clientRequestId 格式不正确");
        }
    }

    private static boolean isSafeRelativePath(String value)
    {
        String path = value.replace('\\', '/');
        if (path.startsWith("/") || path.matches("^[A-Za-z]:/.*")) return false;
        for (String segment : path.split("/"))
        {
            if ("..".equals(segment)) return false;
        }
        return value.indexOf('\0') < 0;
    }

    /** 客户端只能覆盖为已启用的聊天模型；空值保留智能体的默认模型。 */
    private AiModel resolveSelectedModel(Long modelId)
    {
        if (modelId == null)
        {
            return null;
        }
        AiModel model = modelService.selectAiModelById(modelId);
        if (model == null || !"0".equals(model.getStatus()) || !"CHAT".equals(model.getModelType()))
        {
            throw new ServiceException("模型不存在或当前不可用");
        }
        return model;
    }

    /** 默认技能与 @ 技能合并后写入 run，执行线程不再依赖提交后被修改的智能体关联。 */
    /**
     * 本轮技能解析结果:生效清单 + 被跳过的清单。
     *
     * @param effective 智能体自带技能 ∪ 校验通过的 @ 技能
     * @param skipped   已删除/已停用/无权使用而被丢弃的技能 ID
     */
    private record SkillResolution(Long[] effective, java.util.List<Long> skipped) { }

    /**
     * 合并智能体自带技能与用户 @ 选择的技能。
     *
     * <p><b>失效的技能跳过而不是抛错</b>:这个入口同时服务「用户主动 @」和「重新生成」。
     * 前者的技能来自前端刚拉的可用列表,几乎不会失效;后者重放的是历史快照
     * ({@code ai_chat_run.effective_skill_ids}),而技能被删除或停用是完全正常的事 ——
     * 拿防篡改的严格度去卡历史重放,会让用户只想重答一遍却收到一句报错。
     *
     * <p>顺带堵住一个信息泄漏:原先「包含不存在、已停用或无权使用的技能」这句话,
     * 等于告诉调用方「这个 ID 是存在的,只是你没权限」。跳过则什么都不透露。
     */
    private SkillResolution resolveEffectiveSkillIds(AiAgent agent, java.util.List<Long> selectedSkillIds,
                                                     Long userId)
    {
        java.util.LinkedHashSet<Long> ids = new java.util.LinkedHashSet<>();
        if (agent.getSkillIds() != null)
        {
            for (Long skillId : agent.getSkillIds())
            {
                if (skillId != null && skillId > 0) ids.add(skillId);
            }
        }
        java.util.List<Long> skipped = java.util.List.of();
        if (selectedSkillIds != null && !selectedSkillIds.isEmpty())
        {
            java.util.LinkedHashSet<Long> selected = new java.util.LinkedHashSet<>(selectedSkillIds);
            java.util.List<AiSkill> available = skillService.selectAvailableSkillsForUser(userId,
                    new java.util.ArrayList<>(selected));
            java.util.Set<Long> usable = new java.util.HashSet<>();
            for (AiSkill skill : available)
            {
                usable.add(skill.getSkillId());
            }
            skipped = selected.stream().filter(id -> !usable.contains(id)).toList();
            if (!skipped.isEmpty())
            {
                log.warn("本轮跳过不可用技能 userId={} skipped={}", userId, skipped);
            }
            for (Long skillId : selected)
            {
                if (usable.contains(skillId)) ids.add(skillId);
            }
        }
        return new SkillResolution(ids.toArray(Long[]::new), skipped);
    }

    private static String queuedEvent()
    {
        JSONObject event = new JSONObject();
        event.put("type", "run_status");
        event.put("status", ChatRunStatus.QUEUED);
        return event.toJSONString();
    }

    private static void afterCommit(Runnable action)
    {
        if (TransactionSynchronizationManager.isSynchronizationActive())
        {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization()
            {
                @Override
                public void afterCommit()
                {
                    action.run();
                }
            });
        }
        else
        {
            action.run();
        }
    }

    private static String truncateTitle(String message)
    {
        if (!StringUtils.hasText(message)) return "新会话";
        return message.length() > 20 ? message.substring(0, 20) + "..." : message;
    }
}
