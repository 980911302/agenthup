import { nextTick, ref } from 'vue'

/** 会改动文件系统的工具，调用结束后刷新工作区 */
const WRITE_TOOLS = ['write', 'edit', 'bash']

/**
 * 工作区面板状态 composable。
 *
 * <p>只负责面板开关 + 写文件类工具调用后的防抖刷新。
 * 是否刷新由调用方按需触发(scheduleWorkspaceRefresh 幂等，面板没开时是空操作)。
 */
export function useWorkspace() {
  const workspaceVisible = ref(false)
  const workspaceRef = ref(null)
  let wsTimer = null

  function scheduleWorkspaceRefresh() {
    if (!workspaceVisible.value) return
    clearTimeout(wsTimer)
    wsTimer = setTimeout(() => workspaceRef.value?.refresh(), 400)   // 防抖：连续多次工具调用只刷一次
  }

  function notifyWorkspaceChanges(payload) {
    if (workspaceVisible.value) {
      scheduleWorkspaceRefresh()
    }
  }

  async function openWorkspace(path) {
    workspaceVisible.value = true
    await nextTick()
    if (path) workspaceRef.value?.openPath(path)
  }

  function toggleWorkspace() {
    if (workspaceVisible.value) {
      workspaceVisible.value = false
      return
    }
    workspaceVisible.value = true
  }

  /** 该工具是否会改动会话工作区文件 */
  function isWriteTool(name) {
    return WRITE_TOOLS.includes(name)
  }

  return {
    workspaceVisible,
    workspaceRef,
    scheduleWorkspaceRefresh,
    notifyWorkspaceChanges,
    openWorkspace,
    toggleWorkspace,
    isWriteTool
  }
}
