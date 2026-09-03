package com.ruoyi.system.tool.channel;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChannelToolCallbackTest
{
    @Test
    void definition_fieldsMatch()
    {
        ChannelToolDef def = new ChannelToolDef("ping", "health", ChannelToolSchemas.DEFAULT_SCHEMA);
        ChannelToolCallback cb = new ChannelToolCallback(def, mock(ChannelToolBroker.class),
                "s", "r", json -> {}, null, null, null);
        assertEquals("ping", cb.getToolDefinition().name());
        assertEquals("health", cb.getToolDefinition().description());
        assertEquals(ChannelToolSchemas.DEFAULT_SCHEMA, cb.getToolDefinition().inputSchema());
    }

    @Test
    void success_lastCallOkTrue()
    {
        ChannelToolBroker broker = mock(ChannelToolBroker.class);
        when(broker.invoke(anyString(), anyString(), anyString(), any(), any(), isNull(), isNull()))
                .thenReturn(new ChannelToolBroker.ChannelToolResult(true, "pong", null, null));
        ChannelToolCallback cb = new ChannelToolCallback(
                new ChannelToolDef("ping", "d", ChannelToolSchemas.DEFAULT_SCHEMA),
                broker, "s", "r", json -> {}, null, null, null);
        assertEquals("pong", cb.call("{}"));
        assertEquals(Boolean.TRUE, cb.lastCallOk());
    }

    @Test
    void failure_lastCallOkFalse()
    {
        ChannelToolBroker broker = mock(ChannelToolBroker.class);
        String err = ChannelToolBroker.errorText("ping", "超时");
        when(broker.invoke(anyString(), anyString(), anyString(), any(), any(), isNull(), isNull()))
                .thenReturn(new ChannelToolBroker.ChannelToolResult(false, err, "超时", null));
        ChannelToolCallback cb = new ChannelToolCallback(
                new ChannelToolDef("ping", "d", ChannelToolSchemas.DEFAULT_SCHEMA),
                broker, "s", "r", json -> {}, null, null, null);
        assertEquals(err, cb.call("{}"));
        assertEquals(Boolean.FALSE, cb.lastCallOk());
    }

    @Test
    void lastCallOk_notSharedAcrossThreads() throws Exception
    {
        ChannelToolBroker broker = mock(ChannelToolBroker.class);
        when(broker.invoke(anyString(), anyString(), anyString(), any(), any(), isNull(), isNull()))
                .thenAnswer(inv -> Thread.currentThread().getName().contains("ok")
                        ? new ChannelToolBroker.ChannelToolResult(true, "ok", null, null)
                        : ChannelToolBroker.failure("ping", "fail"));
        ChannelToolCallback cb = new ChannelToolCallback(
                new ChannelToolDef("ping", "d", ChannelToolSchemas.DEFAULT_SCHEMA),
                broker, "s", "r", json -> {}, null, null, null);

        CountDownLatch called = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Boolean> a = new AtomicReference<>();
        AtomicReference<Boolean> b = new AtomicReference<>();
        Thread t1 = new Thread(() -> {
            try {
                cb.call("{}");
            } finally {
                called.countDown();
            }
            try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            a.set(cb.lastCallOk());
        }, "ok-thread");
        Thread t2 = new Thread(() -> {
            try {
                cb.call("{}");
            } finally {
                called.countDown();
            }
            try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            b.set(cb.lastCallOk());
        }, "fail-thread");
        t1.start();
        t2.start();
        assertTrue(called.await(3, java.util.concurrent.TimeUnit.SECONDS));
        go.countDown();
        t1.join(3000);
        t2.join(3000);
        assertEquals(Boolean.TRUE, a.get());
        assertEquals(Boolean.FALSE, b.get());
    }
}
