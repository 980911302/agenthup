# AI 平台解耦与标准协议 v1

## 实施状态（2026-08-26）

v1 契约已落为独立 `ai-contract` Maven 模块，核心实现与 RuoYi 适配层也已完成物理拆分：

- `ai-runtime`：运行事件的标准信封与 legacy 双写转换。
- `ai-kb`：不依赖 Spring/RuoYi 的知识检索结果与引用格式化。
- `ai-tool`：标准 `ToolExecutor` 与 Spring AI `ToolCallback` 的桥接。
- `ruoyi-adapter`：DB 模型路由、Spring AI、知识库、工作区和 Quartz 等框架/基础设施适配器。

- 运行事件由 Redis/WebSocket 同时投递 legacy `event` 与标准 `eventV1`，前端优先消费 v1。
- DB 模型路由与 Spring AI 模型已分别适配 `ModelRouteResolver`、`ModelProvider`。
- 内置/MCP `ToolCallback` 已通过 `ToolContractRegistry` 暴露为 `ToolExecutor`。
- `KnowledgeSearchToolCallback` 已只依赖 `KnowledgeSearchPort`；检索、索引均有现有实现适配器。
- 本地沙箱和 Quartz 已分别实现 `WorkspaceStorage`、`JobSchedulerPort`。

`ruoyi-admin` 通过 `ruoyi-adapter` 装配这些实现，`ruoyi-system` 不反向依赖适配层。旧 REST、JSON-RPC 和事件字段继续可用；后续删除 legacy 分支属于独立破坏性升级，不纳入 v1 兼容迁移。

## 结论

原有代码已经有一些可替换点（`KbVectorStore`、`KbGraphStore`、`KbParser`、`KbChunker`），但它们与 Spring、MyBatis、`Ai*` 数据库实体混放在 `ruoyi-system` 中；模型路由、工具回调、对话事件和知识库服务也直接依赖具体实现。v1 先以稳定 port 隔开上述边界，并把纯实现与基础设施适配器迁出 `ruoyi-system`。

`ai-contract` 是纯 Java 模块（只允许 JDK、Jackson 注解；不能依赖 Spring AI、MyBatis、RuoYi domain 或 Web），由 `ai-runtime`、`ai-kb`、`ai-tool`、`ruoyi-adapter` 分别实现或适配。现有 entity/mapper 不作为协议 DTO 发布。

| 优先级 | 可解耦能力 | 当前耦合证据 | 对外标准 / 新契约 |
| --- | --- | --- | --- |
| P0 | 模型供应与路由 | `ChatModelFactory`、`EmbeddingModelFactory`、`ImageModelFactory` 等重复读取 `AiChannel` / `AiModelChannel` 并构造 OpenAI 客户端 | OpenAI-compatible HTTP；内部 `ModelProvider`、`ModelRouteResolver` |
| P0 | 工具执行 | `ToolConfig` 固定枚举 Bean；`RecordingToolCallback` 同时负责执行、审计、事件、附件和 UI 产物 | MCP 作为远程工具协议；内部 `ToolDescriptor`、`ToolExecutor`、`ToolResult` |
| P0 | 对话运行与事件 | `ChatEventJson` 手写 JSON；WebSocket、Redis、前端直接依赖 `type` 和字段 | JSON-RPC 2.0 命令面；`RunEventEnvelope v1` 事件面 |
| P1 | 知识库检索与索引 | `KbSearchService` 直接编排模型、DB、向量和图谱；`KbIngestPipeline` 与本地文件/DB 紧耦合 | `KnowledgeSearchPort`、`KnowledgeIndexPort`、`DocumentSource` |
| P1 | 工作区文件 | controller、`WorkspaceSandbox`、`FileTools` 共用本地文件语义 | `WorkspaceStorage`、`WorkspaceFileRef` |
| P2 | 调度与运行观测 | `AiJob*`、Quartz、聊天 run 状态直接互相引用 | `JobSchedulerPort`、CloudEvents 风格审计事件 |

## 目标模块与依赖

```text
api / websocket / quartz / mcp
              │
        ruoyi-adapter
      ┌───────┼───────────┐
 ai-runtime  ai-kb     ai-tool
      └───────┴───────────┘
             ai-contract
                 │
          JDK + JSON DTO
```

`ai-contract` 只放请求、响应、错误码、枚举和 port 接口。数据表实体（如 `AiModel`、`KbChunk`）、Spring `ToolCallback`、`SseEmitter`、Redis 消息都只能留在适配层。

当前 Maven 依赖方向：

```text
ai-runtime ─┐
ai-kb ──────┼──> ai-contract
ai-tool ────┘

ruoyi-system  ──> ai-runtime + ai-kb + ai-contract
ruoyi-adapter ──> ruoyi-system + ai-runtime + ai-kb + ai-tool + ai-contract
ruoyi-admin   ──> ruoyi-adapter
```

## 契约 1：模型供应（P0）

外部调用优先采用 OpenAI-compatible API：`/v1/chat/completions`、`/v1/embeddings`、`/v1/images/generations`、`/v1/audio/speech`。这正是当前五个 Factory 的主要实现路径；不兼容的供应商须写 adapter，不能把分支扩散到 Agent 运行时。

内部端口：

```java
public interface ModelRouteResolver {
    ResolvedModel resolve(ModelRequest request);
}

public interface ModelProvider {
    ModelCapabilities capabilities();
    ChatStream chat(ChatRequest request, InvocationContext context);
    EmbeddingResponse embed(EmbeddingRequest request, InvocationContext context);
}

public record ResolvedModel(String providerId, String model, ModelCapabilities capabilities) {}
```

`ModelRequest` 必带 `capability`（`CHAT`、`EMBEDDING`、`IMAGE`、`VIDEO`、`TTS`），禁止用 `Long modelId` 作为跨模块字段。`modelId -> channel/modelName`、密钥解密、权重、30 秒缓存留在 `ruoyi-adapter` 的 `DbModelRouteResolver`。

## 契约 2：工具（P0）

远程工具直接兼容 MCP；本地工具采用同一份工具描述，并通过 adapter 转成 Spring AI `ToolCallback`。这样工具不再要求实现 `AttachmentAware`、`UiArtifactAware`、`ToolOutcomeAware` 三个“最近一次调用”状态接口。

```java
public record ToolDescriptor(
    String name, String version, String description,
    JsonNode inputSchema, JsonNode outputSchema,
    ToolSafety safety, Set<String> capabilities) {}

public interface ToolExecutor {
    ToolResult execute(ToolCall call, InvocationContext context);
}

public record ToolResult(
    boolean success, JsonNode output, List<ArtifactRef> artifacts,
    List<UiArtifact> uiArtifacts, ToolError error, Usage usage) {}
```

约束：`name` 全局稳定（建议 `namespace.action`）；schema 用 JSON Schema 2020-12；`ToolResult` 一次调用只产生一个不可变结果；危险工具只通过 `ToolSafety` + `confirmation` 协议确认，不能把确认状态保存在 callback 实例字段中。

## 契约 3：对话运行命令与事件（P0）

命令面继续使用 JSON-RPC 2.0，现有 `chat.run.create/get/cancel/subscribe` 可平滑迁移。HTTP REST 仅做同语义的 adapter，不能演化出另一套请求字段。

事件面定义为统一信封，WebSocket、Redis、SSE（若保留）均传输同一对象：

```json
{
  "specversion": "1.0",
  "type": "ai.run.tool.completed",
  "id": "evt_01J...",
  "time": "2026-08-26T10:20:30Z",
  "subject": "run/8c...",
  "sequence": 42,
  "data": { "stepId": "tool_1", "name": "kb.search", "success": true }
}
```

字段规则：

- `id` 是全局幂等键，`sequence` 在单个 run 内严格递增，断线恢复用 `sequence`；不要只依赖当前 Redis envelope 的 `eventJson` 字符串。
- `type` 采用 `ai.run.<noun>.<verb>`：`started`、`text.delta`、`reasoning.delta`、`tool.started`、`tool.confirmation.required`、`tool.completed`、`agent.started`、`agent.completed`、`ui.published`、`completed`、`failed`、`cancelled`。
- `data` 是事件专属 schema；二进制或大对象以 `ArtifactRef` / 下载 URL 引用，不能内嵌字符串化 JSON。
- `stepId`、`parentStepId`、`invocationId` 分别表达步骤、父子关系和某次 agent 调用；不要复用 `owner` 同时表达两种语义。

现有 `ChatEventJson` 可先作为 `RunEventV1 -> legacy JSON` 的兼容序列化器。前端先同时接受 legacy `type` 与新信封，稳定后再删除 legacy 分支。

## 契约 4：知识库（P1）

将“检索能力”从 `KbSearchService` 的实现细节中抽出。调用者只应知道请求、命中、引用和追踪信息；不应知道 pgvector、Neo4j、`AiModel` 或 chunk 表。

```java
public interface KnowledgeSearchPort {
    SearchResponse search(SearchRequest request, InvocationContext context);
}

public interface KnowledgeIndexPort {
    IndexOperation start(IndexRequest request, InvocationContext context);
    IndexStatus status(String operationId, InvocationContext context);
    void delete(IndexSelector selector, InvocationContext context);
}

public record SearchRequest(List<String> knowledgeBaseIds, String query,
    SearchOptions options) {}
public record SearchHit(String documentId, String chunkId, String content,
    double score, List<Citation> citations, Map<String, Object> metadata) {}
```

保留并迁移现有 `KbVectorStore`、`KbGraphStore`、`KbParser`、`KbChunker`，但其参数改为 `String` 资源 ID 和 contract DTO。`PgVectorKbVectorStore`、`Neo4jKbGraphStore`、解析器注册表成为 `ai-kb` adapter。`basic/local/hybrid/global/drift/auto` 是 `SearchOptions.mode` 的可扩展枚举，未知值必须显式降级并写入 `SearchResponse.degradations`。

## 契约 5：工作区与产物（P1）

聊天工作区、工具附件和 UI 产物当前分散在 controller、本地路径和事件字符串中。统一使用不可猜测的逻辑引用：

```java
public interface WorkspaceStorage {
    WorkspaceNode stat(WorkspaceRef workspace, String path, InvocationContext context);
    ReadHandle open(WorkspaceRef workspace, String path, ByteRange range, InvocationContext context);
    WriteResult write(WorkspaceRef workspace, WriteRequest request, InvocationContext context);
}
public record ArtifactRef(String id, String mediaType, String name,
                          long size, URI downloadUrl, String sha256) {}
```

`WorkspaceSandbox` 继续负责本地路径校验，但只实现该端口；API 和工具不得返回宿主机绝对路径。`UiArtifact` 改为 `artifactType + schemaVersion + JsonNode payload`，注册表通过显式 schema 管理，而不是由 Java record 约束前端。

## 通用治理规则

1. 协议版本：HTTP 使用媒体类型或 `/v1`；事件使用 `specversion`；每个 UI artifact 使用独立 `schemaVersion`。仅允许新增可选字段，删除/改语义必须发布 v2。
2. 身份与租户：所有 port 的最后一个参数均为 `InvocationContext`（`tenantId`、`principalId`、角色、traceId、deadline、idempotencyKey）；禁止从 `SecurityUtils` 或 ThreadLocal 在核心模块隐式读取。
3. 错误：统一 `code`、`message`、`retryable`、`details`。至少定义 `VALIDATION`、`UNAUTHORIZED`、`NOT_FOUND`、`CONFLICT`、`RATE_LIMITED`、`UPSTREAM_UNAVAILABLE`、`TIMEOUT`、`INTERNAL`。
4. 可观测性：所有命令、工具和索引操作继承 `traceId`；审计与计费是事件订阅者，不应堵塞主调用。
5. 测试：每个 provider/port 都要有 contract test fixture；生产 adapter 与 in-memory fake 共用同一套 fixture，避免只测 Spring bean 装配。

## 迁移顺序

1. 建 `ai-contract`，先落 DTO、错误模型、`InvocationContext` 与 `RunEventEnvelope`；不改接口行为。
2. 把 `ChatEventJson` 改为新事件的 legacy adapter，并给 WebSocket/前端加入新旧双读测试。
3. 提取 `ModelRouteResolver` 和 `ModelProvider`；将现有 Factory 收敛为一个 DB 路由 adapter 加多个 OpenAI-compatible provider adapter。
4. 提取 `ToolDescriptor` / `ToolExecutor`，以 adapter 包住 Spring AI 与 MCP；再拆 `RecordingToolCallback` 的审计、事件、确认职责。
5. 让 `KnowledgeSearchToolCallback` 只依赖 `KnowledgeSearchPort`，然后迁移索引、向量、图谱、解析实现。
6. 最后迁移工作区和 Quartz；此时才评估是否把 `ai-kb` 或 `ai-runtime` 部署成独立服务。

## 暂不建议拆出的部分

- RuoYi 用户、角色、菜单、字典、登录：它们是当前单体的基础域，先通过 `InvocationContext` 隔离即可，直接微服务化收益低。
- `AiAgent` 配置后台 CRUD：先把它作为 runtime 的配置适配器；在 Agent 配置模型稳定前，不要对外发布管理 API。
- 具体的提示词、知识图谱提取/重排策略：它们属于可演进策略，不应冻结成跨服务协议。
