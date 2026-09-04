package com.ruoyi.web.controller.ai;

import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.system.ai.run.ActiveChatRunException;
import com.ruoyi.system.ai.run.ChatRunAttachment;
import com.ruoyi.system.ai.run.ChatRunCreateCommand;
import com.ruoyi.system.ai.run.ChatRunService;
import com.ruoyi.system.domain.AiChatRun;
import com.ruoyi.system.domain.vo.DesktopViews;
import com.ruoyi.web.controller.ai.dto.ChatAttachment;
import com.ruoyi.web.controller.ai.dto.ChatRunRequest;
import com.ruoyi.web.controller.ai.dto.ToolConfirmRequest;
import com.ruoyi.web.controller.ai.dto.ToolResultRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/** 持久化对话运行 REST 入口；实时事件通过 JSON-RPC WebSocket 订阅。 */
@RestController
@RequestMapping("/ai/chat/run")
public class AiChatRunController extends BaseController
{
    private final ChatRunService runService;

    public AiChatRunController(ChatRunService runService)
    {
        this.runService = runService;
    }

    @PostMapping
    public AjaxResult create(@Valid @RequestBody ChatRunRequest request)
    {
        try
        {
            AiChatRun run = runService.create(new ChatRunCreateCommand(
                    request.getSessionId(), request.getAgentId(), request.getMessage(),
                    convertAttachments(request.getAttachments()), request.getClientRequestId(),
                    getUserId(), getUsername(), isAdmin(), request.getKbIds(),
                    request.getModelId(), request.getSkillIds(), request.getProjectId(),
                    request.getClientType(), request.getCapabilitiesVersion(),
                    request.getClientToolsJson()));
            return AjaxResult.success(DesktopViews.run(run));
        }
        catch (ActiveChatRunException e)
        {
            return new AjaxResult(409, e.getMessage(), DesktopViews.run(e.getActiveRun()));
        }
    }

    @GetMapping("/{runId}")
    public AjaxResult get(@PathVariable String runId)
    {
        return AjaxResult.success(DesktopViews.run(runService.get(runId, getUserId(), isAdmin())));
    }

    @GetMapping("/{runId}/state")
    public AjaxResult state(@PathVariable String runId)
    {
        return AjaxResult.success(DesktopViews.state(runService.state(runId, getUserId(), isAdmin())));
    }

    @GetMapping("/active")
    public AjaxResult active(@RequestParam String sessionId)
    {
        return AjaxResult.success(DesktopViews.run(runService.activeBySession(sessionId, getUserId(), isAdmin())));
    }

    @GetMapping("/latest")
    public AjaxResult latest(@RequestParam String sessionId)
    {
        return AjaxResult.success(DesktopViews.run(runService.latestBySession(sessionId, getUserId(), isAdmin())));
    }

    @PostMapping("/{runId}/cancel")
    public AjaxResult cancel(@PathVariable String runId)
    {
        return AjaxResult.success(DesktopViews.run(runService.cancel(runId, getUserId(), isAdmin())));
    }

    /**
     * 危险工具人工确认。
     */
    @PostMapping("/{runId}/tool-confirm")
    public AjaxResult toolConfirm(@PathVariable String runId, @Valid @RequestBody ToolConfirmRequest request)
    {
        boolean ok = runService.confirmTool(runId, request.confirmId(), request.approved(), getUserId(), isAdmin());
        return ok ? AjaxResult.success() : AjaxResult.error("确认已过期或不存在");
    }

    /**
     * 渠道工具结果回传。与 tool-confirm 对称。
     */
    @PostMapping("/{runId}/tool-result")
    public AjaxResult toolResult(@PathVariable String runId, @Valid @RequestBody ToolResultRequest request)
    {
        boolean matched = runService.completeChannelTool(
                runId, request.callId(), Boolean.TRUE.equals(request.ok()),
                request.result(), request.error(), request.mediaFileId(), request.workspacePath(),
                getUserId(), isAdmin());
        return AjaxResult.success(java.util.Map.of("ok", true, "matched", matched));
    }

    private boolean isAdmin()
    {
        return com.ruoyi.common.utils.SecurityUtils.isAdmin(getUserId());
    }

    private static List<ChatRunAttachment> convertAttachments(List<ChatAttachment> source)
    {
        if (source == null || source.isEmpty()) return List.of();
        List<ChatRunAttachment> result = new ArrayList<>(source.size());
        for (ChatAttachment item : source)
        {
            if (item == null) continue;
            ChatRunAttachment target = new ChatRunAttachment();
            target.setName(item.getName());
            target.setPath(item.getPath());
            target.setMime(item.getMime());
            target.setSize(item.getSize());
            result.add(target);
        }
        return result;
    }
}
