import request from '@/utils/request'

// 清空指定业务会话的记忆 + 上下文(按 sessionId 清,后端按 sessionId:agentId 清表)
export function clearSession(sessionId) {
  return request({
    url: '/ai/chat/session/' + sessionId + '/memory',
    method: 'delete'
  })
}

// 当前上下文用量(刻度条)
export function getContextUsage(sessionId, agentId) {
  return request({
    url: '/ai/chat/session/' + sessionId + '/context',
    method: 'get',
    params: agentId != null ? { agentId } : {}
  })
}

// 回滚最后一轮(重新生成前调用)
export function rollbackLastTurn(sessionId, agentId) {
  return request({
    url: '/ai/chat/session/' + sessionId + '/last-turn',
    method: 'delete',
    params: agentId != null ? { agentId } : {}
  })
}

// 创建持久化运行。clientRequestId 由前端生成，网络重试不会重复执行。
export function createChatRun(data) {
  return request({
    url: '/ai/chat/run',
    method: 'post',
    data,
    headers: { repeatSubmit: false }
  })
}

export function getChatRun(runId) {
  return request({
    url: '/ai/chat/run/' + runId,
    method: 'get'
  })
}

export function getChatRunState(runId) {
  return request({
    url: '/ai/chat/run/' + runId + '/state',
    method: 'get'
  })
}

export function getActiveChatRun(sessionId) {
  return request({
    url: '/ai/chat/run/active',
    method: 'get',
    params: { sessionId }
  })
}

export function getLatestChatRun(sessionId) {
  return request({
    url: '/ai/chat/run/latest',
    method: 'get',
    params: { sessionId }
  })
}

export function cancelChatRun(runId) {
  return request({
    url: '/ai/chat/run/' + runId + '/cancel',
    method: 'post',
    headers: { repeatSubmit: false }
  })
}

/** 危险工具人工确认/拒绝 */
export function confirmChatTool(runId, confirmId, approved) {
  return request({
    url: '/ai/chat/run/' + runId + '/tool-confirm',
    method: 'post',
    data: { confirmId, approved: !!approved },
    headers: { repeatSubmit: false }
  })
}

// 原生 WebSocket 不能设置 Authorization 请求头，先用已认证 REST 换一次性票据。
export function createChatWebSocketTicket() {
  return request({
    url: '/ai/chat/ws-ticket',
    method: 'post',
    headers: { repeatSubmit: false }
  })
}
