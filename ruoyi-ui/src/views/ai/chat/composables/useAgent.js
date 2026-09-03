import { ref, computed, getCurrentInstance } from 'vue'
import { listAllAgent } from '@/api/ai/agent'
import { colorOf } from '@/utils/ai-palette'

/**
 * 智能体状态 composable。
 *
 * <p>只关心「当前页面用哪个智能体」：列表加载、当前智能体的派生展示(颜色/头像)、切换。
 * 切换智能体 = 换一个大脑，必须开新会话(system prompt / 工具集 / 记忆键都不同)，
 * 确认后通过 onStartNewConversation 回调通知编排层重置会话状态。
 */
export function useAgent(options = {}) {
  const { getStreaming, getHasTurns, onStartNewConversation } = options
  const { proxy } = getCurrentInstance()

  const agents = ref([])
  const agentId = ref(null)
  const agentLoading = ref(false)

  const currentAgent = computed(() => agents.value.find(a => a.agentId === agentId.value) || null)
  const agentColor = computed(() => currentAgent.value ? colorOf(currentAgent.value.agentCode || '', currentAgent.value.theme) : '#0A84FF')
  const assistantEmoji = computed(() => currentAgent.value ? (currentAgent.value.icon || '🤖') : '🤖')
  const canSend = computed(() => !!agentId.value)

  function loadAgents() {
    agentLoading.value = true
    listAllAgent().then((res) => {
      agents.value = (res.data || []).filter(a => a.status === '0')
      if (!agents.value.length) {
        proxy.$modal.msgWarning('暂无可用智能体，请先在智能体管理中创建并启用')
      } else if (agentId.value == null) {
        agentId.value = agents.value[0].agentId
      }
    }).finally(() => { agentLoading.value = false })
  }

  /**
   * 切换智能体。选择器是受控的(值由 agentId 驱动)，
   * 所以取消时什么都不用做 —— 不改 agentId，选择器自然显示原值。
   */
  /** 程序化设置当前智能体(切换会话恢复绑定用)，不弹确认、不触发 change 事件。 */
  function setAgent(id) {
    agentId.value = id
  }

  function changeAgent(nextAgentId) {
    if (getStreaming() || nextAgentId == null || nextAgentId === agentId.value) return
    // 没有对话内容时直接切，不打扰
    if (!getHasTurns()) {
      agentId.value = nextAgentId
      return
    }
    // 切智能体 = 换一个大脑，必须开新会话。但旧会话仍在左侧列表里，
    // 这里要让用户知道，避免"我的对话没了"的错觉。
    proxy.$modal.confirm('切换智能体会开启一轮新对话。当前会话已保存，可从左侧列表随时找回。')
      .then(() => {
        agentId.value = nextAgentId
        onStartNewConversation && onStartNewConversation()
      })
      .catch(() => {})
  }

  return {
    agents,
    agentId,
    agentLoading,
    currentAgent,
    agentColor,
    assistantEmoji,
    canSend,
    loadAgents,
    changeAgent,
    setAgent
  }
}
