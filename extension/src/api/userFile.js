import request from './request'

/**
 * 个人文件空间 API(/ai/files)。
 *
 * 与 workspace.js 的分工:
 *   workspace.js  会话工作区,文件随会话生灭,AI 用 bash/read 直接操作
 *   userFile.js   个人文件空间,文件跟人走,存在对象存储里
 * 两者唯一的交汇点是 attachToSession —— 把个人文件拷一份进会话工作区给模型用。
 */

// 文件列表。keyword/orderBy 后端也支持,当前前端做本地过滤,这里保留透传能力
export function listUserFiles(params) {
  return request({
    url: '/ai/files',
    method: 'get',
    params: params || {}
  })
}

// 存储配额
export function getUserFileQuota() {
  return request({
    url: '/ai/files/quota',
    method: 'get'
  })
}

/**
 * 上传到个人空间
 * @param {File} file
 * @param {(percent:number)=>void} [onProgress]
 */
export function uploadUserFile(file, onProgress) {
  const data = new FormData()
  data.append('file', file)
  return request({
    url: '/ai/files/upload',
    method: 'post',
    data,
    headers: { repeatSubmit: false },
    timeout: 300000,
    onUploadProgress(e) {
      if (onProgress && e.total) onProgress(Math.round((e.loaded * 100) / e.total))
    }
  })
}

// 重命名
export function renameUserFile(fileId, name) {
  return request({
    url: '/ai/files/' + fileId + '/name',
    method: 'put',
    data: { name }
  })
}

// 删除
export function deleteUserFile(fileId) {
  return request({
    url: '/ai/files/' + fileId,
    method: 'delete'
  })
}

/**
 * 预签名直链。图片/PDF 内联预览用,让浏览器直连对象存储。
 * @param {boolean} [download] true 则链接带另存为响应头
 */
export function getUserFilePreviewUrl(fileId, download) {
  return request({
    url: '/ai/files/' + fileId + '/preview-url',
    method: 'get',
    params: download ? { download: true } : {}
  })
}

/**
 * 投递到会话工作区,返回 { name, path, mime, size },
 * path 形如 uploads/report.csv,可直接作为消息附件。
 */
export function attachUserFileToSession(fileId, sessionId, projectId) {
  return request({
    url: '/ai/files/' + fileId + '/attach',
    method: 'post',
    params: projectId != null ? { sessionId, projectId } : { sessionId }
  })
}

/**
 * 把会话工作区里的文件存进个人文件空间（与 attach 方向相反）。
 * 工作区随会话删除而清空，AI 产出的东西要留得住就得收进个人空间。
 */
export function saveWorkspaceFileToUserFiles(sessionId, path, projectId) {
  return request({
    url: '/ai/files/save-from-workspace',
    method: 'post',
    params: projectId != null ? { sessionId, path, projectId } : { sessionId, path }
  })
}

/** 带 Token 鉴权的 Blob 下载 */
export async function downloadUserFileBlob(fileId, filename) {
  const res = await request({
    url: '/ai/files/' + fileId + '/download',
    method: 'get',
    responseType: 'blob'
  })
  const blob = new Blob([res])
  const url = window.URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename || 'file'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  window.URL.revokeObjectURL(url)
}

/**
 * 取文本文件正文用于预览。
 * 走 blob 而不是预签名直链:预览要读内容,跨域直取会被 CORS 挡住,
 * 而后端转发天然同源。图片走 preview-url 是因为 <img> 不受此限。
 */
export async function readUserFileText(fileId, maxBytes = 200 * 1024) {
  const res = await request({
    url: '/ai/files/' + fileId + '/download',
    method: 'get',
    responseType: 'blob'
  })
  const blob = new Blob([res])
  if (blob.size > maxBytes) return null
  return await blob.text()
}
