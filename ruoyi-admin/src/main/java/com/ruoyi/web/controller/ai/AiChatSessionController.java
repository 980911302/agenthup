package com.ruoyi.web.controller.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import com.alibaba.fastjson2.JSON;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;
import com.ruoyi.web.controller.ai.dto.ClientDeclareRequest;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.ai.ContextFileStore;
import com.ruoyi.system.ai.event.SpecialEventService;
import com.ruoyi.system.ai.session.SessionAccessGuard;
import com.ruoyi.system.ai.trace.AiTraceSpanService;
import com.ruoyi.system.domain.AiChatMessage;
import com.ruoyi.system.domain.AiChatRun;
import com.ruoyi.system.domain.AiChatSession;
import com.ruoyi.system.domain.AiChatSpecialEvent;
import com.ruoyi.system.domain.vo.DesktopViews;
import com.ruoyi.system.mapper.AiChatMessageMapper;
import com.ruoyi.system.mapper.AiChatRunMapper;
import com.ruoyi.system.service.IAiChatSessionService;

/**
 * AI 会话 信息操作处理
 *
 * <p>会话管理不再独立成菜单,而是集成在 AI 对话页的左侧列表里。
 * 这里只暴露会话列表 / 详情 / 删除 / 消息时间线四个端点。
 *
 * <p>消息时间线 {@code /timeline} 直接调 {@link AiChatMessageMapper#selectTimelineBySession},
 * 只读查询,不经过 service(该 mapper 方法已现成,避免跨层加壳)。
 *
 * @author ruoyi
 */
@RestController
@RequestMapping("/ai/chat/session")
public class AiChatSessionController extends BaseController
{
    @Autowired
    private IAiChatSessionService aiChatSessionService;

    @Autowired
    private AiChatMessageMapper aiChatMessageMapper;

    @Autowired
    private AiChatRunMapper aiChatRunMapper;

    /** 只为把「智能体自带技能」从本轮生效技能里减掉,拿到用户真正 @ 的那几个。30s TTL,稳态零 SQL。 */
    @Autowired
    private com.ruoyi.system.ai.agent.AgentAssemblyCache assemblyCache;

    @Autowired
    private ContextFileStore contextFileStore;

    @Autowired
    private AiTraceSpanService aiTraceSpanService;

    @Autowired
    private SessionAccessGuard sessionGuard;

    @Autowired(required = false)
    private SpecialEventService specialEventService;

    /** 历史加载时 TOOL 消息结果的前端预览上限(完整内容按需通过 tool-result 接口拉取) */
    @Value("${ai.chat.timeline.tool-result-preview:300}")
    private int toolResultPreview;

    /** 历史加载时 TOOL 消息入参的前端预览上限 */
    @Value("${ai.chat.timeline.tool-args-preview:200}")
    private int toolArgsPreview;

    /**
     * 查询会话列表(按 create_time 倒序)
     */
    @GetMapping("/list")
    public TableDataInfo list(AiChatSession aiChatSession)
    {
        // 对话页是个人工作区。即使管理员也只能查询自己的会话，忽略客户端传入的 userId。
        aiChatSession.setUserId(getUserId());
        startPage();
        List<AiChatSession> list = aiChatSessionService.selectAiChatSessionList(aiChatSession);
        return getDataTable(list.stream().map(DesktopViews::session).toList());
    }

    /**
     * 删除会话(逻辑删除 + 消息/run/LLM 明细 + 工作区/上下文/工具预算)
     */
    @Log(title = "AI会话", businessType = BusinessType.DELETE)
    @DeleteMapping("/{sessionIds}")
    @Transactional
    public AjaxResult remove(@PathVariable String[] sessionIds)
    {
        for (String sessionId : sessionIds)
        {
            requireOwnedSessionForUpdate(sessionId);
            requireNoActiveRun(sessionId);
        }
        return toAjax(aiChatSessionService.deleteAiChatSessionByIds(sessionIds));
    }

    /** 时间线分页默认/上限 */
    private static final int TIMELINE_DEFAULT_PAGE = 100;
    private static final int TIMELINE_MAX_PAGE = 300;

    /**
     * 查询会话消息时间线(游标分页,切换历史会话 / 加载更早用)。
     * <p>返回按 message_id 升序的一页消息(USER/ASSISTANT/TOOL/SUMMARY/THINKING),
     * 前端按 messageType 渲染。参数:
     * <ul>
     *   <li>limit:每页条数(默认 100,上限 300)</li>
     *   <li>beforeMessageId:只取 message_id 更小的消息(加载更早);缺省取最新一页</li>
     * </ul>
     * <p>返回 data = 消息数组(兼容旧结构),hasMore = 是否还有更早。
     * <p>边界保证:返回的第一条始终是 USER 消息(完整轮次),避免前端拼接出半轮。
     */
    @GetMapping(value = "/{sessionId}/timeline")
    public AjaxResult timeline(@PathVariable String sessionId,
                               @RequestParam(required = false) Integer limit,
                               @RequestParam(required = false) Long beforeMessageId)
    {
        requireOwnedSession(sessionId);
        int pageSize = limit == null ? TIMELINE_DEFAULT_PAGE
            : Math.max(1, Math.min(limit, TIMELINE_MAX_PAGE));

        // 倒序多取 1 条用于判断是否还有更早
        List<AiChatMessage> desc = aiChatMessageMapper.selectTimelinePage(
            sessionId, beforeMessageId, pageSize + 1);
        boolean hasMore = desc.size() > pageSize;
        if (hasMore)
        {
            desc.remove(desc.size() - 1);
        }
        Collections.reverse(desc);

        List<AiChatMessage> messages = desc;
        if (!messages.isEmpty() && !"USER".equals(messages.get(0).getMessageType()))
        {
            // 补齐完整轮次:找到本页第一条之前最近的 USER,从它开始取连续区间
            AiChatMessage prevUser = aiChatMessageMapper.selectPrevUserBefore(
                sessionId, beforeMessageId, messages.get(0).getMessageId());
            if (prevUser != null)
            {
                List<AiChatMessage> complete = aiChatMessageMapper.selectTimelineRangeFrom(
                    sessionId, prevUser.getMessageId(), beforeMessageId);
                if (!complete.isEmpty())
                {
                    messages = complete;
                    hasMore = aiChatMessageMapper.countBefore(sessionId,
                        complete.get(0).getMessageId()) > 0;
                }
            }
        }

        // 历史加载轻量化:TOOL 消息的入参/结果只返回前端展示用的预览,
        // 完整内容仍落库/落文件,前端点击「查看完整结果」时经 tool-result 接口按需拉取。
        for (AiChatMessage m : messages)
        {
            if ("TOOL".equals(m.getMessageType()))
            {
                m.setToolArgs(trimPreview(m.getToolArgs(), toolArgsPreview));
                m.setToolResult(trimPreview(m.getToolResult(), toolResultPreview));
            }
        }

        AjaxResult res = success(messages);
        res.put("hasMore", hasMore);
        res.put("runs", runStatesOf(messages));
        if (specialEventService != null)
        {
            res.put("specialEvents", specialEventService.summariesByMessage(sessionId));
        }
        return res;
    }

    /**
     * 本页消息涉及的运行终态,runId -> {status, errorMessage, skillIds}。
     *
     * <p>失败/取消/中断的那一轮不写 ASSISTANT_FINAL 行,光看消息账本分不出
     * 「这轮已经结束了」还是「还在跑」,前端只能一直渲染成「正在输入」。
     * 控制面事实在 ai_chat_run 上,所以时间线连它一起带出去。
     *
     * <p>注意不能只对账最新一轮(/run/latest):同一会话里更早的失败轮同样没有终态行,
     * 它们才是永久卡住打字点的那些。
     *
     * <p>skillIds 是该轮生效技能的快照(ai_chat_run.effective_skill_ids),
     * 前端「重新生成」要按当时那套技能重跑,所以随时间线一起带出。
     */
    private Map<String, Map<String, Object>> runStatesOf(List<AiChatMessage> messages)
    {
        Set<String> runIds = new LinkedHashSet<>();
        for (AiChatMessage m : messages)
        {
            if (StringUtils.isNotEmpty(m.getRunId()))
            {
                runIds.add(m.getRunId());
            }
        }
        Map<String, Map<String, Object>> states = new HashMap<>();
        if (runIds.isEmpty())
        {
            return states;
        }
        List<AiChatRun> runs = aiChatRunMapper.selectByRunIds(new ArrayList<>(runIds));
        for (AiChatRun run : runs)
        {
            Map<String, Object> one = new HashMap<>();
            one.put("status", run.getStatus());
            one.put("errorMessage", run.getErrorMessage());
            one.put("skillIds", atMentionedSkillIds(run));
            states.put(run.getRunId(), one);
        }
        return states;
    }

    /**
     * 本轮<b>用户 @ 选择</b>的技能(生效清单减掉智能体自带的)。
     *
     * <p>{@code effective_skill_ids} 存的是「智能体自带 ∪ @ 选择」的并集,直接给前端会让
     * 聊天气泡上多出几个用户根本没点过的技能。这里减掉自带的,只留用户的显式选择。
     *
     * <p>智能体的技能事后被改过会有轻微漂移(最多多显示一个),对展示无害;
     * 「重新生成」那条路本来就要按并集重跑,少给几个自带技能也不影响 ——
     * {@code resolveEffectiveSkillIds} 会重新并上。
     */
    private List<Long> atMentionedSkillIds(AiChatRun run)
    {
        List<Long> effective = parseSkillIds(run.getRunId(), run.getEffectiveSkillIds());
        if (effective.isEmpty() || run.getAgentId() == null)
        {
            return effective;
        }
        try
        {
            com.ruoyi.system.domain.AiAgent agent = assemblyCache.agent(run.getAgentId());
            if (agent == null || agent.getSkillIds() == null || agent.getSkillIds().length == 0)
            {
                return effective;
            }
            Set<Long> bound = new java.util.HashSet<>(java.util.Arrays.asList(agent.getSkillIds()));
            return effective.stream().filter(id -> !bound.contains(id)).toList();
        }
        catch (Exception ex)
        {
            // 智能体查不到不该让时间线失败,退回并集(最多多显示几个)
            logger.warn("解析 run[{}] 的 @ 技能失败,退回生效清单: {}", run.getRunId(), ex.getMessage());
            return effective;
        }
    }

    /**
     * 解析 ai_chat_run.effective_skill_ids(形如 "[100,101]")为技能 ID 列表。
     *
     * <p>历史数据里该列可能为 null / 空串 / 脏 JSON,这些一律降级成空列表:
     * 时间线是只读展示接口,不能因为一行快照解析不了就整页 500。
     */
    private List<Long> parseSkillIds(String runId, String raw)
    {
        if (StringUtils.isBlank(raw))
        {
            return List.of();
        }
        try
        {
            List<Long> parsed = JSON.parseArray(raw, Long.class);
            if (parsed == null)
            {
                return List.of();
            }
            // 数组里可能混入 null 元素(如 "[100,null]"),过滤掉再给前端
            return parsed.stream().filter(Objects::nonNull).toList();
        }
        catch (Exception ex)
        {
            logger.warn("解析 run[{}] 的 effective_skill_ids 失败,按空技能处理: {}", runId, raw, ex);
            return List.of();
        }
    }

    /**
     * 某回合某产物的完整 payload。摘要走时间线,正文点开才拉。
     */
    @GetMapping(value = "/{sessionId}/special-events")
    public AjaxResult specialEvents(@PathVariable String sessionId,
                                    @RequestParam(required = false) Long messageId,
                                    @RequestParam(required = false) String name)
    {
        requireOwnedSession(sessionId);
        if (specialEventService == null)
        {
            return success(List.of());
        }
        List<AiChatSpecialEvent> rows = specialEventService.payloadsAuthorized(
                sessionId, messageId, name, getUserId(), isAdmin());
        List<Map<String, Object>> data = new ArrayList<>();
        if (rows != null)
        {
            for (AiChatSpecialEvent row : rows)
            {
                Map<String, Object> item = new HashMap<>();
                item.put("name", row.getName());
                item.put("schemaVersion", row.getSchemaVersion());
                item.put("eventId", row.getEventId());
                item.put("payload", parseJson(row.getPayload()));
                data.add(item);
            }
        }
        return success(data);
    }

    /**
     * 按需拉取某条 TOOL 消息的完整工具结果(前端「查看完整结果」)。
     * <p>优先读外置大字段文件(tool_result_path),无外置则返回表内原文。
     */
    @GetMapping(value = "/{sessionId}/message/{messageId}/tool-result")
    public AjaxResult toolResult(@PathVariable String sessionId, @PathVariable Long messageId)
    {
        requireOwnedSession(sessionId);
        AiChatMessage msg = aiChatMessageMapper.selectMessageById(messageId);
        if (msg == null || !sessionId.equals(msg.getSessionId()))
        {
            throw new ServiceException("消息不存在");
        }
        String full = contextFileStore.loadExternal(msg.getToolResultPath());
        if (full == null)
        {
            full = msg.getToolResult();
        }
        Map<String, Object> data = new HashMap<>();
        data.put("toolResult", full);
        data.put("toolName", msg.getToolName());
        data.put("toolArgs", msg.getToolArgs());
        return success(data);
    }

    private static Object parseJson(String raw)
    {
        if (raw == null || raw.isBlank())
        {
            return null;
        }
        try
        {
            return JSON.parse(raw);
        }
        catch (Exception ex)
        {
            return raw;
        }
    }

    /** 超长文本截断为前端预览,并附「完整内容按需查看」提示。 */
    private String trimPreview(String s, int max)
    {
        if (s == null || s.length() <= max)
        {
            return s;
        }
        return s.substring(0, max) + "\n…[结果较长,完整内容点击「查看完整结果」]";
    }

    /**
     * 保存会话选中的知识库(会话中途改库，即时持久化；下一轮生效)。
     * <p>整组替换：body 为知识库ID数组，空数组表示清空；
     * 每个库校验当前用户 USE 权限(KbAuthorizationService.requireKb)。
     * <p>运行中禁止修改，避免本轮装配读到中间态。
     */
    @Log(title = "AI会话", businessType = BusinessType.UPDATE)
    @PutMapping("/{sessionId}/knowledge-bases")
    public AjaxResult saveKnowledgeBases(@PathVariable String sessionId,
                                         @RequestBody List<Long> kbIds)
    {
        requireOwnedSession(sessionId);
        requireNoActiveRun(sessionId);
        aiChatSessionService.saveSessionKbs(sessionId, kbIds);
        return success();
    }

    // 会话准入统一走 SessionAccessGuard，这里只做身份取值的适配。
    // 规则(是否加行锁、错误口径、越权审计日志)集中在 Guard 里，不在各 Controller 各写一份。

    private AiChatSession requireOwnedSession(String sessionId)
    {
        return sessionGuard.requireOwned(sessionId, getUserId(), isAdmin());
    }

    private void requireNoActiveRun(String sessionId)
    {
        sessionGuard.requireNoActiveRun(sessionId);
    }

    private AiChatSession requireOwnedSessionForUpdate(String sessionId)
    {
        return sessionGuard.requireOwnedForUpdate(sessionId, getUserId(), isAdmin());
    }

    /**
     * 会话内全部用户消息(右侧音轨导航:聊天区是分页的,音轨需要全量)。
     * 只返回 messageId/runId/content(预览)/createTime,数量上限 1000。
     *
     * <p>runId 是给前端对齐用的:本页实时发出的那一轮在页面上只有内存态 turn(没有
     * messageId),靠 runId 才能把库里的 messageId 回填上去,否则点音轨跳不过去。
     */
    @GetMapping(value = "/{sessionId}/user-messages")
    public AjaxResult userMessages(@PathVariable String sessionId,
                                   @RequestParam(required = false, defaultValue = "1000") Integer limit)
    {
        requireOwnedSession(sessionId);
        int cap = Math.max(1, Math.min(limit, 1000));
        List<AiChatMessage> list = aiChatMessageMapper.selectUserMessagesBySession(sessionId, cap);
        // 内容只给导航预览,完整文本在时间线/消息详情里按需取
        for (AiChatMessage m : list)
        {
            m.setContent(trimPreview(m.getContent(), 200));
            m.setToolArgs(null);
            m.setToolResult(null);
        }
        return success(list.stream().map(DesktopViews::userMessageNav).toList());
    }

    /**
     * 会话链路追踪:该会话内所有轮次(run)的聚合概览。
     * <p>数据来自 ai_trace_span(埋点降级时可能为空列表,不影响会话使用)。
     */
    @GetMapping(value = "/{sessionId}/traces")
    public AjaxResult sessionTraces(@PathVariable String sessionId,
                                    @RequestParam(required = false, defaultValue = "50") Integer limit)
    {
        requireOwnedSession(sessionId);
        AjaxResult res = success(aiTraceSpanService.sessionOverview(sessionId, limit));
        res.put("sessionId", sessionId);
        return res;
    }

    /**
     * 某一轮对话的调用树(扁平 spans,前端按 parentSpanId 组瀑布图)。
     */
    @GetMapping(value = "/{sessionId}/traces/{runId}")
    public AjaxResult runTrace(@PathVariable String sessionId, @PathVariable String runId)
    {
        requireOwnedSession(sessionId);
        AjaxResult res = success(aiTraceSpanService.runTrace(runId));
        res.put("sessionId", sessionId);
        return res;
    }

    /**
     * 客户端声明形态与工具清单。版本相同不写库。
     */
    @PostMapping("/{sessionId}/client")
    public AjaxResult declareClient(@PathVariable String sessionId,
                                    @Valid @RequestBody ClientDeclareRequest request)
    {
        requireOwnedSession(sessionId);
        String toolsJson = JSON.toJSONString(request.tools() != null ? request.tools() : List.of());
        var result = aiChatSessionService.declareClient(
                sessionId, request.clientType(), request.capabilitiesVersion(),
                toolsJson, getUserId(), isAdmin());
        Map<String, Object> body = new HashMap<>();
        body.put("ok", true);
        body.put("applied", result.applied());
        body.put("clientType", result.clientType());
        body.put("skipped", result.skipped());
        return AjaxResult.success(body);
    }

    private boolean isAdmin()
    {
        return SecurityUtils.isAdmin(getUserId());
    }
}
