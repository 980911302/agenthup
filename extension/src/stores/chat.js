import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

/** 桌面对话工作台:当前智能体、可选知识库。 */
export const useChatStore = defineStore('chat', () => {
  const agents = ref([])
  const agentId = ref(null)
  const models = ref([])
  const modelId = ref(null)
  const skills = ref([])
  const skillIds = ref([])
  const kbs = ref([])
  const kbIds = ref([])
  const kbLoading = ref(false)

  const agent = computed(() => agents.value.find(a => String(a.agentId) === String(agentId.value)) || null)
  const selectedKbs = computed(() => kbs.value.filter(k => kbIds.value.includes(k.kbId)))
  const selectedSkills = computed(() => skills.value.filter(s => skillIds.value.includes(s.skillId)))

  function setAgent(id) {
    agentId.value = id
  }

  function setModel(id) {
    modelId.value = id == null ? null : Number(id)
  }

  function setSkillIds(ids) {
    skillIds.value = Array.isArray(ids) ? [...new Set(ids.map(Number).filter(Boolean))] : []
  }

  function toggleKb(id) {
    if (kbIds.value.includes(id)) kbIds.value = kbIds.value.filter(x => x !== id)
    else kbIds.value = [...kbIds.value, id]
  }

  function setKbIds(ids) {
    kbIds.value = Array.isArray(ids) ? ids : []
  }

  return {
    agents,
    agentId,
    agent,
    models,
    modelId,
    skills,
    skillIds,
    selectedSkills,
    kbs,
    kbIds,
    selectedKbs,
    kbLoading,
    setAgent,
    setModel,
    setSkillIds,
    toggleKb,
    setKbIds
  }
})
