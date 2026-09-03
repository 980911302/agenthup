import request from '@/utils/request'

/**
 * 个人文件管理端 API(/ai/userfile)。
 *
 * ⚠️ 与 desktop 走的 /ai/files 不是一套:那边是 C 端,无权限点、只能操作自己的文件;
 * 这里是后台权限点 ai:userfile:*,跨用户查看与强制删除。
 */

// 跨用户文件列表
export function listUserFile(query) {
  return request({
    url: '/ai/userfile/list',
    method: 'get',
    params: query
  })
}

// 全局汇总 + 当前生效的配额配置
export function getUserFileTotals() {
  return request({
    url: '/ai/userfile/totals',
    method: 'get'
  })
}

// 按用户汇总占用(倒序)
export function getUserFileUsage() {
  return request({
    url: '/ai/userfile/usage',
    method: 'get'
  })
}

// 强制删除；服务端会清理不再被引用的对象存储文件
export function delUserFile(fileIds) {
  return request({
    url: '/ai/userfile/' + fileIds,
    method: 'delete'
  })
}
