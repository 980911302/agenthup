import request from './request'

/** 我的项目列表(带 session_count) */
export function listProject(query) {
  return request({ url: '/ai/project/list', method: 'get', params: query })
}

/** 查询项目详情（编辑前以服务端数据为准） */
export function getProject(projectId) {
  return request({ url: '/ai/project/' + projectId, method: 'get' })
}

/** 新建项目 */
export function createProject(data) {
  return request({ url: '/ai/project', method: 'post', data })
}

/** 修改项目 */
export function updateProject(projectId, data) {
  return request({ url: '/ai/project/' + projectId, method: 'put', data })
}

/** 删除项目(级联删其下会话) */
export function deleteProject(projectId) {
  return request({ url: '/ai/project/' + projectId, method: 'delete' })
}
