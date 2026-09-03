import request from '@/utils/request'

// 管理员查询长期记忆台账
export function listMemory(query) {
  return request({
    url: '/ai/memory/list',
    method: 'get',
    params: query
  })
}

// 删除长期记忆；服务端会同步清理 PostgreSQL 中的关联向量
export function delMemory(memoryIds) {
  return request({
    url: '/ai/memory/' + memoryIds,
    method: 'delete'
  })
}
