package com.ruoyi.system.service.impl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.ruoyi.system.ai.ContextFileStore;
import com.ruoyi.system.ai.session.SessionAccessGuard;
import com.ruoyi.system.domain.AiChatSession;
import com.ruoyi.system.domain.AiChatSessionAgent;
import com.ruoyi.system.kb.access.KbAccessAction;
import com.ruoyi.system.kb.access.KbAuthorizationService;
import com.ruoyi.system.mapper.AiChatMessageMapper;
import com.ruoyi.system.mapper.AiChatRunMapper;
import com.ruoyi.system.mapper.AiChatRunStepMapper;
import com.ruoyi.system.mapper.AiChatSessionKbMapper;
import com.ruoyi.system.mapper.AiChatSessionMapper;
import com.ruoyi.system.mapper.AiChatSpecialEventMapper;
import com.ruoyi.system.mapper.AiLlmCallMapper;
import com.ruoyi.system.service.IAiChatSessionService;
import com.ruoyi.system.tool.AiToolProperties;
import com.ruoyi.system.tool.RemoteWorkspaceService;
import com.ruoyi.system.tool.ToolBudgetRegistry;
import com.ruoyi.system.tool.ToolConfirmBroker;
import com.ruoyi.system.tool.WorkspaceSandbox;
import com.ruoyi.system.tool.WorkspaceScopeService;
import com.ruoyi.system.tool.channel.ChannelToolDef;
import com.ruoyi.system.tool.channel.ChannelToolProperties;
import com.ruoyi.system.tool.channel.ChannelToolSchemas;

/**
 * 会话 服务层实现
 * <p>
 * 管理会话生命周期 + token 累加 + agent 接入。
 * 删除时级联清理 DB 关联、上下文留痕、工作区沙箱与工具预算。
 *
 * @author ruoyi
 */
@Service
public class AiChatSessionServiceImpl implements IAiChatSessionService
{
    private static final Logger log = LoggerFactory.getLogger(AiChatSessionServiceImpl.class);

    @Autowired
    private AiChatSessionMapper aiChatSessionMapper;

    @Autowired
    private AiChatMessageMapper aiChatMessageMapper;

    @Autowired
    private AiLlmCallMapper aiLlmCallMapper;

    @Autowired
    private AiChatRunMapper aiChatRunMapper;

    @Autowired
    private AiChatRunStepMapper aiChatRunStepMapper;

    @Autowired(required = false)
    private ContextFileStore contextFileStore;

    @Autowired(required = false)
    private ToolBudgetRegistry toolBudgetRegistry;

    @Autowired(required = false)
    private ToolConfirmBroker toolConfirmBroker;

    @Autowired(required = false)
    private AiToolProperties aiToolProperties;

    @Autowired(required = false)
    private WorkspaceScopeService workspaceScopeService;

    @Autowired(required = false)
    private RemoteWorkspaceService remoteWorkspaceService;

    @Autowired
    private AiChatSessionKbMapper aiChatSessionKbMapper;

    @Autowired(required = false)
    private AiChatSpecialEventMapper aiChatSpecialEventMapper;

    @Autowired(required = false)
    private KbAuthorizationService kbAuthorizationService;

    @Autowired
    private SessionAccessGuard sessionGuard;

    @Autowired(required = false)
    private ChannelToolProperties channelToolProperties;

    /**
     * 查询会话详情(含参与的智能体列表)
     */
    @Override
    public AiChatSession selectAiChatSessionById(String sessionId)
    {
        AiChatSession session = aiChatSessionMapper.selectAiChatSessionById(sessionId);
        if (session != null)
        {
            session.setAgents(aiChatSessionMapper.selectAgentsBySessionId(sessionId));
        }
        return session;
    }

    /**
     * 查询会话列表
     */
    @Override
    public List<AiChatSession> selectAiChatSessionList(AiChatSession aiChatSession)
    {
        return aiChatSessionMapper.selectAiChatSessionList(aiChatSession);
    }

    /**
     * 新增会话
     */
    @Override
    public int insertAiChatSession(AiChatSession aiChatSession)
    {
        return aiChatSessionMapper.insertAiChatSession(aiChatSession);
    }

    /**
     * 修改会话
     */
    @Override
    public int updateAiChatSession(AiChatSession aiChatSession)
    {
        return aiChatSessionMapper.updateAiChatSession(aiChatSession);
    }

    /**
     * 删除会话(逻辑删除，同时清理关联表与磁盘附属物)。
     * <p>DB：智能体关联、消息、运行记录、会话主表。
     * LLM 调用明细保留：Token 已真实消耗，删除会话不应抹掉计量历史。
     * <p>磁盘/内存（事务提交后）：ContextFileStore、会话工作区沙箱、工具预算。
     */
    @Override
    @Transactional
    public int deleteAiChatSessionById(String sessionId)
    {
        AiChatSession existing = aiChatSessionMapper.selectAiChatSessionById(sessionId);
        Long projectId = existing != null ? existing.getProjectId() : null;
        purgeDb(sessionId);
        int rows = aiChatSessionMapper.deleteAiChatSessionById(sessionId);
        scheduleArtifactCleanup(sessionId, projectId);
        return rows;
    }

    /**
     * 批量删除会话
     */
    @Override
    @Transactional
    public int deleteAiChatSessionByIds(String[] sessionIds)
    {
        Map<String, Long> projectIds = new HashMap<>();
        if (sessionIds != null)
        {
            for (String sessionId : sessionIds)
            {
                AiChatSession existing = aiChatSessionMapper.selectAiChatSessionById(sessionId);
                projectIds.put(sessionId, existing != null ? existing.getProjectId() : null);
                purgeDb(sessionId);
            }
        }
        int rows = aiChatSessionMapper.deleteAiChatSessionByIds(sessionIds);
        if (sessionIds != null)
        {
            for (String sessionId : sessionIds)
            {
                scheduleArtifactCleanup(sessionId, projectIds.get(sessionId));
            }
        }
        return rows;
    }

    private void purgeDb(String sessionId)
    {
        if (sessionId == null || sessionId.isBlank())
        {
            return;
        }
        aiChatSessionMapper.deleteAgentsBySessionId(sessionId);
        aiChatSessionKbMapper.deleteBySessionId(sessionId);
        aiChatMessageMapper.deleteBySessionId(sessionId);
        aiChatRunStepMapper.deleteBySessionId(sessionId);
        aiChatRunMapper.deleteBySessionId(sessionId);
        if (aiChatSpecialEventMapper != null)
        {
            aiChatSpecialEventMapper.deleteBySessionId(sessionId);
        }
    }

    /**
     * 事务提交后再清磁盘/内存，避免 DB 回滚后文件已删。
     * 无事务时立即执行（单测等）。
     */
    private void scheduleArtifactCleanup(String sessionId, Long projectId)
    {
        if (sessionId == null || sessionId.isBlank())
        {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive())
        {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization()
            {
                @Override
                public void afterCommit()
                {
                    cleanupSessionArtifacts(sessionId, projectId);
                }
            });
        }
        else
        {
            cleanupSessionArtifacts(sessionId, projectId);
        }
    }

    /**
     * 清理会话附属物：上下文留痕目录、工作区沙箱、工具预算计数。
     * 任一步失败只打日志，不回滚已提交的 DB 删除。
     */
    private void cleanupSessionArtifacts(String sessionId, Long projectId)
    {
        if (contextFileStore != null)
        {
            try
            {
                contextFileStore.deleteSession(sessionId);
            }
            catch (Exception e)
            {
                log.warn("删除会话上下文目录失败 sessionId={}: {}", sessionId, e.getMessage());
            }
        }
        if (toolBudgetRegistry != null)
        {
            try
            {
                toolBudgetRegistry.clearSession(sessionId);
            }
            catch (Exception e)
            {
                log.warn("清理会话工具预算失败 sessionId={}: {}", sessionId, e.getMessage());
            }
        }
        if (toolConfirmBroker != null)
        {
            try
            {
                toolConfirmBroker.cancelAll(sessionId);
            }
            catch (Exception e)
            {
                log.warn("清理会话工具确认等待失败 sessionId={}: {}", sessionId, e.getMessage());
            }
        }
        if (workspaceScopeService != null)
        {
            workspaceScopeService.evictSession(sessionId);
        }
        // 项目文件属于项目，不属于其中某个会话；删除单个会话时必须保留。
        if (projectId == null)
        {
            deleteWorkspaceDir(sessionId);
            deleteRemoteWorkspaceDir(sessionId);
        }
    }

    /**
     * 远端模式(mode=mcp)下同步清理 OPI 上的会话工作区。
     * <p>跟配置走:仅 {@code remoteWorkspaceService.enabled()} 时执行
     * (mode=mcp 且配了 remote-workspace-base-url);local 模式零开销。
     * <p>与本地删除同规则:只清本会话自己的 key,失败只打日志,不阻断删会话。
     */
    private void deleteRemoteWorkspaceDir(String sessionId)
    {
        if (remoteWorkspaceService == null || !remoteWorkspaceService.enabled())
        {
            return;
        }
        try
        {
            String workspaceKey = workspaceScopeService != null
                    ? workspaceScopeService.resolveWorkspaceKey(sessionId)
                    : sessionId;
            remoteWorkspaceService.clear(workspaceKey);
            log.info("已删除远端会话工作区: {}", workspaceKey);
        }
        catch (Exception e)
        {
            log.warn("删除远端会话工作区失败 sessionId={}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 删除 {workspaceRoot}/{sessionId} 整目录。
     * 仅在按会话隔离时执行；全局共用工作区时禁止整树删除。
     */
    private void deleteWorkspaceDir(String sessionId)
    {
        if (aiToolProperties == null)
        {
            return;
        }
        if (!aiToolProperties.isWorkspacePerSession())
        {
            log.debug("workspace-per-session=false，跳过删除会话工作区 sessionId={}", sessionId);
            return;
        }
        if (!WorkspaceSandbox.isValidSessionId(sessionId))
        {
            log.warn("拒绝删除非法 sessionId 的工作区: {}", sessionId);
            return;
        }
        try
        {
            Path root = WorkspaceSandbox.resolveRoot(aiToolProperties, sessionId, false);
            Path base = WorkspaceSandbox.resolveBaseRoot(aiToolProperties, false);
            if (!root.startsWith(base) || root.equals(base))
            {
                log.warn("拒绝删除越界/根工作区路径: {}", root);
                return;
            }
            if (!Files.exists(root))
            {
                return;
            }
            try (Stream<Path> walk = Files.walk(root))
            {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try
                    {
                        Files.deleteIfExists(p);
                    }
                    catch (IOException e)
                    {
                        log.warn("删除工作区文件失败 {}: {}", p, e.getMessage());
                    }
                });
            }
            log.info("已删除会话工作区: {}", root);
        }
        catch (Exception e)
        {
            log.warn("删除会话工作区失败 sessionId={}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 查询某会话下的全部智能体
     */
    @Override
    public List<AiChatSessionAgent> selectAgentsBySessionId(String sessionId)
    {
        return aiChatSessionMapper.selectAgentsBySessionId(sessionId);
    }

    /**
     * 接入智能体：若该 agent 未接入过本会话则新增关联，已存在则跳过。
     * 用于 worker 首次被 supervisor 路由到时自动登记。
     */
    @Override
    @Transactional
    public AiChatSessionAgent ensureAgentJoined(String sessionId, Long agentId, String role)
    {
        AiChatSessionAgent existing = aiChatSessionMapper.selectSessionAgent(sessionId, agentId);
        if (existing != null)
        {
            return existing;
        }
        AiChatSessionAgent agent = new AiChatSessionAgent();
        agent.setSessionId(sessionId);
        agent.setAgentId(agentId);
        agent.setRole(role != null ? role : "worker");
        agent.setTokensUsed(0L);
        agent.setTurnCount(0);
        aiChatSessionMapper.insertSessionAgent(agent);
        return agent;
    }

    /**
     * 记录一次 agent 调用的 token 消耗。
     * 同时更新关联表与会话表，全部原子加，并发安全。
     */
    @Override
    @Transactional
    public void recordTokenUsage(String sessionId, Long agentId, long tokens, long contextLength)
    {
        recordTokenUsage(sessionId, agentId, tokens, 0, 0, 0, contextLength);
    }

    @Override
    @Transactional
    public void recordTokenUsage(String sessionId, Long agentId, long tokens,
                                 long promptTokens, long completionTokens,
                                 int callCount, long contextLength)
    {
        if (agentId != null)
        {
            int rows = aiChatSessionMapper.addAgentTokenDetail(
                    sessionId, agentId, tokens, promptTokens, completionTokens, callCount);
            if (rows == 0)
            {
                ensureAgentJoined(sessionId, agentId, "worker");
                aiChatSessionMapper.addAgentTokenDetail(
                        sessionId, agentId, tokens, promptTokens, completionTokens, callCount);
            }
        }
        aiChatSessionMapper.addSessionTokenDetail(
                sessionId, tokens, promptTokens, completionTokens, callCount, contextLength);
    }

    /**
     * 保存会话选中的知识库(整组替换)。
     * <p>先对每个 kbId 做 USE 权限校验(KbAuthorizationService 挂在 SLAVE 且
     * NOT_SUPPORTED，会挂起本 MASTER 事务去读 PG，校验失败抛 403 使本事务整体回滚)，
     * 再删旧插新。kbIds 为 null/空 即清空。
     */
    @Override
    @Transactional
    public void saveSessionKbs(String sessionId, List<Long> kbIds)
    {
        if (sessionId == null || sessionId.isBlank())
        {
            return;
        }
        List<Long> target = kbIds == null ? new ArrayList<>() : kbIds;
        if (kbAuthorizationService != null)
        {
            for (Long kbId : target)
            {
                kbAuthorizationService.requireKb(kbId, KbAccessAction.USE);
            }
        }
        aiChatSessionKbMapper.deleteBySessionId(sessionId);
        if (!target.isEmpty())
        {
            aiChatSessionKbMapper.batchInsert(sessionId, target);
        }
        log.info("会话知识库已保存 sessionId={} kbIds={}", sessionId, target);
    }

    /**
     * 查询某会话选中的知识库ID，按选择顺序返回(装配 RAG 检索工具时用)。
     */
    @Override
    public List<Long> getSessionKbIds(String sessionId)
    {
        if (sessionId == null || sessionId.isBlank())
        {
            return new ArrayList<>();
        }
        return aiChatSessionKbMapper.selectKbIdsBySessionId(sessionId);
    }

    @Override
    public ClientDeclareResult declareClient(String sessionId, String clientType, String version,
                                             String toolsJson, Long userId, boolean admin)
    {
        AiChatSession session = sessionGuard.requireOwned(sessionId, userId, admin);
        String type = normalizeClientType(clientType);
        String ver = version == null ? "" : version.trim();
        String storedVer = session.getClientToolsVer();
        if (ver.equals(storedVer == null ? "" : storedVer))
        {
            return new ClientDeclareResult(false, session.getClientType() != null
                    ? session.getClientType() : type, List.of());
        }

        List<String> skipped = new ArrayList<>();
        int maxTools = channelToolProperties != null
                ? channelToolProperties.maxToolsPerSession() : ChannelToolSchemas.DEFAULT_MAX_TOOLS;
        List<ChannelToolDef> defs = ChannelToolSchemas.parseAndNormalize(toolsJson, skipped, maxTools);
        String canonical = ChannelToolSchemas.serialize(defs);

        // 空清单不许跨端抹掉已有清单。会话行只存得下一份 client_tools,谁后声明谁说了算,
        // 而 desktop 这类一个客户端工具都不注册的端,每次打开会话都会送来一份空清单 ——
        // 用户在 desktop 点开插件建的会话,插件那 15 个浏览器工具就地清零,之后模型
        // 再也看不到浏览器能力(线上实测发生过)。同端自己清空是合法的(工具下线),只挡跨端。
        if (defs.isEmpty() && !ChannelToolSchemas.parseQuietly(session.getClientTools()).isEmpty()
                && !java.util.Objects.equals(type, session.getClientType()))
        {
            log.warn("忽略 [{}] 的空客户端工具声明:会话 {} 已有 [{}] 声明的清单,不做跨端清空 userId={}",
                    type, session.getSessionId(), session.getClientType(), userId);
            return new ClientDeclareResult(false, session.getClientType(), List.of());
        }

        if (!java.util.Objects.equals(type, session.getClientType()))
        {
            log.info("会话客户端形态变更 sessionId={} {} -> {} userId={}",
                    session.getSessionId(), session.getClientType(), type, userId);
        }

        AiChatSession patch = new AiChatSession();
        patch.setSessionId(session.getSessionId());
        patch.setClientType(type);
        patch.setClientTools(canonical);
        patch.setClientToolsVer(ver);
        aiChatSessionMapper.updateClientDeclare(patch);
        return new ClientDeclareResult(true, type, skipped);
    }

    private static String normalizeClientType(String raw)
    {
        if (raw == null || raw.isBlank())
        {
            return "desktop";
        }
        String t = raw.trim().toLowerCase();
        if (t.length() > 32 || !t.matches("[a-z][a-z0-9_-]{0,31}"))
        {
            return "desktop";
        }
        return t;
    }
}
