import { STEP_TYPES, UI_ARTIFACT_NAMES, isTerminalRunStatus, terminalRunLabel } from '../types/chat'
import { parseKbHits } from './useStepDisplay'
import { mergeWorkspaceChanges } from './workspaceChanges'

/**
 * 扁平 messages[] -> Turn[] 聚合(§5.4)。
 *
 * 规则:
 * - USER -> 开新 Turn(userMsg = 该消息)
 * - THINKING -> push step(type=reasoning)
 * - TOOL + toolSource in (builtin,mcp) -> push step(type=tool)
 * - TOOL + toolSource=agent -> push step(type=agent),并登记用于嵌套
 * - ASSISTANT + visibleToLlm=0(最终回答) -> push step(type=content),Turn.completed=true
 * - SUMMARY -> push step(type=summary)
 *
 * 嵌套:子智能体内部的工具行/思考行 agent_id=子agentId,而 agent step 登记在 sub_agent_id(=子agentId)下,
 * 二者精确匹配 -> 内部步骤嵌进对应 agent step,不再平铺到顶层(与实时流的 owner 嵌套一致)。
 *
 * 注意行序:子 agent 的内部行是**边跑边写**的,而那条 agent 行由 SubAgentToolCallback 在
 * finally 里写,**排在它们之后**。所以单靠"先登记后归属"会漏 —— 内部行到达时 agent step 还不存在。
 * 这里的做法是给每个 step 记住产出它的 ownerAgentId,agent 行到达时再把先前落到顶层的
 * 同源 step 收回去(reclaimOwnedSteps)。嵌套子 agent 也成立:内层的 agent 行先写完先收,
 * 外层再连它一起收走。
 *
 * 终态:失败/取消/中断的一轮没有 ASSISTANT_FINAL 行,只靠消息聚合永远是 completed=false。
 * 这类轮次的结束事实在 ai_chat_run 上,由 runStates 修正(见 applyRunStates)。
 *
 * @param {ChatMessage[]} messages - 按 message_id/time 升序的扁平消息
 * @param {Record<string, Array<{name:string,count:number}>>} [specialEventsByMessage]
 * @param {Record<string, {status:string, errorMessage?:string}>} [runStates] - 时间线接口带回的 run 终态
 * @returns {Turn[]} 聚合后的轮次
 */
export function buildTurns(messages, specialEventsByMessage, runStates) {
  const turns = []
  let current = null
  // 当前 Turn 内:子agentId -> 对应的 agent step(收纳它内部嵌套的步骤)
  let agentStepByChildId = new Map()
  let agentStepByStepId = new Map()

  /** 该行应放进哪个 steps 数组:agent_id 命中已登记的子 agent -> 嵌进它;否则顶层 */
  function containerOf(m) {
    const stableOwner = m.parentStepId ? agentStepByStepId.get(m.parentStepId) : undefined
    if (stableOwner) {
      if (!stableOwner.steps) stableOwner.steps = []
      return stableOwner.steps
    }
    const owner = m.agentId != null ? agentStepByChildId.get(m.agentId) : undefined
    if (owner) {
      if (!owner.steps) owner.steps = []
      return owner.steps
    }
    return current.steps
  }

  function reclaimOwnedStepId(parentStepId) {
    const owned = []
    for (let i = current.steps.length - 1; i >= 0; i--) {
      if (current.steps[i].parentStepId === parentStepId) owned.unshift(current.steps.splice(i, 1)[0])
    }
    return owned
  }

  /**
   * 把先前因 agent 行尚未到达而落到顶层的同源 step 收回来,保持原有先后顺序。
   * @param {number} childAgentId 子 agent 的 ID(= agent 行的 sub_agent_id)
   */
  function reclaimOwnedSteps(childAgentId) {
    const owned = []
    for (let i = current.steps.length - 1; i >= 0; i--) {
      if (current.steps[i].ownerAgentId === childAgentId) {
        owned.unshift(current.steps.splice(i, 1)[0])
      }
    }
    return owned
  }

  for (const m of messages) {
    const type = m.messageType
    if (type === 'USER' && (!m.messageKind || m.messageKind === 'USER_INPUT')) {
      current = { userMsg: m, runId: m.runId || null, steps: [], completed: false,
        usage: null, workspaceChanges: [], attachments: parseAttachments(m) }
      agentStepByChildId = new Map()
      agentStepByStepId = new Map()
      turns.push(current)
      continue
    }
    // 没有 USER 开头的孤儿消息(THINKING/TOOL 在会话开头):兜底建一个空 Turn
    if (!current) {
      current = { userMsg: null, steps: [], completed: false, usage: null, workspaceChanges: [] }
      agentStepByChildId = new Map()
      turns.push(current)
    }
    if (type === 'THINKING') {
      containerOf(m).push({
        type: STEP_TYPES.REASONING,
        text: m.content || '',
        streaming: false,
        stepId: m.stepId || null,
        parentStepId: m.parentStepId || null,
        ownerAgentId: m.agentId
      })
    } else if (type === 'TOOL') {
      const isAgent = m.toolSource === 'agent'
      const step = {
        type: isAgent ? STEP_TYPES.AGENT : STEP_TYPES.TOOL,
        name: m.toolName || '',
        source: m.toolSource || 'builtin',
        agentCode: isAgent ? m.toolName : undefined,
        args: m.toolArgs || '',
        result: m.toolResult || '',
        attachments: parseAttachments(m),
        ok: m.toolSuccess !== '1',
        ms: m.toolDurationMs || 0,
        streaming: false,
        stepId: m.stepId || m.toolCallId || null,
        parentStepId: m.parentStepId || null,
        ownerAgentId: m.agentId,
        toolResultPath: m.toolResultPath || '',
        toolResultLength: m.toolResultLength || 0,
        toolArgsLength: m.toolArgsLength || 0,
        messageId: m.messageId,
        sessionId: m.sessionId
      }
      if (isAgent) {
        // 这条 agent 行写在它内部行之后,先把已落顶层的同源 step 收回来
        step.steps = m.stepId ? reclaimOwnedStepId(m.stepId)
          : (m.subAgentId != null ? reclaimOwnedSteps(m.subAgentId) : [])
        // 登记:后续再出现的同源行直接嵌进来(agent 行先于内部行时走这条)
        if (m.subAgentId != null) agentStepByChildId.set(m.subAgentId, step)
        if (m.stepId) agentStepByStepId.set(m.stepId, step)
      }
      containerOf(m).push(step)
    } else if (type === 'ASSISTANT' && m.messageKind === 'ASSISTANT_FINAL') {
      current.steps.push({
        type: STEP_TYPES.CONTENT,
        text: m.content || '',
        streaming: false,
        stepId: m.stepId || 'answer'
      })
      current.runId = m.runId || current.runId
      current.completed = true
      // 本轮用量：回填的真实 usage(见 AiChatController.finalizeTurn)。
      // 注意 m.tokens 存的是 completion(回填时的口径)，总量要用 prompt+completion 算。
      const p = m.promptTokens || 0
      const c = m.completionTokens || 0
      if (p || c || m.tokens) {
        current.usage = {
          promptTokens: p,
          completionTokens: c,
          totalTokens: (p + c) || m.tokens || 0,
          modelName: m.modelName || null,
          usageSource: m.usageSource || '1'
        }
      }
    } else if (type === 'SUMMARY') {
      current.steps.push({
        type: STEP_TYPES.SUMMARY,
        text: m.content || '',
        streaming: false
      })
    }
    // ASSISTANT + visibleToLlm=1(中间文本)忽略,不展示
  }
  applyRunStates(turns, runStates)
  applySpecialEventSummaries(turns, specialEventsByMessage)
  // 旧历史没有特殊事件时,仍扒 searchKnowledge 文本;新路径只挂摘要 count,正文按需拉。
  for (const t of turns) {
    const refs = collectKbCitationState(t.steps)
    if (refs.hits.length) {
      t.citations = refs.hits
      t.citationFiles = refs.files
      t.citationTotal = t.citationCount || refs.files.length || refs.total
    } else if (!t.citations) {
      t.citations = []
      t.citationTotal = t.citationCount || 0
    }
  }
  return turns
}

/**
 * 用 ai_chat_run 的终态修正历史轮次。
 *
 * <p>失败 / 取消 / 节点中断的那一轮不会写 ASSISTANT_FINAL 行,聚合出来的 completed 恒为 false,
 * 界面就会一直渲染「正在输入」的三个点——哪怕这轮几天前就失败了。运行是否结束是控制面的事实,
 * 存在 ai_chat_run 上,由时间线接口随消息一并带回。
 *
 * <p>只对账最新一轮(/run/latest)不够:同一会话里更早的失败轮同样缺终态行,而它们不会再有人来纠正。
 *
 * <p>查不到状态时(老数据 run_id 为空 / run 行已被清理)退回结构性判据:一个会话同一时刻只允许一个
 * 活动 run(uk_ai_chat_run_active),所以后面还有别的轮次,就说明前面那轮必然已经结束。
 *
 * @param {Turn[]} turns 按时间升序的轮次
 * @param {Record<string, {status:string, errorMessage?:string}>} [runStates] runId -> 运行终态
 */
export function applyRunStates(turns, runStates) {
  const list = turns || []
  for (let i = 0; i < list.length; i++) {
    const t = list[i]
    // 已经出了最终回答的轮次不需要修正
    if (t.completed) continue
    const runId = t.runId || (t.userMsg && t.userMsg.runId) || null
    const state = runId && runStates ? runStates[runId] : null
    if (state) {
      // 非终态说明它真的还在跑,保留流式态,交给 resume 接管订阅
      if (!isTerminalRunStatus(state.status)) continue
      t.runId = runId
      t.runStatus = state.status
      t.completed = true
      if (state.status !== 'SUCCEEDED' && !t.terminalMessage) {
        t.terminalMessage = state.errorMessage || terminalRunLabel(state.status)
      }
      continue
    }
    if (i < list.length - 1) t.completed = true
  }
  return list
}

/** 把时间线摘要挂到对应 USER 回合,只给徽标 count,不带 payload。 */
export function applySpecialEventSummaries(turns, specialEventsByMessage) {
  if (!specialEventsByMessage) return
  for (const t of turns || []) {
    const id = t.userMsg && t.userMsg.messageId
    if (id == null) continue
    const items = specialEventsByMessage[String(id)] || specialEventsByMessage[id]
    if (!items || !items.length) continue
    t.specialEvents = items
    const kb = items.find(s => s && s.name === UI_ARTIFACT_NAMES.KB_REFERENCES)
    if (kb) {
      const fileCount = Number(kb.fileCount != null ? kb.fileCount : kb.count) || 0
      t.citationCount = fileCount
      t.citationTotal = fileCount
      t.citationChunkCount = Number(kb.chunkCount) || 0
    }
    const workspaceEvents = items.filter(s => s && s.name === UI_ARTIFACT_NAMES.WORKSPACE_CHANGES)
    for (const event of workspaceEvents) {
      t.workspaceChanges = mergeWorkspaceChanges(t.workspaceChanges, event.files)
      t.workspaceChangesTruncated = !!(t.workspaceChangesTruncated || event.truncated)
    }
  }
}

/**
 * 递归收集一轮内知识库引用。steps 不再带 ui 产物,只回退扒 searchKnowledge 文本。
 * @param {Array} steps 步骤树(含嵌套子 agent 的 steps)
 * @returns {{ hits: Array, total: number }}
 */
export function collectKbCitationState(steps) {
  const out = []
  const seen = new Set()
  let declaredTotal = 0
  function addHit(h) {
    if (!h) return
    const key = h.chunkId != null && h.chunkId !== ''
      ? 'id:' + h.chunkId
      : (h.docName || '') + '|' + (h.content || '')
    if (seen.has(key)) return
    seen.add(key)
    out.push(h)
  }
  function walk(list) {
    for (const s of list || []) {
      if (s.type === STEP_TYPES.TOOL && s.name === 'searchKnowledge' && s.result) {
        const parsed = parseKbHits(s.result)
        declaredTotal += parsed.length
        for (const h of parsed) addHit(h)
      }
      if (s.steps && s.steps.length) walk(s.steps)
    }
  }
  walk(steps)
  return {
    hits: out,
    files: legacyFilesFromHits(out),
    total: Math.max(declaredTotal, out.length)
  }
}

/** 仅旧历史扒文本回退用,特殊事件路径禁止再走这套。 */
function legacyFilesFromHits(hits) {
  const map = new Map()
  for (const hit of hits || []) {
    if (!hit) continue
    const docName = hit.docName || '未知文档'
    let file = map.get(docName)
    if (!file) {
      file = {
        docName,
        kbId: hit.kbId || null,
        docId: hit.docId || null,
        kbName: hit.kbName || '',
        chunkCount: 0,
        chunks: []
      }
      map.set(docName, file)
    }
    if (!file.kbId && hit.kbId) file.kbId = hit.kbId
    if (!file.docId && hit.docId) file.docId = hit.docId
    file.chunks.push(hit)
    file.chunkCount = file.chunks.length
  }
  return Array.from(map.values())
}

/** @returns {Array} 引用片段列表 */
export function collectKbCitations(steps) {
  return collectKbCitationState(steps).hits
}



/**
 * 创建一个空的流式 Turn(发送消息时用)。
 * @param {string} userText
 * @returns {Turn}
 */
export function newTurn(userText, attachments) {
  return {
    userMsg: { messageType: 'USER', content: userText },
    steps: [],
    completed: false,
    usage: null,
    workspaceChanges: [],
    attachments: attachments && attachments.length ? attachments : null
  }
}

/**
 * 解析历史消息上的附件元数据。
 * 后端存的是 json 字符串([{name,path,mime,size}])，某些驱动也可能直接给出数组。
 */
function parseAttachments(m) {
  const raw = m.attachments
  if (!raw) return null
  if (Array.isArray(raw)) return raw.length ? raw : null
  try {
    const arr = JSON.parse(raw)
    return Array.isArray(arr) && arr.length ? arr : null
  } catch (e) {
    return null
  }
}
