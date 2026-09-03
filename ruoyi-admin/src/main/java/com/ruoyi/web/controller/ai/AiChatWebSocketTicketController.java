package com.ruoyi.web.controller.ai;

import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.web.websocket.chat.ChatWebSocketTicketService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 已认证 HTTP 客户端换取一次性 WebSocket 握手票据。 */
@RestController
@RequestMapping("/ai/chat/ws-ticket")
public class AiChatWebSocketTicketController extends BaseController
{
    private final ChatWebSocketTicketService ticketService;

    public AiChatWebSocketTicketController(ChatWebSocketTicketService ticketService)
    {
        this.ticketService = ticketService;
    }

    @PostMapping
    public AjaxResult issue()
    {
        String ticket = ticketService.issue(getUserId(), getUsername(),
                com.ruoyi.common.utils.SecurityUtils.isAdmin(getUserId()));
        return AjaxResult.success(Map.of("ticket", ticket,
                "expiresIn", ChatWebSocketTicketService.TTL_SECONDS));
    }
}

