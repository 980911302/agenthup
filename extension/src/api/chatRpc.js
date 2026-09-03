/** 协议层副本：desktop/src/api/chatRpc.js。后端 RPC/事件变更时两边一起改。 */
import { createChatWebSocketTicket } from './chat.js'
import { getToken } from '../utils/auth'

const MAX_PENDING_GAP_EVENTS = 256

/**
 * 单页面共享的 JSON-RPC 2.0 WebSocket 客户端。
 * 每个浏览器标签页都有自己的实例；服务端按 runId 将同一事件扇出给所有标签页。
 */
class ChatRpcClient {
  constructor() {
    this.socket = null
    this.connectPromise = null
    this.pending = new Map()
    this.subscriptions = new Map()
    this.sessionWatches = new Map()
    this.connectionListeners = new Set()
    this.requestId = 0
    this.generation = 0
    this.reconnectAttempts = 0
    this.reconnectTimer = null
    this.pingTimer = null
    this.closedByClient = false
    this.retainCount = 0
  }

  onConnectionState(listener) {
    this.connectionListeners.add(listener)
    return () => this.connectionListeners.delete(listener)
  }

  /**
   * 页面级长连接。页面打开就握手，即使当前没有任何订阅也保持在线并自动重连：
   * 一来在线/离线状态才有真实含义，二来发消息时不必再等一次票据 + 握手。
   *
   * @returns {Function} 释放函数；最后一个持有者释放且无订阅时才真正关闭连接。
   */
  retain() {
    this.retainCount += 1
    this.closedByClient = false
    this.connect().catch(() => this.scheduleReconnect())
    let released = false
    return () => {
      if (released) return
      released = true
      this.retainCount = Math.max(this.retainCount - 1, 0)
      if (this.retainCount === 0 && this.subscriptions.size === 0) this.close()
    }
  }

  /** 断线后是否还需要把连接拉回来：有人持有长连接，或还有未退订的 run/会话。 */
  shouldStayConnected() {
    return this.retainCount > 0 || this.subscriptions.size > 0 || this.sessionWatches.size > 0
  }

  /**
   * 订阅整个会话的运行生命周期。
   *
   * <p>同一会话在别处开了新一轮时，本页只能靠这条通知拿到 runId；拿到后再走
   * {@link #subscribe} 订阅那一轮的完整事件流。返回值里的 activeRun 用于订阅瞬间就挂上正在跑的那轮。</p>
   */
  async subscribeSession(sessionId, onSessionEvent, onActiveRun) {
    const existing = this.sessionWatches.get(sessionId)
    const entry = existing || { sessionId, onSessionEvent, onActiveRun, subscribedGeneration: 0, subscribePromise: null }
    entry.onSessionEvent = onSessionEvent
    entry.onActiveRun = onActiveRun
    this.sessionWatches.set(sessionId, entry)
    try {
      await this.connect()
      if (this.sessionWatches.get(sessionId) !== entry) return null
      return await this.ensureSessionSubscribed(entry)
    } catch (error) {
      this.scheduleReconnect()
      throw error
    }
  }

  async unsubscribeSession(sessionId) {
    const existed = this.sessionWatches.delete(sessionId)
    if (!existed || !this.isOpen()) return
    try {
      await this.requestRaw('chat.session.unsubscribe', { sessionId }, 5000)
    } catch (_) {
      // 连接断开时服务端会随 socket 一并清理该连接下的全部会话监听。
    }
  }

  async ensureSessionSubscribed(entry) {
    if (this.sessionWatches.get(entry.sessionId) !== entry) return
    if (!this.isOpen()) return
    if (entry.subscribedGeneration === this.generation) return
    if (entry.subscribePromise) return entry.subscribePromise
    const targetGeneration = this.generation
    entry.subscribePromise = this.requestRaw('chat.session.subscribe', {
      sessionId: entry.sessionId
    }, 15000).then(result => {
      if (this.generation === targetGeneration) {
        entry.subscribedGeneration = targetGeneration
      }
      // 首次订阅与断线重连走同一条路：服务端回的活动运行必须交出去，
      // 否则掉线期间别处开始的那一轮，本页永远补不上。
      if (this.sessionWatches.get(entry.sessionId) === entry && result?.activeRun) {
        entry.onActiveRun && entry.onActiveRun(result.activeRun)
      }
      return result
    }).finally(() => {
      entry.subscribePromise = null
    })
    return entry.subscribePromise
  }

  async subscribe(runId, afterSeq, onEvent, onRunState, onGap) {
    const existing = this.subscriptions.get(runId)
    const entry = existing || {
      runId,
      afterSeq: Math.max(Number(afterSeq) || 0, 0),
      onEvent,
      onRunState,
      onGap,
      subscribedGeneration: 0,
      subscribePromise: null,
      gapRecoveryPromise: null,
      gapRecoveryTimer: null,
      gapRecoveryAttempts: 0,
      highestGapSeq: 0,
      pendingGapEvents: new Map()
    }
    entry.onEvent = onEvent
    entry.onRunState = onRunState
    entry.onGap = onGap
    entry.afterSeq = Math.max(entry.afterSeq, Number(afterSeq) || 0)
    this.subscriptions.set(runId, entry)
    try {
      await this.connect()
      if (this.subscriptions.get(runId) !== entry) return null
      return await this.ensureSubscribed(entry)
    } catch (error) {
      this.scheduleReconnect()
      throw error
    }
  }

  async unsubscribe(runId) {
    const entry = this.subscriptions.get(runId)
    const existed = this.subscriptions.delete(runId)
    if (entry?.gapRecoveryTimer) clearTimeout(entry.gapRecoveryTimer)
    if (!existed || !this.isOpen()) return
    try {
      await this.requestRaw('chat.run.unsubscribe', { runId }, 5000)
    } catch (_) {
      // 连接断开会在服务端自动清理 socket 下的全部订阅。
    }
  }

  async request(method, params, timeout = 15000) {
    await this.connect()
    return this.requestRaw(method, params, timeout)
  }

  async connect() {
    if (this.isOpen()) return
    if (!getToken()) {
      this.emitState('closed')
      return
    }
    if (this.connectPromise) return this.connectPromise
    this.closedByClient = false
    clearTimeout(this.reconnectTimer)
    this.emitState(this.generation ? 'reconnecting' : 'connecting')

    this.connectPromise = (async () => {
      const ticketResponse = await createChatWebSocketTicket()
      const ticket = ticketResponse.data?.ticket
      if (!ticket) throw new Error('未获取到实时连接票据')

      await new Promise((resolve, reject) => {
        const socket = new WebSocket(buildWebSocketUrl(ticket))
        let opened = false
        let abandoned = false
        this.socket = socket
        const handshakeTimer = setTimeout(() => {
          if (opened) return
          abandoned = true
          reject(new Error('实时连接握手超时'))
          try { socket.close() } catch (_) { /* no-op */ }
        }, 10000)

        socket.onopen = () => {
          if (abandoned || this.socket !== socket) {
            socket.close(1000, 'stale connection')
            return
          }
          clearTimeout(handshakeTimer)
          opened = true
          this.generation += 1
          this.reconnectAttempts = 0
          this.emitState('open')
          this.startPing()
          resolve()
          // 重连后按每个 run 的最新 seq 自动补订阅；ensureSubscribed 内部有代际去重。
          for (const entry of this.subscriptions.values()) {
            this.ensureSubscribed(entry).catch(() => {})
          }
          for (const entry of this.sessionWatches.values()) {
            this.ensureSessionSubscribed(entry).catch(() => {})
          }
        }
        socket.onmessage = event => this.handleMessage(event.data)
        socket.onerror = () => {
          if (!opened) {
            clearTimeout(handshakeTimer)
            abandoned = true
            reject(new Error('实时连接建立失败'))
            try { socket.close() } catch (_) { /* no-op */ }
          }
        }
        socket.onclose = () => {
          clearTimeout(handshakeTimer)
          if (!opened) reject(new Error('实时连接建立失败'))
          // 超时连接可能在新连接建立后才触发 close，不能清掉新连接状态。
          if (this.socket !== socket) return
          this.stopPing()
          this.socket = null
          for (const entry of this.subscriptions.values()) {
            entry.subscribedGeneration = 0
            entry.subscribePromise = null
          }
          for (const entry of this.sessionWatches.values()) {
            entry.subscribedGeneration = 0
            entry.subscribePromise = null
          }
          this.rejectPending(new Error('实时连接已断开，正在重连'))
          const stayConnected = this.shouldStayConnected()
          if (!this.closedByClient && stayConnected) this.scheduleReconnect()
          this.emitState(stayConnected && !this.closedByClient ? 'reconnecting' : 'closed')
        }
      })
    })().finally(() => {
      this.connectPromise = null
    })

    return this.connectPromise
  }

  close() {
    this.closedByClient = true
    clearTimeout(this.reconnectTimer)
    this.reconnectTimer = null
    this.stopPing()
    if (this.socket) this.socket.close(1000, 'page closed')
    // socket 置空后 onclose 会因代际检查提前返回，这里必须自己广播终态。
    this.socket = null
    this.rejectPending(new Error('实时连接已关闭'))
    this.emitState('closed')
  }

  async ensureSubscribed(entry) {
    if (this.subscriptions.get(entry.runId) !== entry) return
    if (!this.isOpen()) return
    if (entry.subscribedGeneration === this.generation) return
    if (entry.subscribePromise) return entry.subscribePromise
    const targetGeneration = this.generation
    entry.subscribePromise = this.requestRaw('chat.run.subscribe', {
      runId: entry.runId,
      afterSeq: entry.afterSeq
    }, 20000).then(result => {
      if (this.generation === targetGeneration) {
        entry.subscribedGeneration = targetGeneration
      }
      // replay 通知先于 RPC result 到达。若终态通知因 Redis 故障/过期无法回放，
      // 服务端返回的数据库快照仍可把页面从“执行中”对账到真实终态。
      if (this.subscriptions.get(entry.runId) === entry && result?.run) {
        entry.onRunState && entry.onRunState(result.run)
      }
      return result
    }).finally(() => { entry.subscribePromise = null })
    return entry.subscribePromise
  }

  requestRaw(method, params, timeout) {
    if (!this.isOpen()) return Promise.reject(new Error('实时连接不可用'))
    const id = String(++this.requestId)
    const payload = JSON.stringify({ jsonrpc: '2.0', id, method, params: params || {} })
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id)
        reject(new Error(`JSON-RPC 请求超时: ${method}`))
      }, timeout)
      this.pending.set(id, { resolve, reject, timer, method })
      try {
        this.socket.send(payload)
      } catch (e) {
        clearTimeout(timer)
        this.pending.delete(id)
        reject(e)
      }
    })
  }

  handleMessage(raw) {
    let message
    try {
      message = JSON.parse(raw)
    } catch (_) {
      return
    }
    if (message.method === 'chat.event') {
      const params = message.params || {}
      const entry = this.subscriptions.get(params.runId)
      const seq = Number(params.seq) || 0
      if (!entry || seq <= entry.afterSeq) return
      // seq 必须连续。发现缺口时不越过高水位，也不能在同一个旧游标上立即重订阅：
      // Redis Stream 被裁剪或回放积压时，那会形成 subscribe 风暴并最终挤爆 WebSocket 队列。
      // 先用持久化 Run State 提升恢复游标，再从新快照之后补订阅。
      if (seq > entry.afterSeq + 1) {
        entry.highestGapSeq = Math.max(entry.highestGapSeq, seq)
        if (entry.pendingGapEvents.size < MAX_PENDING_GAP_EVENTS
            || entry.pendingGapEvents.has(seq)) {
          entry.pendingGapEvents.set(seq, params)
        }
        this.scheduleGapRecovery(entry)
        return
      }
      this.deliverRunEvent(entry, params)
      this.drainPendingGapEvents(entry)
      return
    }
    if (message.method === 'chat.session.event') {
      const params = message.params || {}
      const watch = this.sessionWatches.get(params.sessionId)
      if (!watch) return
      watch.onSessionEvent && watch.onSessionEvent(params)
      return
    }
    if (message.id == null) return
    const pending = this.pending.get(String(message.id))
    if (!pending) return
    clearTimeout(pending.timer)
    this.pending.delete(String(message.id))
    if (message.error) {
      const error = new Error(message.error.message || 'JSON-RPC 请求失败')
      error.code = message.error.code
      error.data = message.error.data
      pending.reject(error)
    } else {
      pending.resolve(message.result)
    }
  }

  scheduleReconnect() {
    if (this.closedByClient || this.reconnectTimer || this.connectPromise || this.isOpen() || !getToken()) return
    if (!this.shouldStayConnected()) return
    const delay = Math.min(1000 * (2 ** Math.min(this.reconnectAttempts, 5)), 20000)
    this.reconnectAttempts += 1
    if (this.reconnectAttempts > 3) {
      this.emitState('closed')
    } else {
      this.emitState('reconnecting')
    }
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null
      this.connect().catch(() => this.scheduleReconnect())
    }, delay + Math.floor(Math.random() * 300))
  }

  /**
   * 序号缺口属于数据恢复，不等同于物理断线。恢复期间保持当前 socket，按退避节奏获取
   * Run State 检查点；只有快照游标确实前进后才替换服务端订阅。
   */
  scheduleGapRecovery(entry) {
    if (this.subscriptions.get(entry.runId) !== entry || entry.gapRecoveryPromise || entry.gapRecoveryTimer) return
    const delay = entry.gapRecoveryAttempts === 0
      ? 0 : Math.min(250 * (2 ** (entry.gapRecoveryAttempts - 1)), 2000)
    entry.gapRecoveryTimer = setTimeout(() => {
      entry.gapRecoveryTimer = null
      this.recoverGap(entry)
    }, delay)
  }

  deliverRunEvent(entry, params) {
    const seq = Number(params?.seq) || 0
    if (seq !== entry.afterSeq + 1) return false
    entry.afterSeq = seq
    entry.gapRecoveryAttempts = 0
    const rawEv = params.event || {}
    const v1Ev = params.eventV1 ? normalizeRunEvent(params.eventV1, rawEv) : {}
    const finalEv = { ...rawEv, ...v1Ev }
    // 确保流式正文/思考 text 字段始终存在
    if (rawEv.text && !finalEv.text) finalEv.text = rawEv.text
    if (rawEv.type && !finalEv.type) finalEv.type = rawEv.type
    entry.onEvent && entry.onEvent(finalEv, params)
    return true
  }

  drainPendingGapEvents(entry) {
    let params = entry.pendingGapEvents.get(entry.afterSeq + 1)
    while (params) {
      entry.pendingGapEvents.delete(entry.afterSeq + 1)
      this.deliverRunEvent(entry, params)
      params = entry.pendingGapEvents.get(entry.afterSeq + 1)
    }
    entry.highestGapSeq = entry.pendingGapEvents.size
      ? Math.max(...entry.pendingGapEvents.keys()) : 0
    if (!entry.highestGapSeq && entry.gapRecoveryTimer) {
      clearTimeout(entry.gapRecoveryTimer)
      entry.gapRecoveryTimer = null
    }
  }

  recoverGap(entry) {
    if (this.subscriptions.get(entry.runId) !== entry || entry.gapRecoveryPromise) return
    const previousSeq = entry.afterSeq
    const receivedSeq = entry.highestGapSeq
    entry.gapRecoveryAttempts += 1
    entry.gapRecoveryPromise = Promise.resolve()
      .then(() => entry.onGap ? entry.onGap({
        runId: entry.runId,
        afterSeq: previousSeq,
        expectedSeq: previousSeq + 1,
        receivedSeq
      }) : null)
      .then(cursor => {
        if (this.subscriptions.get(entry.runId) !== entry) return false
        const recoveredSeq = Number(cursor) || 0
        if (recoveredSeq <= entry.afterSeq) return false
        entry.afterSeq = recoveredSeq
        for (const seq of entry.pendingGapEvents.keys()) {
          if (seq <= recoveredSeq) entry.pendingGapEvents.delete(seq)
        }
        this.drainPendingGapEvents(entry)
        entry.gapRecoveryAttempts = 0
        entry.subscribedGeneration = 0
        return true
      })
      .catch(() => false)
      .then(progressed => {
        if (this.subscriptions.get(entry.runId) !== entry) return
        if (progressed) {
          this.ensureSubscribed(entry).catch(() => this.scheduleReconnect())
        } else {
          this.scheduleGapRecovery(entry)
        }
      })
      .finally(() => {
        entry.gapRecoveryPromise = null
        // scheduleGapRecovery 在 promise 存在期间会主动跳过，finally 后补调一次保证退避继续。
        if (this.subscriptions.get(entry.runId) === entry
            && entry.highestGapSeq > entry.afterSeq + 1) {
          this.scheduleGapRecovery(entry)
        }
      })
  }

  startPing() {
    this.stopPing()
    this.pingTimer = setInterval(() => {
      if (!this.isOpen()) return
      const socket = this.socket
      this.requestRaw('chat.ping', {}, 5000).catch(() => {
        // 半开连接不会总能及时触发 close；ping 超时后主动重建并按 seq 补订阅。
        if (this.socket === socket && this.isOpen()) {
          socket.close(4000, 'ping timeout')
        }
      })
    }, 25000)
  }

  stopPing() {
    clearInterval(this.pingTimer)
    this.pingTimer = null
  }

  rejectPending(error) {
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timer)
      pending.reject(error)
    }
    this.pending.clear()
  }

  emitState(state) {
    for (const listener of this.connectionListeners) listener(state)
  }

  isOpen() {
    return this.socket && this.socket.readyState === WebSocket.OPEN
  }
}

/** 将标准 v1 信封投影为现有视图模型，迁移期保留 legacy 回退。 */
export function normalizeRunEvent(eventV1, legacyEvent) {
  if (!eventV1?.specversion || !eventV1?.type) return legacyEvent || {}
  const typeMap = {
    'ai.run.status.changed': 'run_status',
    'ai.run.text.delta': 'text',
    'ai.run.reasoning.delta': 'reasoning',
    'ai.run.tool.started': 'tool_start',
    'ai.run.tool.confirmation.required': 'tool_confirm_required',
    'ai.run.tool.call.requested': 'tool_call_request',
    'ai.run.tool.completed': 'tool_end',
    'ai.run.agent.started': 'agent_start',
    'ai.run.agent.completed': 'agent_end',
    'ai.run.ui.published': 'ui',
    'ai.run.context.compacted': eventV1.data?.kind === 'overflow_trimmed'
      ? 'context_overflow_trimmed' : 'context_cleaned',
    'ai.run.completed': 'done',
    'ai.run.failed': 'error',
    'ai.run.cancelled': 'cancelled',
    'ai.run.interrupted': 'interrupted'
  }
  return { ...(eventV1.data || {}), type: typeMap[eventV1.type] || eventV1.type }
}

function buildWebSocketUrl(ticket) {
  const base = import.meta.env.VITE_APP_BASE_API || '/dev-api'
  const url = new URL(base || '/', window.location.origin)
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  url.pathname = url.pathname.replace(/\/$/, '') + '/ws/ai/chat'
  url.search = '?ticket=' + encodeURIComponent(ticket)
  url.hash = ''
  return url.toString()
}

export const chatRpc = new ChatRpcClient()
