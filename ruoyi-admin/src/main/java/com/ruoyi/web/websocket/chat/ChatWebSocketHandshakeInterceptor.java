package com.ruoyi.web.websocket.chat;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/** 在协议升级前原子消费票据，并将身份放入 WebSocket session attributes。 */
@Component
public class ChatWebSocketHandshakeInterceptor implements HandshakeInterceptor
{
    public static final String USER_ID = "chat.userId";
    public static final String USERNAME = "chat.username";
    public static final String ADMIN = "chat.admin";

    private final ChatWebSocketTicketService ticketService;

    public ChatWebSocketHandshakeInterceptor(ChatWebSocketTicketService ticketService)
    {
        this.ticketService = ticketService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes)
    {
        String ticket = UriComponentsBuilder.fromUri(request.getURI()).build()
                .getQueryParams().getFirst("ticket");
        ChatWebSocketIdentity identity = ticketService.consume(ticket);
        if (identity == null)
        {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(USER_ID, identity.getUserId());
        attributes.put(USERNAME, identity.getUsername());
        attributes.put(ADMIN, identity.isAdmin());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception)
    {
        // no-op
    }
}
