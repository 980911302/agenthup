import request from './request'

/** 当前登录用户的 Token 消耗、每日趋势与缓存命中统计。 */
export function getMyUsageStats(days = 7) {
  return request({
    url: '/ai/stat/my-usage',
    method: 'get',
    params: { days }
  })
}
