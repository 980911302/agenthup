package com.ruoyi.system.tool;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.ai.job.AiJobConstants;
import com.ruoyi.system.domain.AiChatRun;
import com.ruoyi.system.domain.AiChatSessionAgent;
import com.ruoyi.system.domain.AiJob;
import com.ruoyi.system.mapper.AiChatRunMapper;
import com.ruoyi.system.mapper.AiChatSessionMapper;
import com.ruoyi.system.service.IAiJobService;

/**
 * 智能体定时任务工具
 *
 * <p>让 agent 能在对话中自建/查询/启停/删除定时任务。owner/agentId/source 一律由
 * 服务端从 ThreadLocal 与会话上下文推导,绝不作为 {@code @ToolParam} 暴露 ——
 * 否则模型可越权给他人或其它智能体派活。
 *
 * <p>用 @Tool 注解,由 ToolConfig.builtinToolCallbackProvider 收录,
 * ToolSyncService 启动时同步到 ai_tool 表(tool_type=1)。需在智能体上手动勾选后才生效。
 *
 * @author ruoyi
 */
@Component
public class AiJobTools
{
    private static final Logger log = LoggerFactory.getLogger(AiJobTools.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private IAiJobService aiJobService;

    @Autowired
    private AiChatSessionMapper aiChatSessionMapper;

    @Autowired
    private AiChatRunMapper aiChatRunMapper;

    /**
     * 创建定时任务
     * <p>
     * cron 与 fireTime 互斥;agentId/owner/source 由上下文注入,配额与最小间隔走 IAiJobService。
     */
    @Tool(description = "创建定时任务:到点唤起当前主智能体执行 prompt。"
        + "周期填 cron、一次性填 fireTime,二选一;prompt 须自包含(无当前对话上下文)。")
    public String createScheduledJob(
        @ToolParam(description = "任务短名") String jobName,
        @ToolParam(description = "6位cron(秒分时日月周),如 0 0 9 * * ?;一次性任务勿填",
            required = false) String cron,
        @ToolParam(description = "一次性时刻 yyyy-MM-dd HH:mm:ss;周期任务勿填",
            required = false) String fireTime,
        @ToolParam(description = "到点执行的完整指令,勿依赖上文指代") String prompt,
        @ToolParam(description = "new每次新会话(默认);fixed固定会话追加",
            required = false) String sessionMode)
    {
        try
        {
            Long ownerUserId = OperatorContext.getUserId();
            if (ownerUserId == null)
            {
                return fail("无法识别当前操作者身份(未登录上下文),不允许创建定时任务");
            }

            boolean hasCron = StringUtils.isNotEmpty(cron);
            boolean hasFire = StringUtils.isNotEmpty(fireTime);
            if (hasCron == hasFire)
            {
                // 二者都填或都不填都会让触发语义歧义,必须在进 service 前拦住
                return fail("周期性任务填 cron,一次性任务填 fireTime,二者必须且只能填一个");
            }
            if (StringUtils.isEmpty(jobName))
            {
                return fail("任务名称不能为空");
            }
            if (StringUtils.isEmpty(prompt))
            {
                return fail("指令内容不能为空");
            }

            Long agentId = resolveSupervisorAgentId();
            if (agentId == null)
            {
                return fail("无法确定当前会话的主智能体,请在对话中重试");
            }

            String mode = StringUtils.isEmpty(sessionMode)
                    ? AiJobConstants.SESSION_MODE_NEW : sessionMode.trim();
            if (!AiJobConstants.SESSION_MODE_NEW.equals(mode)
                    && !AiJobConstants.SESSION_MODE_FIXED.equals(mode))
            {
                return fail("会话模式仅支持 new 或 fixed");
            }

            AiJob job = new AiJob();
            job.setJobName(jobName.trim());
            job.setPrompt(prompt);
            job.setAgentId(agentId);
            job.setOwnerUserId(ownerUserId);
            job.setSessionMode(mode);
            // source 固定 agent,可溯源到用户原话所在 run
            job.setSource("agent");
            job.setSourceRunId(resolveActiveRunId());
            job.setStatus(AiJobConstants.STATUS_NORMAL);
            String operator = OperatorContext.getUsername();
            job.setCreateBy(operator != null ? operator : "agent");

            if (hasCron)
            {
                job.setTriggerType(AiJobConstants.TRIGGER_CRON);
                job.setCronExpression(cron.trim());
            }
            else
            {
                Date fire = DateUtils.parseDate(fireTime.trim());
                if (fire == null)
                {
                    return fail("执行时刻格式错误,应为 yyyy-MM-dd HH:mm:ss");
                }
                job.setTriggerType(AiJobConstants.TRIGGER_ONCE);
                job.setFireTime(fire);
            }

            aiJobService.insertAiJob(job);

            Map<String, Object> result = new HashMap<>(8);
            result.put("success", true);
            result.put("jobId", job.getJobId());
            result.put("jobName", job.getJobName());
            result.put("triggerType", job.getTriggerType());
            result.put("cronExpression", job.getCronExpression());
            result.put("fireTime", job.getFireTime() != null
                    ? DateUtils.parseDateToStr(DateUtils.YYYY_MM_DD_HH_MM_SS, job.getFireTime()) : null);
            result.put("sessionMode", job.getSessionMode());
            result.put("agentId", job.getAgentId());
            result.put("nextFireTime", job.getNextFireTime() != null
                    ? DateUtils.parseDateToStr(DateUtils.YYYY_MM_DD_HH_MM_SS, job.getNextFireTime()) : null);
            return objectMapper.writeValueAsString(result);
        }
        catch (ServiceException e)
        {
            // 配额/最小间隔等业务校验错误原样转述给模型
            return fail(e.getMessage());
        }
        catch (Exception e)
        {
            log.warn("createScheduledJob 失败: {}", e.getMessage());
            return fail("创建定时任务失败: " + e.getMessage());
        }
    }

    /**
     * 查询当前用户已创建的定时任务
     */
    @Tool(description = "列出当前用户的定时任务(含 jobId)。改/停/删前先调此工具取 ID。")
    public String listScheduledJobs()
    {
        try
        {
            Long ownerUserId = OperatorContext.getUserId();
            if (ownerUserId == null)
            {
                return fail("无法识别当前操作者身份(未登录上下文),不允许查询定时任务");
            }

            List<AiJob> list = aiJobService.selectAiJobList(new AiJob(), ownerUserId, false);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (AiJob j : list)
            {
                Map<String, Object> item = new HashMap<>(12);
                item.put("jobId", j.getJobId());
                item.put("jobName", j.getJobName());
                item.put("agentId", j.getAgentId());
                item.put("agentName", j.getAgentName());
                item.put("triggerType", j.getTriggerType());
                item.put("cronExpression", j.getCronExpression());
                item.put("fireTime", j.getFireTime() != null
                        ? DateUtils.parseDateToStr(DateUtils.YYYY_MM_DD_HH_MM_SS, j.getFireTime()) : null);
                item.put("sessionMode", j.getSessionMode());
                item.put("status", j.getStatus());
                item.put("prompt", j.getPrompt());
                item.put("nextFireTime", j.getNextFireTime() != null
                        ? DateUtils.parseDateToStr(DateUtils.YYYY_MM_DD_HH_MM_SS, j.getNextFireTime()) : null);
                item.put("prevFireTime", j.getPrevFireTime() != null
                        ? DateUtils.parseDateToStr(DateUtils.YYYY_MM_DD_HH_MM_SS, j.getPrevFireTime()) : null);
                item.put("lastStatus", j.getLastStatus());
                item.put("source", j.getSource());
                rows.add(item);
            }
            Map<String, Object> result = new HashMap<>(4);
            result.put("success", true);
            result.put("total", rows.size());
            result.put("jobs", rows);
            return objectMapper.writeValueAsString(result);
        }
        catch (ServiceException e)
        {
            return fail(e.getMessage());
        }
        catch (Exception e)
        {
            log.warn("listScheduledJobs 失败: {}", e.getMessage());
            return fail("查询定时任务失败: " + e.getMessage());
        }
    }

    /**
     * 暂停或恢复定时任务
     * <p>
     * 非本人任务由 IAiJobService.changeStatus 的 owner 校验拦截,异常转 JSON 不中断对话。
     */
    @Tool(description = "暂停(status=1)或恢复(status=0)定时任务;jobId 来自 listScheduledJobs。永久删除用 deleteScheduledJob。")
    public String toggleScheduledJob(
        @ToolParam(description = "任务ID") Long jobId,
        @ToolParam(description = "0恢复 1暂停") String status)
    {
        try
        {
            Long ownerUserId = OperatorContext.getUserId();
            if (ownerUserId == null)
            {
                return fail("无法识别当前操作者身份(未登录上下文),不允许操作定时任务");
            }
            if (jobId == null)
            {
                return fail("任务ID不能为空");
            }
            if (StringUtils.isEmpty(status))
            {
                return fail("目标状态不能为空,0恢复 1暂停");
            }
            String st = status.trim();
            if (!AiJobConstants.STATUS_NORMAL.equals(st) && !AiJobConstants.STATUS_PAUSE.equals(st))
            {
                return fail("目标状态仅支持 0(恢复) 或 1(暂停)");
            }

            aiJobService.changeStatus(jobId, st, ownerUserId, false);

            Map<String, Object> result = new HashMap<>(4);
            result.put("success", true);
            result.put("jobId", jobId);
            result.put("status", st);
            return objectMapper.writeValueAsString(result);
        }
        catch (ServiceException e)
        {
            return fail(e.getMessage());
        }
        catch (Exception e)
        {
            log.warn("toggleScheduledJob 失败: {}", e.getMessage());
            return fail("变更定时任务状态失败: " + e.getMessage());
        }
    }

    /**
     * 删除定时任务
     * <p>
     * 非本人任务由 IAiJobService.deleteAiJobByIds 的 owner 校验拦截。
     */
    @Tool(description = "删除定时任务,不可恢复。仅用户明确要求删除时用;暂不跑用 toggle 暂停。jobId 来自 listScheduledJobs。")
    public String deleteScheduledJob(
        @ToolParam(description = "任务ID") Long jobId)
    {
        try
        {
            Long ownerUserId = OperatorContext.getUserId();
            if (ownerUserId == null)
            {
                return fail("无法识别当前操作者身份(未登录上下文),不允许删除定时任务");
            }
            if (jobId == null)
            {
                return fail("任务ID不能为空");
            }

            aiJobService.deleteAiJobByIds(new Long[] { jobId }, ownerUserId, false);

            Map<String, Object> result = new HashMap<>(4);
            result.put("success", true);
            result.put("jobId", jobId);
            return objectMapper.writeValueAsString(result);
        }
        catch (ServiceException e)
        {
            return fail(e.getMessage());
        }
        catch (Exception e)
        {
            log.warn("deleteScheduledJob 失败: {}", e.getMessage());
            return fail("删除定时任务失败: " + e.getMessage());
        }
    }

    /**
     * 从当前会话解析主智能体(role=supervisor)。
     * 不让模型传 agentId,避免给任意智能体派活。
     */
    private Long resolveSupervisorAgentId()
    {
        String sessionId = WorkspaceContext.getOrNull();
        if (StringUtils.isEmpty(sessionId))
        {
            return null;
        }
        List<AiChatSessionAgent> agents = aiChatSessionMapper.selectAgentsBySessionId(sessionId);
        if (agents == null || agents.isEmpty())
        {
            return null;
        }
        for (AiChatSessionAgent sa : agents)
        {
            if (sa != null && "supervisor".equals(sa.getRole()) && sa.getAgentId() != null)
            {
                return sa.getAgentId();
            }
        }
        return null;
    }

    /**
     * 取当前会话活动 run 的 runId,作为 source_run_id 溯源。
     * active_key = sessionId 表示该会话上仍在跑的那条 run。
     */
    private String resolveActiveRunId()
    {
        String sessionId = WorkspaceContext.getOrNull();
        if (StringUtils.isEmpty(sessionId))
        {
            return null;
        }
        AiChatRun run = aiChatRunMapper.selectActiveBySession(sessionId);
        return run != null ? run.getRunId() : null;
    }

    /** 统一失败 JSON,工具内绝不向外抛异常以免中断对话 */
    private String fail(String message)
    {
        try
        {
            Map<String, Object> result = new HashMap<>(4);
            result.put("success", false);
            result.put("message", message != null ? message : "未知错误");
            return objectMapper.writeValueAsString(result);
        }
        catch (Exception e)
        {
            return "{\"success\":false,\"message\":\"序列化失败\"}";
        }
    }
}
