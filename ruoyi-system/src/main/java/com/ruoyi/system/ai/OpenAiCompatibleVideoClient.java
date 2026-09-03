package com.ruoyi.system.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.exception.ServiceException;

/**
 * OpenAI / grok2api 视频协议客户端:提交任务、轮询、下载。
 * <p>创建先试 {@code POST /v1/videos/generations}(grok2api),404 再试 {@code POST /v1/videos}(官方)。
 */
public class OpenAiCompatibleVideoClient
{
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    static final Duration POLL_INTERVAL = Duration.ofSeconds(2);
    static final Duration DEFAULT_JOB_TIMEOUT = Duration.ofMinutes(10);

    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final HttpClient http;
    private final Duration jobTimeout;
    private final Sleeper sleeper;

    public OpenAiCompatibleVideoClient(String baseUrl, String apiKey, String modelName)
    {
        this(baseUrl, apiKey, modelName, DEFAULT_JOB_TIMEOUT, Thread::sleep);
    }

    OpenAiCompatibleVideoClient(String baseUrl, String apiKey, String modelName,
                                Duration jobTimeout, Sleeper sleeper)
    {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey;
        this.modelName = modelName;
        this.jobTimeout = jobTimeout == null ? DEFAULT_JOB_TIMEOUT : jobTimeout;
        this.sleeper = sleeper == null ? Thread::sleep : sleeper;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    public void generateToFile(VideoGenerateRequest request, Path target) throws IOException
    {
        String createBody = JSON.toJSONString(createPayload(request));
        HttpResponse<String> created = postJson(baseUrl + "/v1/videos/generations", createBody);
        if (created.statusCode() == 404)
        {
            created = postJson(baseUrl + "/v1/videos", createBody);
        }
        if (created.statusCode() / 100 != 2)
        {
            throw new ServiceException("创建视频任务失败 HTTP " + created.statusCode() + ": " + trim(created.body()));
        }
        String jobId = VideoJobParser.jobId(created.body());
        if (jobId == null || jobId.isBlank())
        {
            throw new ServiceException("创建视频任务未返回 id: " + trim(created.body()));
        }
        String jobJson = pollUntilDone(jobId);
        String url = VideoJobParser.videoUrl(jobJson);
        download(jobId, url, target);
    }

    Map<String, Object> createPayload(VideoGenerateRequest request)
    {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        if (request.prompt() != null && !request.prompt().isBlank())
        {
            body.put("prompt", request.prompt());
        }
        if (request.duration() != null && request.duration() > 0)
        {
            body.put("duration", request.duration());
        }
        if (request.aspectRatio() != null && !request.aspectRatio().isBlank())
        {
            body.put("aspect_ratio", request.aspectRatio());
        }
        if (request.resolution() != null && !request.resolution().isBlank())
        {
            body.put("resolution", request.resolution());
        }
        // grok2api 用 encoding/json DisallowUnknownFields,只认 image 为 {url}。
        // reference_image_urls / image_urls / images / 裸字符串都会 400。
        // 多张身份参考图当前渠道发不出去,首帧优先,否则取第一张参考图。
        String startImage = request.startImageUrl();
        if (startImage != null && !startImage.isBlank())
        {
            body.put("image", Map.of("url", startImage));
        }
        return body;
    }

    private String pollUntilDone(String jobId)
    {
        long deadline = System.nanoTime() + jobTimeout.toNanos();
        String last = null;
        while (System.nanoTime() < deadline)
        {
            HttpResponse<String> resp = get(baseUrl + "/v1/videos/" + jobId);
            if (resp.statusCode() / 100 != 2)
            {
                throw new ServiceException("查询视频任务失败 HTTP " + resp.statusCode() + ": " + trim(resp.body()));
            }
            last = resp.body();
            VideoJobParser.Status st = VideoJobParser.status(last);
            if (st == VideoJobParser.Status.COMPLETED)
            {
                return last;
            }
            if (st == VideoJobParser.Status.FAILED)
            {
                throw new ServiceException(VideoJobParser.errorMessage(last));
            }
            try
            {
                sleeper.sleep(POLL_INTERVAL.toMillis());
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                throw new ServiceException("等待视频任务被中断");
            }
        }
        throw new ServiceException("视频任务超时: " + jobId);
    }

    private void download(String jobId, String url, Path target) throws IOException
    {
        String contentUrl = baseUrl + "/v1/videos/" + jobId + "/content";
        String playback = resolveDownloadUrl(url);
        IOException lastIo = null;
        Integer lastStatus = null;
        for (String candidate : downloadCandidates(playback, contentUrl))
        {
            try
            {
                HttpResponse<byte[]> resp = getBytes(candidate);
                if (resp.statusCode() / 100 == 2 && resp.body() != null && resp.body().length > 0)
                {
                    Files.write(target, resp.body());
                    return;
                }
                lastStatus = resp.statusCode();
            }
            catch (IOException e)
            {
                lastIo = e;
            }
        }
        if (lastIo != null)
        {
            throw new IOException("下载视频失败: " + describeIo(lastIo), lastIo);
        }
        throw new ServiceException("下载视频失败 HTTP " + lastStatus);
    }

    private HttpResponse<byte[]> getBytes(String url) throws IOException
    {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(3))
                .GET();
        if (url.startsWith(baseUrl) || url.contains("/v1/videos/") || url.contains("/v1/media/"))
        {
            b.header("Authorization", "Bearer " + apiKey);
        }
        try
        {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IOException("下载视频被中断", e);
        }
    }

    String resolveDownloadUrl(String url)
    {
        if (url == null || url.isBlank())
        {
            return null;
        }
        String raw = url.trim();
        URI uri = URI.create(raw);
        if (uri.isAbsolute())
        {
            return raw;
        }
        return raw.startsWith("/") ? baseUrl + raw : baseUrl + "/" + raw;
    }

    static java.util.List<String> downloadCandidates(String playbackUrl, String contentUrl)
    {
        java.util.LinkedHashSet<String> urls = new java.util.LinkedHashSet<>();
        if (playbackUrl != null && !playbackUrl.isBlank())
        {
            urls.add(playbackUrl);
        }
        if (contentUrl != null && !contentUrl.isBlank())
        {
            urls.add(contentUrl);
        }
        return java.util.List.copyOf(urls);
    }

    public static String describeIo(Throwable error)
    {
        if (error == null)
        {
            return "unknown I/O error";
        }
        StringBuilder sb = new StringBuilder();
        Throwable t = error;
        int depth = 0;
        while (t != null && depth < 4)
        {
            if (depth > 0)
            {
                sb.append(" <- ");
            }
            sb.append(t.getClass().getSimpleName());
            String msg = t.getMessage();
            if (msg != null && !msg.isBlank())
            {
                sb.append(": ").append(msg);
            }
            t = t.getCause();
            depth++;
        }
        return sb.length() == 0 ? "unknown I/O error" : sb.toString();
    }

    private HttpResponse<String> postJson(String url, String json)
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
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new ServiceException("创建视频任务被中断");
        }
        catch (IOException e)
        {
            throw new ServiceException("创建视频任务失败: " + e.getMessage());
        }
    }

    private HttpResponse<String> get(String url)
    {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build();
        try
        {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new ServiceException("查询视频任务被中断");
        }
        catch (IOException e)
        {
            throw new ServiceException("查询视频任务失败: " + e.getMessage());
        }
    }

    static String normalizeBaseUrl(String baseUrl)
    {
        String url = baseUrl == null ? "" : baseUrl.trim();
        while (url.endsWith("/"))
        {
            url = url.substring(0, url.length() - 1);
        }
        if (url.endsWith("/v1"))
        {
            url = url.substring(0, url.length() - 3);
        }
        return url;
    }

    private static String trim(String body)
    {
        if (body == null)
        {
            return "";
        }
        return body.length() > 300 ? body.substring(0, 300) : body;
    }

    interface Sleeper
    {
        void sleep(long millis) throws InterruptedException;
    }

    /**
     * @param firstFrameUrl       图生视频的起始帧,可空;当前渠道只把这一张放进 {@code image.url}
     * @param referenceImageUrls  兼容旧入参;仅当首帧为空时取第一张当作起始图,不会整组上送
     */
    public record VideoGenerateRequest(String prompt, Integer duration, String aspectRatio,
                                       String resolution, String firstFrameUrl,
                                       List<String> referenceImageUrls)
    {
        String startImageUrl()
        {
            if (firstFrameUrl != null && !firstFrameUrl.isBlank())
            {
                return firstFrameUrl;
            }
            if (referenceImageUrls == null)
            {
                return null;
            }
            for (String url : referenceImageUrls)
            {
                if (url != null && !url.isBlank())
                {
                    return url;
                }
            }
            return null;
        }
    }
}
