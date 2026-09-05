# AgentHub Chat SDK 核心实现计划(阶段 0/共 3 阶段)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立独立仓库 `agenthub-chat-sdk`，把三份前端聊天协议副本收敛为一套 TypeScript SDK（protocol / transport / engine / clientTools / rest / vue 六模块），vitest 单测全绿，构建出 ESM + d.ts，配好 tag 触发的 npm 发布流水线。

**Architecture:** 以 desktop/extension 的最新 JS 实现为行为基准做「保语义移植」——transport、engine、clientTools 的既有精密语义（代际重订阅、seq 缺口恢复、补发幂等等）逐字保留，仅把环境耦合（token、票据、WS 地址、重连参数、UI 回调）收口为注入点。规格见 `docs/superpowers/specs/2026-09-05-chat-sdk-design.md`。

**Tech Stack:** TypeScript 5（strict）、vitest、tsc 构建（ESM + d.ts）、GitHub Actions（tag → npm publish）。

**阶段边界:** 本计划只做 SDK 仓库本身（规格 §6 阶段 0）。三端接入是计划 2（desktop+extension）与计划 3（ruoyi-ui），在 SDK API 落定后另行编写。

**两个用户闸门（执行到此必须停下询问）:**
- Task 1 Step 7 的 `gh repo create` 会创建公开 GitHub 仓库——执行前确认。
- Task 11 的 npm 发布需要用户配置 NPM_TOKEN，无法自动完成。

**移植类任务的通用约定（下文「移植」即指此）:** 源文件逐字保留，仅做下列类别的机械替换，不重写、不「顺手优化」：
1. `import` 路径改为 SDK 相对路径/注入依赖；
2. 函数间共享的可变模块级单例（如 clientTools 的 `registry`/`handled`/`declared`）收进类/工厂闭包；
3. 环境读取（`getToken`、`import.meta.env`、`window.location`）改为构造参数；
4. JSDoc `@typedef` 改为 TS `interface`/`type`，字段与注释逐字保留；
5. 除上述外，任何行为差异都是移植 bug。

---

## File Structure（SDK 仓库根:`/Users/zhanglinlin/IdeaProjects/agenthub-chat-sdk`）

```
agenthub-chat-sdk/
├─ package.json / tsconfig.json / vitest.config.ts / .gitignore / LICENSE / README.md
├─ src/
│  ├─ index.ts                # 主入口 barrel（框架无关核心）
│  ├─ vue.ts                  # ./vue 子路径 barrel
│  ├─ http/types.ts           # HttpClientLike 契约（唯一 http 抽象）
│  ├─ client.ts               # createChatClient：装配 rest + rpc，唯一入口工厂
│  ├─ protocol/
│  │  ├─ eventTypes.ts        # EVENT_TYPES/STEP_TYPES/UI_ARTIFACT_*/isSupportedUiArtifact（移植 desktop types.js）
│  │  ├─ runEvent.ts          # ChatEvent/Step/Turn 类型 + v1 信封类型 + normalizeRunEvent
│  │  ├─ rpcMethods.ts        # 15 个 WS RPC 方法名常量
│  │  └─ status.ts            # isTerminalRunStatus/terminalRunLabel/TERMINAL_RUN_STATUS
│  ├─ rest/chatRest.ts        # chat 域 REST（desktop src/api/chat.js 的 10 个函数，http 注入）
│  ├─ transport/
│  │  ├─ chatRpcClient.ts     # ChatRpcClient（移植 desktop src/api/chatRpc.js，525 行）
│  │  └─ wsUrl.ts             # defaultWsUrlBuilder
│  ├─ clientTools/registry.ts # createClientToolRegistry（移植 extension src/chat/clientTools.js）
│  ├─ engine/
│  │  ├─ timeline.ts          # buildTurns/applyRunStates/... （移植 desktop useTurnBuilder.js 308 行）
│  │  ├─ applyEvent.ts        # applyEvent 及全部事件处理（移植 desktop useChatRun.js 事件处理段）
│  │  └─ engine.ts            # createChatEngine 状态机外壳（新代码）
│  └─ vue/
│     ├─ useChatRun.ts        # composable 适配（新代码，薄）
│     └─ useConnectionState.ts
└─ test/                      # vitest（与 src 同构分目录）
```

**移植源对照（源仓库:`/Users/zhanglinlin/IdeaProjects/agent`）:**

| SDK 目标 | 源文件（基准=desktop，除 clientTools=extension） |
| --- | --- |
| protocol/eventTypes.ts, status.ts | `desktop/src/chat/types.js`（142 行） |
| protocol/runEvent.ts | `desktop/src/chat/types.js`(typedef 部分) + `desktop/src/api/chatRpc.js:491-513`(normalizeRunEvent) |
| rest/chatRest.ts | `desktop/src/api/chat.js`（68 行） |
| transport/chatRpcClient.ts | `desktop/src/api/chatRpc.js`（525 行） |
| clientTools/registry.ts | `extension/src/chat/clientTools.js`（229 行，**最新版**，desktop 版是旧的） |
| engine/timeline.ts | `desktop/src/composables/useTurnBuilder.js`（308 行） |
| engine/applyEvent.ts | `desktop/src/composables/useChatRun.js` 的 applyEvent 段（约 :598-880） |
| engine/engine.ts | `desktop/src/composables/useChatRun.js` 其余段（send/abort/detach/recover/confirm 等）改写为状态机 |
| engine 辅助 | `desktop/src/chat/kbHits.js`、`desktop/src/chat-ui/composables/workspaceChanges.js`（mergeWorkspaceChanges） |
| vue/useChatRun.ts | 消费 engine 的新胶水（对齐原 composable 返回面） |

---

### Task 1: 仓库脚手架

**Files:** Create: `package.json` `tsconfig.json` `vitest.config.ts` `.gitignore` `LICENSE` `README.md` `src/index.ts` `src/http/types.ts`

- [ ] **Step 1: 建目录与 git**

```bash
mkdir -p /Users/zhanglinlin/IdeaProjects/agenthub-chat-sdk/src/http /Users/zhanglinlin/IdeaProjects/agenthub-chat-sdk/test
cd /Users/zhanglinlin/IdeaProjects/agenthub-chat-sdk && git init -b main
```

- [ ] **Step 2: 写 `package.json`**

```json
{
  "name": "@agenthub/chat",
  "version": "0.1.0",
  "description": "AgentHub 聊天协议 SDK:WebSocket JSON-RPC 传输、Run 事件状态机、渠道工具协议与 REST 封装",
  "type": "module",
  "license": "MIT",
  "main": "./dist/index.js",
  "types": "./dist/index.d.ts",
  "exports": {
    ".": { "types": "./dist/index.d.ts", "default": "./dist/index.js" },
    "./vue": { "types": "./dist/vue.d.ts", "default": "./dist/vue.js" }
  },
  "files": ["dist"],
  "sideEffects": false,
  "scripts": {
    "build": "tsc -p tsconfig.json",
    "test": "vitest run",
    "prepublishOnly": "npm run test && npm run build"
  },
  "peerDependencies": { "vue": "^3.0.0" },
  "peerDependenciesMeta": { "vue": { "optional": true } },
  "devDependencies": {
    "typescript": "^5.5.0",
    "vitest": "^2.0.0",
    "vue": "^3.4.0"
  }
}
```

- [ ] **Step 3: 写 `tsconfig.json`**

```json
{
  "compilerOptions": {
    "target": "ES2020",
    "module": "ESNext",
    "moduleResolution": "Bundler",
    "lib": ["ES2020", "DOM"],
    "strict": true,
    "declaration": true,
    "outDir": "dist",
    "rootDir": "src",
    "skipLibCheck": true,
    "noUncheckedIndexedAccess": false,
    "sourceMap": false
  },
  "include": ["src"]
}
```

- [ ] **Step 4: 写 `vitest.config.ts`、`.gitignore`、`LICENSE`、`README.md`（占位内容：一句话 + 指回主仓协议文档链接）**

```ts
// vitest.config.ts
import { defineConfig } from 'vitest/config'
export default defineConfig({ test: { environment: 'node', include: ['test/**/*.test.ts'] } })
```

`.gitignore` 内容：`node_modules/`、`dist/`。`LICENSE`：MIT，版权行 `Copyright (c) 2026 AgentHub`。`README.md` 先写：包名、一句话简介、协议语义文档见主仓 `docs/聊天执行引擎.md` 与 `docs/渠道工具与浏览器插件.md`。

- [ ] **Step 5: 写 `src/http/types.ts`（http 契约，全 SDK 唯一）**

```ts
/** 与三端现有 axios 封装兼容的最小契约。 */
export interface HttpResult<T = any> {
  data: T
  [key: string]: any
}

export interface HttpRequestConfig {
  url: string
  method: 'get' | 'post' | 'put' | 'delete'
  params?: Record<string, any>
  data?: any
  headers?: Record<string, string>
  [key: string]: any
}

export interface HttpClientLike {
  request(config: HttpRequestConfig): Promise<HttpResult>
}
```

- [ ] **Step 6: 写空 barrel `src/index.ts`（`export {} from './http/types'`，后续任务逐步充实）→ `npm install` → 提交**

```bash
npm install
git add -A && git commit -m "chore: 仓库脚手架(tsconfig/vitest/http 契约)"
```

- [ ] **Step 7: ⚠️用户闸门——建远端仓库并推送**（需 gh 已登录；建公开仓前先确认）

```bash
gh repo create 980911302/agenthub-chat-sdk --public --source=. --push
# gh 不可用或用户要求私有/暂不建远端时:跳过,后续任务照常本地提交
```

---

### Task 2: protocol/eventTypes.ts + status.ts（移植 types.js）

**Files:** Create: `src/protocol/eventTypes.ts` `src/protocol/status.ts`；Test: `test/protocol/eventTypes.test.ts`

- [ ] **Step 1: 先写失败测试** `test/protocol/eventTypes.test.ts`

```ts
import { describe, expect, it } from 'vitest'
import { EVENT_TYPES, STEP_TYPES, UI_ARTIFACT_NAMES, UI_ARTIFACT_SPECS, isSupportedUiArtifact } from '../../src/protocol/eventTypes'
import { isTerminalRunStatus, terminalRunLabel } from '../../src/protocol/status'

describe('eventTypes', () => {
  it('事件类型常量与后端 ChatEventJson 对齐', () => {
    expect(EVENT_TYPES).toMatchObject({ TEXT: 'text', TOOL_START: 'tool_start', TOOL_CONFIRM_REQUIRED: 'tool_confirm_required', TOOL_CALL_REQUEST: 'tool_call_request', AGENT_START: 'agent_start', DONE: 'done' })
    expect(STEP_TYPES).toMatchObject({ TOOL: 'tool', AGENT: 'agent', CONTENT: 'content' })
    expect(UI_ARTIFACT_NAMES.KB_REFERENCES).toBe('kb.references')
  })

  it.each([
    ['kb.references', 2, true],   // 恰好等于 schemaVersion
    ['kb.references', 1, false],  // 低于 minSchemaVersion
    ['kb.references', 3, false],  // 高于 schemaVersion
    ['run.tokenUsage', 1, true],
    ['unknown.artifact', 1, false],
    [undefined, 1, false]
  ])('isSupportedUiArtifact(%s, v%s) = %s', (name, version, expected) => {
    expect(isSupportedUiArtifact({ name, schemaVersion: version })).toBe(expected)
  })

  it('UI_ARTIFACT_SPECS 与 desktop types.js 一致', () => {
    expect(UI_ARTIFACT_SPECS['kb.references']).toEqual({ schemaVersion: 2, minSchemaVersion: 2 })
  })
})

describe('status', () => {
  it.each([
    ['QUEUED', false], ['RUNNING', false], ['FINALIZING', false],
    ['SUCCEEDED', true], ['FAILED', true], ['CANCELLED', true], ['INTERRUPTED', true]
  ])('isTerminalRunStatus(%s) = %s', (s, expected) => expect(isTerminalRunStatus(s)).toBe(expected))

  it('运行时兜底文案与 desktop 版一致', () => {
    expect(terminalRunLabel('CANCELLED')).toBe('已停止生成')
    expect(terminalRunLabel('INTERRUPTED')).toBe('执行节点中断，可重新发起')
    expect(terminalRunLabel('FAILED')).toBe('对话执行失败，请重试')
  })
})
```

- [ ] **Step 2: 跑测试确认失败**：`npm test` → FAIL（模块不存在）。

- [ ] **Step 3: 实现**：把 `desktop/src/chat/types.js` 移植为两个 TS 文件——`EVENT_TYPES/STEP_TYPES/UI_ARTIFACT_NAMES/UI_ARTIFACT_SPECS/isSupportedUiArtifact` + ChatMessage/ChatEvent 的 JSDoc 逐字转 TS interface 进 `eventTypes.ts`（同文件导出类型）；`TERMINAL_RUN_STATUS/isTerminalRunStatus/terminalRunLabel` 进 `status.ts`。**文案逐字保留**（含全角逗号）。

- [ ] **Step 4: `npm test` 全绿** → 提交：

```bash
git add -A && git commit -m "feat(protocol): 事件/步骤常量与终态判定(移植 desktop types.js)"
```

---

### Task 3: protocol/runEvent.ts（v1 信封 + normalizeRunEvent）

**Files:** Create: `src/protocol/runEvent.ts`；Test: `test/protocol/runEvent.test.ts`

- [ ] **Step 1: 先写失败测试**（覆盖全 typeMap，与 `desktop/src/api/chatRpc.js:491-513` 一致）

```ts
import { describe, expect, it } from 'vitest'
import { normalizeRunEvent } from '../../src/protocol/runEvent'

const cases: Array<[string, string]> = [
  ['ai.run.status.changed', 'run_status'],
  ['ai.run.text.delta', 'text'],
  ['ai.run.reasoning.delta', 'reasoning'],
  ['ai.run.tool.started', 'tool_start'],
  ['ai.run.tool.confirmation.required', 'tool_confirm_required'],
  ['ai.run.tool.call.requested', 'tool_call_request'],
  ['ai.run.tool.completed', 'tool_end'],
  ['ai.run.agent.started', 'agent_start'],
  ['ai.run.agent.completed', 'agent_end'],
  ['ai.run.ui.published', 'ui'],
  ['ai.run.completed', 'done'],
  ['ai.run.failed', 'error'],
  ['ai.run.cancelled', 'cancelled'],
  ['ai.run.interrupted', 'interrupted']
]

describe('normalizeRunEvent', () => {
  it.each(cases)('%s → %s', (v1, legacy) => {
    expect(normalizeRunEvent({ specversion: '1.0', type: v1, data: {} }, {})).toMatchObject({ type: legacy })
  })

  it('context.compacted 按 data.kind 分流', () => {
    expect(normalizeRunEvent({ specversion: '1.0', type: 'ai.run.context.compacted', data: { kind: 'overflow_trimmed' } }, {}))
      .toMatchObject({ type: 'context_overflow_trimmed' })
    expect(normalizeRunEvent({ specversion: '1.0', type: 'ai.run.context.compacted', data: {} }, {}))
      .toMatchObject({ type: 'context_cleaned' })
  })

  it('data 字段展开进结果且 legacy 字段不丢', () => {
    const out = normalizeRunEvent({ specversion: '1.0', type: 'ai.run.text.delta', data: { text: 'hi', seq: 7 } }, { type: 'text', text: '', runId: 'r1' })
    expect(out).toMatchObject({ type: 'text', text: 'hi', seq: 7, runId: 'r1' })
  })

  it('无 specversion/type 时回退 legacy', () => {
    expect(normalizeRunEvent(undefined, { type: 'text', text: 'x' })).toEqual({ type: 'text', text: 'x' })
    expect(normalizeRunEvent({ type: 'ai.run.text.delta' }, { type: 'text' })).toEqual({ type: 'text' })
  })
})
```

- [ ] **Step 2: `npm test` 确认 FAIL** → **Step 3: 实现**：`runEvent.ts` 定义 `RunEventV1Envelope`（`specversion: string; type: string; data?: Record<string, any>`）、`NormalizedRunEvent = Record<string, any> & { type: string }`，并把 `normalizeRunEvent` 按 desktop 原版移植（typeMap 逐项保留、`{ ...data, type: mapped }`、回退语义一致）。

- [ ] **Step 4: `npm test` 全绿** → 提交 `feat(protocol): v1 信封类型与 normalizeRunEvent 全映射`。

---

### Task 4: protocol/rpcMethods.ts + rest/chatRest.ts

**Files:** Create: `src/protocol/rpcMethods.ts` `src/rest/chatRest.ts`；Test: `test/rest/chatRest.test.ts`

- [ ] **Step 1: 先写失败测试**

```ts
import { describe, expect, it, vi } from 'vitest'
import { createChatRest } from '../../src/rest/chatRest'
import { RPC_METHODS } from '../../src/protocol/rpcMethods'
import type { HttpClientLike } from '../../src/http/types'

function fakeHttp() {
  return { request: vi.fn(async (cfg: any) => ({ data: { ok: true }, config: cfg })) } as unknown as HttpClientLike
}

describe('rpcMethods', () => {
  it('15 个方法名与后端一致', () => {
    expect(Object.keys(RPC_METHODS)).toHaveLength(15)
    expect(RPC_METHODS).toMatchObject({ RUN_SUBSCRIBE: 'chat.run.subscribe', SESSION_CLIENT_DECLARE: 'chat.session.client.declare', TOOL_RESULT: 'chat.tool.result', PING: 'chat.ping' })
  })
})

describe('chatRest', () => {
  it.each([
    ['createChatRun', c => c.createChatRun({ sessionId: 's1', inputText: 'hi' }), { url: '/ai/chat/run', method: 'post', data: { sessionId: 's1', inputText: 'hi' } }],
    ['getChatRun', c => c.getChatRun('r1'), { url: '/ai/chat/run/r1', method: 'get' }],
    ['getChatRunState', c => c.getChatRunState('r1'), { url: '/ai/chat/run/r1/state', method: 'get' }],
    ['getActiveChatRun', c => c.getActiveChatRun('s1'), { url: '/ai/chat/run/active', method: 'get', params: { sessionId: 's1' } }],
    ['getLatestChatRun', c => c.getLatestChatRun('s1'), { url: '/ai/chat/run/latest', method: 'get', params: { sessionId: 's1' } }],
    ['cancelChatRun', c => c.cancelChatRun('r1'), { url: '/ai/chat/run/r1/cancel', method: 'post' }],
    ['confirmChatTool', c => c.confirmChatTool('r1', 'c1', true), { url: '/ai/chat/run/r1/tool-confirm', method: 'post', data: { confirmId: 'c1', approved: true } }],
    ['createChatWebSocketTicket', c => c.createChatWebSocketTicket(), { url: '/ai/chat/ws-ticket', method: 'post' }],
    ['getContextUsage', c => c.getContextUsage('s1'), { url: '/ai/chat/session/s1/context', method: 'get', params: {} }],
    ['rollbackLastTurn', c => c.rollbackLastTurn('s1', 3), { url: '/ai/chat/session/s1/last-turn', method: 'delete', params: { agentId: 3 } }]
  ])('%s 的 url/method/参数与 desktop chat.js 一致', (_n, call, expected) => {
    const http = fakeHttp()
    const rest = createChatRest(http)
    call(rest)
    expect(http.request).toHaveBeenCalledWith(expect.objectContaining(expected))
  })
})
```

（注：`it.each` 的元组里回调直接展开调用，写测试时如 TS 对元组类型报错，可把用例数组显式标注 `Array<[string, (c: ReturnType<typeof createChatRest>) => any, object]>`。）

- [ ] **Step 2: FAIL** → **Step 3: 实现**：`rpcMethods.ts` 导出 15 个常量（KEY 取自 `chat.session.subscribe` → `SESSION_SUBSCRIBE` 等大写下划线风格）；`chatRest.ts` 把 `desktop/src/api/chat.js` 的 10 个函数逐个移植为 `(http) => 绑定函数` 的工厂 `createChatRest(http)`，url/method/params/data/`headers: { repeatSubmit: false }` 逐字保留（repeatSubmit 是三端 axios 拦截器的防重标记，保留无害）。

- [ ] **Step 4: `npm test` 全绿** → 提交 `feat(rest): chat 域 REST 封装与 RPC 方法常量`。

---

### Task 5: transport/wsUrl.ts

**Files:** Create: `src/transport/wsUrl.ts`；Test: `test/transport/wsUrl.test.ts`

- [ ] **Step 1: 先写失败测试**（语义取自 `desktop/src/api/chatRpc.js:515-523`，但不再读 `import.meta.env`/`window.location`）

```ts
import { describe, expect, it } from 'vitest'
import { defaultWsUrlBuilder } from '../../src/transport/wsUrl'

describe('defaultWsUrlBuilder', () => {
  it('同源相对路径 http→ws', () => {
    expect(defaultWsUrlBuilder('http://localhost:8080', 'tk'))
      .toBe('ws://localhost:8080/ws/ai/chat?ticket=tk')
  })
  it('https→wss，保留已有上下文路径，去尾斜杠', () => {
    expect(defaultWsUrlBuilder('https://a.com/agent', 't k'))
      .toBe('wss://a.com/agent/ws/ai/chat?ticket=t%20k')
  })
  it('空 base 回退 /（由调用方传入的 origin 参数兜底）', () => {
    expect(defaultWsUrlBuilder('', 'tk', 'http://x.local')).toBe('ws://x.local/ws/ai/chat?ticket=tk')
  })
})
```

- [ ] **Step 2: FAIL** → **Step 3: 实现**

```ts
/** 环境无关的默认 WS 地址构造: baseUrl + /ws/ai/chat?ticket=。base 为空时用 origin。 */
export function defaultWsUrlBuilder(base: string, ticket: string, origin = 'http://localhost'): string {
  const url = new URL(base || '/', origin)
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  url.pathname = url.pathname.replace(/\/$/, '') + '/ws/ai/chat'
  url.search = '?ticket=' + encodeURIComponent(ticket)
  url.hash = ''
  return url.toString()
}
```

- [ ] **Step 4: 全绿** → 提交 `feat(transport): 默认 WS 地址构造`。

---

### Task 6: transport/chatRpcClient.ts（移植 desktop chatRpc.js）

**Files:** Create: `src/transport/chatRpcClient.ts`；Test: `test/transport/chatRpcClient.test.ts` `test/transport/mockWebSocket.ts`

- [ ] **Step 1: 写 MockWebSocket**（`test/transport/mockWebSocket.ts`，全部测试的基础设施）

```ts
/** 手工驱动的 WebSocket 测试替身。 */
export class MockWebSocket {
  static instances: MockWebSocket[] = []
  static CONNECTING = 0; static OPEN = 1; static CLOSING = 2; static CLOSED = 3
  readyState = MockWebSocket.CONNECTING
  sent: any[] = []
  onopen: (() => void) | null = null
  onclose: ((ev: { code?: number; reason?: string }) => void) | null = null
  onerror: (() => void) | null = null
  onmessage: ((ev: { data: string }) => void) | null = null
  constructor(public url: string) { MockWebSocket.instances.push(this) }
  send(data: string) { this.sent.push(JSON.parse(data)) }
  close(code = 1000, reason = '') {
    if (this.readyState === MockWebSocket.CLOSED) return
    this.readyState = MockWebSocket.CLOSED
    this.onclose?.({ code, reason })
  }
  /* 测试驱动 */
  serverOpen() { this.readyState = MockWebSocket.OPEN; this.onopen?.() }
  serverMessage(msg: object) { this.onmessage?.({ data: JSON.stringify(msg) }) }
  serverDrop() { this.readyState = MockWebSocket.CLOSED; this.onclose?.({ code: 1006, reason: '' }) }
  lastRequest(): { id: string; method: string; params: any } { return this.sent[this.sent.length - 1] }
  respond(id: string, result: unknown) { this.serverMessage({ jsonrpc: '2.0', id, result }) }
  respondError(id: string, message: string) { this.serverMessage({ jsonrpc: '2.0', id, error: { code: -1, message } }) }
}

export function installMockWebSocket() {
  const RealWS = (globalThis as any).WebSocket
  MockWebSocket.instances = []
  ;(globalThis as any).WebSocket = MockWebSocket
  return () => { (globalThis as any).WebSocket = RealWS }
}
```

- [ ] **Step 2: 先写失败测试**（六个关键场景，都基于 mock + `vi.useFakeTimers()`）

```ts
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ChatRpcClient } from '../../src/transport/chatRpcClient'
import { MockWebSocket, installMockWebSocket } from './mockWebSocket'

let uninstall: () => void
beforeEach(() => { uninstall = installMockWebSocket(); vi.useFakeTimers() })
afterEach(() => { uninstall(); vi.useRealTimers() })

function makeClient(overrides: Partial<any> = {}) {
  return new ChatRpcClient({
    tokenProvider: () => 'tok',
    ticketProvider: async () => ({ data: { ticket: 'T' + Math.random() } }),
    wsUrlBuilder: ticket => 'ws://test/' + ticket,
    ...overrides
  })
}

/** 完成一次握手并响应指定 method 的订阅请求,返回 { client, socket }。 */
async function handshake(client: ChatRpcClient, method = 'chat.run.subscribe', result: any = {}) {
  const p = client.subscribe('r1', 0, () => {}, () => {}, () => {})
  const socket = MockWebSocket.instances.at(-1)!
  socket.serverOpen()
  await vi.advanceTimersByTimeAsync(0)
  const req = socket.sent.find(m => m.method === method)
  socket.respond(req.id, result)
  await vi.advanceTimersByTimeAsync(0)
  return { client, socket, promise: p }
}

describe('ChatRpcClient', () => {
  it('无 token 时不建连并广播 closed', async () => {
    const states: string[] = []
    const c = makeClient({ tokenProvider: () => null })
    c.onConnectionState(s => states.push(s))
    c.retain()
    expect(MockWebSocket.instances).toHaveLength(0)
    expect(states).toContain('closed')
  })

  it('订阅后按序投递事件并推进 afterSeq,旧 seq 丢弃', async () => {
    const events: any[] = []
    const { socket } = await handshake(makeClient(), 'chat.run.subscribe', { run: { status: 'RUNNING' } })
    socket.serverMessage({ method: 'chat.event', params: { runId: 'r1', seq: 1, event: { type: 'text', text: 'a' } } })
    socket.serverMessage({ method: 'chat.event', params: { runId: 'r1', seq: 0, event: { type: 'text', text: 'old' } } })
    expect(events).toHaveLength(1) // 由引擎侧回调收集,这里直接在 handshake 里换成收集器
  })

  it('缺口缓存 + onGap 检查点推进 + 补投', async () => {
    const events: any[] = []
    const onGap = vi.fn(async () => 3) // 检查点返回新游标 3
    const { socket } = await handshake(makeClient(), 'chat.run.subscribe', {})
    // seq=2 先到(缺口),seq=1 缺失 → 2 进 pending
    socket.serverMessage({ method: 'chat.event', params: { runId: 'r1', seq: 2, event: { type: 'text', text: 'b' } } })
    expect(events).toHaveLength(0)
    await vi.advanceTimersByTimeAsync(1) // gapRecovery 首次 delay=0
    socket.serverMessage({ method: 'chat.event', params: { runId: 'r1', seq: 1, event: { type: 'text', text: 'a' } } })
    socket.serverMessage({ method: 'chat.event', params: { runId: 'r1', seq: 3, event: { type: 'text', text: 'c' } } })
    expect(events.map(e => e.text)).toEqual(['a', 'b', 'c'])
    expect(onGap).toHaveBeenCalled()
  })

  it('重连: 断开后指数退避,重连成功自动补订阅(带最新 afterSeq)', async () => {
    const { client, socket } = await handshake(makeClient())
    socket.serverMessage({ method: 'chat.event', params: { runId: 'r1', seq: 1, event: { type: 'text', text: 'a' } } })
    socket.serverDrop()
    await vi.advanceTimersByTimeAsync(1000 + 300) // 首次退避 1s+抖动
    const socket2 = MockWebSocket.instances.at(-1)!
    socket2.serverOpen()
    await vi.advanceTimersByTimeAsync(0)
    const resub = socket2.sent.find(m => m.method === 'chat.run.subscribe')
    expect(resub.params).toMatchObject({ runId: 'r1', afterSeq: 1 })
  })

  it('retain/release: 最后一个持有者释放且无订阅才关闭', async () => {
    const { client, socket } = await handshake(makeClient())
    const release1 = client.retain()
    const release2 = client.retain()
    release1(); expect(socket.readyState).toBe(MockWebSocket.OPEN)
    release2()
    expect(client.isOpen()).toBe(false)
  })

  it('ping 超时主动重建半开连接', async () => {
    const { socket } = await handshake(makeClient())
    await vi.advanceTimersByTimeAsync(25000) // 到 ping 周期
    expect(socket.sent.some(m => m.method === 'chat.ping')).toBe(true)
    await vi.advanceTimersByTimeAsync(5000 + 1) // ping 5s 超时 → close(4000)
    expect(socket.readyState).toBe(MockWebSocket.CLOSED)
    await vi.advanceTimersByTimeAsync(1000 + 300)
    expect(MockWebSocket.instances.at(-1)).not.toBe(socket) // 已重建
  })
})
```

（执行注意：`handshake` 里的 `events` 收集器要把回调真正接进 `client.subscribe(...)` 的 `onEvent`——上面为排版省略了部分连线，写测试时把 `events.push` 回调补全；每个用例独立新建 client，勿共享。）

- [ ] **Step 3: 移植实现** `src/transport/chatRpcClient.ts`：源 `desktop/src/api/chatRpc.js`（525 行）。**逐字保留**：retain/shouldStayConnected/subscribeSession/unsubscribeSession/ensureSessionSubscribed/subscribe/unsubscribe/connect/close/ensureSubscribed/requestRaw/handleMessage/scheduleReconnect/scheduleGapRecovery/deliverRunEvent/drainPendingGapEvents/recoverGap/startPing/stopPing/rejectPending/emitState/isOpen 全部方法体，以及 `MAX_PENDING_GAP_EVENTS=256` 等常量。**按下表替换**：

| 原文 | 替换为 |
| --- | --- |
| `import { createChatWebSocketTicket } from './chat.js'` | 构造参数 `ticketProvider` |
| `import { getToken } from '../utils/auth'` | 构造参数 `tokenProvider`（返回 `string \| null \| undefined`） |
| `buildWebSocketUrl(ticket)`（读 env/location） | 构造参数 `wsUrlBuilder: (ticket) => string` |
| `class ChatRpcClient { constructor() {...} }` | `constructor(private readonly deps: ChatRpcClientDeps)`，deps 含上述三项 + `reconnect?: { maxDelayMs?: number; giveUpAfter?: number }`（默认 `{ maxDelayMs: 20000, giveUpAfter: 3 }`，`scheduleReconnect` 里 `20000`/`> 3` 改读配置） |
| `deliverRunEvent` 内的 `normalizeRunEvent` 内联实现 | `import { normalizeRunEvent } from '../protocol/runEvent'`（Task 3 已测） |
| `export const chatRpc = new ChatRpcClient()` | 删除（实例由 `client.ts` 创建并持有） |

补 15 行 JSDoc 说明 deps 注入语义。文件头注释改为「移植自 AgentHub 主仓 desktop/src/api/chatRpc.js@ef573ac2,协议语义以主仓 docs/聊天执行引擎.md 为准」。

- [ ] **Step 4: `npm test` 全绿**（六个场景全过；若 gap/重连用例与实现时序打架，先怀疑测试接线,再查移植——**不许改原语义迁就测试**）→ 提交 `feat(transport): ChatRpcClient 移植(注入化)与六场景单测`。

---

### Task 7: clientTools/registry.ts（移植 extension clientTools.js）

**Files:** Create: `src/clientTools/registry.ts`；Test: `test/clientTools/registry.test.ts`

- [ ] **Step 1: 先写失败测试**

```ts
import { describe, expect, it, vi } from 'vitest'
import { createClientToolRegistry } from '../../src/clientTools/registry'
import type { ChatRpcClient } from '../../src/transport/chatRpcClient'

function fakeRpc() {
  return { request: vi.fn(async (method: string, params: any) => ({ method, params, skipped: [] })) } as unknown as ChatRpcClient
}

describe('clientTools registry', () => {
  it('defineClientTool 校验名字与 handler', () => {
    const reg = createClientToolRegistry({ rpc: fakeRpc(), version: '1' })
    expect(() => reg.defineClientTool({ name: '9bad' }, () => '')).toThrow()
    expect(() => reg.defineClientTool({ name: 'ok_name' }, 'x' as any)).toThrow()
    reg.defineClientTool({ name: 'readPage' }, () => 'ok')
    expect(reg.snapshot().map(t => t.name)).toEqual(['readPage'])
  })

  it('空清单不声明(防止洗掉别的端已声明的清单)', async () => {
    const rpc = fakeRpc()
    const reg = createClientToolRegistry({ rpc, version: '1' })
    expect(reg.declarePayloadFor('s1')).toBeNull()
    await expect(reg.declare('s1')).resolves.toBeNull()
    expect(rpc.request).not.toHaveBeenCalled()
  })

  it('declarePayloadFor 首轮捎带 + markDeclared 幂等', () => {
    const reg = createClientToolRegistry({ rpc: fakeRpc(), version: '1' })
    reg.defineClientTool({ name: 'screenshotTab', description: 'd', parameters: { type: 'object' } }, () => 'x')
    const p1 = reg.declarePayloadFor('s1')
    expect(p1).toMatchObject({ clientType: 'browser_ext', clientTools: [{ name: 'screenshotTab' }] })
    expect(p1.capabilitiesVersion).toMatch(/^1\+/)
    reg.markDeclared('s1')
    expect(reg.declarePayloadFor('s1')).toBeNull()
  })

  it('同 callId 补发:不重跑 handler,原样重发结局', async () => {
    const rpc = fakeRpc()
    const reg = createClientToolRegistry({ rpc, version: '1' })
    const handler = vi.fn(async () => 'result-A')
    reg.defineClientTool({ name: 'click' }, handler)
    const event = { callId: 'c1', name: 'click', args: '{"x":1}', sessionId: 's1' }
    await reg.handleToolCallRequest('r1', event as any)
    expect(handler).toHaveBeenCalledTimes(1)
    expect(rpc.request).toHaveBeenCalledWith('chat.tool.result', expect.objectContaining({ callId: 'c1', ok: true, result: 'result-A' }))
    rpc.request.mockClear()
    await reg.handleToolCallRequest('r1', event as any) // 补发
    expect(handler).toHaveBeenCalledTimes(1) // 不重跑
    expect(rpc.request).toHaveBeenCalledWith('chat.tool.result', expect.objectContaining({ callId: 'c1', result: 'result-A' }))
  })

  it('handler 卡死被 watchdog 截断为 ok:false', async () => {
    vi.useFakeTimers()
    const reg = createClientToolRegistry({ rpc: fakeRpc(), version: '1' })
    reg.defineClientTool({ name: 'slow' }, () => new Promise(() => {}))
    const p = reg.handleToolCallRequest('r1', { callId: 'c2', name: 'slow' } as any)
    await vi.advanceTimersByTimeAsync(100001)
    await p
    expect(vi.mocked((reg as any)._rpcForTest?.request ?? (() => {}))). // 简化:直接断言 rpc.request 收到 ok:false
      toHaveBeenCalled // 实现时改为持有 fakeRpc 引用断言 { ok: false, error: /超过 100 秒/ }
    vi.useRealTimers()
  })
})
```

（执行注意：最后一个用例的断言按持有 `fakeRpc` 引用的方式写——`const rpc = fakeRpc()` 后 `createClientToolRegistry({ rpc, ... })`，结束时 `expect(rpc.request).toHaveBeenCalledWith('chat.tool.result', expect.objectContaining({ ok: false, error: expect.stringMatching(/超过 100 秒/) }))`。）

- [ ] **Step 2: FAIL** → **Step 3: 移植**：源 `extension/src/chat/clientTools.js`。变换：`registry/handled/declared/lastDeclaredSessionId` 收进工厂闭包；`chatRpc.request` → 构造参数 `rpc.request`；`toast(...)` → `onNotice?.(...)`（构造参数，默认 noop）；`__CLIENT_TOOLS_VERSION__` → 构造参数 `version`；`clientType` 可配（默认 `'browser_ext'`）；删掉 `window.defineClientTool` 挂载与文件头副本注释；`resetClientToolsForTest` 改为工厂返回的 `resetForTest`。**其余逐字保留**（名字正则、FNV-1a shortHash、HANDLED_LIMIT=200、HANDLER_TIMEOUT_MS=100000、outcome 重发语义、空清单不发声明）。

- [ ] **Step 4: 全绿** → 提交 `feat(clientTools): 渠道工具注册表移植(补发幂等/首轮捎带/watchdog)`。

---

### Task 8: engine/timeline.ts（移植 useTurnBuilder.js + kbHits + workspaceChanges）

**Files:** Create: `src/engine/timeline.ts`；Test: `test/engine/timeline.test.ts`

- [ ] **Step 1: 先写失败测试**（三个核心场景）

```ts
import { describe, expect, it } from 'vitest'
import { buildTurns, applyRunStates, newTurn } from '../../src/engine/timeline'

const USER = (over: object) => ({ messageType: 'USER', content: 'hi', messageId: 1, runId: 'r1', ...over })

describe('buildTurns', () => {
  it('USER→TOOL→ASSISTANT_FINAL 聚合为单轮,usage 落轮', () => {
    const turns = buildTurns([
      USER({}),
      { messageType: 'TOOL', toolName: 'searchKnowledge', toolSource: 'builtin', toolArgs: '{}', toolResult: JSON.stringify([{ chunkId: 'c1', docName: 'D', content: 'x' }]), toolSuccess: '0', messageId: 2 },
      { messageType: 'ASSISTANT', messageKind: 'ASSISTANT_FINAL', content: '答案', runId: 'r1', promptTokens: 10, completionTokens: 5, messageId: 3 }
    ])
    expect(turns).toHaveLength(1)
    expect(turns[0].completed).toBe(true)
    expect(turns[0].steps.map(s => s.type)).toEqual(['tool', 'content'])
    expect(turns[0].usage).toMatchObject({ promptTokens: 10, completionTokens: 5, totalTokens: 15 })
    expect(turns[0].citations.length).toBe(1) // searchKnowledge 命中聚合
  })

  it('agent 子消息按 parentStepId/agentId 归位嵌套', () => {
    const turns = buildTurns([
      USER({}),
      { messageType: 'TOOL', toolName: 'research', toolSource: 'agent', toolArgs: '{}', toolResult: '', toolSuccess: '0', stepId: 'st1', subAgentId: 7, messageId: 2 },
      { messageType: 'THINKING', content: '子思考', agentId: 7, parentStepId: 'st1', messageId: 3 },
      { messageType: 'TOOL', toolName: 't2', toolSource: 'builtin', toolArgs: '{}', toolResult: '', toolSuccess: '0', agentId: 7, parentStepId: 'st1', messageId: 4 }
    ])
    const agent = turns[0].steps[0]
    expect(agent.type).toBe('agent')
    expect(agent.steps!.map(s => s.type)).toEqual(['reasoning', 'tool'])
  })

  it('applyRunStates: 失败轮无 ASSISTANT_FINAL 时按 run 状态收终态', () => {
    const turns = buildTurns([USER({})])
    applyRunStates(turns, { r1: { status: 'FAILED', errorMessage: 'boom' } })
    expect(turns[0]).toMatchObject({ completed: true, runStatus: 'FAILED', terminalMessage: 'boom', skillIds: [] })
  })
})
```

- [ ] **Step 2: FAIL** → **Step 3: 移植**：源 `desktop/src/composables/useTurnBuilder.js`（308 行）逐字移植为 `timeline.ts`（`isTerminalRunStatus/terminalRunLabel` 此文件**保留自己的历史版本文案**——desktop 原文如此，不改）；`desktop/src/chat/kbHits.js` 整文件并入（或独立 `src/engine/kbHits.ts`，推荐独立）；`mergeWorkspaceChanges` 从 `desktop/src/chat-ui/composables/workspaceChanges.js` 移植进 `src/engine/workspaceChanges.ts`。`../chat/types` 的导入改 `../protocol/eventTypes`、`../protocol/status`。

- [ ] **Step 4: 全绿** → 提交 `feat(engine): 时间线重建移植(buildTurns/runState 对账/引用聚合)`。

---

### Task 9: engine（applyEvent + createChatEngine，移植 useChatRun.js）

**Files:** Create: `src/engine/applyEvent.ts` `src/engine/engine.ts`；Test: `test/engine/applyEvent.test.ts` `test/engine/engine.test.ts`

- [ ] **Step 1: 先写失败测试（事件处理纯函数部分）**

```ts
import { describe, expect, it } from 'vitest'
import { applyEvent, newEngineState } from '../../src/engine/applyEvent'
import { EVENT_TYPES } from '../../src/protocol/eventTypes'

function run() {
  const state = newEngineState()
  state.turns.push({ userMsg: { messageType: 'USER', content: 'hi' }, steps: [], completed: false, usage: null })
  const turn = state.turns[0]
  const apply = (event: any, envelope: any = { runId: 'r1', sessionId: 's1' }) =>
    applyEvent(state, { ...envelope, event }, { onNotice: () => {}, onToolConfirm: async () => true, clientTools: null })
  return { state, turn, apply }
}

describe('applyEvent', () => {
  it('text 追加 content 步骤;reasoning 建/续思考条', () => {
    const { turn, apply } = run()
    apply({ type: EVENT_TYPES.REASONING, text: '思' })
    apply({ type: EVENT_TYPES.REASONING, text: '考' })
    apply({ type: EVENT_TYPES.TEXT, text: '答' })
    expect(turn.steps[0]).toMatchObject({ type: 'reasoning', text: '思考' })
    expect(turn.steps[1]).toMatchObject({ type: 'content', text: '答' })
  })

  it('tool_start/tool_end 同 stepId 复用卡片,args 脱敏一致', () => {
    const { turn, apply } = run()
    apply({ type: EVENT_TYPES.TOOL_START, stepId: 's1', name: 'shell', args: '***' })
    apply({ type: EVENT_TYPES.TOOL_END, stepId: 's1', result: 'out', ms: 12, ok: true })
    expect(turn.steps[0]).toMatchObject({ type: 'tool', name: 'shell', args: '***', result: 'out', ms: 12, ok: true, streaming: false })
  })

  it('agent_start/agent_end 同 invId 配对,第二次调用不串卡', () => {
    const { turn, apply } = run()
    apply({ type: EVENT_TYPES.AGENT_START, stepId: 'a1', name: 'research', agentCode: 'r1code', invId: 'i1' })
    apply({ type: EVENT_TYPES.AGENT_END, stepId: 'a1', result: 'R1' })
    apply({ type: EVENT_TYPES.AGENT_START, stepId: 'a2', name: 'research', agentCode: 'r1code', invId: 'i2' })
    apply({ type: EVENT_TYPES.AGENT_END, stepId: 'a2', result: 'R2' })
    expect(turn.steps.map(s => (s as any).result)).toEqual(['R1', 'R2'])
  })

  it('终态 done 去重:同 runId 第二次 done 不重建轮', () => {
    const { state, turn, apply } = run()
    apply({ type: EVENT_TYPES.DONE, status: 'SUCCEEDED' })
    const n = state.turns.length
    apply({ type: EVENT_TYPES.DONE, status: 'SUCCEEDED' })
    expect(state.turns.length).toBe(n)
    expect(turn.completed).toBe(true)
  })
})
```

- [ ] **Step 2: 移植 applyEvent**：源 `desktop/src/composables/useChatRun.js` 的 applyEvent 段（约 :598-880）及其私有助手（`findStepById/findAgentStep/promptToolConfirm/applyUiEvent/parseToolAttachments/cleanUserText/isTerminal/readableError/terminalLabel` 等全部）。变换：`turns.value` → `state.turns`；`confirmDanger/ElMessageBox` → 注入 `onToolConfirm({ name, args })`；`confirmChatTool/runId` → 由 engine 提供 `sendToolConfirm` 回调（构造参数 `deps`）；`handleToolCallRequest` → `deps.clientTools?.handleToolCallRequest(...)`；渠道工具 sessionId 透传、skillIds 盖章、workspaceChanges/citations 接力逻辑**逐字保留**。签名定为：

```ts
export interface EngineCallbacks {
  onToolConfirm: (info: { name: string; argsPreview: string }) => Promise<boolean>
  onNotice: (message: string, type?: 'info' | 'warning' | 'error') => void
  clientTools: ClientToolRegistry | null
  sendToolConfirm: (runId: string, confirmId: string, approved: boolean) => Promise<void>
  cancelRun: (runId: string) => Promise<void>
  rest: ChatRest
  rpc: ChatRpcClient
}
export function applyEvent(state: EngineState, envelope: RunEventEnvelope, cb: EngineCallbacks): void
```

- [ ] **Step 3: 移植 engine 外壳** `engine.ts`：源 useChatRun.js 的 `send/abort/detach/recoverSession/watchSession/confirmTool` 段。核心改写（这是全计划唯一"新写"的大块，其余全是移植）：

```ts
export interface EngineState { turns: Turn[]; loading: boolean; connectionState: string; activeRunId: string | null; [k: string]: any }

export function createChatEngine(client: ChatClient, options: {
  onToolConfirm: EngineCallbacks['onToolConfirm']
  onNotice?: EngineCallbacks['onNotice']
  clientTools?: ClientToolRegistry | null
}) {
  const state: EngineState = newEngineState()
  const listeners = new Set<(s: EngineState) => void>()
  const notify = () => { for (const l of listeners) l(state) }
  const cb: EngineCallbacks = {
    onToolConfirm: options.onToolConfirm,
    onNotice: options.onNotice ?? (() => {}),
    clientTools: options.clientTools ?? null,
    sendToolConfirm: (runId, confirmId, approved) => client.rest.confirmChatTool(runId, confirmId, approved).then(() => undefined),
    cancelRun: runId => client.rest.cancelChatRun(runId).then(() => undefined),
    rest: client.rest,
    rpc: client.rpc
  }
  // send/abort/detach/recoverSession/watchSession:按源 useChatRun.js 对应函数逐字移植,
  // turns.value → state.turns,末尾统一调 notify()
  const engine = { state, subscribe(l: (s: EngineState) => void) { listeners.add(l); return () => listeners.delete(l) },
    .../* 移植来的方法 */ }
  return engine
}
```

`send` 内的 `createChatRun` 调用要把 `clientTools?.declarePayloadFor(sessionId)` 捎进请求体（源 desktop 版已有此逻辑，保留）、返回 `runId` 后走 `subscribeRun`（源逻辑保留）。

- [ ] **Step 4: engine 外壳测试** `test/engine/engine.test.ts`：注入 fake `ChatClient`（`rest` 用 Task 4 的 fakeHttp 包一层、`rpc` 用 MockWebSocket 版），场景：①`send` 建 optimistic turn → REST 返回 runId → WS 事件推进 → `done` 收口；②`abort` 调 `cancelRun` 且轮收 `CANCELLED` 终态文案「已停止生成」；③`detach` 仅退订不改服务端（断言 `chat.run.unsubscribe` 已发、无 cancel 请求）。

- [ ] **Step 5: 全绿** → 提交 `feat(engine): 无头状态机(applyEvent 移植 + createChatEngine 外壳)`。

---

### Task 10: client.ts + vue 适配层 + barrels

**Files:** Create: `src/client.ts` `src/vue/useChatRun.ts` `src/vue/useConnectionState.ts` `src/index.ts` `src/vue.ts`；Test: `test/client.test.ts` `test/vue/useChatRun.test.ts`

- [ ] **Step 1: 实现 `src/client.ts`（新代码，全量如下）**

```ts
import { ChatRpcClient } from './transport/chatRpcClient'
import { defaultWsUrlBuilder } from './transport/wsUrl'
import { createChatRest, type ChatRest } from './rest/chatRest'
import type { HttpClientLike } from './http/types'

export interface ReconnectOptions { maxDelayMs?: number; giveUpAfter?: number }
export interface ChatClientConfig {
  http: HttpClientLike
  tokenProvider: () => string | null | undefined
  baseUrl: string
  wsUrlBuilder?: (ticket: string) => string
  reconnect?: ReconnectOptions
}
export interface ChatClient {
  http: HttpClientLike
  baseUrl: string
  rest: ChatRest
  rpc: ChatRpcClient
}

export function createChatClient(config: ChatClientConfig): ChatClient {
  const wsUrlBuilder = config.wsUrlBuilder ?? ((ticket: string) => defaultWsUrlBuilder(config.baseUrl, ticket))
  const rest = createChatRest(config.http)
  const rpc = new ChatRpcClient({
    tokenProvider: config.tokenProvider,
    ticketProvider: () => rest.createChatWebSocketTicket(),
    wsUrlBuilder,
    reconnect: config.reconnect
  })
  return { http: config.http, baseUrl: config.baseUrl, rest, rpc }
}
```

- [ ] **Step 2: 实现 vue 适配层**（新代码）

```ts
// src/vue/useConnectionState.ts
import { onUnmounted, ref } from 'vue'
import type { ChatClient } from '../client'
export function useConnectionState(client: ChatClient) {
  const state = ref<string>(client.rpc.isOpen() ? 'open' : 'closed')
  const off = client.rpc.onConnectionState(s => { state.value = s })
  onUnmounted(off)
  return state
}
```

```ts
// src/vue/useChatRun.ts —— 对齐 desktop useChatRun 的返回面,engine 已承担全部逻辑
import { onUnmounted, ref } from 'vue'
import type { ChatClient } from '../client'
import type { EngineState, ChatEngine } from '../engine/engine'
import type { ClientToolRegistry } from '../clientTools/registry'

export function useChatRun(client: ChatClient, options: {
  onToolConfirm: (info: { name: string; argsPreview: string }) => Promise<boolean>
  onNotice?: (message: string, type?: 'info' | 'warning' | 'error') => void
  clientTools?: ClientToolRegistry | null
}): ChatEngine & { state: Ref<EngineState> } {
  const engine = createChatEngine(client, options)
  const state = ref(engine.state)
  const off = engine.subscribe(s => { state.value = s })   // 状态整体替换触发响应式
  onUnmounted(off)
  return Object.assign(engine, { state })
}
```

（执行注意：`Ref` 从 `vue` 导入；若深层数组变更不触发渲染，改为 `state.value = { ...s, turns: [...s.turns] }`——迁移计划 2/3 的回归会暴露，此处先保持简单。）

- [ ] **Step 3: barrels**：`src/index.ts` 导出 `createChatClient/ChatClientConfig/ChatClient/ReconnectOptions/createClientToolRegistry/createChatEngine/EngineState/ChatEngine` 及 `protocol/*` 全部常量与类型、`transport` 的 `ChatRpcClient` 类型；`src/vue.ts` 导出 `useChatRun/useConnectionState`。
- [ ] **Step 4: 测试**：`test/client.test.ts` 断言 `createChatClient` 装配出的 `rpc` 用的是传入 tokenProvider/ticketProvider（复用 MockWebSocket 走一次握手）；`test/vue/useChatRun.test.ts` 用 `vue` 的 `effect` 断言 engine 事件后 `state.value.loading` 变化（无需组件挂载）。
- [ ] **Step 5: 全绿** → 提交 `feat: createChatClient 装配与 vue 适配层,补齐双入口 barrel`。

---

### Task 11: 构建、发布流水线与首版

**Files:** Modify: `package.json`；Create: `.github/workflows/release.yml`

- [ ] **Step 1: `npm run build`** → `dist/` 产出 `index.js/index.d.ts/vue.js/vue.d.ts` 及各模块 d.ts；`npx tsc --noEmit` 零错误。
- [ ] **Step 2: `npm pack --dry-run`** 检查 `files` 只含 dist + README + LICENSE，确认无 src/test 泄漏。
- [ ] **Step 3: 写 `.github/workflows/release.yml`**

```yaml
name: release
on:
  push:
    tags: ['v*']
jobs:
  publish:
    runs-on: ubuntu-latest
    permissions: { contents: read }
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: 20, registry-url: 'https://registry.npmjs.org' }
      - run: npm ci
      - run: npm run test && npm run build
      - run: npm publish --access public
        env: { NODE_AUTH_TOKEN: '${{ secrets.NPM_TOKEN }}' }
```

- [ ] **Step 4: 提交 + 打 tag**：

```bash
git add -A && git commit -m "build: tsc 构建与 tag 触发 npm 发布流水线"
git tag v0.1.0 && git push origin main --tags
```

- [ ] **Step 5: ⚠️用户闸门——npm 发布**：发布前 `npm view @agenthub/chat` 核查包名可用性（被占用则改 `agenthub-chat` 并同步改 package.json）；npm 仓库需要用户在 npmjs.com 创建 granular token 并配到 GitHub `NPM_TOKEN` secret，或本地 `npm publish` 由用户执行。无法自动化的部分如实告知用户，不要伪造发布结果。

- [ ] **Step 6: 回主仓更新规格开放项**（勾掉已落地的两项），提交 `docs(spec): chat-sdk 阶段 0 落地,开放项收敛`。

---

## Self-Review 记录（写计划时已核）

1. **规格覆盖**：规格 §4 结构 → Task 1-10 文件树；§5.1 注入点 → Task 6/10；§5.2 transport 语义清单 → Task 6「逐字保留」清单+六场景测试；§5.3 engine → Task 9；§5.4 clientTools → Task 7（含空清单不声明、HANDLED_LIMIT、watchdog）；§5.5 rest → Task 4；§5.6 vue → Task 10；§5.7 ChatTransportError → Task 6 移植时把 `new Error(...)` 归一为带 `code` 的 `ChatTransportError`（超时/断线/握手失败三处）；§6 阶段 0 验收（test 绿 + d.ts）→ Task 11；§7 protocol/clientTools 测试 → Task 3/7/8/9。**无缺口**。
2. **占位符**：移植类步骤以「源路径 + 逐字保留 + 替换表」表达（源文件真实存在于主仓，非占位）；新代码全部给出全文；两处「执行注意」是给执行者的连线提示而非省略。
3. **类型一致性**：`HttpClientLike.request(config)`（Task 1）↔ `createChatRest(http)`（Task 4）↔ `ChatClientConfig.http`（Task 10）；`ChatRpcClientDeps{tokenProvider,ticketProvider,wsUrlBuilder,reconnect}`（Task 6 定义）↔ `client.ts` 装配（Task 10）一致；`EngineCallbacks`（Task 9）↔ `createChatEngine` options（Task 9/10）一致；`createClientToolRegistry` 返回方法名与 Task 7 测试一致。
