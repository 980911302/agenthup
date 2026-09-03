import request from './request'

export function listSession(query) {
  return request({ url: '/ai/chat/session/list', method: 'get', params: query })
}

export function delSession(sessionIds) {
  return request({ url: '/ai/chat/session/' + sessionIds, method: 'delete' })
}

export function getSessionTimeline(sessionId, query) {
  return request({
    url: '/ai/chat/session/' + sessionId + '/timeline',
    method: 'get',
    params: query || {}
  })
}

export function saveSessionKbs(sessionId, kbIds) {
  return request({
    url: '/ai/chat/session/' + sessionId + '/knowledge-bases',
    method: 'put',
    data: kbIds || []
  })
}

// 会话内全部用户消息(右侧消息导航音轨的数据源,不受聊天区分页影响)
export function getSessionUserMessages(sessionId, limit = 1000) {
  return request({
    url: '/ai/chat/session/' + sessionId + '/user-messages',
    method: 'get',
    params: { limit }
  })
}

// 某回合特殊事件正文(点开引用卡片才拉,不进时间线)
export function getSpecialEvents(sessionId, query) {
  return request({
    url: '/ai/chat/session/' + sessionId + '/special-events',
    method: 'get',
    params: query || {}
  })
}

// 按需拉取某条 TOOL 消息的完整工具结果(历史加载只给预览,点击「查看完整结果」时调用)
export function getToolResult(sessionId, messageId) {
  return request({
    url: '/ai/chat/session/' + sessionId + '/message/' + messageId + '/tool-result',
    method: 'get'
  })
}
