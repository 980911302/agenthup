import request from '@/utils/request'

// 查询 MCP 服务列表
export function listMcpServer(query) {
  return request({
    url: '/ai/mcpServer/list',
    method: 'get',
    params: query
  })
}

// 查询 MCP 服务详细
export function getMcpServer(mcpServerId) {
  return request({
    url: '/ai/mcpServer/' + mcpServerId,
    method: 'get'
  })
}

// 新增 MCP 服务
export function addMcpServer(data) {
  return request({
    url: '/ai/mcpServer',
    method: 'post',
    data: data
  })
}

// 修改 MCP 服务
export function updateMcpServer(data) {
  return request({
    url: '/ai/mcpServer',
    method: 'put',
    data: data
  })
}

// 删除 MCP 服务
export function delMcpServer(mcpServerIds) {
  return request({
    url: '/ai/mcpServer/' + mcpServerIds,
    method: 'delete'
  })
}

// 重新连接(建立或刷新 MCP 客户端)
export function reconnectMcpServer(mcpServerId) {
  return request({
    url: '/ai/mcpServer/' + mcpServerId + '/reconnect',
    method: 'post'
  })
}

/**
 * MCP 运行时连接状态（全部）。
 * 与列表里的 status 不同：那是「启用/停用」的配置，这里是「此刻连没连上」的事实。
 */
export function getMcpRuntimeStatus() {
  return request({
    url: '/ai/mcpServer/runtime-status',
    method: 'get'
  })
}
