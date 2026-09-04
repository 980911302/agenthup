# 聊天 API 文档

> 适用版本:agent-java(RuoYi-Vue 改造版)
> 范围:聊天域 5 个 Controller —— `AiChatController`(会话辅助)、`AiChatRunController`(Run 执行)、`AiChatSessionController`(会话/消息查询)、`AiChatWebSocketTicketController`(WS 票据)、`AiChatWorkspaceController`(工作区)。
> 配套阅读:`docs/聊天对话模块.md`、`docs/聊天执行引擎.md`、`docs/流式与事件模块.md`。
> 已下线:SSE 短链路(`POST /ai/chat/stream`)随 commit `411bf8b` 移除;对话执行统一走持久化 Run(`POST /ai/chat/run`),实时事件经 WebSocket 订阅。

---

## 1. 模块定位

一次"发消息"的完整链路:

```
前端 → POST /ai/chat/run(创建持久化 Run,立即返回 runId)
     → ChatRunExecutor 异步执行(ChatTurnRunner → AgentContextFactory → LLM)
     → 事件经 ChatRunEventBroker 三投递(Redis Stream + Pub/Sub + 进程内)
     → 前端 WebSocket 订阅 run 事件实时渲染
```

REST 承担:Run 创建/控制、会话与消息查询、工作区文件、WS 票据。**对话正文与工具/思考事件不走 REST,走 WebSocket**(见 `docs/流式与事件模块.md`)。

---

## 2. Run 执行 `/ai/chat/run`(`AiChatRunController`)

### 2.1 接口总览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/` | 创建并启动 Run(幂等,`clientRequestId` 防重) |
| GET | `/{runId}` | 查询 Run 状态/结果 |
| GET | `/{runId}/state` | 可恢复状态（Run + 本轮消息账本 + 步骤投影 + `snapshotSeq`） |
| GET | `/active?sessionId=` | 该会话当前活跃 Run(无则 `data=null`) |
| GET | `/latest?sessionId=` | 该会话最近一次 Run |
| POST | `/{runId}/cancel` | 取消执行中的 Run(跨实例控制) |
| POST | `/{runId}/tool-confirm` | 危险工具人工确认/拒绝 |

### 2.2 创建 Run `POST /ai/chat/run`

请求体:

```json
{
  "sessionId": "s-20260727-001",     // 必填,前端生成
  "agentId": 1001,                   // 必填
  "message": "帮我分析季度营收",       // 必填
  "attachments": [],                 // 可选,附件数组 {name,path,mime,size}
  "kbIds": [7, 9],                   // 可选,会话级知识库多选(整组替换;空数组=清空;null=不改已有选择)
  "clientRequestId": "uuid"          // 可选,幂等键;网络重试不会重复执行
}
```

出参:`{ code:200, data:{ runId, status:"RUNNING" } }`,立即返回、异步执行。

关键语义:

- **ActiveRun CAS 守门**:同一会话已有活跃 Run 时并发提交返回 409(`ActiveChatRunException`),多 Tab 同时发消息不会重复执行。
- **会话自动就绪**:`ChatRunExecutor.ensureSessionArtifacts` 负责"无会话则建(标题取消息前 20 字)+ `ensureAgentJoined(supervisor)` + 工作区目录"(`ChatRunExecutor.java:453-455`)。
- **会话知识库**:`kbIds` 可选。会话建立时(`ensureOwnedSession`)随即写入 `ai_chat_session_kb`(每库 `requireKb(USE)` 校验,只允许选自己可访问的库);老会话为整组替换。不传 `null` 表示不改动会话已有选择。装配期 `resolveKnowledgeTool` 按 sessionId 查会话知识库下发 `searchKnowledge`。
- **编码与预算**:装配期按 agent 的 `modelCode` 解析模型、按窗口算 `inputBudget`、挂静态工具 + 六类动态工具(截图/生图/视频/语音/技能/会话知识库,见 `docs/智能体模块API文档.md` §5)。
- 执行结果经事件总线推送;成功终态是 **`SUCCEEDED`**(不是 COMPLETED)。`done` 事件携带 `usage` / `context` 与完整 `text`(断线重连后校准用);过程量 token 另走 `type=ui` / `run.tokenUsage`。

### 2.3 取消与工具确认

```http
POST /ai/chat/run/{runId}/cancel            # → {code:200}
POST /ai/chat/run/{runId}/tool-confirm
Content-Type: application/json
{ "confirmId": "c-xxx", "approved": true }  # → {code:200} 或 error"确认已过期或不存在"
```

工具确认流程:模型要调危险工具(Shell/删除)时,事件流推 `tool_confirm_required`;用户点"允许/拒绝"后调 `tool-confirm`,`confirmId` 在 Redis 有 TTL,过期返回错误。

---

## 3. 会话与消息查询 `/ai/chat/session`(`AiChatSessionController`)

### 3.1 接口总览

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/list` | 会话分页列表(**非管理员强制只看自己**) |
| DELETE | `/{sessionIds}` | 批量删除(逻辑删除;逐个校验归属 + 无活跃 Run) |
| GET | `/{sessionId}/timeline?limit=&beforeMessageId=` | 消息时间线(游标分页:缺省最新一页,`beforeMessageId` 取更早) |
| GET | `/{sessionId}/message/{messageId}/tool-result` | 按需拉取某条 TOOL 消息的完整工具结果(历史加载只给预览) |
| GET | `/{sessionId}/user-messages?limit=` | 会话内全部用户消息(右侧音轨导航) |
| GET | `/{sessionId}/traces?limit=` | 会话内全部轮次(Run)的链路聚合概览 |
| GET | `/{sessionId}/traces/{runId}` | 某一轮对话的调用树(扁平 spans,前端按 `parentSpanId` 组瀑布) |
| PUT | `/{sessionId}/knowledge-bases` | **保存会话知识库多选**(body=`Long[] kbIds`,整组替换/空数组清空;需会话归属 + 无活跃 Run;每库 `requireKb(USE)`) |

### 3.2 消息时间线(游标分页)

```http
GET /ai/chat/session/s-001/timeline?limit=30&beforeMessageId=1042
```

出参 `data=AiChatMessage[]` + `hasMore` + `runs`:消息按 `messageType` 区分 `USER / ASSISTANT / TOOL / SUMMARY / THINKING`,按 `message_id` 升序。滚动到顶传更早的 `beforeMessageId`。页面命中轮次中间时，后端会回溯到该轮 USER 并返回完整轮次，不再用固定倍数上限截掉超长工具链。

`runs` 是本页消息涉及的运行终态,`runId -> { status, errorMessage }`:

```json
{ "runs": { "3f2a…": { "status": "FAILED", "errorMessage": "403 Forbidden from POST …" } } }
```

**为什么消息之外还要带它**:失败 / 取消 / 节点中断的一轮不会写 `ASSISTANT_FINAL` 行,只看消息账本区分不出「这轮已经结束」和「还在跑」,前端会把几天前就失败的轮次一直渲染成「正在输入」。运行是否结束是控制面事实(`ai_chat_run`),所以随时间线一起下发。只对账最新一轮(`/ai/chat/run/latest`)不够 —— 更早的失败轮同样缺终态行,而且不会再有人来纠正它们。

### 3.3 工具结果按需拉取

```http
GET /ai/chat/session/s-001/message/1045/tool-result
```

出参 `{ code:200, data: { result, args, attachments?, ms, ok } }`。历史加载时工具结果只存预览(截断/落盘 `tools/*.txt`),点"查看完整结果"再拉全量。

---

## 4. 会话辅助 `/ai/chat`(`AiChatController`)

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/session/{sessionId}/context?agentId=` | 上下文用量(前端刻度条) |
| DELETE | `/session/{sessionId}/memory` | 清空会话记忆 + 上下文:删消息、`ai_llm_call` 解绑、清上下文文件、清工具预算(`@Transactional`) |
| DELETE | `/session/{sessionId}/last-turn?agentId=` | 回滚最后一轮(重新生成前调用):解绑 llm_call 再删消息;**token 已真实消耗的明细保留**(统计不丢) |

---

## 5. WebSocket 票据 `/ai/chat/ws-ticket`(`AiChatWebSocketTicketController`)

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/` | 签发一次性票据 `{ ticket, expiresIn: 60 }` |

原生 WebSocket 不能设 `Authorization` 请求头,流程:已认证 REST 换 60s 短时票据 → `new WebSocket("/ws/ai/chat?ticket=...")` → 服务端 `getAndDelete` **原子消费**(同一票据不可被两个 socket 使用)→ 握手成功后 attributes 写入 `userId/username/admin`。

WebSocket 协议为 JSON-RPC 2.0,方法:`chat.ping` / `chat.run.create` / `chat.run.get` / `chat.run.cancel` / `chat.run.subscribe` / `chat.run.unsubscribe` / `chat.session.subscribe` / `chat.session.unsubscribe`(见 `docs/流式与事件模块.md`)。

---

## 6. 工作区 `/ai/chat/workspace`(`AiChatWorkspaceController`)

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/{sessionId}/tree` | 目录树(深度 ≤5、节点 ≤500) |
| GET | `/{sessionId}/file?path=` | 读取单个文本文件(≤200KB,防路径穿越) |
| POST | `/{sessionId}/upload` | 上传文件(FormData `file`,`source=user|ai`);用户文件进 `uploads/`，AI 产物进 `outputs/`，返回 `{name,path,mime,size}` |
| DELETE | `/{sessionId}` | 清空整个会话工作区 |
| GET | `/{sessionId}/download?path=` | 单文件下载(带 token 的 URL) |
| GET | `/{sessionId}/download-zip?path=` | 目录打包下载,path 缺省为整个工作区 |

> 设计约束:只开"新增到 `uploads/`"一个写口子,不开覆盖/编辑——人工改 AI 写的文件,模型不知情,会与对话上下文脱节(注释见 `AiChatWorkspaceController.java:44-47`)。

---

## 7. 前端调用对照

| 文件 | 函数 |
|---|---|
| `ruoyi-ui/src/api/ai/chat.js` | `clearSession` / `getContextUsage` / `rollbackLastTurn`(会话辅助);`createChatRun` / `getChatRun` / `getActiveChatRun` / `getLatestChatRun` / `cancelChatRun` / `confirmChatTool`(Run);`createChatWebSocketTicket` |
| `ruoyi-ui/src/api/ai/session.js` | `listSession` / `delSession` / `getSessionTimeline` / `getToolResult` / `getSessionUserMessages` / `getSessionTraces` / `getRunTrace` |
| `ruoyi-ui/src/api/ai/workspace.js` | `getWorkspaceTree` / `getWorkspaceFile` / `uploadWorkspaceFile` / `clearWorkspace` / `workspaceFileDownloadUrl` / `workspaceZipDownloadUrl` |
| `ruoyi-ui/src/api/ai/chatRpc.js` | `ChatRpcClient` 单例:WebSocket 订阅/回放/取消/心跳 |

页面:`views/ai/chat/`(`index.vue` + `composables/useChatRun.js`、`useSessions.js` + `components/steps/` 各事件步骤卡片)。

---

## 附录:关键文件速查

| 关注点 | 路径 |
|---|---|
| Run REST | `ruoyi-admin/.../controller/ai/AiChatRunController.java` |
| 会话 REST | `ruoyi-admin/.../controller/ai/AiChatSessionController.java` |
| 辅助 REST | `ruoyi-admin/.../controller/ai/AiChatController.java` |
| 工作区 REST | `ruoyi-admin/.../controller/ai/AiChatWorkspaceController.java` |
| WS 票据 | `ruoyi-admin/.../controller/ai/AiChatWebSocketTicketController.java` |
| Run 服务 | `ruoyi-system/.../ai/run/ChatRunService.java`、`ChatRunExecutor.java` |
| 事件总线 | `ruoyi-system/.../ai/run/ChatRunEventBroker.java` |
| WS 处理器 | `ruoyi-admin/.../websocket/chat/ChatJsonRpcWebSocketHandler.java` |
| 前端 API | `ruoyi-ui/src/api/ai/chat.js`、`session.js`、`workspace.js`、`chatRpc.js` |
