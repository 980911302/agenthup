package com.ruoyi.system.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.exception.ServiceException;

/**
 * TTS 客户端:先试 OpenAI {@code POST /v1/audio/speech},404 再试百炼
 * {@code POST /api/v1/services/audio/tts/SpeechSynthesizer}。
 */
public class OpenAiCompatibleTtsClient
{
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);
    static final String DEFAULT_VOICE = TtsVoiceCatalog.DEFAULT_VOICE;

    private final String openaiBase;
    private final String dashscopeOrigin;
    private final String apiKey;
    private final String modelName;
    private final HttpClient http;

    public OpenAiCompatibleTtsClient(String baseUrl, String apiKey, String modelName)
    {
        this.openaiBase = normalizeOpenAiBase(baseUrl);
        this.dashscopeOrigin = normalizeDashscopeOrigin(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey;
        this.modelName = modelName;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    public String generateToFile(TtsGenerateRequest request, Path target) throws IOException
    {
        String voice = TtsVoiceCatalog.resolve(request.voice());
        String openaiUrl = openaiBase + "/v1/audio/speech";
        String synthUrl = dashscopeOrigin + "/api/v1/services/audio/tts/SpeechSynthesizer";
        // 百炼 compatible-mode/v1/audio/speech 对 qwen-audio TTS 会 400 url error,
        // 官方接口是 workspace 上的 SpeechSynthesizer。
        HttpResponse<byte[]> speech;
        if (preferDashscopeSynthesizer())
        {
            speech = postBytes(synthUrl, JSON.toJSONString(dashscopePayload(request.text(), voice, request.instructions())));
            if (shouldTryAlternate(speech))
            {
                speech = postBytes(openaiUrl, JSON.toJSONString(openaiPayload(request.text(), voice)));
            }
        }
        else
        {
            speech = postBytes(openaiUrl, JSON.toJSONString(openaiPayload(request.text(), voice)));
            if (shouldTryAlternate(speech))
            {
                speech = postBytes(synthUrl, JSON.toJSONString(dashscopePayload(request.text(), voice, request.instructions())));
            }
        }
        if (speech.statusCode() / 100 != 2)
        {
            String body = decode(speech.body());
            throw new ServiceException("语音合成失败 HTTP " + speech.statusCode() + ": "
                    + TtsAudioParser.errorMessage(body) + " " + trim(body));
        }
        if (looksLikeAudio(speech))
        {
            Files.write(target, speech.body());
            return mimeOf(speech, target);
        }
        String json = decode(speech.body());
        String url = TtsAudioParser.audioUrl(json);
        if (url == null || url.isBlank())
        {
            throw new ServiceException("语音合成未返回音频: " + trim(json));
        }
        download(url, target);
        return mimeOfUrl(url, target);
    }

    private Map<String, Object> openaiPayload(String text, String voice)
    {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("input", text);
        body.put("voice", voice);
        return body;
    }

    private Map<String, Object> dashscopePayload(String text, String voice, String instructions)
    {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("text", text);
        input.put("voice", voice);
        input.put("format", "mp3");
        input.put("sample_rate", 24000);
        if (instructions != null && !instructions.isBlank())
        {
            input.put("instruction", instructions.trim());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("input", input);
        return body;
    }

    private void download(String url, Path target) throws IOException
    {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .GET();
        if (url.startsWith(dashscopeOrigin) || url.startsWith(openaiBase) || url.contains("aliyuncs.com"))
        {
            b.header("Authorization", "Bearer " + apiKey);
        }
        try
        {
            HttpResponse<byte[]> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2 || resp.body() == null || resp.body().length == 0)
            {
                throw new ServiceException("下载语音失败 HTTP " + resp.statusCode());
            }
            Files.write(target, resp.body());
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IOException("下载语音被中断", e);
        }
    }

    private HttpResponse<byte[]> postBytes(String url, String json)
    {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        try
        {
            return http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new ServiceException("语音合成被中断");
        }
        catch (IOException e)
        {
            throw new ServiceException("语音合成失败: " + OpenAiCompatibleVideoClient.describeIo(e));
        }
    }

    boolean preferDashscopeSynthesizer()
    {
        String host = (dashscopeOrigin + " " + openaiBase).toLowerCase(Locale.ROOT);
        return host.contains("aliyuncs.com") || host.contains("compatible-mode")
                || (modelName != null && modelName.toLowerCase(Locale.ROOT).contains("qwen-audio"));
    }

    static boolean shouldTryAlternate(HttpResponse<byte[]> resp)
    {
        int code = resp.statusCode();
        if (code == 404 || code == 405)
        {
            return true;
        }
        if (code != 400)
        {
            return false;
        }
        String body = decode(resp.body()).toLowerCase(Locale.ROOT);
        return body.contains("url error") || body.contains("invalidparameter")
                || body.contains("does not match") || body.contains("endpoint");
    }

    static String normalizeOpenAiBase(String baseUrl)
    {
        String url = trimSlash(baseUrl);
        if (url.endsWith("/v1"))
        {
            url = url.substring(0, url.length() - 3);
        }
        return url;
    }

    static String normalizeDashscopeOrigin(String baseUrl)
    {
        String url = normalizeOpenAiBase(baseUrl);
        if (url.endsWith("/compatible-mode"))
        {
            url = url.substring(0, url.length() - "/compatible-mode".length());
        }
        if (url.endsWith("/api"))
        {
            url = url.substring(0, url.length() - 4);
        }
        return url;
    }

    private static String trimSlash(String baseUrl)
    {
        String url = baseUrl == null ? "" : baseUrl.trim();
        while (url.endsWith("/"))
        {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private static boolean looksLikeAudio(HttpResponse<byte[]> resp)
    {
        String type = header(resp, "Content-Type");
        if (type != null && type.toLowerCase(Locale.ROOT).startsWith("audio/"))
        {
            return true;
        }
        byte[] body = resp.body();
        if (body == null || body.length < 4)
        {
            return false;
        }
        // ID3 / MP3 / RIFF / ftyp
        return body[0] == 'I' && body[1] == 'D' && body[2] == '3'
                || (body[0] == (byte) 0xFF && (body[1] & 0xE0) == 0xE0)
                || (body[0] == 'R' && body[1] == 'I' && body[2] == 'F' && body[3] == 'F');
    }

    private static String mimeOf(HttpResponse<byte[]> resp, Path target)
    {
        String type = header(resp, "Content-Type");
        if (type != null && type.toLowerCase(Locale.ROOT).startsWith("audio/"))
        {
            return type.split(";")[0].trim();
        }
        return mimeOfUrl(target.getFileName().toString(), target);
    }

    private static String mimeOfUrl(String url, Path target)
    {
        String name = (url == null ? "" : url).toLowerCase(Locale.ROOT);
        if (name.contains(".wav"))
        {
            return "audio/wav";
        }
        return "audio/mpeg";
    }

    private static String header(HttpResponse<byte[]> resp, String name)
    {
        return resp.headers().firstValue(name).orElse(null);
    }

    private static String decode(byte[] body)
    {
        if (body == null)
        {
            return "";
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    private static String trim(String body)
    {
        if (body == null)
        {
            return "";
        }
        return body.length() > 300 ? body.substring(0, 300) : body;
    }

    public record TtsGenerateRequest(String text, String voice, String instructions)
    {
    }
}
