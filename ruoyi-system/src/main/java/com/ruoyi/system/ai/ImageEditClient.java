package com.ruoyi.system.ai;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;

/**
 * 图片编辑(图生图):multipart 打 {@code POST /v1/images/edits}。
 *
 * <p>为什么不用 Spring AI:{@code OpenAiImageApi$OpenAiImageRequest} 是 record,字段固定为
 * prompt/model/n/quality/responseFormat/size/style/user,**没有参考图字段且无法扩展**;
 * {@code OpenAiImageOptions} 同样没有透传任意字段的口子。所以带参考图的请求只能自己发。
 * 文生图仍走框架的 {@code OpenAiImageModel},两条路互不影响。
 *
 * <p>选 multipart 而非 JSON:参考实现(makalong 的 AiImageService)在同类中转网关上用的就是
 * multipart {@code /v1/images/edits} 并跑通了多图。xAI 直连文档说 OpenAI SDK 的 images.edit()
 * 不被支持(它要 JSON),但网关自己实现了这个端点,以网关为准。
 */
public class ImageEditClient
{
    private static final Logger log = LoggerFactory.getLogger(ImageEditClient.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration READ_TIMEOUT = Duration.ofMinutes(5);

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final HttpClient http;

    /**
     * @param model 供应表里的调用标识({@code ai_model_channel.model_name}),不是 modelCode ——
     *              调用方拿不到供应记录,所以由工厂在构造时钉进来
     */
    public ImageEditClient(String baseUrl, String apiKey, String model)
    {
        this.baseUrl = normalize(baseUrl);
        this.apiKey = apiKey;
        this.model = model;
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    /** 一张参考图:字节 + 文件名 + mime */
    public record ImagePart(byte[] bytes, String filename, String mimeType) {}

    /**
     * 带参考图生成。返回图片字节。
     *
     * @param images 参考图,至少一张;顺序即提示词里 @image1/@image2 的编号顺序
     * @param size   可空,如 1024x1024
     */
    public byte[] edit(String prompt, List<ImagePart> images, String size)
    {
        if (images == null || images.isEmpty())
        {
            throw new ServiceException("图片编辑至少需要一张参考图");
        }
        String boundary = "----agentjava" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = buildMultipart(boundary, model, prompt, images, size);
        String url = baseUrl + "/v1/images/edits";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(READ_TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try
        {
            HttpResponse<byte[]> resp = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2)
            {
                String text = new String(resp.body() == null ? new byte[0] : resp.body(), StandardCharsets.UTF_8);
                throw new ServiceException("图片编辑失败 HTTP " + resp.statusCode() + ": " + trim(text));
            }
            return extractImage(new String(resp.body(), StandardCharsets.UTF_8));
        }
        catch (ServiceException e)
        {
            throw e;
        }
        catch (IOException e)
        {
            throw new ServiceException("图片编辑请求失败: " + OpenAiCompatibleVideoClient.describeIo(e));
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new ServiceException("图片编辑被中断");
        }
    }

    /**
     * 响应可能给 b64_json 也可能给 url,两种都要认 —— 中转网关各家不一。
     */
    private byte[] extractImage(String responseBody)
    {
        JSONObject json;
        try
        {
            json = JSON.parseObject(responseBody);
        }
        catch (Exception e)
        {
            throw new ServiceException("图片编辑返回的不是 JSON: " + trim(responseBody));
        }
        JSONArray data = json == null ? null : json.getJSONArray("data");
        if (data == null || data.isEmpty())
        {
            throw new ServiceException("图片编辑未返回图片: " + trim(responseBody));
        }
        JSONObject first = data.getJSONObject(0);
        String b64 = first == null ? null : first.getString("b64_json");
        if (b64 != null && !b64.isBlank())
        {
            return Base64.getDecoder().decode(b64);
        }
        String url = first == null ? null : first.getString("url");
        if (url != null && !url.isBlank())
        {
            return download(url);
        }
        throw new ServiceException("图片编辑响应里既无 b64_json 也无 url: " + trim(responseBody));
    }

    private byte[] download(String url)
    {
        try
        {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                    .timeout(READ_TIMEOUT).GET().build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2 || resp.body() == null || resp.body().length == 0)
            {
                throw new ServiceException("下载生成图失败 HTTP " + resp.statusCode());
            }
            return resp.body();
        }
        catch (ServiceException e)
        {
            throw e;
        }
        catch (IOException e)
        {
            throw new ServiceException("下载生成图失败: " + OpenAiCompatibleVideoClient.describeIo(e));
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new ServiceException("下载生成图被中断");
        }
    }

    private static byte[] buildMultipart(String boundary, String model, String prompt,
                                         List<ImagePart> images, String size)
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<byte[]> chunks = new ArrayList<>();
        chunks.add(textField(boundary, "model", model));
        chunks.add(textField(boundary, "prompt", prompt));
        if (size != null && !size.isBlank())
        {
            chunks.add(textField(boundary, "size", size));
        }
        // 多张参考图统一用 image[] 字段名:OpenAI 的 edits 端点对多图用数组式字段,
        // 单图时字段名 image 也被广泛接受,这里保持一致以免网关按数量区别对待。
        for (ImagePart part : images)
        {
            chunks.add(fileFieldHeader(boundary, "image[]", part.filename(), part.mimeType()));
            chunks.add(part.bytes());
            chunks.add("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        chunks.add(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        try
        {
            for (byte[] c : chunks)
            {
                out.write(c);
            }
        }
        catch (IOException e)
        {
            throw new ServiceException("拼装图片编辑请求失败: " + e.getMessage());
        }
        return out.toByteArray();
    }

    private static byte[] textField(String boundary, String name, String value)
    {
        String s = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + (value == null ? "" : value) + "\r\n";
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] fileFieldHeader(String boundary, String name, String filename, String mime)
    {
        String s = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + mime + "\r\n\r\n";
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** 与 ChatModelFactory/ImageModelFactory 同口径:去掉结尾的 / 与 /v1,路径自己拼 */
    static String normalize(String raw)
    {
        if (raw == null || raw.isBlank())
        {
            throw new ServiceException("渠道 baseUrl 未配置");
        }
        String s = raw.trim();
        while (s.endsWith("/"))
        {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith("/v1"))
        {
            s = s.substring(0, s.length() - 3);
        }
        while (s.endsWith("/"))
        {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String trim(String s)
    {
        if (s == null)
        {
            return "";
        }
        String t = s.strip();
        return t.length() > 500 ? t.substring(0, 500) + "..." : t;
    }
}
