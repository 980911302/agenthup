import request from '@/utils/request'

/** 近 N 天 token 消耗概览 */
export function getStatOverview(days = 7) {
  return request({
    url: '/ai/stat/overview',
    method: 'get',
    params: { days }
  })
}

/** 按模型 TOP */
export function getStatByModel(days = 7, limit = 5) {
  return request({
    url: '/ai/stat/by-model',
    method: 'get',
    params: { days, limit }
  })
}

/** 按智能体 TOP */
export function getStatByAgent(days = 7, limit = 5) {
  return request({
    url: '/ai/stat/by-agent',
    method: 'get',
    params: { days, limit }
  })
}

/** 按天趋势(用于折线图) */
export function getStatTrend(days = 30) {
  return request({
    url: '/ai/stat/trend',
    method: 'get',
    params: { days }
  })
}

/** 缓存命中聚合(首页) */
export function getStatCache(days = 7) {
  return request({
    url: '/ai/stat/cache',
    method: 'get',
    params: { days }
  })
}

/** 任务健康度(成功率 / 状态分布 / 平均耗时) */
export function getStatRuns(days = 7) {
  return request({
    url: '/ai/stat/runs',
    method: 'get',
    params: { days }
  })
}

/** 渠道健康度(配置态 + 运行时态) */
export function getStatChannelsHealth() {
  return request({
    url: '/ai/stat/channels-health',
    method: 'get'
  })
}

/** MCP 健康度(配置态 + 运行时态) */
export function getStatMcpHealth() {
  return request({
    url: '/ai/stat/mcp-health',
    method: 'get'
  })
}
