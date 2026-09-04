/* global __CLIENT_TOOLS_VERSION__ */
/** 协议层副本：extension/src/chat/clientTools.js。渠道工具协议变更时两边一起改。 */
import { chatRpc } from '../api/chatRpc'
import { toast } from '../utils/confirm'

const registry = new Map()
const inFlight = new Set()
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
export async function declare(sessionId) {
  if (!sessionId) return null
  const tools = snapshot()
  // 空清单不发。会话行(ai_chat_session.client_tools)只存得下一份清单，谁后声明谁说了算：
  // desktop 一个 defineClientTool 都没调用，snapshot 恒为 []，而 ChatView 每次
  // sessionPersisted 都会 declare 一次 —— 用户在 desktop 打开插件建的会话，
  // 插件那 15 个浏览器工具就被就地抹平，之后那个会话的模型再也看不到浏览器能力。
  if (!tools.length) return null
  lastDeclaredSessionId = sessionId
  try {
    const res = await chatRpc.request('chat.session.client.declare', {
      sessionId,
      clientType: 'desktop',
      capabilitiesVersion: capabilitiesVersion(),
      tools
    })
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
 * 处理 tool_call_request。按 callId 去重；找不到 handler 立刻 ok:false。
 */
export async function handleToolCallRequest(runId, event) {
  const callId = event?.callId
  const name = event?.name
  if (!callId || !runId) return
  if (inFlight.has(callId)) return
  inFlight.add(callId)
  try {
    const outcome = await runHandler(name, event)
    await sendResult(runId, callId, outcome)
  } finally {
    if (inFlight.size > 200) {
      const first = inFlight.values().next().value
      inFlight.delete(first)
    }
  }
}

/** 跑一次本地 handler，把成功与失败都收敛成可回传的 outcome。 */
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
    // 第二个参数只供客户端 handler 使用，不会混进模型生成的工具入参。
    const raw = await Promise.resolve(entry.handler(args, {
      sessionId: event.sessionId || null
    }))
    // 截图等工具回工作区路径引用；图片本体不走 WebSocket，服务端按当前工作区读取。
    // mediaFileId 继续兼容旧版本客户端。
    const hasMedia = raw && typeof raw === 'object' && !Array.isArray(raw)
      && ('workspacePath' in raw || 'mediaFileId' in raw)
    const payload = hasMedia ? raw.text : raw
    const mediaFileId = hasMedia && raw.mediaFileId != null ? Number(raw.mediaFileId) : null
    const workspacePath = hasMedia && typeof raw.workspacePath === 'string'
      ? raw.workspacePath : null
    const result = payload == null ? '' : (typeof payload === 'string' ? payload : JSON.stringify(payload))
    return { ok: true, result, mediaFileId, workspacePath }
  } catch (e) {
    return { ok: false, error: e?.message || String(e) }
  }
}

async function sendResult(runId, callId, outcome) {
  const mediaFileId = outcome?.mediaFileId
  const workspacePath = outcome?.workspacePath
  try {
    await chatRpc.request('chat.tool.result', {
      runId,
      callId,
      ok: !!outcome?.ok,
      result: outcome?.result ?? null,
      error: outcome?.error ?? null,
      ...(Number.isFinite(mediaFileId) ? { mediaFileId } : {}),
      ...(workspacePath ? { workspacePath } : {})
    })
  } catch (e) {
    console.warn('回传客户端工具结果失败', e)
  }
}

export function resetClientToolsForTest() {
  registry.clear()
  inFlight.clear()
  lastDeclaredSessionId = null
}

if (typeof window !== 'undefined') {
  window.defineClientTool = defineClientTool
}
