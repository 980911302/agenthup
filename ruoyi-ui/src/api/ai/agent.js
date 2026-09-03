import request from '@/utils/request'

// 查询智能体列表
export function listAgent(query) {
  return request({
    url: '/ai/agent/list',
    method: 'get',
    params: query
  })
}

// 查询全部正常智能体(下拉用)
export function listAllAgent() {
  return request({
    url: '/ai/agent/listAll',
    method: 'get'
  })
}

// 查询智能体详细
export function getAgent(agentId) {
  return request({
    url: '/ai/agent/' + agentId,
    method: 'get'
  })
}

// 新增智能体
export function addAgent(data) {
  return request({
    url: '/ai/agent',
    method: 'post',
    data: data
  })
}

// 修改智能体
export function updateAgent(data) {
  return request({
    url: '/ai/agent',
    method: 'put',
    data: data
  })
}

// 删除智能体
export function delAgent(agentIds) {
  return request({
    url: '/ai/agent/' + agentIds,
    method: 'delete'
  })
}
