import request from './request'

// 查询会话工作区目录树
export function getWorkspaceTree(sessionId, projectId) {
  return request({
    url: '/ai/chat/workspace/' + sessionId + '/tree',
    method: 'get',
    params: projectId != null ? { projectId } : {}
  })
}

// 读取工作区内单个文本文件
export function getWorkspaceFile(sessionId, path, projectId) {
  return request({
    url: '/ai/chat/workspace/' + sessionId + '/file',
    method: 'get',
    params: projectId != null ? { path, projectId } : { path }
  })
}

/**
 * 上传文件到会话工作区的 uploads/ 目录
 */
export function uploadWorkspaceFile(sessionId, file, onProgress, projectId) {
  const data = new FormData()
  data.append('file', file)
  if (projectId != null) data.append('projectId', String(projectId))
  return request({
    url: '/ai/chat/workspace/' + sessionId + '/upload',
    method: 'post',
    data,
    headers: { repeatSubmit: false },
    timeout: 120000,
    onUploadProgress(e) {
      if (onProgress && e.total) onProgress(Math.round((e.loaded * 100) / e.total))
    }
  })
}

// 删除工作区内单个文件或目录
export function deleteWorkspaceFile(sessionId, path, projectId) {
  return request({
    url: '/ai/chat/workspace/' + sessionId + '/file',
    method: 'delete',
    params: projectId != null ? { path, projectId } : { path }
  })
}

// 清空会话工作区
export function clearWorkspace(sessionId, projectId) {
  return request({
    url: '/ai/chat/workspace/' + sessionId,
    method: 'delete',
    params: projectId != null ? { projectId } : {}
  })
}

export function workspaceFileDownloadUrl(sessionId, path, projectId) {
  const project = projectId != null ? '&projectId=' + encodeURIComponent(projectId) : ''
  return '/ai/chat/workspace/' + sessionId + '/download?path=' + encodeURIComponent(path) + project
}

export function workspaceZipDownloadUrl(sessionId, path, projectId) {
  const base = '/ai/chat/workspace/' + sessionId + '/download-zip'
  const params = new URLSearchParams()
  if (path) params.set('path', path)
  if (projectId != null) params.set('projectId', projectId)
  const query = params.toString()
  return query ? base + '?' + query : base
}

/**
 * 带 Token 鉴权的 Blob 单文件安全下载
 */
export async function downloadWorkspaceFileBlob(sessionId, path, filename, projectId) {
  const res = await request({
    url: '/ai/chat/workspace/' + sessionId + '/download',
    method: 'get',
    params: projectId != null ? { path, projectId } : { path },
    responseType: 'blob'
  })
  const blob = new Blob([res])
  const url = window.URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename || String(path).split('/').pop() || 'file'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  window.URL.revokeObjectURL(url)
}

/**
 * 带 Token 鉴权的 Blob 目录/全量 Zip 安全下载
 */
export async function downloadWorkspaceZipBlob(sessionId, path, filename, projectId) {
  const res = await request({
    url: '/ai/chat/workspace/' + sessionId + '/download-zip',
    method: 'get',
    params: {
      ...(path ? { path } : {}),
      ...(projectId != null ? { projectId } : {})
    },
    responseType: 'blob'
  })
  const blob = new Blob([res])
  const url = window.URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename || 'workspace.zip'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  window.URL.revokeObjectURL(url)
}
