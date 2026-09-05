# AgentHub Chat SDK 设计文档

> 日期:2026-09-05
> 状态:设计已确认(三节均经用户逐节确认),待写实施计划
> 相关文档:`docs/聊天执行引擎.md`、`docs/聊天对话模块.md`、`docs/流式与事件模块.md`、`docs/渠道工具与浏览器插件.md`(协议语义权威来源)

## 1. 背景与问题

AgentHub 有三个前端:ruoyi-ui(Web 管理端)、desktop(桌面端)、extension(Chrome MV3 侧边栏插件),全部 Vue 3 + Vite + Pinia。聊天协议层(WebSocket JSON-RPC + Run 事件 + 渠道工具)在三个仓库里各持一份手写副本,且**已经三个方向各自漂移**:

| 文件 | desktop | extension | ruoyi-ui | 漂移 |
| --- | --- | --- | --- | --- |
| `chatRpc.js`(WS 传输) | 525 行 | 525 行 | 486 行 | desktop↔extension 差 2 行;ruoyi-ui 缺 v1 事件信封消费(`eventV1`/`normalizeRunEvent`),重连退避也是旧策略 |
| `useChatRun.js`(Run 状态机) | 1050 行 | 1050 行 | 1027 行 | desktop 有 skillIds 盖章、workspaceChanges 接力、渠道工具 sessionId 透传,ruoyi-ui 均无 |
| `types.js`(事件/步骤常量) | 142 行 | 142 行 | 142 行 | desktop↔extension 相同 |
| `useTurnBuilder.js`(时间线重建) | 308 行 | 308 行 | 343 行 | desktop↔extension 相同 |
| `clientTools.js`(渠道工具协议) | 157 行 | ~270 行 | 无 | desktop 是旧版:`inFlight` Set,缺 extension 的 `handled` Map 补发幂等与 `declarePayloadFor` |

每次后端协议变更要人工同步 2-3 处,漏一处就是隐性 bug。CLAUDE.md 已多次出现「两边一起改」「同一协议的两份副本」的提醒。

## 2. 目标与非目标

**目标**:

1. 把聊天协议层收敛为一套对外发布的 TypeScript SDK,三端以 npm 依赖消费,删除全部副本。
2. 框架无关核心 + Vue 适配层,第三方(不用 Vue)也能只用核心把 AgentHub 聊天嵌进自己的应用。
3. 三份副本的行为分歧以 desktop/extension 最新版为基准合并;ruoyi-ui 迁移后自动获得 v1 信封消费、skillIds 盖章、sessionId 透传等修复(顺带修 3 个已知漂移 bug)。
4. 协议语义的单一收口:后端协议演进(如 v1 迁移收尾删 legacy)时只改 SDK 发版,三端不动。

**非目标**:

- 不包含会话/知识库/文件/统计等其他 REST 面的 SDK 化(仅聊天域:transport + protocol + engine + clientTools + chat REST)。
- 不动三端的 UI 组件(气泡、步骤卡片、确认框、侧栏)、路由、鉴权页面。
- 不统一三端的 axios 实例与 auth 体系(各自保留,通过注入适配)。
- 本项目不新建后端功能;后端协议面(15 个 WS RPC 方法 + `/ai/chat/*` REST)保持现状。

## 3. 已确认的关键决策

| 决策点 | 结论 | 备选与否决理由 |
| --- | --- | --- |
| SDK 层次 | 传输 + 协议常量 + 无头状态机 + clientTools 协议 + chat REST 封装 | 只统一传输层被否:1050 行状态机是漂移重灾区,留下必继续分叉 |
| 目标用户 | 明确对外发布(npm 公网) | 内部优先方案被否:用户明确要面向第三方开发者 |
| 仓库形态 | 独立 Git 仓库,三端以 npm 依赖消费 | workspace 单包被否:用户选定独立仓库;代价(联调慢)用 npm link 工作流化解 |
| 语言/产物 | TypeScript,ESM + d.ts | 三端全是 Vite,无 CJS 包袱 |
| Vue 绑定 | 核心框架无关,`./vue` 子路径出适配层(vue 为 peerDependency) | 三端皆 Vue 3,但对外发布要求框架无关 |
| 行为基准 | desktop/extension 最新版 | ruoyi-ui 版本落后(缺 v1 信封等),以它为基准会倒退 |
| 协议文档 | 语义文档留在主仓 `docs/`(权威),SDK README 只放使用文档并链接主仓 | 避免文档第三处漂移 |

## 4. SDK 仓库结构

新仓库 `agenthub-chat-sdk`(名可调),MIT 许可(与主仓一致)。

```
agenthub-chat-sdk/
├─ src/
│  ├─ protocol/     协议类型与常量:EVENT_TYPES、STEP_TYPES、RunEvent(legacy + v1 信封)、
│  │                RPC 方法名常量、终态判定、normalizeRunEvent(类型化)
│  ├─ transport/    ChatRpcClient:WS JSON-RPC 2.0 客户端(环境耦合改注入,语义原样保留)
│  ├─ engine/       createChatEngine:无头状态机(现 useChatRun 核心 + useTurnBuilder 时间线重建)
│  ├─ clientTools/  createClientToolRegistry:渠道工具注册表(declare 捎带/补发幂等/confirmPolicy 挂钩)
│  ├─ rest/         chat REST 封装(createChatRun/confirmChatTool/cancelChatRun/
│  │                getActiveChatRun/timeline/context...),http 适配器注入
│  └─ vue/          Vue 适配层:useChatRun/useConnectionState 等 composable(engine ↔ ref 胶水)
├─ test/            vitest 单测(见 §7)
├─ package.json     exports: { ".": 核心入口, "./vue": 适配层 };sideEffects: false
└─ .github/workflows/release.yml   tag v* → build → npm publish(公网)
```

- 包名建议 `@agenthub/chat`(发布前先到 npm 查 scope 可用性,备选 `agenthub-chat`)。
- 主入口零运行时依赖(WebSocket 用浏览器原生);`./vue` 入口 peerDependency `vue`。
- 版本:semver + conventional commits,tag 触发 GitHub Actions 发布,CHANGELOG 自动生成。
- 开发期联调:三端用 `npm link` / `pnpm link` 软链本地 SDK 仓库,改 SDK 即时生效;稳定后发版、三端升依赖。三端 Vite 配置预置 `optimizeDeps.exclude` 规避 link 缓存问题。

## 5. SDK 内部设计

### 5.1 注入点(createChatClient)

三份副本中写死的环境耦合收口为配置:

```ts
const client = createChatClient({
  http: { request },            // 各端现有 axios 封装(ruoyi-ui/desktop 各自的 request.js)
  tokenProvider: () => getToken(),   // 各端 token 来源(ruoyi-ui cookie / desktop+ext auth store)
  baseUrl: import.meta.env.VITE_APP_BASE_API,
  wsUrlBuilder: ticket => ...,  // 可选;默认按 baseUrl 推导 <base>/ws/ai/chat?ticket=
  reconnect: { ... },           // 可选;默认 desktop 版退避语义(见 §5.2)
})
```

### 5.2 transport/ChatRpcClient

以 desktop 版为基准原样迁移,以下精密语义**一行不改**:

- retain/release 引用计数(页面级长连接,最后持有者释放且无订阅才真正关闭);
- 指数退避重连:延迟 `min(1000 × 2^min(attempts,5), 20000) + 随机抖动`,连续失败超过阈值后广播 `closed` 停止重连(ruoyi-ui 旧版「永不放弃」语义不保留);
- generation 代际重订阅(重连后按各 run 最新 seq 自动补订阅,代际去重防 subscribe 风暴);
- seq 缺口恢复(缺口不越过高水位,onGap 回调拿持久化 Run State 检查点推进游标后换订阅,指数退避);
- chat.ping 25s 保活,超时主动重建半开连接;
- ticket → WebSocket 握手 10s 超时,stale socket 代际丢弃。

v1 信封归一化(`normalizeRunEvent`)从 transport 内部函数提升到 protocol 层,类型化为 `(eventV1, legacy) => NormalizedRunEvent`。

### 5.3 engine/createChatEngine(无头状态机)

现 `useChatRun` 约 1050 行核心抽成纯 TS:内部 turns 状态 + `subscribe(listener)`,不碰任何 Vue API。UI 关注点全部回调注入:

```ts
const engine = createChatEngine(client, {
  onToolConfirm: async ({ name, args }) => boolean,
  // ruoyi-ui 用 ElMessageBox(cancel/close 都算拒绝);desktop 用 confirmDanger;第三方自定
  onNotice: (message, type) => void,   // toast 类提示
  clientTools: registry,               // 渠道工具注册表;无渠道工具的端(ruoyi-ui)传空注册表
})
```

职责:

- turns 模型与 applyEvent 全事件处理(text/reasoning/tool_start/tool_end/tool_confirm_required/context_cleaned/agent_start/agent_end/ui/终态),含 owner/invId 嵌套归位、args 双时刻一致、handledRunIds 终态去重;
- send / abort / detach / recover 会话语义(REST 创建 + WS 订阅对账,afterSeq 断点续传);
- useTurnBuilder 时间线重建并入 engine(`rebuildTimeline`);
- 渠道工具分发:tool_call_request 时调 `clientTools.handle`,并透传事件信封 sessionId;
- skillIds 盖章、workspaceChanges/citations 接力等 desktop 版修复全部保留。

### 5.4 clientTools/createClientToolRegistry

以 extension 最新版为基准(它是三份里唯一修齐的):

- `handled: Map<callId, outcome>` 补发幂等:同 callId 重订/断线补发不重跑 handler,原样重发当时结局(HANDLED_LIMIT=200 容量上限);写操作不可重跑;
- `declarePayloadFor(sessionId)` 首轮捎带声明(新会话第一轮 run.create 捎上清单,capabilitiesVersion 幂等;空清单返回 null,防止把别的端已声明的清单原地洗掉)+ `markDeclared`;
- 工具定义由各端注册:extension 注册 15 个 browserTools,desktop 注册空集;confirmPolicy 两档确认逻辑留在端内,注册表只挂接。

### 5.5 rest/

chat 域 REST 封装,函数名与返回结构同现 `api/chat.js`,全部走注入的 http 适配器:创建运行、确认工具、取消、活动运行、时间线、上下文用量、WebSocket 票据等。

### 5.6 vue/ 适配层

`useChatRun(client, options)`、`useConnectionState(client)` 等薄封装:engine 状态接 `ref`/`watch`,预计 100-200 行胶水。确认框、toast 等 UI 组件由各端实现并通过 options 注入。

### 5.7 错误处理

transport 层超时/断线/握手失败统一抛 `ChatTransportError`(带 code),engine 据此区分「可重试」与「终态」,替代三端各自的 `catch (_)` 隐性约定。

## 6. 迁移策略

顺序:**先孪生、后主站**。不做长期双轨,切完即删副本。

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| 0. SDK 仓搭建 | TS 骨架 + vitest + CI;desktop 版三份实现机械迁移为 TS 起点(不改行为),单测补齐 | `npm test` 绿,d.ts 产出 |
| 1. desktop + extension 接入 | npm link 联调 → 组件改 import SDK → 删除本地 `chatRpc.js / types.js / useChatRun.js / useTurnBuilder.js / clientTools.js` 副本;extension 注册 15 个 browserTools,confirmPolicy 留端内 | 手工回归:对话、工具确认、断线重连、插件截图、补发幂等;发 0.1.0 |
| 2. ruoyi-ui 接入 | 同流程;确认框适配 ElMessageBox 注入;registry 传空 | 回归 + 验证自动获得 v1 信封/skillIds 盖章等修复;更新主仓 CLAUDE.md、README(删除「两份副本要同步改」的提醒,改为指向 SDK) |

## 7. 测试设计(vitest,SDK 仓)

把过去只在生产环境才暴露的漂移变成单测断言:

- **transport**:mock WebSocket 覆盖重连退避、代际重订阅、seq 缺口恢复(含 Redis Stream 裁剪场景)、ping 超时重建、ticket 失效重握手、retain/release 计数;
- **engine**:applyEvent 事件序(agent 嵌套按 invId 归位、args 双时刻一致、终态去重)、send/abort/detach 语义、确认流 approve/reject/close 三态、时间线重建与 runs 对账;
- **clientTools**:同 callId 补发不重跑且原样重发结局、declare 首轮捎带、空清单不声明;
- **protocol**:normalizeRunEvent v1→legacy 全映射表。

三端手工回归清单:登录、建会话、发消息、流式渲染、工具确认、断线重连续传、历史时间线、上下文刻度条、多端同会话广播、(插件)渠道工具截图与补发。

## 8. 风险与对策

| 风险 | 对策 |
| --- | --- |
| ruoyi-ui 端特有行为(ElMessageBox cancel/close 语义、keep-alive 场景)回归遗漏 | 回调注入后按 §7 清单逐项手工回归;ruoyi-ui 阶段单独验收 |
| npm link + Vite 预构建缓存问题 | 三端 `optimizeDeps.exclude` 预置;联调异常先清 vite 缓存 |
| 三仓版本协同(协议变更需发版) | semver + conventional commits;开发期 npm link 免发版;protocol 层单一收口后改动面收敛到一处 |
| 后端 v1 迁移收尾删 legacy 投递 | SDK protocol 层同时容纳两种形态,收尾时只改 SDK 发 minor |
| extension MV3 环境(WebSocket 生命周期随 SW 挂起) | 现有副本已在 MV3 下工作,SDK 不改变连接语义;侧栏页是常驻文档页,风险低 |

## 9. 开放项

- npm 包名 `@agenthub/chat` 可用性待发布前核查(备选 `agenthub-chat`);
- SDK 仓库的 GitHub 仓库路径与 CI secrets(NPM_TOKEN)配置,随阶段 0 落地。
