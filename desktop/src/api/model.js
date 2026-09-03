import request from './request'

/** 客户端可选择的启用聊天模型。 */
export function listModelOptions() {
  return request({ url: '/ai/model/options', method: 'get' })
}
