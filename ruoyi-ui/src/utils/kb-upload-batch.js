/**
 * 知识库上传批次本地持久化（刷新后可恢复抽屉中的任务视图）。
 * 服务端以文档 parse_status 为准；本存储仅辅助展示批次元数据。
 */

const PREFIX = 'kb_upload_batch_v1:'

export function loadBatch(kbId) {
  if (!kbId) return null
  try {
    const raw = localStorage.getItem(PREFIX + kbId)
    if (!raw) return null
    const data = JSON.parse(raw)
    if (!data || !Array.isArray(data.items)) return null
    return data
  } catch {
    return null
  }
}

export function saveBatch(kbId, batch) {
  if (!kbId) return
  try {
    if (!batch || !batch.items || !batch.items.length) {
      localStorage.removeItem(PREFIX + kbId)
      return
    }
    localStorage.setItem(PREFIX + kbId, JSON.stringify({
      batchId: batch.batchId,
      kbId: Number(kbId),
      createdAt: batch.createdAt || Date.now(),
      updatedAt: Date.now(),
      onDuplicate: batch.onDuplicate || 'skip',
      items: batch.items.map(i => ({
        uid: i.uid,
        name: i.name,
        size: i.size,
        status: i.status, // queued | uploading | success | failed | duplicate | skipped
        docId: i.docId || null,
        error: i.error || null,
        progress: i.progress || 0
      }))
    }))
  } catch {
    /* quota / private mode */
  }
}

export function clearBatch(kbId) {
  if (!kbId) return
  try { localStorage.removeItem(PREFIX + kbId) } catch { /* ignore */ }
}

export function newBatchId() {
  return 'b_' + Date.now().toString(36) + '_' + Math.random().toString(36).slice(2, 8)
}

export function batchSummary(items) {
  const list = items || []
  const s = { total: list.length, success: 0, failed: 0, duplicate: 0, uploading: 0, queued: 0 }
  for (const i of list) {
    if (i.status === 'success') s.success++
    else if (i.status === 'failed') s.failed++
    else if (i.status === 'duplicate' || i.status === 'skipped') s.duplicate++
    else if (i.status === 'uploading') s.uploading++
    else s.queued++
  }
  return s
}
