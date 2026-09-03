import request from '@/utils/request'

// 查询上游渠道列表
export function listChannel(query) {
  return request({
    url: '/ai/channel/list',
    method: 'get',
    params: query
  })
}

// 查询渠道详细
export function getChannel(channelId) {
  return request({
    url: '/ai/channel/' + channelId,
    method: 'get'
  })
}

// 新增渠道
export function addChannel(data) {
  return request({
    url: '/ai/channel',
    method: 'post',
    data: data
  })
}

// 修改渠道
export function updateChannel(data) {
  return request({
    url: '/ai/channel',
    method: 'put',
    data: data
  })
}

// 删除渠道
export function delChannel(channelIds) {
  return request({
    url: '/ai/channel/' + channelIds,
    method: 'delete'
  })
}

// 健康检查
export function checkChannel(channelId) {
  return request({
    url: '/ai/channel/' + channelId + '/check',
    method: 'post'
  })
}
