package com.ruoyi.web.websocket.chat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** 对话 JSON-RPC WebSocket 注册。 */
@Configuration
@EnableWebSocket
public class ChatWebSocketConfig implements WebSocketConfigurer
{
    private final ChatJsonRpcWebSocketHandler handler;
    private final ChatWebSocketHandshakeInterceptor handshakeInterceptor;
    private final String[] allowedOriginPatterns;

    public ChatWebSocketConfig(ChatJsonRpcWebSocketHandler handler,
                               ChatWebSocketHandshakeInterceptor handshakeInterceptor,
                               @Value("${ai.chat.websocket.allowed-origin-patterns:*}")
                               String[] allowedOriginPatterns)
    {
        this.handler = handler;
        this.handshakeInterceptor = handshakeInterceptor;
        this.allowedOriginPatterns = allowedOriginPatterns;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry)
    {
        // 不显式放行时，Spring 追加的 OriginHandshakeInterceptor 只允许同源握手：
        // 前端跑在 80 端口经代理转发到 8080，Origin(:80) 与 Host(:8080) 端口不同，握手会被 403，
        // 浏览器只看到连接失败并无限重连。握手身份由一次性票据保证，同源判断不是这里的认证边界。
        registry.addHandler(handler, "/ws/ai/chat")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOriginPatterns(allowedOriginPatterns);
    }
}
