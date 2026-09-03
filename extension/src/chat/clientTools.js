/* global __CLIENT_TOOLS_VERSION__ */
/** 协议层副本：desktop/src/chat/clientTools.js。clientType 为 browser_ext。 */
import { chatRpc } from '../api/chatRpc'
import { toast } from '../utils/confirm'

const registry = new Map()

/**
 * 已受理的调用：callId -> { outcome }。outcome 为 null 表示 handler 还在跑。
 *
 * 不能只记「见过就跳过」：服务端在客户端重新订阅这一轮时会补发尚未回传的请求
 * (侧边栏关掉再打开、断线重连、序号缺口恢复都会走到)，而补发的目标恰恰是
 * 结果没送达的那些调用 —— 见过就跳过等于让补发对本页永远空转。
 * 直接重跑也不行：click / fillInput / navigate / closeTabs 都是写操作，
 * 重跑一次就多点一次。所以记住结局，补发时把当时的结果原样重发。
 */
const handled = new Map()
const HANDLED_LIMIT = 200
let lastDeclaredSessionId = null

/**
 * 注册一条客户端工具。只维护页面内 Map，不发 RPC。
 * handler(args) 返回值非字符串会 JSON.stringify。
 */
export function defineClientTool(def, handler) {
  if (!def || typeof def.name !== 'string' || !/^[a-zA-Z_][a-zA-Z0-9_-]{0,63}$/.test(def.name)) {
    throw new Error('非法的客户端工具名')
  }
  if (typeof handler !== 'function') {
    throw new Error('客户端工具 handler 必须是函数')
  }
  registry.set(def.name, {
    name: def.name,
    description: String(def.description || '').trim(),
    parameters: def.parameters && typeof def.parameters === 'object' ? def.parameters : { type: 'object', properties: {} },
    handler
  })
}

export function snapshot() {
  return [...registry.values()]
    .map(({ name, description, parameters }) => ({ name, description, parameters }))
    .sort((a, b) => a.name.localeCompare(b.name))
}

function capabilitiesVersion() {
  const pkg = typeof __CLIENT_TOOLS_VERSION__ !== 'undefined' ? __CLIENT_TOOLS_VERSION__ : '0'
  return pkg + '+' + shortHash(JSON.stringify(snapshot()))
}

function shortHash(s) {
  let h = 2166136261
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i)
    h = Math.imul(h, 16777619)
  }
  return (h >>> 0).toString(16).padStart(8, '0')
}

/**
 * 会话落库后声明一次。版本相同服务端不写库。
 */
// 已成功声明过的会话。首轮 run.create 要不要捎上清单看它。
const declared = new Set()

/**
 * 首轮补声明用的载荷。
 *
 * declare 只能在会话落库后才发得出去（服务端 declareClient 要求会话已存在），
 * 而新会话的第一轮 run 就在落库的同一次调用里装配 —— 不把清单捎在 run.create 上，
 * 新对话的第一轮永远没有客户端工具。
 * 已声明过的会话返回 null，不重复占请求体。
 */
export function declarePayloadFor(sessionId) {
  if (!sessionId || declared.has(sessionId)) return null
  const tools = snapshot()
  // 没有工具就什么都别声明：会话行只存得下一份清单，空清单发过去就是把别的端
  // （比如插件刚声明的 15 个浏览器工具）原地抹掉。desktop 一个工具都不注册，
  // 每开一个会话就会这么洗一次。
  if (!tools.length) return null
  return {
    clientType: 'browser_ext',
    capabilitiesVersion: capabilitiesVersion(),
    clientTools: tools
  }
}

/** run.create 已把清单带过去了，标记为已声明。 */
export function markDeclared(sessionId) {
  if (sessionId) declared.add(sessionId)
}

export async function declare(sessionId) {
  if (!sessionId) return null
  const tools = snapshot()
  // 同 declarePayloadFor：空清单不发，否则等于替别的端把清单清了。
  if (!tools.length) return null
  lastDeclaredSessionId = sessionId
  try {
    const res = await chatRpc.request('chat.session.client.declare', {
      sessionId,
      clientType: 'browser_ext',
      capabilitiesVersion: capabilitiesVersion(),
      tools
    })
    declared.add(sessionId)
    const skipped = res?.skipped || []
    if (skipped.length) {
      toast('部分客户端工具未生效：' + skipped.join('、'))
    }
    return res
  } catch (e) {
    console.warn('声明客户端工具失败', e)
    return null
  }
}

/**
 * 处理 tool_call_request。同一 callId 只执行一次；找不到 handler 立刻 ok:false。
 *
 * 收到补发(同一 callId 再来一次)时不重跑 handler，只把上次的结局重发一遍。
 */
export async function handleToolCallRequest(runId, event) {
  const callId = event?.callId
  const name = event?.name
  if (!callId || !runId) return
  const seen = handled.get(callId)
  if (seen) {
    // handler 还在跑就等它自己回；已经跑完则补一次回传(上一次多半是没送出去)
    if (seen.outcome) await sendResult(runId, callId, seen.outcome)
    return
  }
  const record = { outcome: null }
  remember(callId, record)
  const outcome = await runHandler(name, event)
  record.outcome = outcome
  await sendResult(runId, callId, outcome)
}

/** 跑一次本地 handler，把成功与失败都收敛成可重发的 outcome。 */
async function runHandler(name, event) {
  const entry = name ? registry.get(name) : null
  if (!entry) {
    return { ok: false, error: '本端没有名为 ' + (name || '?') + ' 的客户端工具' }
  }
  let args = {}
  try {
    args = event.args ? JSON.parse(event.args) : {}
  } catch (_) {
    args = {}
  }
  try {
    const raw = await withWatchdog(name, entry.handler(args))
    // 工具可以回 { text, mediaFileId } 附带一张已上传到个人文件的图片(如 screenshotTab)。
    // 只传 id：图片本体走这条通道会撑爆审计流与工具字符预算，服务端凭 id 自己取回。
    const hasMedia = raw && typeof raw === 'object' && !Array.isArray(raw) && 'mediaFileId' in raw
    const payload = hasMedia ? raw.text : raw
    const mediaFileId = hasMedia && raw.mediaFileId != null ? Number(raw.mediaFileId) : null
    const result = payload == null ? '' : (typeof payload === 'string' ? payload : JSON.stringify(payload))
    return { ok: true, result, mediaFileId }
  } catch (e) {
    return { ok: false, error: e?.message || String(e) }
  }
}

/** 有界记录，长会话下不无限增长；淘汰的是最早的调用，补发只可能落在最近几条上。 */
function remember(callId, record) {
  handled.set(callId, record)
  if (handled.size > HANDLED_LIMIT) {
    handled.delete(handled.keys().next().value)
  }
}

// 最后一道兜底：无论 handler 卡在哪，都要在服务端渠道超时(120s)之前给出结果。
// 否则模型白等两分钟才拿到一句「等待客户端执行超时」，既慢又没有可用信息。
const HANDLER_TIMEOUT_MS = 100000

function withWatchdog(name, promise) {
  let timer
  return Promise.race([
    Promise.resolve(promise).finally(() => clearTimeout(timer)),
    new Promise((_, reject) => {
      timer = setTimeout(
        () => reject(new Error(`客户端工具 ${name} 超过 ${HANDLER_TIMEOUT_MS / 1000} 秒未返回，已放弃。`)),
        HANDLER_TIMEOUT_MS
      )
    })
  ])
}

/**
 * 回传结果。失败只记日志不抛：这一轮的挂起态在服务端，本页抛出去也没人接。
 * 真正的兜底是服务端补发 —— 客户端下次订阅这一轮时会把这条请求再发一遍，
 * 那时 {@link handleToolCallRequest} 认得出 callId，直接重发这里没送出去的结果。
 */
async function sendResult(runId, callId, outcome) {
  const mediaFileId = outcome?.mediaFileId
  try {
    await chatRpc.request('chat.tool.result', {
      runId,
      callId,
      ok: !!outcome?.ok,
      result: outcome?.result ?? null,
      error: outcome?.error ?? null,
      ...(Number.isFinite(mediaFileId) ? { mediaFileId } : {})
    })
  } catch (e) {
    console.warn('回传客户端工具结果失败', e)
  }
}

export function resetClientToolsForTest() {
  registry.clear()
  handled.clear()
  lastDeclaredSessionId = null
}

if (typeof window !== 'undefined') {
  window.defineClientTool = defineClientTool
}
