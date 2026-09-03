import request from '@/utils/request'

// 查询会话列表(对话页左侧侧边栏用)
export function listSession(query) {
  return request({
    url: '/ai/chat/session/list',
    method: 'get',
    params: query
  })
}

// 删除会话(逻辑删除,支持批量,逗号分隔)
export function delSession(sessionIds) {
  return request({
    url: '/ai/chat/session/' + sessionIds,
    method: 'delete'
  })
}

// 查询会话消息时间线(游标分页:切换历史会话时还原最新一页,滚动到顶加载更早)
// query: { limit?, beforeMessageId? } — beforeMessageId 取更早的消息,缺省取最新一页
export function getSessionTimeline(sessionId, query) {
  return request({
    url: '/ai/chat/session/' + sessionId + '/timeline',
    method: 'get',
    params: query || {}
  })
}

// 会话链路追踪:该会话内所有轮次(run)的聚合概览
export function getSessionTraces(sessionId, limit = 50) {
  return request({
    url: '/ai/chat/session/' + sessionId + '/traces',
    method: 'get',
    params: { limit }
  })
}

// 某一轮对话的调用树(扁平 spans,前端按 parentSpanId 组瀑布图)
export function getRunTrace(sessionId, runId) {
  return request({
    url: '/ai/chat/session/' + sessionId + '/traces/' + runId,
    method: 'get'
  })
}

// 会话内全部用户消息(右侧音轨全量导航,不受聊天区分页影响)
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

// 保存会话选中的知识库(会话级多选;整组替换,空数组=清空;会话中途改库下一轮生效)
export function saveSessionKbs(sessionId, kbIds) {
  return request({
    url: '/ai/chat/session/' + sessionId + '/knowledge-bases',
    method: 'put',
    data: kbIds || []
  })
}
