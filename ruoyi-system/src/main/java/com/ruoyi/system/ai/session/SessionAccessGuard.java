package com.ruoyi.system.ai.session;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.AiChatRun;
import com.ruoyi.system.domain.AiChatSession;
import com.ruoyi.system.domain.AiProject;
import com.ruoyi.system.mapper.AiChatRunMapper;
import com.ruoyi.system.mapper.AiChatSessionMapper;
import com.ruoyi.system.mapper.AiProjectMapper;

/**
 * 会话访问准入 —— 全系统唯一的会话鉴权入口。
 *
 * <p><b>为什么需要这个类</b>:在此之前,同一套「查会话 → 判空 → 比对 userId」的逻辑
 * 在 4 个类里复制了 10 份(3 个 Controller + ChatRunService),各自的错误消息、
 * 是否加行锁、是否允许会话不存在都略有差异。每新增一个吃 sessionId 的接口,
 * 就得靠作者记得复制一遍 —— 漏一次就是一个越权漏洞。收敛到这里之后,
 * 新接口只有一件事要做:调用本类的某个 require 方法。
 *
 * <p><b>为什么参数显式传 userId/admin 而不是内部读 SecurityUtils</b>:
 * WebSocket 的消息处理跑在独立线程池上,{@code SecurityContextHolder} 里没有认证信息,
 * 身份是握手阶段存进 session attributes 再显式往下传的(见 {@code ChatJsonRpcWebSocketHandler})。
 * 内部隐式取当前用户会在 WS 路径上静默取到 null,那正是最不该出错的地方。
 *
 * <p><b>关于错误消息</b>:对外统一为「会话不存在或无权访问」,不区分两种情况 ——
 * 区分开就等于给出一个存在性预言机,任何登录用户都能拿 id 探测某会话是否存在。
 * 真实原因连同尝试者身份写进 warn 日志,既不影响排障,也让越权尝试可被审计。
 *
 * @author ruoyi
 */
@Component
public class SessionAccessGuard
{
    private static final Logger log = LoggerFactory.getLogger(SessionAccessGuard.class);

    /** 对外统一口径:不泄露「存在但不属于你」与「根本不存在」的区别 */
    private static final String DENIED = "会话不存在或无权访问";

    private final AiChatSessionMapper sessionMapper;
    private final AiChatRunMapper runMapper;

    @Autowired(required = false)
    private AiProjectMapper projectMapper;

    public SessionAccessGuard(AiChatSessionMapper sessionMapper, AiChatRunMapper runMapper)
    {
        this.sessionMapper = sessionMapper;
        this.runMapper = runMapper;
    }

    /**
     * 会话必须存在且属于当前用户。会话是个人数据，管理员也不能跨用户访问。
     *
     * <p>只读接口的标准准入:时间线、用户消息、链路追踪、上下文用量、工作区浏览。
     */
    public AiChatSession requireOwned(String sessionId, Long userId, boolean admin)
    {
        SessionIds.requireWellFormed(sessionId);
        AiChatSession session = sessionMapper.selectAiChatSessionById(SessionIds.normalize(sessionId));
        return checkOwnership(session, sessionId, userId, admin);
    }

    /**
     * 同 {@link #requireOwned},但对会话主行加行锁。
     *
     * <p>写接口用:删除会话、清空记忆、回滚最后一轮。与创建运行时的加锁是同一把,
     * 避免「检查通过后会话被并发删除、同时又有新运行写进来」的竞态。
     */
    public AiChatSession requireOwnedForUpdate(String sessionId, Long userId, boolean admin)
    {
        SessionIds.requireWellFormed(sessionId);
        AiChatSession session = sessionMapper.selectAiChatSessionByIdForUpdate(SessionIds.normalize(sessionId));
        return checkOwnership(session, sessionId, userId, admin);
    }

    /**
     * 会话存在则必须属于当前用户;不存在则放行,返回 {@code null}。
     *
     * <p><b>只给一种场景用:新会话在首轮发送前的事件预订阅。</b>
     * 此时会话尚未落库,查不到属主,拒绝订阅会导致新标签页要等第一轮发完才能同步。
     *
     * <p><b>这个放行口子的安全边界</b>:调用方必须自行保证泄露面仅限于生命周期元数据
     * (有无活动运行、runId、状态),事件正文必须另按 runId 校验属主。同时,
     * 因为「id 不存在」这条分支无法比对属主,它的安全性完全依赖 id 不可预测 ——
     * 这正是 {@link SessionIds#requireStrongForNew} 存在的理由。
     */
    public AiChatSession requireOwnedIfExists(String sessionId, Long userId, boolean admin)
    {
        SessionIds.requireWellFormed(sessionId);
        AiChatSession session = sessionMapper.selectAiChatSessionById(SessionIds.normalize(sessionId));
        if (session == null)
        {
            return null;
        }
        return checkOwnership(session, sessionId, userId, admin);
    }

    /**
     * 取得会话,不存在则以当前用户为属主建出来。
     *
     * <p>给「首轮发送」和「发送前先传附件」两条会话创建路径用。
     * 新建分支要求 id 达到 {@link SessionIds#requireStrongForNew} 的强度;
     * 已存在分支只校验属主,不追溯 id 强度 —— 否则存量短 id 的老会话会突然不可用。
     *
     * <p>用 {@code insert ignore} 而非「先查后插」:并发首轮(多标签页同时发送)下,
     * 先查后插会两边都判定不存在然后主键冲突。insert ignore 之后必须<b>重新读一次</b>
     * 再判属主 —— 若该 id 已被他人占用,ignore 会静默跳过,只有重读才能发现。
     */
    public AiChatSession requireOrCreate(String sessionId, Long userId, String username, boolean admin,
                                         String title, String sessionType)
    {
        return requireOrCreate(sessionId, userId, username, admin, title, sessionType, null);
    }

    public AiChatSession requireOrCreate(String sessionId, Long userId, String username, boolean admin,
                                         String title, String sessionType, Long projectId)
    {
        SessionIds.requireWellFormed(sessionId);
        String id = SessionIds.normalize(sessionId);
        AiChatSession existing = sessionMapper.selectAiChatSessionById(id);
        if (existing != null)
        {
            AiChatSession owned = checkOwnership(existing, sessionId, userId, admin);
            if (projectId != null && !Objects.equals(projectId, owned.getProjectId()))
            {
                throw new ServiceException("会话所属项目不一致，请重新新建项目会话");
            }
            return owned;
        }

        SessionIds.requireStrongForNew(id);
        requireOwnedProject(projectId, userId);
        AiChatSession candidate = new AiChatSession();
        candidate.setSessionId(id);
        candidate.setTitle(title == null ? "" : title);
        candidate.setSessionType(sessionType);
        candidate.setUserId(userId);
        candidate.setStatus("0");
        candidate.setTotalTokens(0L);
        candidate.setContextLength(0L);
        candidate.setCreateBy(username);
        candidate.setProjectId(projectId);   // 仅新建生效;存量 insert ignore 不覆盖
        sessionMapper.insertAiChatSessionIfAbsent(candidate);

        // 必须重读:insert ignore 在 id 已被他人占用时是静默 no-op,
        // 直接返回 candidate 等于把别人的会话当成自己的。
        AiChatSession created = sessionMapper.selectAiChatSessionById(id);
        return checkOwnership(created, sessionId, userId, admin);
    }

    private void requireOwnedProject(Long projectId, Long userId)
    {
        if (projectId == null)
        {
            return;
        }
        if (projectMapper == null)
        {
            throw new ServiceException("项目服务不可用");
        }
        AiProject project = projectMapper.selectAiProjectById(projectId);
        if (project == null || userId == null || !Objects.equals(project.getUserId(), userId))
        {
            throw new ServiceException("项目不存在");
        }
    }

    /** 会话上不能有正在执行的运行 —— 删除/清空/回滚这类破坏性操作的前置条件。 */
    public void requireNoActiveRun(String sessionId)
    {
        AiChatRun active = runMapper.selectActiveBySession(SessionIds.normalize(sessionId));
        if (active != null)
        {
            throw new ServiceException("会话正在执行，请先停止运行再修改");
        }
    }

    /** 运行必须属于当前用户 —— 事件正文订阅、取消运行的准入。 */
    public AiChatRun requireOwnedRun(AiChatRun run, Long userId, boolean admin)
    {
        if (run == null)
        {
            throw new ServiceException("运行不存在或无权访问");
        }
        if (!Objects.equals(run.getUserId(), userId))
        {
            log.warn("越权访问运行被拒: runId={}, owner={}, requester={}",
                    run.getRunId(), run.getUserId(), userId);
            throw new ServiceException("运行不存在或无权访问");
        }
        return run;
    }

    private AiChatSession checkOwnership(AiChatSession session, String sessionId, Long userId, boolean admin)
    {
        if (session == null)
        {
            throw new ServiceException(DENIED);
        }
        // userId 为 null 的会话行(历史脏数据)对任何用户都不可访问,
        // 否则 Objects.equals(null, null) 会让未登录态意外通过。
        if (session.getUserId() == null || !Objects.equals(session.getUserId(), userId))
        {
            log.warn("越权访问会话被拒: sessionId={}, owner={}, requester={}",
                    sessionId, session.getUserId(), userId);
            throw new ServiceException(DENIED);
        }
        return session;
    }
}
