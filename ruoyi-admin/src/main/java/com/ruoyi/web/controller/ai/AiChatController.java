package com.ruoyi.web.controller.ai;

import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.system.ai.ContextFileStore;
import com.ruoyi.system.ai.memory.ConversationIds;
import com.ruoyi.system.ai.session.SessionAccessGuard;
import com.ruoyi.system.ai.run.ChatTurnRunner;
import com.ruoyi.system.domain.AiChatMessage;
import com.ruoyi.system.domain.AiChatSession;
import com.ruoyi.system.domain.AiChatRun;
import com.ruoyi.system.mapper.AiChatMessageMapper;
import com.ruoyi.system.mapper.AiChatRunMapper;
import com.ruoyi.system.mapper.AiChatRunStepMapper;
import com.ruoyi.system.mapper.AiLlmCallMapper;

/**
 * AI 对话 信息操作处理
 *
 * <p>对话统一走「会话 + 智能体」的 Run 持久化链路({@code POST /ai/chat/run}),
 * {@code sessionId} 与 {@code agentId} 都是必填:
 * <ul>
 *   <li>{@code AgentContextFactory} 按 agent 装配系统提示词、工具与记忆键</li>
 *   <li>{@code ChatMemory}(DbChatMemory)是 LLM 实际上下文来源,读写 ai_chat_message</li>
 *   <li>{@code ContextFileStore} 做可审计留痕(人读快照)</li>
 *   <li>会话服务记 token / 上下文长度 / 参与的 agent;真实 usage 经 LlmCallCollector 落 ai_llm_call</li>
 * </ul>
 *
 * <p>历史上曾有「通用对话」(不选智能体、直接按 modelId 跟模型聊)的旁路,已下线:
 * 它绕过 agent 装配,拿不到工具、技能与消息级留痕,与平台的智能体模型不一致。
 *
 * @author ruoyi
 */
@RestController
@RequestMapping("/ai/chat")
public class AiChatController extends BaseController
{
    private static final Logger log = LoggerFactory.getLogger(AiChatController.class);


    @Autowired
    private ContextFileStore contextFileStore;

    @Autowired
    private ChatTurnRunner chatTurnRunner;

    @Autowired
    private AiLlmCallMapper aiLlmCallMapper;

    @Autowired
    private AiChatMessageMapper aiChatMessageMapper;

    @Autowired
    private AiChatRunMapper aiChatRunMapper;

    @Autowired
    private AiChatRunStepMapper aiChatRunStepMapper;

    @Autowired
    private com.ruoyi.system.tool.ToolBudgetRegistry toolBudgetRegistry;

    @Autowired
    private SessionAccessGuard sessionGuard;

    /**
     * 当前上下文用量(驱动前端刻度条)
     * GET /ai/chat/session/{sessionId}/context?agentId=
     */
    @GetMapping("/session/{sessionId}/context")
    public AjaxResult contextUsage(@PathVariable String sessionId,
                                   @RequestParam(required = false) Long agentId)
    {
        requireOwnedSession(sessionId);
        return AjaxResult.success(chatTurnRunner.buildContextUsage(sessionId, agentId, null));
    }

    /**
     * 清空指定业务会话的记忆 + 上下文文件
     */
    @DeleteMapping("/session/{sessionId}/memory")
    @Transactional
    public AjaxResult clearSessionMemory(@PathVariable String sessionId)
    {
        requireOwnedSessionForUpdate(sessionId);
        requireNoActiveRun(sessionId);
        aiChatRunMapper.unbindMessageIdsBySession(sessionId);
        aiLlmCallMapper.unbindMessageIdsBySession(sessionId);
        aiChatRunStepMapper.deleteBySessionId(sessionId);
        aiChatMessageMapper.deleteBySessionId(sessionId);
        // 清该会话下所有 agent 的上下文文件
        contextFileStore.deleteSession(sessionId);
        // 会话级工具累计随记忆一起清零
        if (toolBudgetRegistry != null)
        {
            toolBudgetRegistry.clearSession(sessionId);
        }
        return AjaxResult.success();
    }

    /**
     * 回滚最后一轮对话(重新生成前调用)。
     * <p>删除最后一条 USER 及其之后的全部消息(THINKING/TOOL/ASSISTANT)，
     * 前端随后用原文重新发送即可得到新回答。
     * <p>ai_llm_call 明细行保留、仅解绑 message_id：token 已真实消耗，统计不能丢。
     * <p>上下文留痕文件(ContextFileStore)不回滚 —— 它已降级为人读快照，
     * 保留重试痕迹反而有助于排查。
     *
     * @param sessionId 会话ID
     * @param agentId   智能体ID(A 轨必传，用于拼 conversationId)
     */
    @DeleteMapping("/session/{sessionId}/last-turn")
    @Transactional
    public AjaxResult rollbackLastTurn(@PathVariable String sessionId,
                                       @RequestParam(required = false) Long agentId)
    {
        requireOwnedSessionForUpdate(sessionId);
        requireNoActiveRun(sessionId);
        String conversationId = agentId != null ? ConversationIds.of(sessionId, agentId) : sessionId;
        AiChatMessage lastUser = aiChatMessageMapper.selectLatestUser(conversationId);
        if (lastUser == null)
        {
            return AjaxResult.error("没有可重新生成的对话");
        }
        // 先取出将被删除的消息 id 与外置路径，解绑/删文件（顺序不能反：删完就查不到了）
        List<Long> doomedIds = aiChatMessageMapper.selectIdsFrom(conversationId, lastUser.getMessageId());
        List<String> overflowPaths = aiChatMessageMapper.selectToolResultPathsFrom(
                conversationId, lastUser.getMessageId());
        if (doomedIds != null && !doomedIds.isEmpty())
        {
            try
            {
                aiLlmCallMapper.unbindMessageIds(doomedIds);
            }
            catch (Exception e)
            {
                log.warn("解绑 llm_call message_id 失败: {}", e.getMessage());
            }
        }
        int deleted = aiChatMessageMapper.deleteFromMessageId(conversationId, lastUser.getMessageId());
        if (overflowPaths != null)
        {
            for (String path : overflowPaths)
            {
                try
                {
                    contextFileStore.deleteExternal(path);
                }
                catch (Exception e)
                {
                    log.warn("回滚清理溢出文件失败 path={}: {}", path, e.getMessage());
                }
            }
        }
        if (lastUser.getRunId() != null)
        {
            aiChatRunStepMapper.deleteByRunId(lastUser.getRunId());
        }
        // ChatMemory 以 DB 为准时，删表即生效；若有 Redis 缓存层此处 conversation 会随下次 get 重载
        log.info("回滚最后一轮 session={} conversation={} 删除{}条", sessionId, conversationId, deleted);
        return AjaxResult.success();
    }

    // 会话准入统一走 SessionAccessGuard(规则与越权审计集中在那里)，这里只适配身份取值。

    private AiChatSession requireOwnedSession(String sessionId)
    {
        return sessionGuard.requireOwned(sessionId, getUserId(), SecurityUtils.isAdmin(getUserId()));
    }

    private AiChatSession requireOwnedSessionForUpdate(String sessionId)
    {
        return sessionGuard.requireOwnedForUpdate(sessionId, getUserId(), SecurityUtils.isAdmin(getUserId()));
    }

    private void requireNoActiveRun(String sessionId)
    {
        sessionGuard.requireNoActiveRun(sessionId);
    }

}
