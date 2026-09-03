/**
 * 通用聊天组件类型定义(§5/§6)。
 * 与后端 ChatEventJson 的事件结构对齐。
 */

/**
 * 扁平消息(对应 ai_chat_message 一行)
 * @typedef {Object} ChatMessage
 * @property {number} [messageId]       - message_id
 * @property {string} sessionId
 * @property {number} [agentId]
 * @property {string} [conversationId]
 * @property {number} [subAgentId]      - agent 调用时非空
 * @property {string} messageType      - USER/ASSISTANT/THINKING/TOOL/SUMMARY
 * @property {string} [content]        - 正文
 * @property {string} [visibleToLlm]   - '0' 参与 LLM / '1' 只给前端
 * @property {string} [toolName]
 * @property {string} [toolArgs]
 * @property {string} [toolResult]
 * @property {boolean} [hasFullToolResult]
 * @property {string} [toolSource]     - builtin / mcp / agent
 * @property {number} [toolDurationMs]
 * @property {string} [toolSuccess]    - '0' 成功 / '1' 失败
 * @property {number} [tokens]
 * @property {number} [createTime]     - 时间戳(ms)
 * @property {boolean} [streaming]     - 前端标记,流式中
 */

/**
 * 一轮对话
 * @typedef {Object} Turn
 * @property {ChatMessage|null} userMsg  - 用户消息
 * @property {Step[]} steps              - 助手过程(思考/工具/子agent/文本)
 * @property {boolean} completed         - 是否已出最终文本
 */

/**
 * 过程节点子步骤
 * @typedef {Object} Step
 * @property {'reasoning'|'tool'|'agent'|'content'|'summary'} type
 * @property {string} [text]           - content/reasoning 的累积文本
 * @property {string} [name]           - tool/agent 的名称
 * @property {string} [source]         - builtin/mcp(tool 用)
 * @property {string} [agentCode]      - agent 的 code(身份,嵌套事件靠它匹配归属)
 * @property {string} [invId]          - 调用实例 id,同一子 agent 一轮被调多次时用它区分串卡
 *                                       (后端 agent_start/agent_end 携带,前端优先按 invId 归属)
 * @property {string} [args]            - tool 的入参
 * @property {string} [result]          - tool/agent 的返回(agent 为子智能体回答,流式累积)
 * @property {boolean} [ok]             - 是否成功
 * @property {number} [ms]              - 耗时
 * @property {boolean} streaming        - 该 step 是否还在流式
 * @property {boolean} [error]          - 出错标记
 * @property {Step[]} [steps]           - agent 的子步骤(子智能体内部的思考/工具,嵌套展示)
 */

/**
 * JSON-RPC chat.event 中的领域事件(与后端 ChatEventJson 对齐)。
 * @typedef {Object} ChatEvent
 * @property {string} type             - 见 EVENT_TYPES
 * @property {string} [owner]          - 直接包裹该事件的子智能体 code;非空时事件嵌进对应 agent step,
 *                                       缺省则为顶层事件。agent_start 的 owner 表示这个 agent step 本身嵌进谁。
 * @property {string} [invId]          - agent_start/agent_end 携带的调用实例 id;同一子 agent 一轮被调
 *                                       多次时用它精确配对 start/end,避免串到上一张卡。
 * @property {string} [name]           - ui 事件的产物名(如 kb.references)
 * @property {string} [eventId]        - ui 事件幂等键
 * @property {object} [payload]        - ui 事件载荷
 */

/** 事件类型常量(与后端 ChatEventJson 对齐) */
export const EVENT_TYPES = {
  TEXT: 'text',
  REASONING: 'reasoning',
  TOOL_START: 'tool_start',
  TOOL_END: 'tool_end',
  TOOL_CONFIRM_REQUIRED: 'tool_confirm_required',
  TOOL_CALL_REQUEST: 'tool_call_request',
  CONTEXT_CLEANED: 'context_cleaned',
  AGENT_START: 'agent_start',
  AGENT_END: 'agent_end',
  RUN_STATUS: 'run_status',
  UI: 'ui',
  DONE: 'done',
  ERROR: 'error',
  CANCELLED: 'cancelled',
  INTERRUPTED: 'interrupted'
}

/** 后端 UiArtifactNames 白名单,与 ChatEventJson.ui 的 name 对齐 */
export const UI_ARTIFACT_NAMES = {
  KB_REFERENCES: 'kb.references',
  RUN_TOKEN_USAGE: 'run.tokenUsage',
  WORKSPACE_CHANGES: 'workspace.changes'
}

/**
 * 前端支持的产物规格。schemaVersion 为可理解的最高版本;
 * 更高版本忽略,避免乱解析。新增产物只加一行。
 */
export const UI_ARTIFACT_SPECS = {
  [UI_ARTIFACT_NAMES.KB_REFERENCES]: { schemaVersion: 2, minSchemaVersion: 2 },
  [UI_ARTIFACT_NAMES.RUN_TOKEN_USAGE]: { schemaVersion: 1 },
  [UI_ARTIFACT_NAMES.WORKSPACE_CHANGES]: { schemaVersion: 1 }
}

export function isSupportedUiArtifact(event) {
  if (!event || !event.name) return false
  const spec = UI_ARTIFACT_SPECS[event.name]
  if (!spec) return false
  const version = Number(event.schemaVersion)
  if (spec.minSchemaVersion && (!Number.isFinite(version) || version < spec.minSchemaVersion)) {
    return false
  }
  return !Number.isFinite(version) || version <= spec.schemaVersion
}

/** step 类型常量 */
export const STEP_TYPES = {
  REASONING: 'reasoning',
  TOOL: 'tool',
  AGENT: 'agent',
  CONTENT: 'content',
  SUMMARY: 'summary',
  UI: 'ui'
}

/** 运行终态集合(与后端 ChatRunStatus.TERMINAL 对齐) */
const TERMINAL_RUN_STATUS = ['SUCCEEDED', 'FAILED', 'CANCELLED', 'INTERRUPTED']

/** run 是否已到终态。非终态(QUEUED/RUNNING/FINALIZING)才允许界面继续显示「执行中」。 */
export function isTerminalRunStatus(status) {
  return TERMINAL_RUN_STATUS.includes(status)
}

/**
 * 非成功终态的兜底文案(后端没带 errorMessage 时用)。
 * 实时路径与历史重建共用一份,避免同一次失败在刷新前后显示成两种说法。
 */
export function terminalRunLabel(status) {
  if (status === 'CANCELLED') return '已停止生成'
  if (status === 'INTERRUPTED') return '执行节点中断，可重新发起'
  return '对话执行失败，请重试'
}
