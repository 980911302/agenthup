# tool-mcp-server —— 内置执行型工具的独立 MCP server

把 agent-java 主应用里那批「执行型」内置工具(bash 命令 / 六个文件工具 / 无头浏览器截图)
做成**独立可跑的 MCP server**:主应用作为 MCP client 经 streamable HTTP 消费,工具名与内置
**完全一致**(`bash`、`read`、`write`、`edit`、`grep`、`find`、`ls`、`captureScreenshot`)。

## 为什么这样做

- **零逻辑复制 → 命名天然一致**:工具就是主应用进程里 `ShellTool` / `FileTools` /
  `ScreenshotToolCallback` 的同款实例(全部来自 `ruoyi-system`),`getToolDefinition()`
  读出的 name/description/inputSchema 原样搬成 MCP 工具。模型提示词依赖的全部输出文案
  (截断规则、危险命令拒绝、`Command exited with code N`…)与内置完全一样,一行未改。
- **隔离**:bash/chromium 跑到独立进程里,不占主应用堆;可整体部署到独立沙箱节点 / OPI。
- **沙箱语义延续**:主应用每调用会把会话工作区键经 args envelope 注入(没有时回退共享键
  `_shared`),server 弹出后 bind —— `ProjectPaths` / `WorkspaceSandbox` 的路径围栏与符号链接
  防护原样生效,文件落到 `{workspace-root}/{workspaceKey}/`。

## 构建 / 启动

```bash
mvn package -pl tool-mcp-server -am -DskipTests     # 必须带 -am:依赖 ruoyi-system 源码
java -jar tool-mcp-server/target/tool-mcp-server.jar
# 或开发态
mvn -pl tool-mcp-server spring-boot:run
```

端口默认 **8090**,端点 **/mcp**(streamable HTTP:initialize/消息统一 POST 到该路径,
另用 GET 建 SSE 流)。全部可配:`server.port`、`ruoyi.ai.tool.server.endpoint`。

## 配置(application.yml)

| 键 | 默认 | 说明 |
|---|---|---|
| `server.port` | 8090 | 监听端口 |
| `ruoyi.ai.tool.server.endpoint` | `/mcp` | MCP 端点路径 |
| `ruoyi.ai.tool.server.max-concurrency` | 4 | 进程类工具(bash/截图)并发上限,超配额直接报中文错(不排队) |
| `ruoyi.ai.tool.server.workspace-key` | `_shared` | v1 无会话隔离,所有调用共享这一个沙箱子目录 |
| `ruoyi.ai.tool.server.request-timeout-seconds` | 600 | 服务端单次调用超时,必须 ≥ bash 上限 |
| `ruoyi.ai.tool.server.keep-alive-seconds` | 30 | SSE 保活心跳,压住 nginx 等链路 60s 空闲超时 |
| `ruoyi.ai.tool.workspace-root` | `./agent-java/ai/workspace` | 沙箱根,生产指向持久卷(env `TOOL_SERVER_WORKSPACE_ROOT`) |
| `ruoyi.ai.tool.shell-timeout-ms` | 30000 | bash 默认超时(上限 600000 由客户端请求可传) |

## 超时矩阵(集成时最重要)

| 边界 | 值 | 谁控制 | 后果 |
|---|---|---|---|
| bash 默认超时 | 30s | `ruoyi.ai.tool.shell-timeout-ms` | 超时 `destroyForcibly` 杀进程树 |
| bash 可传上限 | 600s | 工具请求参数 `timeout` | 超上限拒绝了 |
| captureScreenshot | 45s 硬超时 | 工具内 | 超时返回中文错误 |
| **主应用 MCP client 请求超时** | **60s(SDK 默认)** | `ruoyi.ai.tool.mcp-request-timeout-ms` | **<600s 时,长命令会在主应用侧被掐断** |

> ⚠️ **主应用侧必须放宽**:给该 MCP server 对应行设置的 `mcp-request-timeout-ms` 要 ≥ bash
> 上限(600000),否则模型发一条 >60s 的命令,响应还没回来就被 client 掐断、按失败处理。
> 见 `sql/ai_mcp_tool_server_demo.sql` 示例。

## 与内置行为的一致性(逐工具)

- **bash**:正文原样(含 `Command exited with code N` 与“拒绝执行危险命令”中文文案);
  isError = `ToolOutcomeAware.lastCallOk()`(非零退出/被拒 → MCP `isError=true`)。
- **文件工具**:失败以正文返回(如 “Path not found”),isError=false —— 与内置一致
  (它们没有 ToolOutcomeAware)。截断规则全保留:read 分页、grep 100 条、find 1000、ls 500、
  media 20MB。
- **captureScreenshot**:成功返回 JSON 正文(含 **server 本地路径** `{workspace-root}/outputs/shot-*.png`);
  缺无头浏览器 / 超时 / 参数非法 → isError=true 且正文保留中文提示(“沙箱未安装无头浏览器…”)。

## 会话隔离 + 工作区文件（/ws）

v1 由主应用在每次工具调用的 args envelope 里注入 `_workspaceKey`（见下）,server 弹出后按会话
bind,读写落到 `{workspace-root}/{workspaceKey}` —— 与主应用工作区抽屉看到的是**同一目录**。

**文件端点（与 /mcp 并存,同一 HTTP 端口）**:

| 端点 | 说明 |
|---|---|
| `GET /ws/tree?workspaceKey=…` | 目录树 `{truncated, nodes}`(形状与主应用本地一致) |
| `GET /ws/file?workspaceKey=…&path=…` | 文本预览 ≤200KB |
| `GET /ws/download?workspaceKey=…&path=…` | 原样下载 |
| `GET /ws/download-zip?workspaceKey=…&path=…` | 目录打包(条数/字节上限,软链接跳过) |
| `DELETE /ws/file` / `DELETE /ws/clear` | 删除单文件 / 清空工作区 |
| `POST /ws/upload` | `source=user` 上传到 uploads/；`source=ai` 上传到 outputs/ |

护栏与主应用同源:`WorkspaceSandbox` 校验 key 格式(SessionIds 正则)与路径穿越/符号链接,
`WorkspaceTreeWalker` 出同一形状。主应用 `AiChatWorkspaceController` 在 mode=mcp 且配了
`ruoyi.ai.tool.remote-workspace-base-url` 时自动走远端代理,前端工作区抽屉零改动。

## 已知限制(v1)

- **会话隔离依赖主应用注入 `_workspaceKey`**:不带该字段的裸调用(curl/直连)仍落到
  server 配置的共享键(默认 `_shared`)。
- **截图产物只有 server 本地路径,像素不回传**:主应用侧要看图须经附件 / 对象存储通道(后续迭代)。
- **临时文件在 server 本地**:bash 截断的全量输出 `/tmp/bash-output-*.txt` 主应用消费方不可见。
- **OPI 前置**:bash 天生可用;`captureScreenshot` 需无头 chromium(装 `chromium` 或设
  `CHROMIUM_PATH`,否则返回“沙箱未安装无头浏览器…”)。

## 部署到 OPI(可选)

1. `scp tool-mcp-server/target/tool-mcp-server.jar` 到沙箱节点。
2. 装 chromium:`apt install chromium` 或设 `CHROMIUM_PATH`。
3. 起 server:`TOOL_SERVER_WORKSPACE_ROOT=<持久卷>/ai/workspace java -jar tool-mcp-server.jar`
4. 主应用 `ai_mcp_server` 行 endpoint 改成 `<奥派 IP>:8090/mcp`(ZeroTier/公网)。

## 集成主应用

1. 在 `sql/ai_mcp_tool_server_demo.sql` 插入一条 `ai_mcp_server`,enabled 后
   `DynamicMcpService.connectAll` 自动建会话、同步 `ai_tool`、进 `ToolCallbackRegistry`。
2. 因远端工具名与内置完全相同,`ai_tool.tool_code` 唯一键 + last-write-wins 会互相覆盖。
   用 `ruoyi.ai.tool.exec-tools-mode` 开关:`local`(默认)注册本地执行型工具;**`mcp`** 时主应用
   跳过本地 bash/文件/截图,全部由 MCP 同步的工具顶上,保证“一行一义”。
3. 不要在主应用里加“回连自己”的 `ai_mcp_server` 记录做自测(同名工具互相覆盖)。

## 自测

```bash
mvn test -pl tool-mcp-server -am -DskipTests -DskipTests=false   # 先装一遍依赖(见上文构建)
mvn test -pl tool-mcp-server                                       # 5 个测试
```

- `BuiltinToolBeansTest`:纯单元,核对 8 个工具名 + bash 输出契约。
- `McpToolServerIntegrationTest`:`@SpringBootTest(RANDOM_PORT)` 起真 server,用 mcp-core
  `HttpClientStreamableHttpTransport`(主应用同款)走全链路:initialize → tools/list →
  bash → 危险命令 → write→read 落共享沙箱根 → 截图非法参数。
