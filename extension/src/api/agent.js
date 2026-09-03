import request from './request'

export function listAllAgent() {
  return request({ url: '/ai/agent/options', method: 'get' })
}
