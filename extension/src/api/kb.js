import request from './request'
import { getToken } from '../utils/auth'

// 聊天使用端的知识库选择器，不读取后台管理配置。
export function listKbOptions(query) {
  return request({ url: '/ai/kb/options', method: 'get', params: query })
}

export function listDesktopKbs(query) {
  return request({ url: '/ai/kb/desktop', method: 'get', params: query })
}

export function addKb(data) {
  return request({ url: '/ai/kb/desktop', method: 'post', data })
}

export function updateKb(data) {
  return request({ url: '/ai/kb', method: 'put', data })
}

export function delKb(kbIds) {
  return request({ url: '/ai/kb/' + kbIds, method: 'delete' })
}

export function listKbDoc(kbId, query) {
  return request({
    url: '/ai/kb/' + kbId + '/document/list',
    method: 'get',
    params: query
  })
}

// 单文档详情：处理完成或失败时只校准对应行，不重拉整个列表。
export function getKbDocument(kbId, docId) {
  return request({
    url: '/ai/kb/' + kbId + '/document/' + docId,
    method: 'get',
    silent: true
  })
}

/**
 * 订阅知识库处理事件。fetch 支持 Authorization 请求头，避免 EventSource
 * 把令牌放进 URL。调用方负责传入 AbortSignal 并在离开页面时关闭。
 */
export async function subscribeKbDocumentEvents(kbId, { signal, onEvent, onConnected } = {}) {
  const base = import.meta.env.VITE_APP_BASE_API || '/dev-api'
  const response = await fetch(`${base}/ai/kb/${encodeURIComponent(kbId)}/document/events`, {
    method: 'GET',
    headers: {
      Accept: 'text/event-stream',
      ...(getToken() ? { Authorization: `Bearer ${getToken()}` } : {})
    },
    cache: 'no-store',
    signal
  })
  if (!response.ok || !response.body) {
    throw new Error(response.status === 401 ? '登录已过期，请重新登录' : '文档状态连接失败')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
    let boundary = buffer.indexOf('\n\n')
    while (boundary >= 0) {
      const block = buffer.slice(0, boundary)
      buffer = buffer.slice(boundary + 2)
      let eventName = 'message'
      const dataLines = []
      for (const line of block.split('\n')) {
        if (line.startsWith('event:')) eventName = line.slice(6).trim()
        if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart())
      }
      if (eventName === 'connected') {
        onConnected?.()
      } else if (dataLines.length) {
        try { onEvent?.(JSON.parse(dataLines.join('\n'))) } catch (_) { /* 忽略无效事件 */ }
      }
      boundary = buffer.indexOf('\n\n')
    }
  }
}

export function uploadKbDoc(kbId, data, onDuplicate = 'skip') {
  const policy = onDuplicate === 'force' ? 'force' : 'skip'
  return request({
    url: '/ai/kb/' + kbId + '/document?onDuplicate=' + encodeURIComponent(policy),
    method: 'post',
    data,
    headers: { repeatSubmit: false },
    timeout: 120000
  })
}

export function reprocessKbDoc(kbId, docId) {
  return request({
    url: '/ai/kb/' + kbId + '/document/' + docId + '/reprocess',
    method: 'post'
  })
}

export function renameKbDoc(kbId, docId, docName) {
  return request({
    url: '/ai/kb/' + kbId + '/document/' + docId + '/name',
    method: 'put',
    data: { docName }
  })
}

export function delKbDoc(kbId, docIds) {
  return request({
    url: '/ai/kb/' + kbId + '/document/' + docIds,
    method: 'delete'
  })
}

/** 文档图谱抽取状态：用于展示实体/关系抽取进度。 */
export function graphDocs(kbId) {
  return request({ url: '/ai/kb/' + kbId + '/graph/docs', method: 'get' })
}

export function graphExplore(kbId, data) {
  return request({
    url: '/ai/kb/' + kbId + '/graph/explore',
    method: 'post',
    data: data || {},
    headers: { repeatSubmit: false }
  })
}

export function graphEntityDetail(kbId, name) {
  return request({
    url: '/ai/kb/' + kbId + '/graph/entity',
    method: 'get',
    params: { name }
  })
}

export function graphRelationDetail(kbId, source, target, label) {
  return request({
    url: '/ai/kb/' + kbId + '/graph/relation',
    method: 'get',
    params: { source, target, label }
  })
}

export function getKbDocPreview(kbId, docId) {
  return request({
    url: '/ai/kb/' + kbId + '/document/' + docId + '/preview',
    method: 'get'
  })
}

// 下载知识库文档原文(blob)
export function downloadKbDocument(kbId, docId) {
  return request({
    url: '/ai/kb/' + kbId + '/document/' + docId + '/download',
    method: 'get',
    responseType: 'blob',
    timeout: 120000
  })
}

// 带 Token 鉴权的知识库文档安全下载
export async function downloadKbDocBlob(kbId, docId, filename) {
  const res = await downloadKbDocument(kbId, docId)
  const blob = new Blob([res])
  const url = window.URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename || 'document'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  window.URL.revokeObjectURL(url)
}
