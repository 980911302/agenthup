package com.ruoyi.system.tool.channel;

import com.ruoyi.system.ai.event.ChatEventSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelToolBrokerTest
{
    private ChannelToolBroker broker;
    private ChannelToolProperties properties;

    @BeforeEach
    void setUp()
    {
        properties = new ChannelToolProperties();
        properties.overrideForTest(true, List.of(), 2, 16, 32);
        broker = new ChannelToolBroker(properties);
    }

    @Test
    void invoke_complete_wakesWithResult() throws Exception
    {
        List<String> events = new ArrayList<>();
        ChatEventSink sink = events::add;
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<String> out = new AtomicReference<>();
        Thread t = new Thread(() -> {
            started.countDown();
            out.set(broker.invoke("s1", "r1", "ping", "{}", sink, null, null).text());
        });
        t.start();
        assertTrue(started.await(1, TimeUnit.SECONDS));
        Thread.sleep(50);
        String json = events.get(0);
        assertTrue(json.contains("\"type\":\"tool_call_request\""));
        Matcher m = Pattern.compile("\"callId\":\"([^\"]+)\"").matcher(json);
        assertTrue(m.find());
        assertTrue(broker.complete(m.group(1), true, "pong", null, null));
        t.join(2000);
        assertEquals("pong", out.get());
    }

    @Test
    void timeout_returnsErrorText()
    {
        properties.overrideForTest(true, List.of(), 1, 16, 32);
        broker = new ChannelToolBroker(properties);
        String text = broker.invoke("s1", "r1", "slow", "{}", json -> {}, null, null).text();
        assertTrue(text.startsWith("[渠道工具]"));
        assertTrue(text.contains("超时"));
    }

    @Test
    void cancelBySession_wakesPending() throws Exception
    {
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<String> out = new AtomicReference<>();
        Thread t = new Thread(() -> {
            started.countDown();
            out.set(broker.invoke("s1", "r1", "ping", "{}", json -> {}, null, null).text());
        });
        t.start();
        assertTrue(started.await(1, TimeUnit.SECONDS));
        Thread.sleep(50);
        broker.cancelBySession("s1");
        t.join(2000);
        assertTrue(out.get().contains("本轮已结束"));
    }

    @Test
    void complete_idempotent() throws Exception
    {
        CountDownLatch started = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            started.countDown();
            broker.invoke("s1", "r1", "ping", "{}", json -> {}, null, null);
        });
        t.start();
        assertTrue(started.await(1, TimeUnit.SECONDS));
        Thread.sleep(50);
        // 没有 callId 时 complete 返回 false；有 callId 第二次幂等
        assertFalse(broker.complete("missing", true, "x", null, null));
        t.interrupt();
        t.join(2000);
    }

    @Test
    void mediaFileId_carriedOnSuccessOnly() throws Exception
    {
        for (boolean ok : new boolean[] { true, false })
        {
            List<String> events = new ArrayList<>();
            CountDownLatch started = new CountDownLatch(1);
            AtomicReference<ChannelToolBroker.ChannelToolResult> out = new AtomicReference<>();
            Thread t = new Thread(() -> {
                started.countDown();
                out.set(broker.invoke("s1", "r1", "shot", "{}", events::add, null, null));
            });
            t.start();
            assertTrue(started.await(1, TimeUnit.SECONDS));
            Thread.sleep(50);
            Matcher m = Pattern.compile("\"callId\":\"([^\"]+)\"").matcher(events.get(0));
            assertTrue(m.find());
            assertTrue(broker.complete(m.group(1), ok, "done", ok ? null : "boom", 42L));
            t.join(2000);
            // 失败结果不能带图：那张图对应的调用没成功，喂给模型只会误导
            assertEquals(ok ? Long.valueOf(42L) : null, out.get().mediaFileId());
        }
    }

    /**
     * 关掉侧边栏再打开后对话卡死的正解:续订阅的游标取自 snapshot_seq,原请求落在游标之前
     * 永远回放不到,只能由服务端按挂起表补发 —— 且 callId 必须与首发一致,
     * 客户端才认得出是同一次调用、结果才回得到正在等的那个 future 上。
     */
    @Test
    void redeliver_reemitsSameCallId() throws Exception
    {
        // 超时必须长过补发间隔，否则调用先被判超时、挂起表已经空了
        properties.overrideForTest(true, List.of(), 30, 16, 32);
        broker = new ChannelToolBroker(properties);
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<String> out = new AtomicReference<>();
        Thread t = new Thread(() -> {
            started.countDown();
            out.set(broker.invoke("s1", "r1", "readPage", "{}", events::add, null, null).text());
        });
        t.start();
        assertTrue(started.await(1, TimeUnit.SECONDS));
        Thread.sleep(50);
        assertEquals(1, events.size());
        String callId = callIdOf(events.get(0));

        // 别的 run 不受影响
        assertEquals(0, broker.redeliver("r2"));

        Thread.sleep(ChannelToolBroker.REDELIVER_MIN_INTERVAL_MS + 100);
        assertEquals(1, broker.redeliver("r1"));
        assertEquals(2, events.size());
        assertEquals(callId, callIdOf(events.get(1)));

        // 补发出去的那条被客户端执行后，结果照样落在这次调用上
        assertTrue(broker.complete(callId, true, "page text", null, null));
        t.join(2000);
        assertEquals("page text", out.get());
    }

    /** 重连风暴下每次订阅都补发会把事件流刷爆，最小间隔内的重复补发必须被吃掉。 */
    @Test
    void redeliver_throttledWithinMinInterval() throws Exception
    {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch started = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            started.countDown();
            broker.invoke("s1", "r1", "readPage", "{}", events::add, null, null);
        });
        t.start();
        assertTrue(started.await(1, TimeUnit.SECONDS));
        Thread.sleep(50);

        assertEquals(0, broker.redeliver("r1"));
        assertEquals(0, broker.redeliver("r1"));
        assertEquals(1, events.size());
        broker.cancelBySession("s1");
        t.join(2000);
    }

    /**
     * 侧边栏关掉后工具的执行体就没了，等满 timeout-seconds 只是把这一轮拖死。
     * 宽限期一过就要判失败，且错误文本要说清是断开而不是笼统的"超时"。
     */
    @Test
    void noSubscriber_failsFastAfterGrace()
    {
        properties.overrideForTest(true, List.of(), 60, 16, 32);
        properties.overrideDisconnectGraceForTest(1);
        broker = new ChannelToolBroker(properties);
        broker.setSubscriberPresence(runId -> false);

        long start = System.nanoTime();
        String text = broker.invoke("s1", "r1", "readPage", "{}", json -> {}, null, null).text();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertTrue(text.contains("侧边栏"), text);
        // 没有这条判定就要等满 60 秒
        assertTrue(elapsedMs < 15_000, "耗时 " + elapsedMs + "ms，未按宽限期提前失败");
    }

    /** 客户端在线就不能提前判死：那会把正常的长耗时工具误杀。 */
    @Test
    void subscriberOnline_waitsUntilTimeout()
    {
        properties.overrideForTest(true, List.of(), 2, 16, 32);
        properties.overrideDisconnectGraceForTest(1);
        broker = new ChannelToolBroker(properties);
        broker.setSubscriberPresence(runId -> true);

        String text = broker.invoke("s1", "r1", "slow", "{}", json -> {}, null, null).text();
        assertTrue(text.contains("超时"), text);
        assertFalse(text.contains("侧边栏"), text);
    }

    private static String callIdOf(String eventJson)
    {
        Matcher m = Pattern.compile("\"callId\":\"([^\"]+)\"").matcher(eventJson);
        assertTrue(m.find(), eventJson);
        return m.group(1);
    }

    @Test
    void semaphoreFull_returnsErrorText() throws Exception
    {
        properties.overrideForTest(true, List.of(), 5, 16, 1);
        broker = new ChannelToolBroker(properties);
        CountDownLatch started = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            started.countDown();
            broker.invoke("s1", "r1", "hold", "{}", json -> {}, null, null);
        });
        t.start();
        assertTrue(started.await(1, TimeUnit.SECONDS));
        Thread.sleep(50);
        String second = broker.invoke("s1", "r1", "other", "{}", json -> {}, null, null).text();
        assertTrue(second.contains("并发已满"));
        broker.cancelBySession("s1");
        t.join(2000);
    }
}
