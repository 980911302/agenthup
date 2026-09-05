# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目定位

本仓库(代号 **agent-java** / 前端名 **AgentHub**)是 RuoYi-Vue 3.9.2 的深度改造版:在若依多租户管理后台之上,叠加了「多 Agent / 工具调用(MCP、Skill、内置工具)/ 知识库 RAG / 定时任务 / 计量统计」的企业级 AI 平台。一句话:**用户像配菜单一样配置智能体,后端把每次对话跑成可观测、可计量、可回放、可续跑的 Run**。

**改代码前必读**:`docs/` 目录是本项目的权威架构文档(全部中文),含横向总览、阅读地图、会话身份、14 篇模块文档、6 篇模块 API、1 篇表结构,且随代码持续校对同步。请先读 `docs/整体架构总览.md`,再按需读对应 `xxx模块.md` 或 `xxxAPI文档.md`。本文档只做索引与速查,不重复细节。

## 技术栈与数据落盘

- 后端:Spring Boot 3.5.14 / Java 17 / Maven 多模块;MyBatis + Druid + PageHelper
- AI 框架:Spring AI 1.1.5(BOM 导入)**但不用任何 `spring.ai.*` 自动配置**——模型由工厂按 DB 渠道在运行时动态构造
- 前端:Vue 3 + Element Plus + Vite(`ruoyi-ui/`)
- 浏览器插件:`extension/`(Chrome MV3 侧边栏「AgentHub」,渠道工具宿主,`@crxjs` + Vue 3,产物 `extension/dist`);桌面端:`desktop/`(Vue 3,与插件共享 clientTools 协议副本)
- 官网落地页:`website/`(React 19 + Vite 8 + TypeScript,Framer Motion / Lucide / Three.js,独立于 Maven 与主前端)
- 主库 MySQL 8(`ai_*` + `sys_*`);知识库 PostgreSQL 14+ + pgvector(`kb_*` + 长期记忆 `mem_vector_*`);Redis(Run 事件总线 / 缓存);文件系统(`./agent-java/ai/` 工作区与上下文);S3 兼容对象存储(个人文件 `ai_user_file`,可选,默认关闭)
- 数据库双源见 `ruoyi-admin/src/main/resources/application-druid.yml`;AI 配置集中在 `application-ai.yml`(由 `application.yml` 经 `spring.config.import` 无条件引入)

## 常用命令

```bash
# 后端打包(根目录,产物 ruoyi-admin.jar)
mvn clean package -DskipTests

# 后端本地开发:IDE 运行 com.ruoyi.RuoYiApplication(ruoyi-admin 模块),端口 8080
# 前置:MySQL + PostgreSQL(pgvector) + Redis 已就绪

# 测试(AI 相关单测都在 ruoyi-system)
# -am 必须带:测试代码引用了 ruoyi-common/ruoyi-framework 的类(EncryptUtils、
# DataSourceScope、DynamicDataSourceContextHolder),不带则用本地仓库里的旧 jar,
# 测试编译期报「找不到符号」
mvn test -pl ruoyi-system -am
# 单个测试类 / 单个方法
# -am 会一并带上兄弟模块,它们没有匹配的测试,须关掉「没匹配就失败」
# (注意是 -Dsurefire.failIfNoSpecifiedTests,不是 -DfailIfNoSpecifiedTests)
mvn test -pl ruoyi-system -am -Dtest=ToolBudgetTest -Dsurefire.failIfNoSpecifiedTests=false
mvn test -pl ruoyi-system -am -Dtest=ContextCleanerTest#testTrimToolResults -Dsurefire.failIfNoSpecifiedTests=false

# 前端
cd ruoyi-ui && pnpm install && pnpm dev   # 开发
pnpm build:prod                            # 构建

# 官网落地页(website/,独立于主前端)
cd website && npm install && npm run dev   # 开发,默认 :5173
npm run lint                               # oxlint
npm run build && npm run preview           # 构建 + 预览(产物 dist/)

# 浏览器插件(extension/,Chrome MV3 侧边栏)
cd extension && npm install && npm run dev   # 开发,crxjs HMR
npm run build                                # 构建,Chrome「加载已解压的扩展程序」选 extension/dist

# 部署:jar 方式启停
./ry.sh start|stop|restart|status
# 生产 CI 走 Jenkinsfile(Docker 构建 + docker compose 部署)

# 全新环境建库:按 sql/init/README.md 的顺序执行(MySQL 8 步 + PG 1 步)
# 注意:sql/ 根目录散落的脚本多为 ALTER 增量,建新库请用 sql/init/
```

接口文档:Springdoc,`/swagger-ui.html`。前端 `ruoyi-ui/src/api/` 与 `ruoyi-ui/src/views/ai/` 按模块一一对应(agent/channel/chat/job/kb/mcpServer/model/skill/tool)。

## 代码架构(需读多文件才能看懂的"大图")

Maven 多模块,分层:

- **ruoyi-admin**:接入层。`web/controller/ai/*`(REST,含 AiChatRunController 等)、`web/websocket/chat/*`(JSON-RPC WebSocket `/ws/ai/chat`)、`web/core/config/SwaggerConfig` 等。
- **ruoyi-system**:AI 域核心,**绝大部分 AI 逻辑在这里**,三个包:
  - `com.ruoyi.system.ai`(agent 装配 / context 上下文 / memory 记忆(会话内 + longterm 跨会话长期记忆)/ metering 计量 / run 执行引擎 / session 访问控制 / trace 链路追踪 / tool)
  - `com.ruoyi.system.kb`(知识库:ingest/parser/chunker/vector/search/graph/policy 子包)
  - `com.ruoyi.system.tool`(工具生态:BuiltinTools/FileTools/ShellTool/DynamicMcpService/RecordingToolCallback/WorkspaceSandbox/channel 渠道工具 等)
- **ai-contract**:纯契约模块,只依赖 Jackson,不碰 Spring / 数据库 / RuoYi 实体。`com.ruoyi.ai.contract` 下按域分包(core / kb / workspace / model / job / artifact / event / tool / storage),跨层接口如 `WorkspaceStorage`、`ObjectStorage`、`ModelProvider`、`KnowledgeSearchPort`、`JobSchedulerPort` 都在这。**单独一层是为了让接口不被实现细节污染**——上层只认契约,换实现不动调用方。
- **ai-runtime**:`com.ruoyi.ai.runtime.event`,把现有 ChatEventJson 转成 v1 标准事件信封;迁移期 legacy 与 v1 双投递,业务生产者不必一次性改写。
- **ai-kb**:`com.ruoyi.ai.kb`,固定检索结果的模型文本与 UI 引用载荷格式。不依赖数据库与 RuoYi 实体,才能被核心与适配层共用。
- **ai-tool**:`com.ruoyi.ai.tool.spring`,Spring AI `ToolCallback` 与契约 `ToolExecutor` 的双向边界适配。唯一允许引 spring-ai 的 ai-* 模块,把框架类型挡在契约之外。
- **ruoyi-adapter**:适配层,`com.ruoyi.adapter` 下 kb / workspace / ai(model / job)/ tool / storage 把 ruoyi-system 的实现接到上述契约端口。**第三方 SDK(如 AWS SDK v2)只出现在这一层**,换厂商不扩散进核心。
- **ruoyi-framework / ruoyi-common**:若依原生框架与通用工具。
- **ruoyi-quartz**:调度。与 RuoYi `sys_job` 共享 Scheduler,**但 Job 实现层完全分叉**(AI 用 `AiJobDispatcher`/`AiJobReconciler`,不能混用 `AbstractQuartzJob` 与 `org.quartz.Job`)。
- **ruoyi-generator**:若依原生代码生成器。

**依赖方向**:`ai-contract` ← `ruoyi-system` ← `ruoyi-adapter` ← `ruoyi-admin`(右边依赖左边)。ai-runtime / ai-kb 由 ruoyi-system 直接依赖,ai-tool 只被 ruoyi-adapter 依赖。

**一次对话的主链路**(详见 `docs/聊天执行引擎.md`、`docs/智能体核心模块.md`):
`POST /ai/chat/run` → `ChatRunService.create`(落 `ai_chat_run`,CAS 抢 `uk_ai_chat_run_active` 唯一索引,并发返回 409)→ 立即返回 runId → `ChatRunExecutor` → `ChatTurnRunner` → `AgentContextFactory.buildForRun`(查 ai_agent + 关联工具/技能;知识库按**会话级多选**装配——存于 `ai_chat_session_kb`,非智能体固定绑定)→ `ChatModelFactory` 动态构造模型 → **`AgentToolLoop` 自建工具循环**(主/子共用,`internalToolExecutionEnabled=false`)→ `ChatRunEventBroker` 经 Redis 事件总线投递 → 各节点 WebSocket 推送 → 结尾 `LlmCallCollector` 差值记账 + 状态落 **SUCCEEDED**(不是 COMPLETED)。

## 关键设计决策(改代码时不要破坏)

1. **不依赖 Spring AI 自动配置**:`ChatModelFactory` / `EmbeddingModelFactory` / `ImageModelFactory` / `VideoModelFactory` / `TtsModelFactory` 全部手写,运行时从 `ai_channel` + `ai_model_channel` 权重表选渠道、解 key、构建模型,按 `channelId:modelName` 缓存;另有路由(modelId→供应)与渠道行两层 30s TTL 快照,命中路径零 SQL、apiKey 仅构造时解密一次。渠道变更发 `AiChannelChangedEvent`,工厂各自 `@EventListener` 按 `channelId:` 前缀精准清缓存(避免 Channel ↔ Factory 循环依赖);供应侧变更无事件,靠 TTL 兜底(≤30s)。
2. **ChatMemory 是追加语义**:`DbChatMemory` 直接实现 Spring AI `ChatMemory` 接口而非继承 `Repository`(绕开框架 `saveAll` 的覆盖语义,保住 `ai_chat_message` 审计流);工具往返以 `assistant(tool_calls)` + `tool` 真实消息跨轮保留(对标 OpenCode),由 `ContextCleaner` 按对清理并回写 `pruned` 标记,`ContextCompactor` 在超阈值时做 LLM 摘要压缩。上下文控制分三层:`ContextCleaner`(轮内清工具往返) → `ContextCompactor`(跨轮 LLM 摘要,边界按 `ContextBudget.target` 收敛) → `ContextOverflowGuard`(发送前按整轮丢弃兜底,保证请求尽可能可发出)。
3. **Run 引擎与 WebSocket 解耦**:`ChatRunEventBroker` 三投递(Stream 实时回放 + Redis Pub/Sub 跨实例广播 + 进程内 ApplicationEvent),长对话可跨节点,任一节点完成一轮推理所有持同 runId 的连接都能收到。chunk 消费经 `AgentToolLoop` 的 `publishOn(boundedElastic)` 离开 WebClient 共享的 nio 事件循环;每事件 Redis 往返 6→2 次(INCR 先行,其余 5 步一次 pipeline);seq 单调性的最终保证是 broker 的 per-run `synchronized` 临界区,与线程归属无关。
4. **LLM 用量用「差值记账」**:`LlmCallCollector.flushPending` 以上一轮快照做差(Spring AI 工具循环里 token 是累计值,直接 sum 会指数级虚高)。
5. **工具预算 + 工具确认是全局拦截器**:`ToolBudgetRegistry`(轮次/字符/token 三层上限 + 会话级累计,配置在 `application-ai.yml` 的 `ai.chat.tool.*`;并行执行开关在 `ai.chat.run.*`,与预算分属两个节点)、`ToolConfirmBroker`(危险工具人工确认)、`RecordingToolCallback` 包裹所有工具调用自动获得记账/事件/沙箱绑定。
6. **知识库分维度物理分表**:`kb_vector_{768,1024,1536,3072}` 绕开 pgvector HNSW 2000 维索引上限;3072 维只能全表扫但带 `kb_id` 过滤可控。索引策略走 `kb_index_policy_version` 的 active/previous/desired 三指针。
7. **systemPrompt 静态、消息前缀稳定**:消息顺序固定为 `[system] + [记忆历史] + [本轮 user]`,历史只追加,保住上游 KV-cache 前缀命中率。工具往返作为真实消息躺在历史里,不再额外注入工具摘要。
8. **工作空间物理隔离**:`workspace-root` 显式独立目录(配置值 `./agent-java/ai/workspace`,绑 `ruoyi.ai.tool.workspace-root`),按 sessionId 隔离,与 RuoYi `/profile/**`(GET 免鉴权)解耦,避免 AI 产出文件匿名可下载。注意它必须在 yml 里显式配:留空会回退到代码默认 `{ruoyi.profile}/ai-workspace`,而那恰是被 `/profile/**` 匿名暴露的目录。会话访问统一走 `SessionAccessGuard`。
9. **会话身份与访问控制**:AI 会话鉴权集中在 `com.ruoyi.system.ai.session`,多端/订阅统一走 `ChatWebSocketTicketService`;改动前读 `docs/会话身份与访问控制.md`。
10. **上游模型清单落库**:`ai_upstream_model` 按渠道存可用模型,导入与供应候选都查它而非实时打上游。非自定义渠道由“同步”全量覆盖(先拉取成功再删旧数据,空列表拒绝覆盖);自定义渠道手动维护。同步只动清单表,不碰 `ai_model` / `ai_model_channel`,失配的供应打“上游已下架”角标由人决定去留。
11. **Run 链路四级超时收敛**:①主 LLM 流分段空闲超时(`ai.chat.run.llm-idle-timeout-seconds`)、②串行工具批次超时(`tool-batch-timeout-seconds`)、③压缩调用超时(`ai.chat.context.compact.timeout-seconds`)、④Run 总时长兜底(`max-duration-seconds`,`ChatRunExecutor` 心跳任务里本地判定)。心跳/stale 只覆盖进程死亡,这四层才覆盖「线程活着但永久阻塞」的永久 RUNNING。调参保持约束链:②>子 agent 合法总时长>①>串行工具静默期、④>①②③典型组合。**四层都罩不到 `ChatTurnRunner.run()` 的同步前奏**(装配 → 压缩 → `buildInitialMessagesForRun` 里的记忆检索),那段在 `awaitTerminal` 之前跑,取消时 `disposable` 还是 null:靠 `ai.embedding.*` 的有界超时(新建模型客户端一律显式设超时,别用框架默认重试)+ `ai.memory.retrieve.timeout-seconds` 硬截止 + `ActiveRun#wakeBlockedWorker` 的中断唤醒三者兜。详见 `docs/聊天执行引擎.md` §3.3。
12. **装配行走 AgentAssemblyCache**:`com.ruoyi.system.ai.agent.AgentAssemblyCache` 提供 agent/tool/skill/model 四类 DB 行的 30s TTL 快照;经 Service 的增删改发 `AiConfigChangedEvent` 即时失效(事件式避免 Service↔Cache 循环依赖),直改库 ≤30s。稳态每轮装配 SQL 从 11+2N+2S+k+5 降到 1(仅会话 KB 实查)。**必须实时**的 ToolCallbackRegistry(MCP 掉线)与 `require_confirm`(ToolPolicyService 独立快照)不经过它;缓存返回共享实例,消费方只读。
13. **跨会话长期记忆:全自动、零工具、两层隔离**:`com.ruoyi.system.ai.memory.longterm` 包实现。写侧 `ContextCompactor` 压缩搭车(经 `MemoryExtractor.persistFacts` 统一去重/supersede/补向量)+ `IdleSessionExtractScheduler` 空闲兜底提炼(带失败退避),落 `ai_memory` 台账 + PG `mem_vector_*`;读侧 `MemoryRetriever` 每轮向量检索注入**发送版** user 消息前(落库存原话,不污染 `ai_chat_message` 审计流)。`userId` 永远强制,`agent_id=0` 用户层 / `>0` agent 层,`MemoryTenant` 构造即校验。向量模型默认跟随 `sys_config` 的 `kb.default.embeddingModel`(`ai.memory.embedding-model-code` 可覆盖),30s TTL。**坑**:搭车层级一律 agent 层(不做「升用户层」判定);`max-per-*-scope` 已配置但无消费方(条数上限未落地);`embedding_dim`/`embedding_model` 是死列(写侧 embedding 失败无补偿)。详见 `docs/上下文与记忆模块.md §7`。
14. **个人文件与会话工作区是两套存储,不能合并**:desktop「文件」菜单走 `ai_user_file` + S3 兼容对象存储(`com.ruoyi.system.ai.userfile` 包,契约 `ObjectStorage` 在 ai-contract、实现 `S3ObjectStorageAdapter` 在 ruoyi-adapter,AWS SDK 只出现在适配层)。**不要把会话工作区也搬到对象存储**:`ShellTool` 用 `ProcessBuilder` 跑真实 `bash`、`FileTools` 有 26 处 `java.nio.Files.*`,对象存储没有 POSIX 语义,搬过去等于放弃 shell 与文件工具能力。两者之间只能**拷贝**:`/ai/files/save-from-workspace` 把工作区产出收进个人空间(前端入口在工作区抽屉每行),`/ai/files/{id}/attach` 反向把对象拷进会话 `uploads/`(接口保留,前端暂未暴露);两个方向共用 `persist`,配额与秒传一视同仁。同理 `ObjectStorage`(扁平 key + 预签名)与既有的 `WorkspaceStorage`(相对路径 + 目录树 + etag CAS)刻意不合并。配置分两段:`ruoyi.ai.storage.*` 管连接、`ruoyi.ai.user-file.*` 管配额,换厂商不该顺带改配额。**C 端与管理端是两套**:`/ai/files`(desktop,无权限点、归属焊在 SQL 的 where 里)与 `/ai/userfile`(ruoyi-ui,`ai:userfile:*` 权限点、跨用户),Mapper 里管理端方法一律 `admin` 前缀,让「能看到别人数据」在调用点可见。详见 `docs/个人文件模块.md`。
15. **渠道工具:执行体在客户端、进程内挂起、同 callId 补发**:浏览器插件把工具定义声明到 `ai_chat_session.client_tools`(新会话首轮捎在 run.create 上——REST 与 WS 两条路都要,`capabilitiesVersion` 幂等),装配期**仅顶层 agent** 在 tools 末尾追加 `ChannelToolCallback`(照常包 RecordingToolCallback,`toolSource="channel"`;子 agent 跑在后台够不到客户端,不装配)。调用时 `ChannelToolBroker`(照抄 ToolConfirmBroker 的进程内挂起)经事件总线发 `tool_call_request`(**不落投影 step**)并挂起等回传,WS `chat.tool.result` / REST `/ai/chat/run/{runId}/tool-result` 唤醒;客户端断线超 `ai.chat.tool.channel.disconnect-grace-seconds` 快速失败,重连订阅在 replay 之后**同 callId 补发**、插件按 callId 幂等不重跑(写操作不可重跑)。截图上传当前会话工作区的 `outputs/`，只回传 `workspacePath`，服务端从本地或 MCP 远端工作区取回并送进下一轮视觉上下文(`PromptMediaBuffer`);`mediaFileId` 仅保留旧客户端兼容。客户端已声明 `screenshotTab` 时不装配服务端 `captureScreenshot`(`CLIENT_SUPERSEDES`)。**服务端 require_confirm 对渠道工具不生效**(不在 ai_tool 表,恒 false),确认在客户端 confirmPolicy 两档做。详见 `docs/渠道工具与浏览器插件.md`。

## 已知约束(踩坑清单)

- `agent-java/`、`uploadPath/`、`sql/backup/` 均已 gitignore,是运行时数据/本地产物,不要提交。
- 生产多实例必须把 `workspace-root` / `context-path` 指向共享持久卷(NFS/OSS/S3),否则 AI 写出的文件与上下文跨节点不共享。
- MCP 保活 `ruoyi.ai.tool.mcp-keepalive-seconds: 30` 必须小于链路最短空闲超时(如 nginx 默认 60s),否则连接被悄悄掐掉。
- **不要往 `application-ai.yml` 添加 `spring.ai.*` 配置**;若未来引入 Spring AI starter,需做 FQCN 排除,避免与本系统自构造的模型 bean 冲突。
- 表名与脚本名不等价:`ai_agent_appearance.sql`、`ai_tool_policy.sql`、`ai_chat_attachment.sql`、`ai_llm_call_cache_tokens.sql` 等只是 ALTER 加列的增量脚本,不是新表。
- 长对话断线续跑:Run 持久化在 `ai_chat_run`,客户端按 `afterSeq` 从 Redis 订阅回放,DB 快照兜底;不要在 WebSocket handler 里直接驱动 LLM。
- 渠道工具的服务端确认是空转的:`ToolPolicyService.requireConfirm` 只读 `ai_tool` 表,渠道工具不在表里恒 false;危险操作确认在客户端 confirmPolicy(默认档全不弹)。`application-ai.yml` 的 `channel.enabled` 注释里的注入面前提(读网页 Agent 不挂 bash/文件写)是部署约束。
- `extension/README.md` 的浏览器工具清单仍是 24 工具时代旧文案,以 `extension/src/tools/browserTools.js` 实际注册的 15 个为准;`extension/src/chat/clientTools.js` 与 `desktop/src/chat/clientTools.js` 是同一协议的两份副本,改协议两侧同步。
