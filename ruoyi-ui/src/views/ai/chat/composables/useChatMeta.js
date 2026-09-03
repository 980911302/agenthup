import { ref, computed } from 'vue'
import { getContextUsage } from '@/api/ai/chat'

/** 连接指示灯文案：open 在线，closed 离线，其余都是过渡态。 */
const CONNECTION_LABELS = {
  open: '在线',
  connecting: '连接中',
  reconnecting: '重连中',
  closed: '离线'
}

/**
 * 页面元信息 composable：连接指示灯 + 上下文刻度条。
 *
 * <p>这两块都是「会话状态之外的旁路信息」：连接状态来自 WebSocket 底层，
 * 上下文用量来自后端统计接口。彼此独立、且不参与对话主流程，集中放这里。
 */
export function useChatMeta(options = {}) {
  const { connectionState } = options

  // 上下文刻度条状态
  const contextUsage = ref(null)

  const connectionLabel = computed(() => CONNECTION_LABELS[connectionState.value] || '离线')
  const connectionTone = computed(() => {
    if (connectionState.value === 'open') return 'online'
    if (connectionState.value === 'closed') return 'offline'
    return 'pending'
  })
  const connectionTip = computed(() => {
    if (connectionState.value === 'open') return '实时连接正常，回答逐字推送'
    if (connectionState.value === 'closed') return '实时连接已断开'
    return '正在建立实时连接，期间已在执行的对话不会中断'
  })

  function resetContextUsage() {
    contextUsage.value = null
  }

  function loadContextUsage(sessionId, agentIdVal) {
    if (!sessionId) {
      resetContextUsage()
      return
    }
    getContextUsage(sessionId, agentIdVal || undefined).then((res) => {
      contextUsage.value = res.data || null
    }).catch(() => { /* 接口未就绪时静默 */ })
  }

  return {
    contextUsage,
    connectionLabel,
    connectionTone,
    connectionTip,
    resetContextUsage,
    loadContextUsage
  }
}
