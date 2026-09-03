import request from './request'

export function createChatRun(data) {
  return request({
    url: '/ai/chat/run',
    method: 'post',
    data,
    headers: { repeatSubmit: false }
  })
}

export function getChatRun(runId) {
  return request({ url: '/ai/chat/run/' + runId, method: 'get' })
}

export function getChatRunState(runId) {
  return request({ url: '/ai/chat/run/' + runId + '/state', method: 'get' })
}

export function getActiveChatRun(sessionId) {
  return request({ url: '/ai/chat/run/active', method: 'get', params: { sessionId } })
}

export function getLatestChatRun(sessionId) {
  return request({ url: '/ai/chat/run/latest', method: 'get', params: { sessionId } })
}

export function cancelChatRun(runId) {
  return request({
    url: '/ai/chat/run/' + runId + '/cancel',
    method: 'post',
    headers: { repeatSubmit: false }
  })
}

export function confirmChatTool(runId, confirmId, approved) {
  return request({
    url: '/ai/chat/run/' + runId + '/tool-confirm',
    method: 'post',
    data: { confirmId, approved: !!approved },
    headers: { repeatSubmit: false }
  })
}

export function createChatWebSocketTicket() {
  return request({
    url: '/ai/chat/ws-ticket',
    method: 'post',
    headers: { repeatSubmit: false }
  })
}

export function getContextUsage(sessionId, agentId) {
  return request({
    url: '/ai/chat/session/' + sessionId + '/context',
    method: 'get',
    params: agentId != null ? { agentId } : {}
  })
}

export function rollbackLastTurn(sessionId, agentId) {
  return request({
    url: '/ai/chat/session/' + sessionId + '/last-turn',
    method: 'delete',
    params: agentId != null ? { agentId } : {}
  })
}
