package com.ruoyi.web.websocket.chat;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.system.ai.run.ChatRunEventEnvelope;
import com.ruoyi.system.ai.run.RunSubscriberPresence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本实例 WebSocket 连接与 run 订阅注册表。
 *
 * <p>映射是 runId → 多个 socket，同一会话在多个标签页打开时每个订阅者都会收到事件。</p>
 *
 * <p>同时实现 {@link RunSubscriberPresence}：渠道工具挂起等待时要知道「这一轮还有没有
 * 客户端在线」，这张表就是答案。</p>
 */
@Component
public class ChatSubscriptionRegistry implements RunSubscriberPresence
{
    private static final Logger log = LoggerFactory.getLogger(ChatSubscriptionRegistry.class);
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int SEND_BUFFER_LIMIT_BYTES = 1024 * 1024;

    /**
     * 会话级只转发生命周期事件。token 级正文由订阅方拿到 runId 后走 {@code chat.run.subscribe}
     * 从 run 通道取(带 seq 去重与回放)，这里再推一遍只会重复放大流量。
     */
    private static final Set<String> LIFECYCLE_TYPES =
            Set.of("run_status", "done", "error", "cancelled", "interrupted");

    private final TaskExecutor sendExecutor;
    private final ConcurrentHashMap<String, Connection> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Subscription>> subscriptionsByRun =
            new ConcurrentHashMap<>();
    /** sessionId -> socketId -> 连接。同一会话在多个标签页/浏览器打开时互相同步靠它。 */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Connection>> watchersBySession =
            new ConcurrentHashMap<>();

    public ChatSubscriptionRegistry(
            @Qualifier("chatWebSocketSendExecutor") TaskExecutor sendExecutor)
    {
        this.sendExecutor = sendExecutor;
    }

    public void connect(WebSocketSession session)
    {
        Connection connection = new Connection(new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES));
        connections.put(session.getId(), connection);
    }

    public void disconnect(String socketId)
    {
        Connection connection = connections.remove(socketId);
        if (connection == null) return;
        connection.dispose();
        for (String runId : Set.copyOf(connection.runIds))
        {
            unsubscribe(socketId, runId);
        }
        for (String sessionId : Set.copyOf(connection.sessionIds))
        {
            unwatchSession(socketId, sessionId);
        }
    }

    /** 监听整个会话：本会话上任何一轮运行的开始/结束都会推给该连接。 */
    public void watchSession(String socketId, String sessionId)
    {
        Connection connection = connections.get(socketId);
        if (connection == null)
        {
            throw new IllegalStateException("WebSocket 连接不存在");
        }
        watchersBySession.computeIfAbsent(sessionId, key -> new ConcurrentHashMap<>())
                .put(socketId, connection);
        connection.sessionIds.add(sessionId);
    }

    public void unwatchSession(String socketId, String sessionId)
    {
        ConcurrentHashMap<String, Connection> watchers = watchersBySession.get(sessionId);
        if (watchers != null)
        {
            watchers.remove(socketId);
            if (watchers.isEmpty())
            {
                watchersBySession.remove(sessionId, watchers);
            }
        }
        Connection connection = connections.get(socketId);
        if (connection != null)
        {
            connection.sessionIds.remove(sessionId);
        }
    }

    public Subscription subscribe(String sessionId, String runId, long afterSeq)
    {
        Connection connection = connections.get(sessionId);
        if (connection == null)
        {
            throw new IllegalStateException("WebSocket 连接不存在");
        }
        unsubscribe(sessionId, runId);
        Subscription subscription = new Subscription(connection, runId, Math.max(afterSeq, 0));
        subscriptionsByRun.computeIfAbsent(runId, key -> new ConcurrentHashMap<>())
                .put(sessionId, subscription);
        connection.runIds.add(runId);
        return subscription;
    }

    public void replay(Subscription subscription, List<ChatRunEventEnvelope> events)
    {
        for (ChatRunEventEnvelope event : events)
        {
            subscription.acceptReplay(event);
        }
        subscription.completeReplay();
    }

    public void unsubscribe(String sessionId, String runId)
    {
        ConcurrentHashMap<String, Subscription> subscriptions = subscriptionsByRun.get(runId);
        if (subscriptions != null)
        {
            subscriptions.remove(sessionId);
            if (subscriptions.isEmpty())
            {
                subscriptionsByRun.remove(runId, subscriptions);
            }
        }
        Connection connection = connections.get(sessionId);
        if (connection != null)
        {
            connection.runIds.remove(runId);
        }
    }

    /**
     * 本实例上是否还有活着的连接订阅着这一轮。
     *
     * <p>连接断开会经 {@link #disconnect} 把订阅摘掉，所以正常情况下表里就是准的；
     * 这里额外过一道 {@code isOpen()}，挡住「已经关了但还没走到 disconnect」的瞬间态——
     * 渠道工具拿它决定要不要提前判失败，宁可判成不在场也不要判成在场。</p>
     */
    @Override
    public boolean hasSubscriber(String runId)
    {
        ConcurrentHashMap<String, Subscription> subscriptions = subscriptionsByRun.get(runId);
        if (subscriptions == null || subscriptions.isEmpty())
        {
            return false;
        }
        for (Subscription subscription : subscriptions.values())
        {
            if (subscription.connection.isAlive())
            {
                return true;
            }
        }
        return false;
    }

    public boolean send(String sessionId, String json)
    {
        Connection connection = connections.get(sessionId);
        return connection != null && connection.send(json);
    }

    @EventListener
    public void onRunEvent(ChatRunEventEnvelope event)
    {
        ConcurrentHashMap<String, Subscription> subscriptions = subscriptionsByRun.get(event.getRunId());
        if (subscriptions != null && !subscriptions.isEmpty())
        {
            for (Subscription subscription : subscriptions.values())
            {
                subscription.acceptLive(event);
            }
        }
        notifySessionWatchers(event);
    }

    /**
     * 把运行的生命周期变化广播给该会话的所有观察者。
     *
     * <p>这是多端同步的入口：另一个标签页并不知道有人在同一会话里发起了新一轮，
     * 只有收到这条通知拿到 runId，才能接着去订阅那一轮的完整事件流。</p>
     */
    private void notifySessionWatchers(ChatRunEventEnvelope envelope)
    {
        ConcurrentHashMap<String, Connection> watchers = watchersBySession.get(envelope.getSessionId());
        if (watchers == null || watchers.isEmpty()) return;
        JSONObject event;
        try
        {
            event = JSON.parseObject(envelope.getEventJson());
        }
        catch (RuntimeException e)
        {
            return;
        }
        if (event == null || !LIFECYCLE_TYPES.contains(event.getString("type"))) return;

        JSONObject params = new JSONObject();
        params.put("sessionId", envelope.getSessionId());
        params.put("runId", envelope.getRunId());
        params.put("seq", envelope.getSeq());
        params.put("timestamp", envelope.getTimestamp());
        params.put("event", event);
        putStandardEvent(params, envelope);
        JSONObject notification = new JSONObject();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "chat.session.event");
        notification.put("params", params);
        String json = notification.toJSONString();
        for (Connection connection : watchers.values())
        {
            connection.send(json);
        }
    }

    public final class Subscription
    {
        private final Connection connection;
        private final String runId;
        private final TreeMap<Long, ChatRunEventEnvelope> pending = new TreeMap<>();
        private boolean replaying = true;
        private long lastSentSeq;

        private Subscription(Connection connection, String runId, long afterSeq)
        {
            this.connection = connection;
            this.runId = runId;
            this.lastSentSeq = afterSeq;
        }

        private synchronized void acceptLive(ChatRunEventEnvelope event)
        {
            if (event.getSeq() <= lastSentSeq) return;
            if (replaying)
            {
                pending.put(event.getSeq(), event);
                return;
            }
            sendEvent(event);
        }

        private synchronized void acceptReplay(ChatRunEventEnvelope event)
        {
            if (event.getSeq() > lastSentSeq)
            {
                sendEvent(event);
            }
        }

        private synchronized void completeReplay()
        {
            for (ChatRunEventEnvelope event : pending.values())
            {
                if (event.getSeq() > lastSentSeq)
                {
                    sendEvent(event);
                }
            }
            pending.clear();
            replaying = false;
        }

        public synchronized long lastSentSeq()
        {
            return lastSentSeq;
        }

        private void sendEvent(ChatRunEventEnvelope envelope)
        {
            JSONObject params = new JSONObject();
            params.put("runId", envelope.getRunId());
            params.put("sessionId", envelope.getSessionId());
            params.put("seq", envelope.getSeq());
            params.put("timestamp", envelope.getTimestamp());
            try
            {
                params.put("event", JSON.parseObject(envelope.getEventJson()));
            }
            catch (RuntimeException e)
            {
                params.put("event", envelope.getEventJson());
            }
            putStandardEvent(params, envelope);
            JSONObject notification = new JSONObject();
            notification.put("jsonrpc", "2.0");
            notification.put("method", "chat.event");
            notification.put("params", params);
            if (connection.send(notification.toJSONString()))
            {
                lastSentSeq = envelope.getSeq();
            }
        }
    }

    /** v1 与 legacy 双写；旧客户端忽略 eventV1，新客户端优先消费它。 */
    private static void putStandardEvent(JSONObject params, ChatRunEventEnvelope envelope)
    {
        if (envelope.getStandardEventJson() == null || envelope.getStandardEventJson().isBlank()) return;
        try
        {
            params.put("eventV1", JSON.parseObject(envelope.getStandardEventJson()));
        }
        catch (RuntimeException ignored)
        {
            // 标准事件构造失败不影响 legacy 主链路。
        }
    }

    private final class Connection
    {
        private final ConcurrentWebSocketSessionDecorator session;
        private final Set<String> runIds = ConcurrentHashMap.newKeySet();
        private final Set<String> sessionIds = ConcurrentHashMap.newKeySet();
        private final Queue<OutboundMessage> outbound = new ConcurrentLinkedQueue<>();
        private final AtomicInteger queuedBytes = new AtomicInteger();
        private final AtomicBoolean draining = new AtomicBoolean(false);
        private final AtomicBoolean disposed = new AtomicBoolean(false);

        private Connection(ConcurrentWebSocketSessionDecorator session)
        {
            this.session = session;
        }

        private boolean isAlive()
        {
            return !disposed.get() && session.isOpen();
        }

        private boolean send(String json)
        {
            if (!isAlive()) return false;
            int bytes = Math.max(json.length() * Character.BYTES, 1);
            int total = queuedBytes.addAndGet(bytes);
            if (total > SEND_BUFFER_LIMIT_BYTES)
            {
                queuedBytes.addAndGet(-bytes);
                fail(new IOException("WebSocket 出站队列超过上限"));
                return false;
            }
            outbound.offer(new OutboundMessage(json, bytes));
            if (!draining.compareAndSet(false, true))
            {
                return true;
            }
            try
            {
                sendExecutor.execute(this::drain);
                return true;
            }
            catch (RuntimeException e)
            {
                draining.set(false);
                fail(e);
                return false;
            }
        }

        /** 单连接始终只有一个 drain，保证 replay、实时事件和 RPC 响应的发送顺序。 */
        private void drain()
        {
            try
            {
                while (!disposed.get())
                {
                    OutboundMessage message;
                    while ((message = outbound.poll()) != null)
                    {
                        queuedBytes.addAndGet(-message.bytes());
                        session.sendMessage(new TextMessage(message.json()));
                    }
                    draining.set(false);
                    if (outbound.isEmpty() || !draining.compareAndSet(false, true))
                    {
                        return;
                    }
                }
                draining.set(false);
            }
            catch (IOException | RuntimeException e)
            {
                draining.set(false);
                fail(e);
            }
        }

        private void fail(Throwable error)
        {
            if (!disposed.compareAndSet(false, true)) return;
            log.warn("WebSocket 发送失败: connectionId={}", session.getId(), error);
            outbound.clear();
            queuedBytes.set(0);
            try
            {
                session.close();
            }
            catch (IOException ignored) { }
            disconnect(session.getId());
        }

        private void dispose()
        {
            disposed.set(true);
            outbound.clear();
            queuedBytes.set(0);
        }
    }

    private record OutboundMessage(String json, int bytes) { }
}
