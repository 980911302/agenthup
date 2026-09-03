package com.ruoyi.system.tool.channel;

import com.ruoyi.system.ai.event.ChatEventSink;
import com.ruoyi.system.ai.run.RunSubscriberPresence;
import com.ruoyi.system.ai.sse.ChatEventJson;
import com.ruoyi.system.tool.PiiRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 渠道工具：挂起工具线程，等客户端经 run 事件流回传结果。
 *
 * <p>照抄 {@code ToolConfirmBroker}：进程内 CompletableFuture，不新造推送通道。
 *
 * <p>挂起表({@code pendings})同时是「还在等回传」的事实源：客户端重新订阅这一轮时
 * 按它补发请求({@link #redeliver})，等待期间也按它判断客户端是否已经离线。
 */
@Component
public class ChannelToolBroker
{
    private static final Logger log = LoggerFactory.getLogger(ChannelToolBroker.class);

    /** 等待分片。完成会立刻唤醒，分片只决定「客户端是否还在线」的复查节奏。 */
    private static final long POLL_INTERVAL_MS = 1000L;

    /** 同一个 callId 两次补发的最小间隔，挡住重连风暴把事件流刷爆。 */
    static final long REDELIVER_MIN_INTERVAL_MS = 2000L;

    private final ChannelToolProperties properties;
    private final Map<String, Pending> pendings = new ConcurrentHashMap<>();
    private volatile Semaphore inflight;

    /**
     * 可选：接入层没装配时(单测、非 Web 环境)退回纯超时行为。
     * 字段注入而非构造器注入，是为了不动现有的一参构造器。
     */
    @Autowired(required = false)
    private RunSubscriberPresence subscriberPresence;

    public ChannelToolBroker(ChannelToolProperties properties)
    {
        this.properties = properties;
        this.inflight = new Semaphore(Math.max(1, properties.maxConcurrentCalls()));
    }

    public String newCallId()
    {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 单测用：注入在场判定，不必起 Spring 容器。 */
    public void setSubscriberPresence(RunSubscriberPresence subscriberPresence)
    {
        this.subscriberPresence = subscriberPresence;
    }

    /**
     * 发出 tool_call_request 并阻塞等待客户端。超时/取消/失败返回错误文本，不抛异常。
     */
    public ChannelToolResult invoke(String sessionId, String runId, String name, String argsJson,
                                    ChatEventSink sink, String owner, String stepId)
    {
        if (sessionId == null || name == null)
        {
            return failure(name, "缺少 sessionId 或工具名");
        }
        Semaphore gate = inflight;
        if (!gate.tryAcquire())
        {
            log.warn("渠道工具并发闸已满: session={} tool={}", sessionId, name);
            return failure(name, "系统繁忙，客户端工具并发已满，请稍后重试");
        }
        String callId = newCallId();
        Pending pending = new Pending(sessionId, runId, name,
                PiiRedactor.forStorage(argsJson), owner, stepId, sink);
        pendings.put(callId, pending);
        try
        {
            pending.emit(callId);
            ChannelToolResult result = awaitResult(pending, callId);
            if (result == null)
            {
                return failure(name, "客户端未返回结果");
            }
            if (!result.ok())
            {
                String err = result.error() != null && !result.error().isBlank()
                        ? result.error() : "客户端执行失败";
                return failure(name, err);
            }
            return result;
        }
        catch (ClientGoneException e)
        {
            log.info("渠道工具无在线客户端: session={} tool={} callId={}", sessionId, name, callId);
            return failure(name, e.getMessage());
        }
        catch (TimeoutException e)
        {
            log.info("渠道工具超时: session={} tool={} callId={}", sessionId, name, callId);
            return failure(name, "等待客户端执行超时（" + properties.timeoutSeconds() + " 秒）");
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return failure(name, "本轮已结束，客户端工具调用被中断");
        }
        catch (Exception e)
        {
            log.warn("渠道工具等待失败: session={} tool={}: {}", sessionId, name, e.getMessage());
            return failure(name, e.getMessage() != null ? e.getMessage() : "客户端工具调用失败");
        }
        finally
        {
            pendings.remove(callId);
            gate.release();
        }
    }

    /**
     * 分片等待。客户端在线就等满 timeout-seconds；本实例上再没有这一轮的订阅者
     * (侧边栏被关掉是最常见的一种)则只多等一个宽限期就判失败 —— 浏览器工具的执行体
     * 就在插件页面里，页面没了谁也答不上来，继续挂满两分钟只是把这一轮拖死，
     * 模型最后还是只拿到一句「超时」。
     *
     * <p>宽限期给重连与重开留了回旋余地：客户端一旦重新订阅，{@link #redeliver}
     * 会把请求补发一遍，在场判定也随之恢复，这次调用就能正常拿到结果。
     */
    private ChannelToolResult awaitResult(Pending pending, String callId)
            throws InterruptedException, TimeoutException, java.util.concurrent.ExecutionException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(properties.timeoutSeconds());
        long grace = properties.disconnectGraceSeconds();
        boolean checkPresence = grace > 0 && subscriberPresence != null
                && pending.runId != null && !pending.runId.isEmpty();
        long graceNanos = TimeUnit.SECONDS.toNanos(Math.max(grace, 0));
        long offlineSince = 0L;
        while (true)
        {
            try
            {
                return pending.future.get(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
            }
            catch (TimeoutException e)
            {
                long now = System.nanoTime();
                if (now - deadline >= 0)
                {
                    throw e;
                }
                if (!checkPresence || subscriberPresence.hasSubscriber(pending.runId))
                {
                    offlineSince = 0L;
                    continue;
                }
                if (offlineSince == 0L)
                {
                    offlineSince = now;
                }
                else if (now - offlineSince >= graceNanos)
                {
                    throw new ClientGoneException("浏览器侧边栏已关闭或断开（"
                            + grace + " 秒内未重新连上），本次调用未能执行；"
                            + "请提示用户重新打开侧边栏后再试");
                }
            }
        }
    }

    /**
     * 客户端(重新)订阅这一轮时，补发尚未回传的请求。
     *
     * <p>非补不可：侧边栏关掉再打开、页面刷新、序号缺口恢复，续订阅的游标都取自
     * {@code ai_chat_run.snapshot_seq}，而 tool_call_request 不落库却照样把 snapshot_seq
     * 推过了自己(见 {@code ChatRunProjectionService#applyDiscrete} 对它没有分支，
     * 但 {@code project} 无条件 advanceSnapshotSeq)。于是这条请求正好落在续传游标之前，
     * 服务端与客户端两侧都会把它按「已消费」滤掉，永远送不到插件手上，
     * 这一轮就只能干等满超时——这正是「侧边栏关掉再打开后对话卡死不动」的成因。</p>
     *
     * <p>补发的事实源取挂起表而不是事件流：它就是「还在等回传」的精确集合，
     * 不会重放已经完成的调用。客户端按 callId 去重，老页面收到也不会重复执行。</p>
     *
     * @return 实际补发的条数
     */
    public int redeliver(String runId)
    {
        if (runId == null || runId.isEmpty())
        {
            return 0;
        }
        long now = System.currentTimeMillis();
        int sent = 0;
        for (Map.Entry<String, Pending> entry : pendings.entrySet())
        {
            Pending pending = entry.getValue();
            if (!runId.equals(pending.runId) || pending.future.isDone()
                    || !pending.tryMarkRedeliver(now))
            {
                continue;
            }
            pending.emit(entry.getKey());
            sent++;
        }
        if (sent > 0)
        {
            log.info("渠道工具补发未回传请求: runId={} count={}", runId, sent);
        }
        return sent;
    }

    /**
     * 客户端回传。先到先得，后到的返回 false。
     */
    public boolean complete(String callId, boolean ok, String result, String error, Long mediaFileId)
    {
        if (callId == null || callId.isEmpty())
        {
            return false;
        }
        Pending pending = pendings.get(callId);
        if (pending == null)
        {
            return false;
        }
        String text = ok ? (result != null ? result : "") : (error != null ? error : "客户端执行失败");
        return pending.future.complete(new ChannelToolResult(ok, text, error, ok ? mediaFileId : null));
    }

    /** 会话终态：唤醒该会话所有挂起调用。 */
    public void cancelBySession(String sessionId)
    {
        if (sessionId == null)
        {
            return;
        }
        for (Pending p : pendings.values())
        {
            if (sessionId.equals(p.sessionId))
            {
                p.future.complete(new ChannelToolResult(false, "", "本轮已结束", null));
            }
        }
    }

    /** 失败结果:文本给模型读,ok=false 让 RecordingToolCallback 记成失败。 */
    static ChannelToolResult failure(String name, String reason)
    {
        return new ChannelToolResult(false, errorText(name, reason), reason, null);
    }

    static String errorText(String name, String reason)
    {
        String n = name != null ? name : "?";
        return "[渠道工具] 「" + n + "」失败：" + reason;
    }

    /** mediaFileId:客户端产出的图片在个人文件里的 id,仅传引用,图片本体不过 WebSocket。 */
    public record ChannelToolResult(boolean ok, String text, String error, Long mediaFileId) { }

    /** 客户端已离线且宽限期内没回来。与超时区分开，好让模型知道该提示用户开侧边栏。 */
    private static final class ClientGoneException extends RuntimeException
    {
        private ClientGoneException(String message)
        {
            super(message);
        }
    }

    private static final class Pending
    {
        private final String sessionId;
        private final String runId;
        private final String name;
        /** 已按 PII 规则脱敏，补发时原样复用，不再重复处理原始入参。 */
        private final String args;
        private final String owner;
        private final String stepId;
        private final ChatEventSink sink;
        private final AtomicLong lastEmitAt = new AtomicLong(0L);
        private final CompletableFuture<ChannelToolResult> future = new CompletableFuture<>();

        private Pending(String sessionId, String runId, String name, String args,
                        String owner, String stepId, ChatEventSink sink)
        {
            this.sessionId = sessionId;
            this.runId = runId;
            this.name = name;
            this.args = args;
            this.owner = owner;
            this.stepId = stepId;
            this.sink = sink;
        }

        private void emit(String callId)
        {
            if (sink == null)
            {
                return;
            }
            lastEmitAt.set(System.currentTimeMillis());
            sink.emit(ChatEventJson.toolCallRequest(callId, name, args, owner, stepId));
        }

        /** CAS 限流:并发的多次订阅只有一个能把这条补发出去。 */
        private boolean tryMarkRedeliver(long now)
        {
            long last = lastEmitAt.get();
            return now - last >= REDELIVER_MIN_INTERVAL_MS && lastEmitAt.compareAndSet(last, now);
        }
    }
}
