package com.ruoyi.system.tool;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;

/**
 * 会话工作区抽屉的「远端代理」:当执行型工具整体跑到远端 tool-mcp-server(mode=mcp)后,
 * AI 产出的文件在 OPI 本地沙箱,本服务把工作区接口的读写原样透传到远端的 /ws REST
 * (同一套 {@link WorkspaceSandbox} 护栏 + {@link WorkspaceTreeWalker} 形状)。
 *
 * <p>会话隔离靠 tool-mcp-server 按 workspaceKey 落盘,这里只负责传对 key;
 * 会话所有权校验仍在主应用 {@code AiChatWorkspaceController} 先做。
 */
@Component
public class RemoteWorkspaceService
{
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {};

    private final AiToolProperties props;

    public RemoteWorkspaceService(AiToolProperties props)
    {
        this.props = props;
    }

    public boolean enabled()
    {
        return props.isRemoteWorkspaceEnabled();
    }

    private String base()
    {
        return props.getRemoteWorkspaceBaseUrl().replaceAll("/+$", "");
    }

    /** GET /ws/tree -> AjaxResult 形状 {truncated, nodes} 拍平在顶层 */
    public Map<String, Object> tree(String workspaceKey)
    {
        return getJson("/ws/tree", workspaceKey, null);
    }

    /** GET /ws/file -> AjaxResult 形状 {found,path,size,tooLarge,binary,content} 拍平在顶层 */
    public Map<String, Object> file(String workspaceKey, String path)
    {
        return getJson("/ws/file", workspaceKey, path);
    }

    /** GET /ws/download -> 原样字节(任意类型) */
    public byte[] download(String workspaceKey, String path)
    {
        return getBytes("/ws/download", workspaceKey, path);
    }

    /** GET /ws/download-zip -> zip 字节(目录为空则整个工作区) */
    public byte[] downloadZip(String workspaceKey, String path)
    {
        return getBytes("/ws/download-zip", workspaceKey, path);
    }

    /** DELETE /ws/file?path=... */
    public void deleteFile(String workspaceKey, String path)
    {
        request("DELETE", "/ws/file", workspaceKey, path, null);
    }

    /** DELETE /ws/clear(清空整个工作区) */
    public void clear(String workspaceKey)
    {
        request("DELETE", "/ws/clear", workspaceKey, null, null);
    }

    /** POST /ws/upload(multipart)-> {name,path,mime,size} */
    public Map<String, Object> upload(String workspaceKey, String originalName, byte[] bytes)
    {
        return upload(workspaceKey, originalName, bytes, WorkspaceTreeWalker.SOURCE_USER);
    }

    /** POST /ws/upload，source=user 进 uploads/，source=ai 进 outputs/。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> upload(String workspaceKey, String originalName, byte[] bytes, String source)
    {
        String boundary = "----" + UUID.randomUUID();
        String parts = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"source\"\r\n\r\n"
                + (source == null ? WorkspaceTreeWalker.SOURCE_USER : source) + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\""
                + WorkspaceTreeWalker.sanitizeFileName(originalName) + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        byte[] head = parts.getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[head.length + bytes.length + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(bytes, 0, body, head.length, bytes.length);
        System.arraycopy(tail, 0, body, head.length + bytes.length, tail.length);

        HttpResponse<byte[]> resp = send(build("POST", "/ws/upload", workspaceKey, null,
                HttpRequest.BodyPublishers.ofByteArray(body))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary), null);
        Map<String, Object> res = parse(resp);
        return (Map<String, Object>) res.getOrDefault("data", Map.of());
    }

    // ==================== 内部 ====================

    private Map<String, Object> getJson(String endpoint, String workspaceKey, String path)
    {
        return parse(send(build("GET", endpoint, workspaceKey, path, HttpRequest.BodyPublishers.noBody()), null));
    }

    private byte[] getBytes(String endpoint, String workspaceKey, String path)
    {
        HttpResponse<byte[]> resp = send(build("GET", endpoint, workspaceKey, path,
                HttpRequest.BodyPublishers.noBody()), HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() / 100 != 2)
        {
            throw new ServiceException("远端工作区请求失败(HTTP " + resp.statusCode() + ")");
        }
        return resp.body();
    }

    private void request(String method, String endpoint, String workspaceKey, String path, byte[] body)
    {
        HttpRequest.Builder b = build(method, endpoint, workspaceKey, path,
                body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        parse(send(b, null));
    }

    private HttpRequest.Builder build(String method, String endpoint, String workspaceKey,
                                      String path, HttpRequest.BodyPublisher body)
    {
        StringBuilder url = new StringBuilder(base()).append(endpoint)
                .append("?workspaceKey=").append(enc(workspaceKey));
        if (path != null && !path.isBlank())
        {
            url.append("&path=").append(enc(path));
        }
        return HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(Duration.ofSeconds(60))
                .method(method, body);
    }

    private HttpResponse<byte[]> send(HttpRequest.Builder builder,
                                      HttpResponse.BodyHandler<byte[]> handler)
    {
        try
        {
            HttpRequest req = builder.header("Accept", "application/json").build();
            return HTTP.send(req, handler == null ? HttpResponse.BodyHandlers.ofByteArray() : handler);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new ServiceException("远端工作区请求被中断: " + e.getMessage());
        }
        catch (IOException e)
        {
            throw new ServiceException("远端工作区不可达: " + e.getMessage());
        }
    }

    private Map<String, Object> parse(HttpResponse<byte[]> resp)
    {
        if (resp.statusCode() / 100 != 2)
        {
            throw new ServiceException("远端工作区请求失败(HTTP " + resp.statusCode() + ")");
        }
        try
        {
            Map<String, Object> json = MAPPER.readValue(resp.body(), MAP_TYPE);
            Object code = json.get("code");
            if (code != null && !Integer.valueOf(200).equals(code))
            {
                throw new ServiceException(String.valueOf(json.getOrDefault("msg", "远端工作区错误")));
            }
            return json;
        }
        catch (IOException e)
        {
            throw new ServiceException("解析远端工作区响应失败: " + e.getMessage());
        }
    }

    private static String enc(String s)
    {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
