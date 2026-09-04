package com.ruoyi.web.websocket.chat;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.ai.run.ActiveChatRunException;
import com.ruoyi.system.ai.run.ChatRunAttachment;
import com.ruoyi.system.ai.run.ChatRunCreateCommand;
import com.ruoyi.system.ai.run.ChatRunEventBroker;
import com.ruoyi.system.ai.run.ChatRunEventEnvelope;
import com.ruoyi.system.ai.run.ChatRunService;
import com.ruoyi.system.domain.AiChatRun;
import com.ruoyi.system.domain.vo.DesktopViews;
import com.ruoyi.system.service.IAiChatSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;

/** JSON-RPC 2.0 WebSocket 入口。 */
@Component
public class ChatJsonRpcWebSocketHandler extends TextWebSocketHandler
{
    private static final Logger log = LoggerFactory.getLogger(ChatJsonRpcWebSocketHandler.class);

    private final ChatSubscriptionRegistry registry;
    private final ChatRunEventBroker eventBroker;
    private final ChatRunService runService;
    private final IAiChatSessionService sessionService;

    public ChatJsonRpcWebSocketHandler(ChatSubscriptionRegistry registry,
                                       ChatRunEventBroker eventBroker,
                                       ChatRunService runService,
                                       IAiChatSessionService sessionService)
    {
        this.registry = registry;
        this.eventBroker = eventBroker;
        this.runService = runService;
        this.sessionService = sessionService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session)
    {
        registry.connect(session);
        JSONObject params = new JSONObject();
        params.put("connectionId", session.getId());
        params.put("serverTime", System.currentTimeMillis());
        sendNotification(session.getId(), "chat.ready", params);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message)
    {
        JSONObject request;
        try
        {
            request = JSON.parseObject(message.getPayload());
        }
        catch (JSONException | IllegalArgumentException e)
        {
            sendError(session.getId(), null, -32700, "Parse error", null);
            return;
        }
        Object id = request != null ? request.get("id") : null;
        boolean hasId = request != null && request.containsKey("id");
        if (request == null || !"2.0".equals(request.getString("jsonrpc"))
                || !(request.get("method") instanceof String method) || method.isBlank()
                || (hasId && id != null && !(id instanceof String) && !(id instanceof Number)))
        {
            // 无法构成合法 Notification 时，应按 JSON-RPC 2.0 返回 id=null 的 Invalid Request。
            sendError(session.getId(), hasId ? id : null, -32600, "Invalid Request", null);
            return;
        }

        try
        {
            Object paramsValue = request.get("params");
            if (paramsValue != null && !(paramsValue instanceof JSONObject))
            {
                throw new JsonRpcException(-32602, "Invalid params: object expected", null);
            }
            Object result = dispatch(session, method, (JSONObject) paramsValue);
            if (hasId)
            {
                sendResult(session.getId(), id, result);
            }
        }
        catch (JsonRpcException e)
        {
            if (hasId) sendError(session.getId(), id, e.code, e.getMessage(), e.data);
        }
        catch (ActiveChatRunException e)
        {
            if (hasId) sendError(session.getId(), id, -32009, e.getMessage(), DesktopViews.run(e.getActiveRun()));
        }
        catch (ServiceException e)
        {
            if (hasId) sendError(session.getId(), id, -32000, e.getMessage(), null);
        }
        catch (Exception e)
        {
            log.error("处理 JSON-RPC 请求失败: method={}", request.getString("method"), e);
            if (hasId) sendError(session.getId(), id, -32603, "Internal error", null);
        }
    }

    private Object dispatch(WebSocketSession socket, String method, JSONObject params)
    {
        Long userId = (Long) socket.getAttributes().get(ChatWebSocketHandshakeInterceptor.USER_ID);
        String username = (String) socket.getAttributes().get(ChatWebSocketHandshakeInterceptor.USERNAME);
        boolean admin = Boolean.TRUE.equals(socket.getAttributes().get(ChatWebSocketHandshakeInterceptor.ADMIN));
        JSONObject safeParams = params != null ? params : new JSONObject();

        return switch (method)
        {
            case "chat.ping" -> ping();
            case "chat.run.create" -> createRun(safeParams, userId, username, admin);
            case "chat.run.get" -> DesktopViews.run(runService.get(requiredString(safeParams, "runId"), userId, admin));
            case "chat.run.cancel" -> runService.cancel(requiredString(safeParams, "runId"), userId, admin);
            case "chat.run.subscribe" -> subscribe(socket.getId(), safeParams, userId, admin);
            case "chat.run.unsubscribe" -> unsubscribe(socket.getId(), safeParams);
            case "chat.session.subscribe" -> subscribeSession(socket.getId(), safeParams, userId, admin);
            case "chat.session.unsubscribe" -> unsubscribeSession(socket.getId(), safeParams);
            case "chat.session.client.declare" -> declareClient(safeParams, userId, admin);
            case "chat.tool.result" -> completeChannelTool(safeParams, userId, admin);
            default -> throw new JsonRpcException(-32601, "Method not found", method);
        };
    }

    private Object createRun(JSONObject params, Long userId, String username, boolean admin)
    {
        Object rawAttachments = params.get("attachments");
        if (rawAttachments != null && !(rawAttachments instanceof JSONArray))
        {
            throw new JsonRpcException(-32602, "Invalid params: attachments must be an array", null);
        }
        List<ChatRunAttachment> attachments;
        try
        {
            attachments = rawAttachments == null ? List.of()
                    : ((JSONArray) rawAttachments).toJavaList(ChatRunAttachment.class).stream()
                    .filter(java.util.Objects::nonNull).toList();
        }
        catch (RuntimeException e)
        {
            throw new JsonRpcException(-32602, "Invalid params: invalid attachment", null);
        }
        List<Long> kbIds;
        Object rawKbIds = params.get("kbIds");
        if (rawKbIds != null && !(rawKbIds instanceof JSONArray))
        {
            throw new JsonRpcException(-32602, "Invalid params: kbIds must be an array", null);
        }
        try
        {
            // null 表示前端未传(不动已有选择)；空数组表示清空。
            kbIds = rawKbIds == null ? null : ((JSONArray) rawKbIds).toJavaList(Long.class);
        }
        catch (RuntimeException e)
        {
            throw new JsonRpcException(-32602, "Invalid params: invalid kbId", null);
        }
        return DesktopViews.run(runService.create(new ChatRunCreateCommand(
                requiredString(params, "sessionId"), params.getLong("agentId"),
                params.getString("message"), attachments, params.getString("clientRequestId"),
                userId, username, admin, kbIds, params.getLong("modelId"),
                toLongList(params.get("skillIds"), "skillIds"), params.getLong("projectId"),
                params.getString("clientType"), params.getString("capabilitiesVersion"),
                rawJson(params, "clientTools"))));
    }

    private Object subscribe(String socketId, JSONObject params, Long userId, boolean admin)
    {
        String runId = requiredString(params, "runId");
        long afterSeq = nonNegativeLong(params, "afterSeq", 0L);
        AiChatRun run = runService.get(runId, userId, admin);

        ChatSubscriptionRegistry.Subscription subscription = registry.subscribe(socketId, runId, afterSeq);
        List<ChatRunEventEnvelope> replay = eventBroker.replay(runId, afterSeq);
        registry.replay(subscription, replay);
        // 补发挂起中的渠道工具请求。必须在 replay 之后:补发是一条新 seq 的实时事件,
        // 回放未结束时它只会被压进 pending 队列;更要紧的是 afterSeq 取自 snapshot_seq,
        // 原请求就落在游标之前,replay 本身根本捞不回来。
        int redelivered = runService.redeliverChannelTools(runId);

        JSONObject result = new JSONObject();
        // 再读一次状态，覆盖“第一次查询与注册订阅之间刚好结束”的竞态。
        // 即使 Redis Stream 暂时不可用，客户端也能用该快照退出执行中状态。
        result.put("run", DesktopViews.run(runService.get(runId, userId, admin)));
        result.put("replayed", replay.size());
        result.put("redelivered", redelivered);
        result.put("lastSeq", subscription.lastSentSeq());
        return result;
    }

    private Object unsubscribe(String socketId, JSONObject params)
    {
        String runId = requiredString(params, "runId");
        registry.unsubscribe(socketId, runId);
        return java.util.Map.of("runId", runId, "subscribed", false);
    }

    /**
     * 订阅整个会话的运行生命周期，用于同一会话在多个标签页/浏览器之间实时同步。
     *
     * <p>返回当前活动运行，订阅方可以立刻挂上正在执行的那一轮；之后新一轮开始时
     * 会收到 {@code chat.session.event} 通知，再按 runId 订阅完整事件流。</p>
     */
    private Object subscribeSession(String socketId, JSONObject params, Long userId, boolean admin)
    {
        String sessionId = requiredString(params, "sessionId");
        // 准入校验必须先于注册，否则无权访问的会话也会被挂上监听。
        AiChatRun active = runService.watchableActiveRun(sessionId, userId, admin);
        registry.watchSession(socketId, sessionId);

        JSONObject result = new JSONObject();
        result.put("sessionId", sessionId);
        result.put("activeRun", DesktopViews.run(active));
        return result;
    }

    private Object unsubscribeSession(String socketId, JSONObject params)
    {
        String sessionId = requiredString(params, "sessionId");
        registry.unwatchSession(socketId, sessionId);
        return java.util.Map.of("sessionId", sessionId, "subscribed", false);
    }

    private Object declareClient(JSONObject params, Long userId, boolean admin)
    {
        String sessionId = requiredString(params, "sessionId");
        String clientType = params.getString("clientType");
        String version = requiredString(params, "capabilitiesVersion");
        Object rawTools = params.get("tools");
        String toolsJson = rawTools == null ? "[]" : JSON.toJSONString(rawTools);
        var result = sessionService.declareClient(sessionId, clientType, version, toolsJson, userId, admin);
        JSONObject body = new JSONObject();
        body.put("ok", true);
        body.put("applied", result.applied());
        body.put("clientType", result.clientType());
        body.put("skipped", result.skipped());
        return body;
    }

    private Object completeChannelTool(JSONObject params, Long userId, boolean admin)
    {
        String runId = requiredString(params, "runId");
        String callId = requiredString(params, "callId");
        boolean ok = Boolean.TRUE.equals(params.getBoolean("ok"));
        boolean matched = runService.completeChannelTool(
                runId, callId, ok, params.getString("result"), params.getString("error"),
                params.getLong("mediaFileId"), params.getString("workspacePath"), userId, admin);
        return java.util.Map.of("ok", true, "matched", matched);
    }

    private static Object ping()
    {
        return java.util.Map.of("pong", true, "serverTime", System.currentTimeMillis());
    }

    /** 客户端工具清单原样取 JSON 文本；解析与校验都在 ChannelToolSchemas 里做。 */
    private static String rawJson(JSONObject params, String name)
    {
        Object v = params.get(name);
        return v == null ? null : JSON.toJSONString(v);
    }

    private static String requiredString(JSONObject params, String name)
    {
        String value = params.getString(name);
        if (value == null || value.isBlank())
        {
            throw new JsonRpcException(-32602, "Invalid params: " + name + " is required", null);
        }
        return value;
    }

    private static List<Long> toLongList(Object raw, String name)
    {
        if (raw == null)
        {
            return null;
        }
        if (!(raw instanceof JSONArray values))
        {
            throw new JsonRpcException(-32602, "Invalid params: " + name + " must be an array", null);
        }
        try
        {
            return values.toJavaList(Long.class);
        }
        catch (RuntimeException e)
        {
            throw new JsonRpcException(-32602, "Invalid params: invalid " + name, null);
        }
    }

    private static long nonNegativeLong(JSONObject params, String name, long defaultValue)
    {
        Object raw = params.get(name);
        if (raw == null) return defaultValue;
        if (!(raw instanceof Number number) || number.longValue() < 0)
        {
            throw new JsonRpcException(-32602,
                    "Invalid params: " + name + " must be a non-negative number", null);
        }
        return number.longValue();
    }

    private void sendResult(String socketId, Object id, Object result)
    {
        JSONObject response = new JSONObject();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        registry.send(socketId, response.toJSONString());
    }

    private void sendError(String socketId, Object id, int code, String message, Object data)
    {
        JSONObject error = new JSONObject();
        error.put("code", code);
        error.put("message", message);
        if (data != null) error.put("data", data);
        JSONObject response = new JSONObject();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", error);
        registry.send(socketId, response.toJSONString());
    }

    private void sendNotification(String socketId, String method, Object params)
    {
        JSONObject notification = new JSONObject();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        notification.put("params", params);
        registry.send(socketId, notification.toJSONString());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status)
    {
        registry.disconnect(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception
    {
        log.debug("WebSocket 传输错误: connectionId={}", session.getId(), exception);
        registry.disconnect(session.getId());
        if (session.isOpen())
        {
            try
            {
                session.close(CloseStatus.SERVER_ERROR);
            }
            catch (IOException ignored) { }
        }
    }

    private static final class JsonRpcException extends RuntimeException
    {
        private final int code;
        private final Object data;

        private JsonRpcException(int code, String message, Object data)
        {
            super(message);
            this.code = code;
            this.data = data;
        }
    }
}
