package com.ruoyi.system.ai;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleTtsClientTest
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
    void usesSpeechSynthesizerWhenCompatibleSpeechReturnsUrlError(@TempDir Path dir) throws Exception
    {
        byte[] mp3 = new byte[] { 'I', 'D', '3', 4, 5, 6 };
        java.util.concurrent.atomic.AtomicInteger synthHits = new java.util.concurrent.atomic.AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/compatible-mode/v1/audio/speech", ex -> {
            ex.getRequestBody().readAllBytes();
            write(ex, 400, "{\"code\":\"InvalidParameter\",\"message\":\"url error, please check url！\"}");
        });
        server.createContext("/api/v1/services/audio/tts/SpeechSynthesizer", ex -> {
            synthHits.incrementAndGet();
            ex.getRequestBody().readAllBytes();
            write(ex, 200, "{\"output\":{\"audio\":{\"url\":\"http://127.0.0.1:"
                    + server.getAddress().getPort() + "/v1/media/a.mp3\"}}}");
        });
        server.createContext("/v1/media/a.mp3", ex -> {
            ex.getResponseHeaders().add("Content-Type", "audio/mpeg");
            ex.sendResponseHeaders(200, mp3.length);
            try (OutputStream os = ex.getResponseBody())
            {
                os.write(mp3);
            }
        });
        server.start();

        Path target = dir.resolve("out.mp3");
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/compatible-mode/v1";
        OpenAiCompatibleTtsClient client = new OpenAiCompatibleTtsClient(base, "k", "qwen-audio-3.0-tts-plus");
        client.generateToFile(new OpenAiCompatibleTtsClient.TtsGenerateRequest("你好", null, null), target);
        assertTrue(Files.exists(target));
        assertArrayEquals(mp3, Files.readAllBytes(target));
        assertTrue(synthHits.get() >= 1, "百炼 TTS 必须打 SpeechSynthesizer,不能停在 compatible-mode/audio/speech");
    }

    @Test
    void mapsSpokenMaleToLufengVoiceId(@TempDir Path dir) throws Exception
    {
        byte[] mp3 = new byte[] { 'I', 'D', '3', 4, 5, 6 };
        java.util.concurrent.atomic.AtomicReference<String> synthBody = new java.util.concurrent.atomic.AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/services/audio/tts/SpeechSynthesizer", ex -> {
            synthBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.getResponseHeaders().add("Content-Type", "audio/mpeg");
            ex.sendResponseHeaders(200, mp3.length);
            try (OutputStream os = ex.getResponseBody())
            {
                os.write(mp3);
            }
        });
        server.start();

        Path target = dir.resolve("out.mp3");
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/compatible-mode/v1";
        OpenAiCompatibleTtsClient client = new OpenAiCompatibleTtsClient(base, "k", "qwen-audio-3.0-tts-plus");
        client.generateToFile(new OpenAiCompatibleTtsClient.TtsGenerateRequest("你好", "男", null), target);
        String body = synthBody.get();
        assertTrue(body.contains("longanlufeng"), body);
        assertFalse(body.contains("\"男\""), body);
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
