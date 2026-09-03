import request from '@/utils/request'

// 查询知识库列表
export function listKb(query) {
  return request({
    url: '/ai/kb/list',
    method: 'get',
    params: query
  })
}

// 知识资产工作台（聚合）
export function listKbWorkbench(query) {
  return request({
    url: '/ai/kb/workbench',
    method: 'get',
    params: query
  })
}

// 删除影响预览
export function deleteKbImpact(kbId) {
  return request({
    url: '/ai/kb/' + kbId + '/delete-impact',
    method: 'get'
  })
}

// 使用情况
export function getKbUsage(kbId) {
  return request({
    url: '/ai/kb/' + kbId + '/usage',
    method: 'get'
  })
}

// 当前用户对该库的 capability（不包含成员/智能体）
export function getKbAccess(kbId) {
  return request({
    url: '/ai/kb/' + kbId + '/access',
    method: 'get'
  })
}

// 成员候选搜索（MANAGE；keyword 至少 2 字，pageSize ≤ 20）
export function listKbMemberCandidates(kbId, query) {
  return request({
    url: '/ai/kb/' + kbId + '/member-candidates',
    method: 'get',
    params: query
  })
}

export function upsertKbMember(kbId, data) {
  return request({
    url: '/ai/kb/' + kbId + '/members',
    method: 'post',
    data
  })
}

export function removeKbMember(kbId, userId) {
  return request({
    url: '/ai/kb/' + kbId + '/members/' + userId,
    method: 'delete'
  })
}

export function transferKbOwner(kbId, userId) {
  return request({
    url: '/ai/kb/' + kbId + '/transfer-owner',
    method: 'post',
    data: { userId }
  })
}

// 新增知识库
export function addKb(data) {
  return request({
    url: '/ai/kb',
    method: 'post',
    data: data
  })
}

// 修改知识库
export function updateKb(data) {
  return request({
    url: '/ai/kb',
    method: 'put',
    data: data
  })
}

// 删除知识库
export function delKb(kbIds) {
  return request({
    url: '/ai/kb/' + kbIds,
    method: 'delete'
  })
}

// ==================== 知识库文档 ====================

// 查询知识库文档列表(kbId 挂路径)
export function listKbDoc(kbId, query) {
  return request({
    url: '/ai/kb/' + kbId + '/document/list',
    method: 'get',
    params: query
  })
}

// 上传文档到知识库(FormData)
// 注意：不要手写 Content-Type: multipart/form-data（缺 boundary，服务端解析失败）。
// request 拦截器会在 data 为 FormData 时删掉默认 application/json，交给浏览器自动带 boundary。
// onDuplicate: skip | force（replace 已由后端拒绝，业务 code 400）
export function uploadKbDoc(kbId, data, onDuplicate = 'skip') {
  const policy = onDuplicate === 'force' ? 'force' : 'skip'
  const url = '/ai/kb/' + kbId + '/document' + ('?onDuplicate=' + encodeURIComponent(policy))
  return request({
    url,
    method: 'post',
    data: data,
    headers: { repeatSubmit: false },
    timeout: 120000
  })
}

// 受控文档下载（blob）；禁止拼接 filePath 走 /profile 静态路径
export function downloadKbDocument(kbId, docId) {
  return request({
    url: '/ai/kb/' + kbId + '/document/' + docId + '/download',
    method: 'get',
    responseType: 'blob',
    timeout: 120000
  })
}

// 单文档详情（裁剪内部路径）
export function getKbDocument(kbId, docId) {
  return request({
    url: '/ai/kb/' + kbId + '/document/' + docId,
    method: 'get'
  })
}

// 批量重新处理
export function batchReprocessKbDoc(kbId, docIds) {
  return request({
    url: '/ai/kb/' + kbId + '/document/batch-reprocess',
    method: 'post',
    data: { docIds },
    headers: { repeatSubmit: false }
  })
}

// 删除知识库文档
export function delKbDoc(kbId, docIds) {
  return request({
    url: '/ai/kb/' + kbId + '/document/' + docIds,
    method: 'delete'
  })
}

// 重新处理文档
export function reprocessKbDoc(kbId, docId) {
  return request({
    url: '/ai/kb/' + kbId + '/document/' + docId + '/reprocess',
    method: 'post'
  })
}

// 文档产品预览（目录/安全 HTML/质量）
export function getKbDocPreview(kbId, docId) {
  return request({
    url: '/ai/kb/' + kbId + '/document/' + docId + '/preview',
    method: 'get'
  })
}

// 检索测试
export function searchKb(kbId, data) {
  return request({
    url: '/ai/kb/' + kbId + '/search',
    method: 'post',
    data: data,
    headers: { repeatSubmit: false }
  })
}

// 受控子图探索
export function graphExplore(kbId, data) {
  return request({
    url: '/ai/kb/' + kbId + '/graph/explore',
    method: 'post',
    data: data || {},
    headers: { repeatSubmit: false }
  })
}

// 实体详情
export function graphEntityDetail(kbId, name) {
  return request({
    url: '/ai/kb/' + kbId + '/graph/entity',
    method: 'get',
    params: { name }
  })
}

// 关系详情
export function graphRelationDetail(kbId, source, target, label) {
  return request({
    url: '/ai/kb/' + kbId + '/graph/relation',
    method: 'get',
    params: { source, target, label }
  })
}

export function graphDocs(kbId) {
  return request({
    url: '/ai/kb/' + kbId + '/graph/docs',
    method: 'get'
  })
}

export function getKbEngine() {
  return request({
    url: '/ai/kb/engine',
    method: 'get'
  })
}

export function saveKbEngine(data) {
  return request({
    url: '/ai/kb/engine',
    method: 'put',
    data: data
  })
}

export function precheckEngine(data) {
  return request({ url: '/ai/kb/engine/precheck', method: 'post', data: data || {} })
}

export function publishEngine(data) {
  return request({
    url: '/ai/kb/engine/publish',
    method: 'post',
    data: data || {},
    headers: { repeatSubmit: false }
  })
}

export function getEngineOps() {
  return request({ url: '/ai/kb/engine/ops', method: 'get' })
}
