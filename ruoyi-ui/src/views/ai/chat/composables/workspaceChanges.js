/** 将同一回合内多次文件操作归并成相对回合开始状态的最终变化。 */
export function mergeWorkspaceChanges(existing, incoming) {
  const byPath = new Map()
  for (const item of existing || []) {
    if (item?.path) byPath.set(item.path, { ...item })
  }
  for (const raw of incoming || []) {
    if (!raw?.path) continue
    const item = {
      ...raw,
      operation: String(raw.operation || 'MODIFY').toUpperCase()
    }
    const previous = byPath.get(item.path)
    if (!previous) {
      byPath.set(item.path, item)
      continue
    }
    const operation = mergeOperation(previous.operation, item.operation)
    if (!operation) {
      byPath.delete(item.path)
    } else {
      byPath.set(item.path, { ...previous, ...item, operation })
    }
  }
  return Array.from(byPath.values())
}

function mergeOperation(previous, next) {
  if (previous === 'CREATE' && next === 'MODIFY') return 'CREATE'
  if (previous === 'CREATE' && next === 'DELETE') return null
  if (previous === 'MODIFY' && next === 'DELETE') return 'DELETE'
  if (previous === 'DELETE' && next === 'CREATE') return 'MODIFY'
  return next
}

export function workspaceChangeCounts(changes) {
  const out = { total: 0, created: 0, modified: 0, deleted: 0 }
  for (const item of changes || []) {
    out.total++
    if (item.operation === 'CREATE') out.created++
    else if (item.operation === 'DELETE') out.deleted++
    else out.modified++
  }
  return out
}

/**
 * 将多个回合的增量事件归并成一个会话级变更集。
 *
 * UI 只关心用户当前可以审阅到的最终状态：同一文件在后续回合继续修改，仍只占一项；
 * 新建后再删除则从变更集中消失。这让右侧工作区的「更改」页具备与源代码管理一致的语义。
 */
export function mergeWorkspaceChangeSets(turns) {
  let result = []
  for (const turn of turns || []) {
    result = mergeWorkspaceChanges(result, turn?.workspaceChanges || [])
  }
  return result.sort((a, b) => String(a.path || '').localeCompare(String(b.path || '')))
}
