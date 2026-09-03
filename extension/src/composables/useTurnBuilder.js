import { STEP_TYPES, UI_ARTIFACT_NAMES } from '../chat/types'
import { mergeWorkspaceChanges } from '../chat-ui/composables/workspaceChanges'
import { parseKbHits } from '../chat/kbHits'

export function isTerminalRunStatus(status) {
  return ['SUCCEEDED', 'FAILED', 'CANCELLED', 'INTERRUPTED'].includes(status)
}

export function terminalRunLabel(status) {
  const map = {
    FAILED: '执行失败',
    CANCELLED: '已取消',
    INTERRUPTED: '节点中断'
  }
  return map[status] || status
}

/**
 * 扁平 messages[] -> Turn[] 聚合。
 *
 * @param {ChatMessage[]} messages - 按 message_id/time 升序的扁平消息
 * @param {Record<string, Array<{name:string,count:number}>>} [specialEventsByMessage]
 * @param {Record<string, {status:string, errorMessage?:string, skillIds?:number[]}>} [runStates] - 时间线接口带回的 run 终态(含该轮技能快照)
 * @returns {Turn[]} 聚合后的轮次
 */
export function buildTurns(messages, specialEventsByMessage, runStates) {
  const turns = []
  let current = null
  let agentStepByChildId = new Map()
  let agentStepByStepId = new Map()

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

  function reclaimOwnedSteps(childAgentId) {
    const owned = []
    for (let i = current.steps.length - 1; i >= 0; i--) {
      if (current.steps[i].ownerAgentId === childAgentId) {
        owned.unshift(current.steps.splice(i, 1)[0])
      }
    }
    return owned
  }

  for (const m of messages || []) {
    const type = m.messageType
    if (type === 'USER' && (!m.messageKind || m.messageKind === 'USER_INPUT')) {
      current = {
        userMsg: m,
        runId: m.runId || null,
        steps: [],
        completed: false,
        usage: null,
        workspaceChanges: [],
        attachments: parseAttachments(m)
      }
      agentStepByChildId = new Map()
      agentStepByStepId = new Map()
      turns.push(current)
      continue
    }

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
        hasFullToolResult: !!m.hasFullToolResult,
        toolResultLength: m.toolResultLength || 0,
        toolArgsLength: m.toolArgsLength || 0,
        messageId: m.messageId,
        sessionId: m.sessionId
      }
      if (isAgent) {
        step.steps = m.stepId ? reclaimOwnedStepId(m.stepId)
          : (m.subAgentId != null ? reclaimOwnedSteps(m.subAgentId) : [])
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
  }

  applyRunStates(turns, runStates)
  applySpecialEventSummaries(turns, specialEventsByMessage)

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
 */
export function applyRunStates(turns, runStates) {
  const list = turns || []
  for (let i = 0; i < list.length; i++) {
    const t = list[i]
    const runId = t.runId || (t.userMsg && t.userMsg.runId) || null
    const state = runId && runStates ? runStates[runId] : null
    // 该轮 @ 过的技能快照:必须挂在下面的 early-continue 之前。
    // 「重新生成」只作用于已完成的轮次,而 completed 的轮次会被直接 continue 掉,
    // 放到后面赋值就正好在唯一需要它的场景里拿不到。
    if (state) t.skillIds = Array.isArray(state.skillIds) ? state.skillIds : []
    if (t.completed) continue
    if (state) {
      if (!isTerminalRunStatus(state.status)) continue
      t.runId = runId
      t.runStatus = state.status
      t.completed = true
      if (state.status !== 'SUCCEEDED' && !t.terminalMessage) {
        t.terminalMessage = state.errorMessage || terminalRunLabel(state.status)
      }
      continue
    }
    // 查不到状态时(老数据 run_id 为空 / run 行已被清理)退回结构性判据:
    // 后面还有别的轮次，说明前面那轮必然已经结束！
    if (i < list.length - 1) t.completed = true
  }
  return list
}

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
      const files = event.files || (event.payload && event.payload.files) || []
      t.workspaceChanges = mergeWorkspaceChanges(t.workspaceChanges, files)
      t.workspaceChangesTruncated = !!(t.workspaceChangesTruncated || event.truncated || (event.payload && event.payload.truncated))
    }
  }
}

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

export function collectKbCitations(steps) {
  return collectKbCitationState(steps).hits
}

export function newTurn(userText, attachments) {
  return {
    userMsg: { messageType: 'USER', content: userText },
    steps: [],
    completed: false,
    usage: null,
    attachments: attachments && attachments.length ? attachments : null
  }
}

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
