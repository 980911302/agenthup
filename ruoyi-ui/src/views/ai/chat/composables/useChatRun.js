import { onBeforeUnmount, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { cancelChatRun, confirmChatTool, createChatRun, getActiveChatRun, getChatRun, getChatRunState } from '@/api/ai/chat'
import { chatRpc } from '@/api/ai/chatRpc'
import { newTurn, buildTurns, collectKbCitationState } from './useTurnBuilder'
import { mergeWorkspaceChanges } from './workspaceChanges'
import {
  EVENT_TYPES, STEP_TYPES, UI_ARTIFACT_NAMES, isSupportedUiArtifact,
  // 与历史重建(useTurnBuilder)共用同一份终态判定与文案
  isTerminalRunStatus as isTerminal, terminalRunLabel as terminalLabel
} from '../types/chat'

/**
 * 持久化对话运行 composable。
 * 页面只订阅 run；刷新、切页、WebSocket 重连都不会拥有或终止后端 Agent 生命周期。
 */
export function useChatRun(options = {}) {
  const { onDone, onError, onEvent, onConnectionState, onRemoteTurn } = options
  const turns = ref([])
  const status = ref('idle')
  const activeRun = ref(null)
  const connectionState = ref('closed')
  let currentTurn = null
  let reconcileTimer = null
  let reconciliationPending = false
  let viewGeneration = 0
  let pendingCreate = null
  let watchedSessionId = null
  // 本页已经自己挂载过的 run。终态在 run 通道和会话通道上会各来一次，
  // 没有这层记录就会在自己发完消息后又整表重建一遍，白闪一下。
  const handledRunIds = new Set()

  const removeConnectionListener = chatRpc.onConnectionState(state => {
    connectionState.value = state
    onConnectionState && onConnectionState(state)
    // 连接中断不代表运行失败；保留 streaming，重连后会从 last seq 回放。
    if (activeRun.value && (state === 'reconnecting' || state === 'connecting')) {
      status.value = 'streaming'
    }
  })

  // 监听器注册后立刻建连：刷新页面时即使没有活动 run 也要在线，
  // 否则连接要等到下一次发送才懒建立，指示灯也无从反映真实状态。
  const releaseConnection = chatRpc.retain()

  /** 有界记录，避免长会话下无限增长。 */
  function rememberHandledRun(runId) {
    if (!runId) return
    handledRunIds.add(runId)
    if (handledRunIds.size > 50) handledRunIds.delete(handledRunIds.values().next().value)
  }

  function setTurns(newTurns) {
    detach()
    turns.value = newTurns || []
    status.value = 'idle'
  }

  async function send(message, payload) {
    if (activeRun.value || pendingCreate) return
    const targetGeneration = viewGeneration
    turns.value.push(newTurn(message, payload?.attachments))
    // 必须取数组里的响应式代理：本轮所有流式写入(steps.push / content.text += ...)都发生在这个对象上，
    // 若继续持有 newTurn 返回的原始对象，写入绕过 Vue 的 set 拦截，事件收到了界面也不会重渲染。
    const turn = turns.value[turns.value.length - 1]
    currentTurn = turn
    turn.runStatus = 'QUEUED'
    status.value = 'streaming'

    const request = {
      ...payload,
      clientRequestId: payload?.clientRequestId || generateId()
    }
    const createAttempt = { cancelRequested: false }
    pendingCreate = createAttempt
    try {
      const response = await createChatRun(request)
      // 用户已切到别的会话：运行仍在后端继续，但旧回调不能污染新页面。
      if (targetGeneration !== viewGeneration) return response.data
      return await attachCreatedRun(response.data, turn, createAttempt, targetGeneration)
    } catch (error) {
      if (targetGeneration !== viewGeneration) return null
      // 两个页面并发发送时唯一活动键会拒绝后到请求；恢复真实活动 run，而不是留下假消息。
      try {
        const activeResponse = await getActiveChatRun(payload.sessionId)
        if (targetGeneration !== viewGeneration) return activeResponse.data || null
        if (activeResponse.data) {
          const optimistic = turns.value.indexOf(turn)
          if (optimistic >= 0) turns.value.splice(optimistic, 1)
          await resume(activeResponse.data)
          if (createAttempt.cancelRequested) await abort()
          return activeResponse.data
        }
      } catch (_) {
        // 下面按原始错误收尾
      }
      if (targetGeneration !== viewGeneration) return null
      turn.runStatus = 'FAILED'
      turn.terminalMessage = readableError(error)
      finishTurn(turn)
      status.value = 'error'
      onError && onError(new Error(turn.terminalMessage))
      return null
    } finally {
      if (pendingCreate === createAttempt) pendingCreate = null
    }
  }

  /** 创建请求返回前收到停止意图时，优先取消，再决定是否还需要订阅最终状态。 */
  async function attachCreatedRun(run, turn, createAttempt, targetGeneration) {
    if (!createAttempt.cancelRequested) {
      await attach(run, turn, 0)
      return run
    }

    let snapshot = run
    try {
      const response = await cancelChatRun(run.runId)
      snapshot = response.data || run
    } catch (error) {
      onError && onError(new Error(readableError(error)))
    }
    // 取消请求仍然有效，但用户已经切走时不能把旧运行重新挂回新视图。
    if (targetGeneration !== viewGeneration) return snapshot
    if (!isTerminal(snapshot.status)) {
      await attach(snapshot, turn, 0)
      return snapshot
    }

    activeRun.value = snapshot
    currentTurn = turn
    turn.runId = snapshot.runId
    turn.runStatus = snapshot.status
    await handleRunSnapshot(snapshot.runId, turn, snapshot)
    return snapshot
  }

  /** 切回/刷新会话时，先恢复一致性步骤快照，再从 snapshotSeq 增量回放。 */
  async function resume(run) {
    if (!run || isTerminal(run.status)) return null
    detach()
    const targetGeneration = viewGeneration
    const response = await getChatRunState(run.runId)
    // 会话监听的 activeRun 回调与切换会话的主动查询可能同时命中同一轮。
    // 后发的恢复/切换拥有视图，旧请求返回后必须静默退出，不能重新挂回陈旧 turn。
    if (targetGeneration !== viewGeneration) return null
    const state = response.data
    const snapshotRun = state?.run || run
    const turn = turnFromRunState(state, snapshotRun)
    currentTurn = replaceRunTurn(turn, snapshotRun)
    if (isTerminal(snapshotRun.status)) {
      status.value = 'idle'
      return snapshotRun
    }
    await attach(snapshotRun, currentTurn, Number(state?.snapshotSeq) || 0)
    if (targetGeneration !== viewGeneration) return null
    promptPendingConfirms(currentTurn.steps)
    return snapshotRun
  }

  async function recoverSession(sessionId) {
    const response = await getActiveChatRun(sessionId)
    if (response.data) await resume(response.data)
    return response.data || null
  }

  /**
   * 监听整个会话：同一会话在另一个标签页/浏览器里发起新一轮时，本页据此实时跟上。
   * 切会话时调用，内部负责退订上一个会话。
   */
  async function watchSession(sessionId) {
    if (watchedSessionId === sessionId) return
    const previous = watchedSessionId
    watchedSessionId = sessionId || null
    if (previous) chatRpc.unsubscribeSession(previous).catch(() => {})
    if (!sessionId) return
    try {
      await chatRpc.subscribeSession(sessionId,
        params => handleSessionEvent(sessionId, params),
        run => adoptRemoteRun(sessionId, run))
    } catch (_) {
      // 断线时订阅留在客户端注册表里，重连后会自动补订。
    }
  }

  function handleSessionEvent(sessionId, params) {
    if (watchedSessionId !== sessionId) return
    const runId = params?.runId
    const type = params?.event?.type
    if (!runId || !type) return
    // 本页已经在管这一轮(正在跑、或刚跑完)，会话通道上的同一条通知要忽略：
    // 否则不但会重复插入用户消息，自己发完消息后还会被终态通知触发一次整表重建。
    // pendingCreate 覆盖「已发出创建请求、还没拿到 runId」的窗口。
    if (pendingCreate || activeRun.value?.runId === runId || handledRunIds.has(runId)) return
    if (type === 'run_status' && !isTerminal(params.event.status)) {
      adoptRemoteRun(sessionId, { runId })
      return
    }
    if (['done', 'error', 'cancelled', 'interrupted'].includes(type)) {
      // 别处结束了一轮而本页没跟上(例如刚切进来)：以消息事实表为准重建。
      onRemoteTurn && onRemoteTurn(sessionId)
    }
  }

  /** 挂载一轮由别处发起的运行：补齐 run 快照后按常规 resume 流程重建并订阅。 */
  async function adoptRemoteRun(sessionId, run) {
    if (watchedSessionId !== sessionId || pendingCreate) return
    if (!run?.runId || activeRun.value?.runId === run.runId || handledRunIds.has(run.runId)) return
    try {
      const snapshot = run.status ? run : (await getChatRun(run.runId)).data
      if (watchedSessionId !== sessionId || pendingCreate) return
      if (!snapshot || isTerminal(snapshot.status)) {
        onRemoteTurn && onRemoteTurn(sessionId)
        return
      }
      if (activeRun.value?.runId === snapshot.runId) return
      await resume(snapshot)
    } catch (_) {
      // 拉不到快照就交给会话终态通知兜底，不打断本页已有内容。
    }
  }

  /** 没有活动运行时，用最近终态修正“只有 USER、没有 ASSISTANT”的历史轮，杜绝永久处理中。 */
  async function reconcileRun(run) {
    if (!run || !isTerminal(run.status)) return
    const response = await getChatRunState(run.runId)
    const state = response.data
    // 清空记忆/回滚后 Run 事实仍保留用于统计，但它已不再代表一条可展示消息。
    if (!state?.userMessage && !state?.finalMessage && !(state?.steps || []).length) return
    replaceRunTurn(turnFromRunState(state, state?.run || run), state?.run || run)
    status.value = 'idle'
  }

  function replaceRunTurn(turn, run) {
    let index = turns.value.findIndex(item => item.runId === run.runId || item.userMsg?.runId === run.runId)
    if (index < 0) {
      const lastIndex = turns.value.length - 1
      const last = turns.value[lastIndex]
      if (last && !last.completed && cleanUserText(last.userMsg?.content || '') === (run.inputText || '')) {
        index = lastIndex
      }
    }
    if (index >= 0) turns.value.splice(index, 1, turn)
    else {
      turns.value.push(turn)
      index = turns.value.length - 1
    }
    return turns.value[index]
  }

  async function attach(run, turn, afterSeq) {
    if (!run) throw new Error('创建运行失败')
    rememberHandledRun(run.runId)
    activeRun.value = run
    currentTurn = turn
    turn.runId = run.runId
    turn.runStatus = run.status
    status.value = 'streaming'
    // 先启动控制面兜底；即使 WebSocket 握手被网络设备挂起也能恢复终态。
    startReconcilePolling(run.runId, turn)
    try {
      await chatRpc.subscribe(run.runId, afterSeq, (event, envelope) => {
        handleRunEvent(run.runId, turn, event, envelope)
      }, snapshot => {
        handleRunSnapshot(run.runId, turn, snapshot).catch(() => {})
      }, gap => {
        return recoverRunGap(run.runId, turn, gap)
      })
    } catch (_) {
      // 订阅仍保留在客户端注册表中，后续会自动重连；不能把 Agent 标为失败。
      // connectionState 只由 WebSocket 自身驱动；单次订阅失败不等于物理连接断开。
    }
  }

  /**
   * 实时序号出现缺口时，以持久化步骤快照替换当前过程视图并返回新的续传游标。
   * 快照尚未推进时返回旧游标，RPC 层会退避重试，不会把数据缺口误报成连接断开。
   */
  async function recoverRunGap(runId, turn, gap) {
    if (activeRun.value?.runId !== runId) return null
    const response = await getChatRunState(runId)
    const state = response.data
    const snapshotSeq = Number(state?.snapshotSeq) || 0
    const appliedSeq = Math.max(Number(gap?.afterSeq) || 0, Number(turn.lastEventSeq) || 0)
    if (snapshotSeq <= appliedSeq) return snapshotSeq
    if (activeRun.value?.runId !== runId) return null

    const run = state?.run || activeRun.value
    const restored = turnFromRunState(state, run)
    Object.assign(turn, restored)
    activeRun.value = { ...activeRun.value, ...run }
    if (isTerminal(run.status)) {
      await handleRunSnapshot(runId, turn, state)
    }
    return snapshotSeq
  }

  /**
   * 数据库运行状态是控制面的事实源。WebSocket/Redis 负责低延迟与过程回放，
   * 这里的低频对账兜住终态事件丢失，保证页面不会永久停在执行中。
   */
  function startReconcilePolling(runId, turn) {
    stopReconcilePolling()
    reconcileTimer = setInterval(async () => {
      if (reconciliationPending || activeRun.value?.runId !== runId) return
      reconciliationPending = true
      try {
        const response = await getChatRunState(runId)
        await handleRunSnapshot(runId, turn, response.data)
      } catch (_) {
        // 网络恢复后下一轮继续；连接异常本身不改变运行状态。
      } finally {
        reconciliationPending = false
      }
    }, 5000)
  }

  function stopReconcilePolling() {
    clearInterval(reconcileTimer)
    reconcileTimer = null
  }

  async function handleRunSnapshot(runId, turn, snapshot) {
    if (!snapshot || activeRun.value?.runId !== runId) return
    // REST state 返回 {run,steps,...}；WebSocket 控制面返回裸 run。
    let state = snapshot.run ? snapshot : null
    let run = snapshot.run || snapshot
    activeRun.value = { ...activeRun.value, ...run }
    turn.runStatus = run.status
    if (!isTerminal(run.status)) return

    stopReconcilePolling()
    if (!state) {
      try { state = (await getChatRunState(runId)).data } catch (_) { /* done 事件正文仍可兜底 */ }
    }
    if (state) {
      const restored = turnFromRunState(state, state.run || run)
      Object.assign(turn, restored)
      run = state.run || run
    }
    if (run.status === 'SUCCEEDED') {
      if (activeRun.value?.runId !== runId) return
      completeActive(turn, 'done', {
        type: EVENT_TYPES.DONE,
        status: run.status,
        recovered: true
      })
      return
    }

    turn.terminalMessage = run.errorMessage || terminalLabel(run.status)
    const type = run.status === 'CANCELLED'
      ? EVENT_TYPES.CANCELLED
      : run.status === 'INTERRUPTED' ? EVENT_TYPES.INTERRUPTED : EVENT_TYPES.ERROR
    completeActive(turn, type, {
      type,
      status: run.status,
      message: turn.terminalMessage,
      recovered: true
    })
    if (run.status === 'FAILED' || run.status === 'INTERRUPTED') {
      onError && onError(new Error(turn.terminalMessage))
    }
  }

  function handleRunEvent(runId, turn, event, envelope) {
    if (!activeRun.value || activeRun.value.runId !== runId) return
    turn.lastEventSeq = envelope?.seq || turn.lastEventSeq || 0
    onEvent && onEvent(event, envelope)

    if (event.type === 'run_status') {
      turn.runStatus = event.status
      activeRun.value.status = event.status
      return
    }
    if (event.type === EVENT_TYPES.DONE) {
      turn.runStatus = event.status || 'SUCCEEDED'
      if (typeof event.text === 'string') setFinalText(turn, event.text)
      if (event.usage) turn.usage = { ...event.usage, usageSource: '0' }
      completeActive(turn, 'done', event)
      return
    }
    if (event.type === EVENT_TYPES.ERROR) {
      turn.runStatus = event.status || 'FAILED'
      turn.terminalMessage = event.message || '对话执行失败'
      completeActive(turn, 'error', event)
      onError && onError(new Error(turn.terminalMessage))
      return
    }
    if (event.type === EVENT_TYPES.CANCELLED) {
      turn.runStatus = 'CANCELLED'
      turn.terminalMessage = event.message || '已停止生成'
      completeActive(turn, 'cancelled', event)
      return
    }
    if (event.type === EVENT_TYPES.INTERRUPTED) {
      turn.runStatus = 'INTERRUPTED'
      turn.terminalMessage = event.message || '执行节点中断，可重新发起'
      completeActive(turn, 'interrupted', event)
      onError && onError(new Error(turn.terminalMessage))
      return
    }
    applyEvent(turn, event)
  }

  function completeActive(turn, nextStatus, event) {
    stopReconcilePolling()
    const runId = activeRun.value?.runId
    finishTurn(turn)
    status.value = nextStatus
    activeRun.value = null
    currentTurn = null
    if (runId) chatRpc.unsubscribe(runId)
    onDone && onDone(event)
  }

  /** 停止按钮是显式业务取消，不是关闭 WebSocket。 */
  async function abort() {
    const run = activeRun.value
    if (!run) {
      if (pendingCreate) {
        pendingCreate.cancelRequested = true
        if (currentTurn) currentTurn.runStatus = 'CANCELLING'
      }
      return
    }
    try {
      const response = await cancelChatRun(run.runId)
      const cancelled = response.data
      if (cancelled && isTerminal(cancelled.status) && activeRun.value) {
        currentTurn.runStatus = cancelled.status
        currentTurn.terminalMessage = cancelled.errorMessage || '已停止生成'
        completeActive(currentTurn, 'cancelled', {
          type: EVENT_TYPES.CANCELLED,
          status: cancelled.status,
          message: currentTurn.terminalMessage
        })
      }
    } catch (error) {
      onError && onError(new Error(readableError(error)))
    }
  }

  /** 离开当前会话只退订，不取消后端运行。 */
  function detach() {
    viewGeneration += 1
    stopReconcilePolling()
    const runId = activeRun.value?.runId
    if (runId) chatRpc.unsubscribe(runId)
    pendingCreate = null
    activeRun.value = null
    currentTurn = null
    status.value = 'idle'
  }

  function finishTurn(turn) {
    turn.completed = true
    clearStreaming(turn.steps)
  }

  function setFinalText(turn, text) {
    let content = [...(turn.steps || [])].reverse()
      .find(item => item.type === STEP_TYPES.CONTENT && item.stepId === 'answer')
    if (!content) {
      content = { type: STEP_TYPES.CONTENT, stepId: 'answer', text: '', streaming: false }
      turn.steps.push(content)
    }
    content.text = text
    content.streaming = false
  }

  function clearStreaming(steps) {
    for (const step of steps || []) {
      step.streaming = false
      if (step.steps?.length) clearStreaming(step.steps)
    }
  }

  // 归属键优先调用实例 invId(同一子 agent 一轮被调多次互不串卡),兼容旧事件的 agentCode
  function findAgentStep(steps, key) {
    for (const step of steps || []) {
      if (step.type === STEP_TYPES.AGENT && (step.invId === key || step.agentCode === key)) return step
      const found = step.steps ? findAgentStep(step.steps, key) : null
      if (found) return found
    }
    return null
  }

  function findStepById(steps, stepId) {
    if (!stepId) return null
    for (const step of steps || []) {
      if (step.stepId === stepId) return step
      const found = step.steps ? findStepById(step.steps, stepId) : null
      if (found) return found
    }
    return null
  }

  /**
   * UI 产物不进时间线。先按 name 挂到产出工具(扩展点),再按 name 分发展示。
   * eventId 去重,回放不会叠两份。未知 name / 过高 schema 直接丢弃。
   */
  function applyUiEvent(turn, event) {
    if (!isSupportedUiArtifact(event)) return
    const eventId = event.eventId || event.stepId
    if (!turn.uiEventIds) turn.uiEventIds = new Set()
    const seen = eventId && turn.uiEventIds.has(eventId)
    if (eventId) turn.uiEventIds.add(eventId)

    const payload = event.payload || {}
    const producerId = event.parentStepId
    const tool = producerId ? findStepById(turn.steps, producerId) : null
    if (tool) {
      if (!tool.uiArtifacts) tool.uiArtifacts = {}
      tool.uiArtifacts[event.name] = payload
    }
    if (event.name === UI_ARTIFACT_NAMES.KB_REFERENCES) {
      applyKbReferences(turn, payload)
    } else if (event.name === UI_ARTIFACT_NAMES.RUN_TOKEN_USAGE) {
      applyTokenUsage(turn, payload)
    } else if (event.name === UI_ARTIFACT_NAMES.WORKSPACE_CHANGES) {
      if (!seen) applyWorkspaceChanges(turn, payload)
    } else if (seen) {
      return
    }
  }

  function applyWorkspaceChanges(turn, payload) {
    const files = Array.isArray(payload?.files) ? payload.files : []
    turn.workspaceChanges = mergeWorkspaceChanges(turn.workspaceChanges, files)
    turn.workspaceChangesTruncated = !!(turn.workspaceChangesTruncated || payload?.truncated)
  }

  function applyTokenUsage(turn, payload) {
    if (!payload) return
    turn.usage = {
      ...(turn.usage || {}),
      promptTokens: Number(payload.promptTokens) || 0,
      completionTokens: Number(payload.completionTokens) || 0,
      totalTokens: Number(payload.totalTokens) || 0,
      callCount: Number(payload.callCount) || 0,
      usageSource: '0'
    }
  }

  function applyKbReferences(turn, payload) {
    const files = Array.isArray(payload?.files) ? payload.files : []
    turn.citationFiles = files
    turn.citationCount = Number(payload?.fileCount) || files.length
    turn.citationTotal = turn.citationCount
    turn.citationChunkCount = Number(payload?.chunkCount) || 0
    turn.citations = files.flatMap(f => f && f.chunks ? f.chunks : [])
  }

  /** 危险工具人工确认(bash 等) */
  function promptToolConfirm(event) {
    const runId = activeRun.value?.runId
    const confirmId = event?.confirmId
    if (!runId || !confirmId) return
    const name = event.name || '工具'
    let argsPreview = String(event.args || '')
    if (argsPreview.length > 280) argsPreview = argsPreview.slice(0, 280) + '…'
    ElMessageBox.confirm(
      `智能体请求执行危险操作「${name}」。\n\n${argsPreview || '(无入参预览)'}\n\n是否允许执行？`,
      '需要确认',
      {
        confirmButtonText: '允许',
        cancelButtonText: '拒绝',
        type: 'warning',
        distinguishCancelAndClose: true
      }
    ).then(() => confirmChatTool(runId, confirmId, true).catch(() => {}))
      .catch((action) => {
        // cancel / close 都视为拒绝,避免工具线程一直挂起
        if (action === 'cancel' || action === 'close') {
          confirmChatTool(runId, confirmId, false).catch(() => {})
        }
      })
  }

  function promptPendingConfirms(steps) {
    for (const step of steps || []) {
      if (step.type === STEP_TYPES.TOOL && step.pendingConfirm && step.confirmId) {
        promptToolConfirm(step)
      }
      if (step.steps?.length) promptPendingConfirms(step.steps)
    }
  }

  function targetSteps(turn, owner) {
    if (!owner) return turn.steps
    const agent = findAgentStep(turn.steps, owner)
    if (!agent) return turn.steps
    if (!agent.steps) agent.steps = []
    return agent.steps
  }

  function applyEvent(turn, event) {
    if (!event?.type) return
    const parentStepId = event.parentStepId || event.owner
    const steps = targetSteps(turn, parentStepId)
    const stepId = event.stepId || event.toolCallId || event.invId
    switch (event.type) {
      case EVENT_TYPES.REASONING: {
        let step = findStepById(turn.steps, stepId)
        if (!step) {
          step = { type: STEP_TYPES.REASONING, stepId, parentStepId, text: '', streaming: true }
          steps.push(step)
        }
        step.text += event.text || ''
        break
      }
      case EVENT_TYPES.TOOL_CONFIRM_REQUIRED: {
        let step = findStepById(turn.steps, stepId)
        if (!step) {
          step = { type: STEP_TYPES.TOOL, stepId, parentStepId }
          steps.push(step)
        }
        Object.assign(step, {
          name: event.name || '', source: event.source || 'builtin',
          args: event.args || '', result: '', ok: true, ms: 0, streaming: true,
          pendingConfirm: true, confirmId: event.confirmId || ''
        })
        // 弹窗确认:工具线程在后端阻塞等待,用户点允许/拒绝后唤醒
        promptToolConfirm(event)
        break
      }
      case EVENT_TYPES.CONTEXT_CLEANED: {
        // 不插入过程步骤,避免时间线噪音;用一条轻量提示标在最后一步旁
        const note = {
          type: STEP_TYPES.SUMMARY,
          text: `已精简早期工具记录（约 ${event.tokensBefore || '?'} → ${event.tokensAfter || '?'} token，清 ${event.pairsCleared || 0} 对）`,
          streaming: false
        }
        steps.push(note)
        break
      }
      case EVENT_TYPES.MEDIA_GATED: {
        // 工具返回的 "loaded into your context" 只是工具层的说法,真正的判定在门控层。
        // 判定结果原本哪儿都看不到,想确认一张图进没进模型只能读模型回答反推 —— 标出来。
        const parts = []
        if (event.accepted > 0) parts.push(`${event.accepted} 份媒体已送入模型`)
        for (const r of event.rejected || []) {
          parts.push(`${r.count} 份${r.label || '媒体'}未送达（${r.reason || '原因未知'}）`)
        }
        if (parts.length) {
          steps.push({ type: STEP_TYPES.SUMMARY, text: parts.join('；'), streaming: false })
        }
        break
      }
      case EVENT_TYPES.TOOL_START: {
        let step = findStepById(turn.steps, stepId)
        if (!step) {
          step = { type: STEP_TYPES.TOOL, stepId, parentStepId, result: '', ok: true, ms: 0 }
          steps.push(step)
        }
        Object.assign(step, {
          name: event.name || step.name || '', source: event.source || step.source || 'builtin',
          args: event.args || step.args || '', streaming: true
        })
        break
      }
      case EVENT_TYPES.TOOL_END: {
        let step = findStepById(turn.steps, stepId)
        if (!step) {
          step = { type: STEP_TYPES.TOOL, stepId, parentStepId, name: event.name || '' }
          steps.push(step)
        }
        Object.assign(step, {
          args: event.args || step.args || '', result: event.result || '', ok: event.ok !== false,
          ms: event.ms || 0, pendingConfirm: false, streaming: false,
          source: event.source || step.source || 'builtin',
          attachments: parseToolAttachments(event.attachments)
        })
        break
      }
      case EVENT_TYPES.UI: {
        applyUiEvent(turn, event)
        break
      }
      case EVENT_TYPES.AGENT_START: {
        let step = findStepById(turn.steps, stepId)
        if (!step) {
          step = { type: STEP_TYPES.AGENT, stepId, parentStepId, steps: [] }
          steps.push(step)
        }
        Object.assign(step, {
          name: event.name || '', agentCode: event.agentCode || '',
          invId: event.invId || stepId || '',
          result: '', ok: true, ms: 0, streaming: true, steps: step.steps || []
        })
        break
      }
      case EVENT_TYPES.AGENT_END: {
        let step = findStepById(turn.steps, stepId)
        if (!step) {
          step = { type: STEP_TYPES.AGENT, stepId, parentStepId, name: event.name || '', steps: [] }
          steps.push(step)
        }
        step.result = event.result || ''
        step.ok = event.ok !== false
        step.ms = event.ms || 0
        step.streaming = false
        if (step.steps?.length) clearStreaming(step.steps)
        break
      }
      case EVENT_TYPES.TEXT:
        if (parentStepId) {
          const agent = findAgentStep(turn.steps, stepId || parentStepId)
          if (agent) {
            agent.result = (agent.result || '') + (event.text || '')
            for (const item of agent.steps || []) {
              if (item.type === STEP_TYPES.REASONING) item.streaming = false
            }
          }
          break
        }
        for (const item of steps) {
          if (item.type === STEP_TYPES.REASONING) item.streaming = false
        }
        let content = findStepById(turn.steps, stepId || 'answer')
        if (!content) {
          content = { type: STEP_TYPES.CONTENT, stepId: stepId || 'answer', text: '', streaming: true }
          steps.push(content)
        }
        content.text += event.text || ''
        break
    }
  }

  onBeforeUnmount(() => {
    detach()
    if (watchedSessionId) chatRpc.unsubscribeSession(watchedSessionId).catch(() => {})
    watchedSessionId = null
    releaseConnection()
    removeConnectionListener()
  })

  return {
    turns,
    status,
    activeRun,
    connectionState,
    send,
    resume,
    recoverSession,
    watchSession,
    reconcileRun,
    setTurns,
    detach,
    abort
  }
}

/** 后端 RunStep 快照 -> 前端步骤树。所有归属只按 stepId/parentStepId。 */
function turnFromRunState(state, run) {
  const rows = Array.isArray(state?.steps) ? state.steps : []
  const byId = new Map()
  const ordered = []
  for (const row of rows) {
    const step = snapshotStep(row)
    if (!step) continue
    byId.set(step.stepId, step)
    ordered.push(step)
  }
  const roots = []
  const uiSteps = []
  for (const step of ordered) {
    // parent !== step:节点不能是自己的父。放行的话它会被 push 进自己的 steps 而永远进不了
    // roots，该子智能体连同挂在它下面的工具一起从时间线消失（只在刷新/重进时暴露，
    // 实时路径按 owner 归位不建父指针树）。写入侧已收口，这里挡的是库里已存在的历史脏行。
    if (step.type === STEP_TYPES.UI) {
      uiSteps.push(step)
      continue
    }
    const parent = step.parentStepId ? byId.get(step.parentStepId) : null
    if (parent && parent !== step && parent.type === STEP_TYPES.AGENT) {
      if (!parent.steps) parent.steps = []
      parent.steps.push(step)
    } else {
      roots.push(step)
    }
  }
  for (const ui of uiSteps) {
    const parent = ui.parentStepId ? byId.get(ui.parentStepId) : null
    if (parent && parent.type === STEP_TYPES.TOOL) {
      if (!parent.uiArtifacts) parent.uiArtifacts = {}
      parent.uiArtifacts[ui.name] = ui.payload
    }
  }

  const finalMessage = state?.finalMessage
  if (finalMessage) {
    let answer = roots.find(step => step.type === STEP_TYPES.CONTENT && step.stepId === 'answer')
    if (!answer) {
      answer = { type: STEP_TYPES.CONTENT, stepId: 'answer', text: '', streaming: false }
      roots.push(answer)
    }
    answer.text = finalMessage.content || ''
    answer.streaming = false
  }

  const user = state?.userMessage || {
    messageType: 'USER', messageKind: 'USER_INPUT', runId: run?.runId,
    content: run?.inputText || '', attachments: run?.attachments || null
  }
  const completed = isTerminal(run?.status)
  const turn = {
    userMsg: user,
    runId: run?.runId || user.runId || null,
    runStatus: run?.status || 'RUNNING',
    lastEventSeq: Number(state?.snapshotSeq) || 0,
    steps: roots,
    completed,
    usage: usageFromMessage(finalMessage),
    workspaceChanges: [],
    attachments: parseToolAttachments(user.attachments),
    terminalMessage: completed && run?.status !== 'SUCCEEDED'
      ? (run?.errorMessage || terminalLabel(run?.status)) : null
  }
  // RunStep 快照没有「思考阶段结束」这个信号：后端只在整个 run 终态时才统一收口，
  // 运行中途 resume（刷新/重进正在跑的会话）拿到的 reasoning 行会一直是 STREAMING，
  // 哪怕模型早已经在吐正文或跑第 5 个工具——面板因此被强制展开。
  // 与实时路径（applyEvent 收到 TEXT/AGENT_END 时反向清空同级 REASONING.streaming）
  // 对齐同一条推导规则：同层只要出现别的产出，说明这轮思考已经翻篇。
  closeReasoningWithSiblings(turn.steps)

  // 终态恢复以 ai_chat_message 不可变事实账本为主，RunStep 快照为补充。
  // 这样即使某次实时投影更新丢失，重进会话也不会少工具调用；
  // 进行中仍以 RunStep 为主，保留流式 checkpoint 和待确认状态。
  if (completed && Array.isArray(state?.messages) && state.messages.length) {
    const ledgerTurns = buildTurns(state.messages)
    const ledger = ledgerTurns.find(item => item.runId === run?.runId || item.userMsg?.runId === run?.runId)
      || ledgerTurns[ledgerTurns.length - 1]
    if (ledger) {
      mergeStepTrees(ledger.steps, turn.steps)
      ledger.userMsg = ledger.userMsg || turn.userMsg
      ledger.runId = turn.runId
      ledger.runStatus = turn.runStatus
      ledger.lastEventSeq = turn.lastEventSeq
      ledger.completed = true
      ledger.usage = ledger.usage || turn.usage
      ledger.workspaceChanges = ledger.workspaceChanges?.length
        ? ledger.workspaceChanges : turn.workspaceChanges
      ledger.attachments = ledger.attachments || turn.attachments
      ledger.terminalMessage = turn.terminalMessage
      if (turn.citationFiles && turn.citationFiles.length) {
        ledger.citationFiles = turn.citationFiles
        ledger.citationCount = turn.citationCount
        ledger.citationTotal = turn.citationTotal
        ledger.citations = turn.citations
      } else {
        const ledgerRefs = collectKbCitationState(ledger.steps)
        ledger.citations = ledgerRefs.hits
        ledger.citationFiles = ledgerRefs.files
        ledger.citationTotal = ledgerRefs.files.length || ledgerRefs.total
      }
      return ledger
    }
  }
  if (!turn.citationFiles || !turn.citationFiles.length) {
    const refs = collectKbCitationState(turn.steps)
    turn.citations = refs.hits
    turn.citationFiles = refs.files
    turn.citationTotal = turn.citationCount || refs.files.length || refs.total
  }
  return turn
}

/**
 * 递归清掉「过时的思考中」：同一层（顶层 steps，或某个 agent 自己的 steps）
 * 只要存在非 REASONING 类型的节点，就说明这层的思考阶段已经结束——
 * 不管它当前的 streaming 字段（来自 RunStep 快照的原始 status）写的是什么。
 * 只有「这一层至今只有思考、什么产出都还没有」时才保留后端给的 streaming 值，
 * 那种情况下模型确实可能还卡在思考阶段。
 */
function closeReasoningWithSiblings(list) {
  if (!Array.isArray(list) || !list.length) return
  const hasOtherOutput = list.some(step => step.type !== STEP_TYPES.REASONING)
  for (const step of list) {
    if (step.type === STEP_TYPES.REASONING && hasOtherOutput) step.streaming = false
    if (step.steps?.length) closeReasoningWithSiblings(step.steps)
  }
}

/**
 * 以消息账本的步骤树为主，按 RunStep 的同层顺序补齐缺失节点。
 * 已有节点只补空字段，避免覆盖 messageId/toolResultPath 等按需拉取信息。
 */
function mergeStepTrees(primary, fallback) {
  function mergeLevel(target, source) {
    for (let i = 0; i < (source || []).length; i++) {
      const candidate = source[i]
      let existing = target.find(item => item.stepId && item.stepId === candidate.stepId)
      if (!existing) {
        existing = { ...candidate, steps: [] }
        let insertAt = target.length
        for (let p = i - 1; p >= 0; p--) {
          const previous = target.findIndex(item => item.stepId === source[p].stepId)
          if (previous >= 0) {
            insertAt = previous + 1
            break
          }
        }
        if (insertAt === target.length) {
          for (let n = i + 1; n < source.length; n++) {
            const next = target.findIndex(item => item.stepId === source[n].stepId)
            if (next >= 0) {
              insertAt = next
              break
            }
          }
        }
        target.splice(insertAt, 0, existing)
      } else {
        for (const [key, value] of Object.entries(candidate)) {
          if (key !== 'steps' && (existing[key] === undefined || existing[key] === null || existing[key] === '')) {
            existing[key] = value
          }
        }
      }
      if (candidate.steps?.length) {
        if (!Array.isArray(existing.steps)) existing.steps = []
        mergeLevel(existing.steps, candidate.steps)
      }
    }
  }
  mergeLevel(primary, fallback)
}

function snapshotStep(row) {
  const common = {
    stepId: row.stepId,
    parentStepId: row.parentStepId || null,
    streaming: ['STREAMING', 'RUNNING', 'WAITING'].includes(row.status)
  }
  if (!common.stepId) return null
  if (row.stepType === 'content') {
    return { ...common, type: STEP_TYPES.CONTENT, text: row.outputData || '' }
  }
  if (row.stepType === 'reasoning') {
    return { ...common, type: STEP_TYPES.REASONING, text: row.outputData || '' }
  }
  if (row.stepType === 'tool') {
    return {
      ...common, type: STEP_TYPES.TOOL, name: row.name || '', source: row.source || 'builtin',
      args: row.inputData || '', result: row.outputData || '',
      attachments: parseToolAttachments(row.attachments), ok: row.success !== '1',
      ms: row.durationMs || 0, pendingConfirm: row.status === 'WAITING', confirmId: row.confirmId || '',
      messageId: row.messageId || null, sessionId: row.sessionId || ''
    }
  }
  if (row.stepType === 'agent') {
    return {
      ...common, type: STEP_TYPES.AGENT, name: row.name || '', agentCode: row.source || '',
      invId: row.stepId, result: row.outputData || '', ok: row.success !== '1',
      ms: row.durationMs || 0, steps: []
    }
  }
  if (row.stepType === 'context') {
    return { ...common, type: STEP_TYPES.SUMMARY, text: '已调整本轮上下文', streaming: false }
  }
  if (row.stepType === 'ui') {
    return {
      ...common,
      type: STEP_TYPES.UI,
      name: row.name || '',
      payload: parseJsonObject(row.outputData),
      streaming: false
    }
  }
  return null
}

function parseJsonObject(raw) {
  if (!raw) return {}
  if (typeof raw === 'object' && !Array.isArray(raw)) return raw
  try {
    const parsed = JSON.parse(raw)
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {}
  } catch (_) {
    return {}
  }
}

function usageFromMessage(message) {
  if (!message) return null
  const prompt = Number(message.promptTokens) || 0
  const completion = Number(message.completionTokens) || 0
  if (!prompt && !completion && !message.tokens) return null
  return {
    promptTokens: prompt,
    completionTokens: completion,
    totalTokens: prompt + completion || Number(message.tokens) || 0,
    modelName: message.modelName || null,
    usageSource: message.usageSource || '1'
  }
}

/** tool_end 事件里的附件：与历史消息 parseAttachments 同语义。 */
function parseToolAttachments(raw) {
  if (!raw) return null
  if (Array.isArray(raw)) return raw.length ? raw : null
  try {
    const arr = JSON.parse(raw)
    return Array.isArray(arr) && arr.length ? arr : null
  } catch (_) {
    return null
  }
}

function cleanUserText(text) {
  const marker = '\n\n[本次上传的文件'
  const index = String(text || '').indexOf(marker)
  return index >= 0 ? String(text).slice(0, index) : String(text || '')
}

function readableError(error) {
  if (error instanceof Error && error.message) return error.message
  if (typeof error === 'string' && error !== 'error') return error
  return '对话执行失败，请重试'
}

function generateId() {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID()
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 12)
}
