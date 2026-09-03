package com.ruoyi.system.ai;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleVideoClientTest
{
    private HttpServer server;

    @AfterEach
    void stop()
    {
        if (server != null)
        {
            server.stop(0);
        }
    }

    @Test
    void fallsBackFromGenerationsToVideosAndDownloads(@TempDir Path dir) throws Exception
    {
        AtomicInteger polls = new AtomicInteger();
        byte[] mp4 = new byte[] { 0, 0, 0, 1, 0x66, 0x74, 0x79, 0x70 };
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/videos/generations", ex -> {
            ex.getRequestBody().readAllBytes();
            write(ex, 404, "{\"error\":\"no\"}");
        });
        server.createContext("/v1/videos", ex -> {
            ex.getRequestBody().readAllBytes();
            String path = ex.getRequestURI().getPath();
            if ("POST".equals(ex.getRequestMethod()) && "/v1/videos".equals(path))
            {
                write(ex, 200, "{\"id\":\"job_1\"}");
                return;
            }
            if (path.equals("/v1/videos/job_1"))
            {
                if (polls.getAndIncrement() == 0)
                {
                    write(ex, 200, "{\"status\":\"in_progress\"}");
                    return;
                }
                write(ex, 200, "{\"status\":\"completed\",\"video\":{\"url\":\"http://127.0.0.1:"
                        + server.getAddress().getPort() + "/v1/media/out.mp4\"}}");
                return;
            }
            write(ex, 404, "{}");
        });
        server.createContext("/v1/media/out.mp4", ex -> {
            ex.getResponseHeaders().add("Content-Type", "video/mp4");
            ex.sendResponseHeaders(200, mp4.length);
            try (OutputStream os = ex.getResponseBody())
            {
                os.write(mp4);
            }
        });
        server.start();

        Path target = dir.resolve("out.mp4");
        OpenAiCompatibleVideoClient client = new OpenAiCompatibleVideoClient(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "test-key", "grok-imagine-video", Duration.ofSeconds(5), millis -> { });
        client.generateToFile(new OpenAiCompatibleVideoClient.VideoGenerateRequest(
                "一只猫走路", 6, "16:9", "720p", null, null), target);

        assertTrue(Files.exists(target));
        assertArrayEquals(mp4, Files.readAllBytes(target));
        assertTrue(polls.get() >= 2);
    }

    @Test
    void resolvesRelativePlaybackUrlAgainstChannelBase(@TempDir Path dir) throws Exception
    {
        byte[] mp4 = new byte[] { 1, 2, 3, 4 };
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/videos/generations", ex -> {
            ex.getRequestBody().readAllBytes();
            write(ex, 200, "{\"request_id\":\"video_rel\"}");
        });
        server.createContext("/v1/videos/video_rel", ex ->
                write(ex, 200, "{\"status\":\"done\",\"video\":{\"url\":\"/v1/media/videos/abc\"}}"));
        server.createContext("/v1/media/videos/abc", ex -> {
            ex.getResponseHeaders().add("Content-Type", "video/mp4");
            ex.sendResponseHeaders(200, mp4.length);
            try (OutputStream os = ex.getResponseBody())
            {
                os.write(mp4);
            }
        });
        server.start();

        Path target = dir.resolve("rel.mp4");
        newClient().generateToFile(req(), target);
        assertArrayEquals(mp4, Files.readAllBytes(target));
    }

    @Test
    void fallsBackToAuthenticatedContentWhenPlaybackUrlBreaks(@TempDir Path dir) throws Exception
    {
        byte[] mp4 = new byte[] { 9, 8, 7, 6 };
        AtomicInteger contentHits = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/videos/generations", ex -> {
            ex.getRequestBody().readAllBytes();
            write(ex, 200, "{\"request_id\":\"video_fb\"}");
        });
        server.createContext("/v1/videos/video_fb", ex -> {
            if (ex.getRequestURI().getPath().endsWith("/content"))
            {
                contentHits.incrementAndGet();
                ex.getResponseHeaders().add("Content-Type", "video/mp4");
                ex.sendResponseHeaders(200, mp4.length);
                try (OutputStream os = ex.getResponseBody())
                {
                    os.write(mp4);
                }
                return;
            }
            write(ex, 200, "{\"status\":\"done\",\"video\":{\"url\":\"http://127.0.0.1:"
                    + server.getAddress().getPort() + "/v1/media/videos/dead\"}}");
        });
        server.createContext("/v1/media/videos/dead", ex -> {
            ex.close();
        });
        server.start();

        Path target = dir.resolve("fb.mp4");
        newClient().generateToFile(req(), target);
        assertArrayEquals(mp4, Files.readAllBytes(target));
        assertTrue(contentHits.get() >= 1);
    }

    @Test
    void describeIoKeepsTypeWhenMessageIsNull()
    {
        String text = OpenAiCompatibleVideoClient.describeIo(new java.nio.channels.ClosedChannelException());
        assertTrue(text.contains("ClosedChannelException"), text);
        assertTrue(!text.contains("null"), text);
    }

    @Test
    void stripsTrailingV1()
    {
        assertEquals("http://host:8080", OpenAiCompatibleVideoClient.normalizeBaseUrl("http://host:8080/v1/"));
    }

    @Test
    void createPayloadSendsOnlyImageObjectAndDropsUnknownFields()
    {
        OpenAiCompatibleVideoClient client = new OpenAiCompatibleVideoClient(
                "http://127.0.0.1:1/v1", "k", "grok-imagine-video");
        Map<String, Object> body = client.createPayload(new OpenAiCompatibleVideoClient.VideoGenerateRequest(
                "一只猫走路", 6, "16:9", "720p",
                "data:image/jpeg;base64,abc",
                List.of("https://cdn.example/a.png", "https://cdn.example/b.png")));

        assertEquals("grok-imagine-video", body.get("model"));
        assertEquals("一只猫走路", body.get("prompt"));
        assertEquals(6, body.get("duration"));
        assertEquals("16:9", body.get("aspect_ratio"));
        assertEquals("720p", body.get("resolution"));
        assertEquals(Map.of("url", "data:image/jpeg;base64,abc"), body.get("image"));
        assertFalse(body.containsKey("reference_image_urls"));
        assertFalse(body.containsKey("image_urls"));
        assertFalse(body.containsKey("images"));
        assertFalse(body.containsKey("image_url"));
    }

    @Test
    void createPayloadUsesFirstReferenceWhenNoFirstFrame()
    {
        OpenAiCompatibleVideoClient client = new OpenAiCompatibleVideoClient(
                "http://127.0.0.1:1/v1", "k", "grok-imagine-video");
        Map<String, Object> body = client.createPayload(new OpenAiCompatibleVideoClient.VideoGenerateRequest(
                "动起来", null, null, null, null, List.of("https://cdn.example/a.png")));

        assertEquals(Map.of("url", "https://cdn.example/a.png"), body.get("image"));
        assertFalse(body.containsKey("reference_image_urls"));
    }

    private OpenAiCompatibleVideoClient newClient()
    {
        return new OpenAiCompatibleVideoClient(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "test-key", "grok-imagine-video", Duration.ofSeconds(5), millis -> { });
    }

    private static OpenAiCompatibleVideoClient.VideoGenerateRequest req()
    {
        return new OpenAiCompatibleVideoClient.VideoGenerateRequest("一只猫走路", 5, "16:9", "720p", null, null);
    }

    private static void write(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException
    {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody())
        {
            os.write(bytes);
        }
    }
}
