import request from '@/utils/request'

// 查询工具列表
export function listTool(query) {
  return request({
    url: '/ai/tool/list',
    method: 'get',
    params: query
  })
}

// 查询工具详细
export function getTool(toolId) {
  return request({
    url: '/ai/tool/' + toolId,
    method: 'get'
  })
}

// 修改工具状态(启停)
export function changeToolStatus(data) {
  return request({
    url: '/ai/tool/status',
    method: 'put',
    data: data
  })
}

// 同步全部 MCP 工具(不含内置;内置启动时已同步)
export function syncMcpAllTools() {
  return request({
    url: '/ai/tool/sync/mcp',
    method: 'post'
  })
}

// 同步指定 MCP server 的工具
export function syncMcpTools(mcpServerId) {
  return request({
    url: '/ai/tool/sync/mcp/' + mcpServerId,
    method: 'post'
  })
}
