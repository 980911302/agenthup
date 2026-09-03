import request from '@/utils/request'

// 查询会话工作区目录树
export function getWorkspaceTree(sessionId) {
  return request({
    url: '/ai/chat/workspace/' + sessionId + '/tree',
    method: 'get'
  })
}

// 读取工作区内单个文本文件
export function getWorkspaceFile(sessionId, path) {
  return request({
    url: '/ai/chat/workspace/' + sessionId + '/file',
    method: 'get',
    params: { path }
  })
}

/**
 * 上传文件到会话工作区的 uploads/ 目录。
 * 返回 { name, path, mime, size }，path 可直接作为 readFile 的入参。
 */
export function uploadWorkspaceFile(sessionId, file, onProgress) {
  const data = new FormData()
  data.append('file', file)
  return request({
    url: '/ai/chat/workspace/' + sessionId + '/upload',
    method: 'post',
    data,
    // Content-Type 由 request 拦截器对 FormData 自动处理（含 boundary）
    headers: { repeatSubmit: false },
    timeout: 120000,
    onUploadProgress(e) {
      if (onProgress && e.total) onProgress(Math.round((e.loaded * 100) / e.total))
    }
  })
}

// 删除工作区内单个文件或目录
export function deleteWorkspaceFile(sessionId, path) {
  return request({
    url: '/ai/chat/workspace/' + sessionId + '/file',
    method: 'delete',
    params: { path }
  })
}

// 清空会话工作区
export function clearWorkspace(sessionId) {
  return request({
    url: '/ai/chat/workspace/' + sessionId,
    method: 'delete'
  })
}

/** 单文件下载地址(交给 $download.zip / axios blob 用，需带 token) */
export function workspaceFileDownloadUrl(sessionId, path) {
  return '/ai/chat/workspace/' + sessionId + '/download?path=' + encodeURIComponent(path)
}

/** 目录打包下载地址，path 为空表示整个工作区 */
export function workspaceZipDownloadUrl(sessionId, path) {
  const base = '/ai/chat/workspace/' + sessionId + '/download-zip'
  return path ? base + '?path=' + encodeURIComponent(path) : base
}
