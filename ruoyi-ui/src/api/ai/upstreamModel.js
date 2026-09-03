import request from '@/utils/request'

export function listUpstreamModel(query) {
  return request({ url: '/ai/upstreamModel/list', method: 'get', params: query })
}

export function addUpstreamModel(data) {
  return request({ url: '/ai/upstreamModel', method: 'post', data })
}

export function updateUpstreamModel(data) {
  return request({ url: '/ai/upstreamModel', method: 'put', data })
}

export function delUpstreamModel(ids) {
  return request({ url: '/ai/upstreamModel/' + ids, method: 'delete' })
}

export function syncUpstreamModel(channelId) {
  return request({ url: '/ai/upstreamModel/sync/' + channelId, method: 'post' })
}
