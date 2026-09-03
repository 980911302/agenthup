import request from '@/utils/request'

// 查询模型列表
export function listModel(query) {
  return request({
    url: '/ai/model/list',
    method: 'get',
    params: query
  })
}

// 查询模型详细
export function getModel(modelId) {
  return request({
    url: '/ai/model/' + modelId,
    method: 'get'
  })
}

// 修改模型
export function updateModel(data) {
  return request({
    url: '/ai/model',
    method: 'put',
    data: data
  })
}

// 删除模型
export function delModel(modelIds) {
  return request({
    url: '/ai/model/' + modelIds,
    method: 'delete'
  })
}

// 查询已落库的渠道模型清单(导入用)
export function listUpstreamModels(channelId) {
  return request({
    url: '/ai/model/import/upstream',
    method: 'get',
    params: { channelId }
  })
}

// 导入模型(模型不存在则创建,已存在则新增渠道供应)
export function importModel(data) {
  return request({
    url: '/ai/model/import',
    method: 'post',
    data: data
  })
}

// 查询模型供应渠道列表
export function listModelSupply(modelId) {
  return request({
    url: '/ai/model/' + modelId + '/supply',
    method: 'get'
  })
}

// 添加供应：排除已绑定，只返回清单里有当前模型的渠道
export function listSupplyCandidates(modelId) {
  return request({
    url: '/ai/model/' + modelId + '/supply/candidates',
    method: 'get'
  })
}

// 为模型添加渠道供应
export function addModelSupply(modelId, data) {
  return request({
    url: '/ai/model/' + modelId + '/supply',
    method: 'post',
    data: data
  })
}

// 修改供应配置
export function updateModelSupply(data) {
  return request({
    url: '/ai/model/supply',
    method: 'put',
    data: data
  })
}

// 删除供应
export function delModelSupply(ids) {
  return request({
    url: '/ai/model/supply/' + ids,
    method: 'delete'
  })
}
