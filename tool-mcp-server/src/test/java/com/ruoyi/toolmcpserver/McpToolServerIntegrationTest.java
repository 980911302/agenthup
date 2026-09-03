package com.ruoyi.toolmcpserver;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端:真正拉起 embedded Tomcat + streamable HTTP MCP server,用 mcp-core 的
 * {@link HttpServletStreamableHttpTransport}(主应用 DynamicMcpService 同款)消费。
 *
 * <p>覆盖计划验证步骤:8 个工具名、bash 输出契约、危险命令拒绝(isError=true)、
 * write→read 走共享沙箱根、非法截图参数报中文错。startup 即建立会话(无显式 start())。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpToolServerIntegrationTest
{
    @LocalServerPort
    int port;

    @TempDir
    static Path workspaceRoot;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry)
    {
        registry.add("ruoyi.ai.tool.workspace-root", () -> workspaceRoot.toAbsolutePath().toString());
    }

    private McpSyncClient client;

    @AfterEach
    void tearDown()
    {
        if (client != null)
        {
            client.close();
        }
    }

    @Test
    void exposesEightToolsAndExecutesBash()
    {
        try (McpSyncClient c = client())
        {
            // initialize + tools/list
            List<McpSchema.Tool> tools = c.listTools().tools();
            List<String> names = tools.stream().map(McpSchema.Tool::name).toList();
            assertEquals(List.of(
                    "bash", "read", "write", "edit", "grep", "find", "ls", "captureScreenshot"), names);

            // bash 输出契约不退化
            McpSchema.CallToolResult ok = c.callTool(new McpSchema.CallToolRequest("bash",
                    Map.of("command", "echo hi")));
            assertFalse(Boolean.TRUE.equals(ok.isError()), text(ok));
            assertTrue(text(ok).contains("Command exited with code 0"), text(ok));
            assertTrue(text(ok).contains("hi"), text(ok));

            // 危险命令:isError=true,中文文案原样
            McpSchema.CallToolResult danger = c.callTool(new McpSchema.CallToolRequest("bash",
                    Map.of("command", "rm -rf /")));
            assertEquals(Boolean.TRUE, danger.isError());
            assertTrue(text(danger).contains("拒绝执行危险命令"), text(danger));
        }
    }

    @Test
    void fileToolsLandInSharedWorkspaceRoot()
    {
        try (McpSyncClient c = client())
        {
            McpSchema.CallToolResult w =
                    c.callTool(new McpSchema.CallToolRequest("write",
                            Map.of("path", "hello.txt", "content", "hello 世界")));
            assertFalse(Boolean.TRUE.equals(w.isError()), text(w));

            // standalone 无会话:文件必须落在共享沙箱根 {workspace-root}/_shared/,而不是进程 cwd
            Path expected = workspaceRoot.resolve("_shared").resolve("hello.txt");
            assertTrue(Files.exists(expected), "文件应写入共享沙箱根,实际写到了: " + expected);

            McpSchema.CallToolResult r = c.callTool(new McpSchema.CallToolRequest("read",
                    Map.of("path", "hello.txt")));
            assertFalse(Boolean.TRUE.equals(r.isError()), text(r));
            assertTrue(text(r).contains("hello 世界"), text(r));
        }
    }

    @Test
    void screenshotRejectsInvalidUrlWithChineseError()
    {
        try (McpSyncClient c = client())
        {
            McpSchema.CallToolResult r = c.callTool(new McpSchema.CallToolRequest("captureScreenshot",
                    Map.of("url", "ftp://not-a-web-page")));
            assertEquals(Boolean.TRUE, r.isError());
            assertTrue(text(r).contains("url 必须以 http"), text(r));
        }
    }

    @Test
    void remoteWriteRoutesToPerSessionWorkspaceKey()
    {
        try (McpSyncClient c = client())
        {
            McpSchema.CallToolResult w = c.callTool(new McpSchema.CallToolRequest("write",
                    Map.of("path", "hello.txt", "content", "per-session", "_workspaceKey", "sess-x")));
            assertFalse(Boolean.TRUE.equals(w.isError()), text(w));

            // 带 _workspaceKey → 按会话落盘,而不是共享 _shared
            Path expected = workspaceRoot.resolve("sess-x").resolve("hello.txt");
            assertTrue(Files.exists(expected), "应按会话键落盘: " + expected);
            assertFalse(Files.exists(workspaceRoot.resolve("_shared").resolve("hello.txt")),
                    "不得落到共享沙箱");
        }
    }

    @Test
    void maliciousWorkspaceKeyIsRejectedWithoutEscape()
    {
        try (McpSyncClient c = client())
        {
            // 非法 key 过不了 SessionIds 正则,在工具内部 resolveRoot 即被拒,
            // 走 isError 结果通道(不抛到 client)
            McpSchema.CallToolResult r = c.callTool(new McpSchema.CallToolRequest("write",
                    Map.of("path", "pwned.txt", "content", "x", "_workspaceKey", "../escape")));
            assertEquals(Boolean.TRUE, r.isError(), text(r));
        }
        assertFalse(Files.exists(workspaceRoot.resolve("pwned.txt")), "非法 key 不得落盘");
        assertFalse(Files.exists(workspaceRoot.getParent().resolve("escape")), "不得逃出沙箱根");
        // 回归:非法 key 曾因 ProjectPaths.cwd 吞异常而静默回退进程 cwd,此处一并锁死
        assertFalse(Files.exists(Path.of(System.getProperty("user.dir"), "pwned.txt")), "不得回退到进程 cwd");
    }

    @Test
    void wsEndpointsServeFilesAndBlockTraversal() throws Exception
    {
        try (McpSyncClient c = client())
        {
            McpSchema.CallToolResult w = c.callTool(new McpSchema.CallToolRequest("write",
                    Map.of("path", "note.txt", "content", "hello ws", "_workspaceKey", "ws-1")));
            assertFalse(Boolean.TRUE.equals(w.isError()), text(w));
        }
        // tree 里能看到这个会话的文件
        String tree = get("/ws/tree", "ws-1", null);
        assertTrue(tree.contains("note.txt"), tree);
        // 该会话的 key 看不到别的会话的文件
        String otherTree = get("/ws/tree", "sess-x", null);
        assertFalse(otherTree.contains("note.txt"), otherTree);
        // 文本预览
        String file = get("/ws/file", "ws-1", "note.txt");
        assertTrue(file.contains("hello ws"), file);
        // 路径穿越被 resolveSafe 拒绝
        String bad = get("/ws/file", "ws-1", "../escape.txt");
        assertTrue(bad.contains("\"code\":500"), bad);
        // 原样下载字节
        byte[] dl = getBytes("/ws/download", "ws-1", "note.txt");
        assertEquals("hello ws", new String(dl, StandardCharsets.UTF_8));
        // 删除后该文件从树里消失
        String del = request("DELETE", "/ws/file", "ws-1", "note.txt");
        assertTrue(del.contains("\"code\":200"), del);
        assertFalse(get("/ws/tree", "ws-1", null).contains("note.txt"));
    }

    @Test
    void wsUploadLandsInUploadsDirAndClearEmpties() throws Exception
    {
        String r = multipartPost("/ws/upload", "ws-1", "report.csv", "a,b\n1,2");
        assertTrue(r.contains("uploads/report.csv"), r);

        assertEquals("a,b\n1,2",
                new String(getBytes("/ws/download", "ws-1", "uploads/report.csv"), StandardCharsets.UTF_8));

        String clear = request("DELETE", "/ws/clear", "ws-1", null);
        assertTrue(clear.contains("\"code\":200"), clear);
        assertTrue(get("/ws/tree", "ws-1", null).contains("\"nodes\":[]"));
    }

    private static final HttpClient HTTP_WS = HttpClient.newHttpClient();

    private String get(String endpoint, String key, String path) throws Exception
    {
        return new String(getBytes(endpoint, key, path), StandardCharsets.UTF_8);
    }

    private byte[] getBytes(String endpoint, String key, String path) throws Exception
    {
        HttpResponse<byte[]> resp = HTTP_WS.send(builder(endpoint, key, path).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, resp.statusCode(), "GET " + endpoint + "?workspaceKey=" + key);
        return resp.body();
    }

    private String request(String method, String endpoint, String key, String path) throws Exception
    {
        HttpResponse<byte[]> resp = HTTP_WS.send(builder(endpoint, key, path)
                        .method(method, HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, resp.statusCode(), method + " " + endpoint);
        return new String(resp.body(), StandardCharsets.UTF_8);
    }

    private String multipartPost(String endpoint, String key, String filename, String content) throws Exception
    {
        String boundary = "----mcptest" + System.nanoTime();
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: text/plain\r\n\r\n";
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] payload = content.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(head.getBytes(StandardCharsets.UTF_8));
        body.write(payload);
        body.write(tail);

        HttpRequest req = builder(endpoint, key, null)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        HttpResponse<byte[]> resp = HTTP_WS.send(req, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, resp.statusCode(), "POST " + endpoint);
        return new String(resp.body(), StandardCharsets.UTF_8);
    }

    private HttpRequest.Builder builder(String endpoint, String key, String path)
    {
        StringBuilder url = new StringBuilder("http://localhost:").append(port).append(endpoint)
                .append("?workspaceKey=").append(key);
        if (path != null && !path.isBlank())
        {
            url.append("&path=").append(path);
        }
        return HttpRequest.newBuilder(URI.create(url.toString()));
    }

    private McpSyncClient client()
    {
        // 与主应用 DynamicMcpService 一致:HttpClientStreamableHttpTransport + 60s 请求超时
        this.client = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + port + "/mcp").build())
                .requestTimeout(Duration.ofSeconds(60))
                .initializationTimeout(Duration.ofSeconds(15))
                .build();
        return this.client;
    }

    private static String text(McpSchema.CallToolResult result)
    {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }
}