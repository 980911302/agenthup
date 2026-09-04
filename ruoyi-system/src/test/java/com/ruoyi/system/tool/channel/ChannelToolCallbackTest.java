package com.ruoyi.system.tool.channel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
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

import javax.imageio.ImageIO;

import com.ruoyi.system.tool.AiToolProperties;

class ChannelToolCallbackTest
{
    @TempDir
    Path tmp;

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

    @Test
    void workspaceImageBecomesPromptMediaAndAttachment() throws Exception
    {
        Path output = tmp.resolve("s/outputs/shot.png");
        Files.createDirectories(output.getParent());
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", output.toFile());
        AiToolProperties props = new AiToolProperties();
        props.setWorkspaceRoot(tmp.toString());
        props.setWorkspacePerSession(true);

        ChannelToolBroker broker = mock(ChannelToolBroker.class);
        when(broker.invoke(anyString(), anyString(), anyString(), any(), any(), isNull(), isNull()))
                .thenReturn(new ChannelToolBroker.ChannelToolResult(
                        true, "ok", null, null, "outputs/shot.png"));
        ChannelToolCallback cb = new ChannelToolCallback(
                new ChannelToolDef("screenshotTab", "d", ChannelToolSchemas.DEFAULT_SCHEMA),
                broker, "s", "r", json -> {}, null, null, null,
                "s", props, null);

        assertEquals("ok", cb.call("{}"));
        assertEquals(1, cb.lastPromptMedia().size());
        assertEquals("outputs/shot.png", cb.lastAttachments().get(0).path());
    }
}
