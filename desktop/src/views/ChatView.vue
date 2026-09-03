<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, provide, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import SessionSidebar from '../components/SessionSidebar.vue'
import ChatBody from '../chat-ui/components/ChatBody.vue'
import ChatTimeline from '../chat-ui/components/ChatTimeline.vue'
import ChatWelcome from '../chat-ui/components/ChatWelcome.vue'
import MessageInput from '../chat-ui/components/MessageInput.vue'
import WorkspaceDrawer from '../components/WorkspaceDrawer.vue'
import SettingsModal from '../components/SettingsModal.vue'
import KbView from './KbView.vue'
import ResourceView from './ResourceView.vue'
import FilesView from './FilesView.vue'
import { useChatStore } from '../stores/chat'
import { useAuthStore } from '../stores/auth'
import { useThemeStore } from '../stores/theme'
import { useChatRun } from '../composables/useChatRun'
import { buildTurns } from '../composables/useTurnBuilder'
import { colorOf } from '../utils/ai-palette'
import { listAllAgent } from '../api/agent'
import { listModelOptions } from '../api/model'
import { listSkillOptions } from '../api/skill'
import { listDesktopKbs } from '../api/kb'
import {
  delSession, getSessionTimeline, getSessionUserMessages, listSession, saveSessionKbs
} from '../api/session'
import { uploadWorkspaceFile } from '../api/workspace'
import { getContextUsage, rollbackLastTurn } from '../api/chat'
import { createProject, deleteProject, getProject, listProject, updateProject } from '../api/project'
import ProjectDialog from '../components/ProjectDialog.vue'
import { genId } from '../utils/id'
import { confirmDanger, toast } from '../utils/confirm'
import { declare as declareClientTools } from '../chat/clientTools'

const router = useRouter()
const auth = useAuthStore()
const chat = useChatStore()
const theme = useThemeStore()

const currentMainView = ref('chat') // 'chat' | 'files' | 'kb' | 'resources'
const sidebarCollapsed = ref(false)
const conversationId = ref(genId())
const sessionPersisted = ref(false)
const sessions = ref([])
const sessionLoading = ref(false)
const timelineLoading = ref(false)
const timelineLoadingMore = ref(false)
const timelineHasMore = ref(false)
const contextUsage = ref(null)
const bodyRef = ref(null)
const inputRef = ref(null)
const workspaceOpen = ref(false)
const targetWorkspacePath = ref('')
const workspaceDrawerRef = ref(null)
const settingsOpen = ref(false)
const settingsTab = ref('profile')

const projects = ref([])
const projectSessions = ref({})
const expandedProjectIds = ref([])
const projectLoading = ref(false)
const pendingProjectId = ref(null)    // 当前新建对话要归属的项目
const projectDialogVisible = ref(false)
const editingProject = ref(null)      // null=新建
const projectDetailLoading = ref(false)

function openWorkspaceFile(filePath) {
  if (!filePath) return
  targetWorkspacePath.value = filePath
  workspaceOpen.value = true
  nextTick(() => {
    workspaceDrawerRef.value?.locateAndHighlight?.(filePath)
  })
}

provide('openWorkspaceFile', openWorkspaceFile)
// 生图/视频/音频附件走工作区下载接口,ToolImages 等通过 inject('sessionId') 拼 URL。
// 管理端在 useSessions 里 provide;desktop 之前漏了,会话 ID 为空时附件静默不加载。
provide('sessionId', conversationId)

function openSettings(tab = 'profile') {
  settingsTab.value = tab
  settingsOpen.value = true
}

const run = useChatRun({
  onError: (e) => toast(e.message || '对话失败'),
  onDone: () => {
    refreshContext()
    loadSessions()
    refreshUserMessages()
  },
  onRemoteTurn: (sessionId) => {
    if (sessionId === conversationId.value) reloadTimeline()
  }
})

const turns = run.turns
const streaming = computed(() => run.status.value === 'streaming')
const canSend = computed(() => !!chat.agentId && !streaming.value)

const headerTitle = computed(() => {
  if (streaming.value) return '正在生成…'
  const agent = chat.agent
  if (agent) return agent.name || '对话'
  return '对话'
})

const activeSession = computed(() => {
  return sessions.value.find(s => s.sessionId === conversationId.value)
    || Object.values(projectSessions.value).flat().find(s => s.sessionId === conversationId.value)
    || null
})

const activeSessionTitle = computed(() => {
  return activeSession.value?.title?.trim() || (sessionPersisted.value ? '未命名会话' : '新建对话')
})

const assistantEmoji = computed(() => '✦')
const agentColor = computed(() => colorOf(chat.agent?.agentCode || chat.agent?.name || ''))
const currentAgent = computed(() => chat.agent)

// 浏览器助手只给扩展端使用：它承载的是不可信网页内容，不应成为桌面端的默认选择。
// 管理员可继续在桌面端手动选择它，但新会话优先从其他可用智能体中挑选。
const BROWSER_AGENT_CODE = 'browser'

function defaultDesktopAgentId() {
  if (!chat.agents.length) return null
  const preferred = chat.agents.find(agent => agent.agentCode !== BROWSER_AGENT_CODE)
  return (preferred || chat.agents[0]).agentId
}

const connectionLabel = computed(() => {
  const s = run.connectionState.value
  if (s === 'connected' || s === 'online') return '在线'
  if (s === 'reconnecting' || s === 'pending') return '连接中'
  return '就绪'
})

const totalTurnsCount = computed(() => {
  return Array.isArray(turns.value) ? turns.value.length : 0
})

const headerSub = computed(() => {
  if (streaming.value) return '正在生成中…'
  if (totalTurnsCount.value > 0) return `${totalTurnsCount.value} 轮对话`
  return chat.agent?.description || 'AI 智能体'
})

function parseKbIds(raw) {
  if (Array.isArray(raw)) return raw.map(Number).filter(Boolean)
  if (!raw) return []
  return String(raw).split(',').map(s => Number(s.trim())).filter(Boolean)
}

function cleanUserText(text) {
  const marker = '\n\n[本次上传的文件'
  const index = String(text || '').indexOf(marker)
  return index >= 0 ? String(text).slice(0, index) : String(text || '')
}

async function loadAgents() {
  try {
    const res = await listAllAgent()
    chat.agents = (res.data || []).map(a => ({ ...a, name: a.agentName || a.name }))
    if (!chat.agentId) {
      const id = defaultDesktopAgentId()
      if (id != null) chat.setAgent(id)
    }
  } catch (_) {
    chat.agents = []
  }
}

async function loadRuntimeOptions() {
  const [models, skills] = await Promise.allSettled([listModelOptions(), listSkillOptions()])
  chat.models = models.status === 'fulfilled' ? (models.value.data || []) : []
  chat.skills = skills.status === 'fulfilled'
    ? (skills.value.data || []).filter(skill => String(skill?.visibility || '').toUpperCase() === 'PRIVATE')
    : []

  // 输入框只允许选择当前用户自己的技能；刷新资源后同步清掉已删除或非私有的旧选择。
  const ownedSkillIds = new Set(chat.skills.map(skill => Number(skill.skillId)).filter(Boolean))
  chat.setSkillIds((chat.skillIds || []).map(Number).filter(id => ownedSkillIds.has(id)))
}

async function loadKbs() {
  chat.kbLoading = true
  try {
    const res = await listDesktopKbs({ status: '0' })
    chat.kbs = res.data || []
  } catch (_) {
    chat.kbs = []
  } finally {
    chat.kbLoading = false
  }
}

async function loadProjects() {
  projectLoading.value = true
  try {
    const res = await listProject({ pageNum: 1, pageSize: 100 })
    projects.value = res.rows || []
    const availableIds = new Set(projects.value.map(project => String(project.projectId)))
    projectSessions.value = Object.fromEntries(
      Object.entries(projectSessions.value).filter(([projectId]) => availableIds.has(String(projectId)))
    )
    expandedProjectIds.value = expandedProjectIds.value.filter(projectId => availableIds.has(String(projectId)))
  } catch (_) {
    projects.value = []
  } finally {
    projectLoading.value = false
  }
}

function openCreateProject() {
  editingProject.value = null
  projectDialogVisible.value = true
}

async function openEditProject(p) {
  if (!p?.projectId || projectDetailLoading.value) return
  projectDetailLoading.value = true
  try {
    const res = await getProject(p.projectId)
    editingProject.value = res.data || res
    projectDialogVisible.value = true
  } catch (e) {
    toast(e.message || '项目详情加载失败')
  } finally {
    projectDetailLoading.value = false
  }
}

async function saveProject(data) {
  try {
    if (data.projectId) await updateProject(data.projectId, data)
    else await createProject(data)
    await loadProjects()
    projectDialogVisible.value = false
  } catch (e) {
    toast(e.message || '保存失败')
  }
}

async function removeProject(p) {
  const ok = await confirmDanger(
    '删除项目',
    `确定删除项目「${p.projectName}」吗？该项目下的所有会话将被级联删除，此操作不可撤销。`,
    { okLabel: '删除', cancelLabel: '取消' }
  )
  if (!ok) return
  try {
    await deleteProject(p.projectId)
    await loadProjects()
    expandedProjectIds.value = expandedProjectIds.value.filter(projectId => Number(projectId) !== Number(p.projectId))
    await loadSessions()
  } catch (e) {
    toast(e.message || '删除失败')
  }
}

async function toggleProject(projectId) {
  const id = Number(projectId)
  if (expandedProjectIds.value.includes(id)) {
    expandedProjectIds.value = expandedProjectIds.value.filter(item => item !== id)
    return
  }
  expandedProjectIds.value = [...expandedProjectIds.value, id]
  await loadProjectSessions(id)
}

async function newProjectChat(projectId) {
  const id = Number(projectId)
  if (!expandedProjectIds.value.includes(id)) {
    expandedProjectIds.value = [...expandedProjectIds.value, id]
  }
  await loadProjectSessions(id)
  newConversation(id)
}

async function loadSessions() {
  try {
    const res = await listSession({
      pageNum: 1, pageSize: 100,
      projectId: undefined
    })
    const rows = res.rows || []
    // 普通历史不混入项目会话；项目会话只显示在所属项目的展开区域。
    sessions.value = rows.filter(session => session.projectId == null)
    const lastId = localStorage.getItem('desktop_last_session_id')
    const target = sessions.value.find(s => s.sessionId === lastId) || sessions.value[0]
    if (target && !sessionPersisted.value) {
      await switchSession(target.sessionId, true)
    }
  } catch (_) {
    sessions.value = []
  }
}

async function loadProjectSessions(projectId) {
  try {
    const res = await listSession({ pageNum: 1, pageSize: 100, projectId })
    projectSessions.value = { ...projectSessions.value, [projectId]: res.rows || [] }
  } catch (e) {
    toast(e.message || '加载项目会话失败')
  }
}

async function reloadTimeline() {
  if (!conversationId.value) return
  timelineLoading.value = true
  try {
    const res = await getSessionTimeline(conversationId.value, { limit: 50 })
    const rows = Array.isArray(res.data) ? res.data : (res.data?.rows || res.rows || [])
    const turns = buildTurns(rows, res.specialEvents, res.runs || res.data?.runs)
    run.setTurns(turns)
    timelineHasMore.value = !!res.hasMore
  } catch (e) {
    toast(e.message || '加载会话失败')
  } finally {
    timelineLoading.value = false
  }
}

async function switchSession(sessionId, force = false) {
  if (!force && sessionId === conversationId.value && sessionPersisted.value) {
    currentMainView.value = 'chat'
    return
  }
  currentMainView.value = 'chat'
  conversationId.value = sessionId
  sessionPersisted.value = true
  try { localStorage.setItem('desktop_last_session_id', sessionId) } catch (_) {}
  const meta = sessions.value.find(s => s.sessionId === sessionId)
    || Object.values(projectSessions.value).flat().find(s => s.sessionId === sessionId)
  pendingProjectId.value = null
  const restoredAgentId = meta?.supervisorAgentId || meta?.agentId
  if (restoredAgentId) chat.setAgent(Number(restoredAgentId))
  chat.setKbIds(parseKbIds(meta?.kbIds))
  refreshContext()
  await reloadTimeline()
  await run.watchSession(sessionId)
  await run.recoverSession(sessionId)
}

function newConversation(projectId = null) {
  currentMainView.value = 'chat'
  conversationId.value = genId()
  sessionPersisted.value = false
  pendingProjectId.value = projectId == null ? null : Number(projectId)
  const defaultAgentId = defaultDesktopAgentId()
  if (defaultAgentId != null) chat.setAgent(defaultAgentId)
  chat.setKbIds([])
  chat.setSkillIds([])
  run.setTurns([])
  timelineHasMore.value = false
  contextUsage.value = null
}

async function sendMessage(data, rawAttachments) {
  if (!chat.agentId) {
    toast('请先选择智能体')
    return
  }
  const text = typeof data === 'string' ? data : (data?.content || '')
  const attachments = Array.isArray(data?.attachments) ? data.attachments : (rawAttachments || [])
  const kbIds = Array.isArray(data?.kbIds) ? data.kbIds : (chat.kbIds || [])
  // 调用方显式传数组就用传进来的(「重新生成」用它带回该轮的技能快照),
  // 否则回退到输入框当前 @ 选中的技能。
  const skillIds = Array.isArray(data?.skillIds) ? data.skillIds : (chat.skillIds || [])

  const payload = {
    sessionId: conversationId.value,
    agentId: chat.agentId,
    message: text,
    kbIds: kbIds,
    modelId: chat.modelId,
    skillIds: skillIds
  }
  const createdProjectId = pendingProjectId.value
  if (createdProjectId) payload.projectId = createdProjectId
  if (attachments?.length) payload.attachments = attachments
  bodyRef.value?.scrollToBottom(true)
  const result = await run.send(text, payload)
  if (!result) return
  // 后端对已删除/已停用/无权使用的技能是跳过而不是报错(重新生成旧轮次时技能可能早没了),
  // 但不能默默少用:告诉用户这轮少了几个,免得他以为技能生效了。
  const skipped = Array.isArray(result.skippedSkillIds) ? result.skippedSkillIds : []
  if (skipped.length) {
    toast(`有 ${skipped.length} 个技能已不可用（已删除或停用），本轮已跳过`)
  }
  // @ 技能是「本轮」语义:发送成功即清空。留着会让后续每一轮都重复注入一遍技能正文
  // (后端注入不落库,所以不会在历史里叠加,但每轮都要重新付一次 token)。
  // 失败时不清,用户可以直接重试。
  chat.setSkillIds([])
  const wasPersisted = sessionPersisted.value
  sessionPersisted.value = true
  pendingProjectId.value = null
  loadSessions()
  loadProjects()
  if (createdProjectId) loadProjectSessions(createdProjectId)
  if (wasPersisted) refreshUserMessages()
}

async function regenerate(turn) {
  if (streaming.value) return
  const t = turn || run.turns.value[run.turns.value.length - 1]
  const userText = cleanUserText(t?.userMsg?.content || '')
  if (!userText) return
  try {
    await rollbackLastTurn(conversationId.value, chat.agentId)
  } catch (e) {
    toast(e.message || '回滚失败，无法重新生成')
    return
  }
  run.setTurns(run.turns.value.slice(0, -1))
  // @ 技能发送后即被清空,这里要用该轮的技能快照(时间线接口按 runId 带回)重放,
  // 否则重新生成会丢掉原轮次 @ 过的技能。
  await sendMessage({ content: userText, skillIds: t?.skillIds || [] })
}

async function deleteSession(sessionId) {
  const ok = await confirmDanger('删除会话', '确定删除这个会话吗？对话记录将被移除，此操作不可撤销。', { okLabel: '删除', cancelLabel: '取消' })
  if (!ok) return
  try {
    await delSession(sessionId)
    sessions.value = sessions.value.filter(s => s.sessionId !== sessionId)
    if (conversationId.value === sessionId) newConversation()
  } catch (e) {
    toast(e.message || '删除失败')
  }
}

async function loadOlder() {
  if (timelineLoadingMore.value || streaming.value) return
  const turns = run.turns.value
  const beforeMessageId = turns[0]?.userMsg?.messageId
  if (!beforeMessageId) return
  timelineLoadingMore.value = true
  try {
    const res = await getSessionTimeline(conversationId.value, { limit: 50, beforeMessageId })
    const rows = res.data?.rows || res.data || res.rows || []
    const olderTurns = buildTurns(rows, res.specialEvents, res.runs || res.data?.runs)
    if (olderTurns.length) {
      run.turns.value = [...olderTurns, ...run.turns.value]
    }
    timelineHasMore.value = !!res.hasMore
  } catch (e) {
    toast(e.message || '加载历史失败')
  } finally {
    timelineLoadingMore.value = false
  }
}

function onLoadOlder() {
  bodyRef.value?.preparePrepend?.()
  loadOlder().finally(() => {
    bodyRef.value?.prependAdjustment?.()
  })
}

function uploadAttachment(file, item) {
  const projectId = pendingProjectId.value || activeSession.value?.projectId || null
  uploadWorkspaceFile(conversationId.value, file, null, projectId)
    .then((res) => {
      const d = res.data || {}
      item.path = d.path
      item.name = d.name || item.name
      item.mime = d.mime || item.mime
      item.size = d.size != null ? d.size : item.size
      item.uploading = false
      sessionPersisted.value = true
      if (projectId) loadProjectSessions(projectId)
      else loadSessions()
    })
    .catch((e) => {
      item.uploading = false
      toast('「' + item.name + '」上传失败：' + (e.message || '请重试'))
      inputRef.value?.dropPending(item)
    })
}

function onModelChange(id) {
  chat.setModel(id)
}

function onSkillsChange(ids) {
  chat.setSkillIds(ids)
}

function onKbsChange(ids) {
  chat.setKbIds(ids || [])
}

function usePrompt(p) {
  inputRef.value?.setText(p)
}

function useSkillFromResource(skillId) {
  const id = Number(skillId)
  if (!id) return
  chat.setSkillIds([...chat.skillIds, id])
  currentMainView.value = 'chat'
  nextTick(() => {
    inputRef.value?.focus()
  })
}

const activeTimelineId = ref(null)
const userMessages = ref([])

function onActiveTurnChange(id) {
  activeTimelineId.value = id
}

async function refreshUserMessages() {
  const sid = conversationId.value
  if (!sid || !sessionPersisted.value) {
    userMessages.value = []
    return
  }
  try {
    const res = await getSessionUserMessages(sid)
    userMessages.value = (res.data && Array.isArray(res.data) ? res.data : []) || []
    backfillTurnMessageIds()
  } catch (_) {
    userMessages.value = []
  }
}

watch([conversationId, sessionPersisted], refreshUserMessages)

watch([conversationId, sessionPersisted], ([id, persisted]) => {
  if (id && persisted) declareClientTools(id)
})

function backfillTurnMessageIds() {
  const byRunId = new Map()
  for (const m of userMessages.value) {
    if (m.runId && m.messageId != null) byRunId.set(String(m.runId), m)
  }
  if (!byRunId.size) return
  for (const t of run.turns.value) {
    if (!t.userMsg || t.userMsg.messageId != null || !t.runId) continue
    const hit = byRunId.get(String(t.runId))
    if (!hit) continue
    t.userMsg.messageId = hit.messageId
    if (!t.userMsg.createTime) t.userMsg.createTime = hit.createTime
  }
}

async function onTimelineJump(id) {
  if (jumpToLoaded(id)) return
  await refreshUserMessages()
  if (jumpToLoaded(id)) return
  if (!worthPagingBack(id)) {
    toast('暂时定位不到这条消息，稍后再试')
    return
  }
  let guard = 0
  while (guard++ < 40 && timelineHasMore.value) {
    await loadOlder()
    if (jumpToLoaded(id)) return
  }
  toast('目标消息在更早的历史中，未能定位')
}

function jumpToLoaded(id) {
  const hit = run.turns.value.some(t => t.userMsg && String(t.userMsg.messageId) === String(id))
  if (hit) bodyRef.value?.scrollToMessage(id)
  return hit
}

function worthPagingBack(id) {
  const loaded = run.turns.value
    .map(t => t.userMsg && t.userMsg.messageId)
    .filter(v => v != null)
    .map(Number)
  if (!loaded.length) return false
  return Number(id) < Math.min(...loaded)
}

let contextTimer = null
async function refreshContext() {
  if (!conversationId.value || !chat.agentId || !sessionPersisted.value) {
    contextUsage.value = null
    return
  }
  try {
    const res = await getContextUsage(conversationId.value, chat.agentId)
    contextUsage.value = res.data || null
  } catch (_) {
    contextUsage.value = null
  }
}

watch([() => chat.agentId, conversationId, sessionPersisted], () => {
  if (sessionPersisted.value) {
    refreshContext()
  } else {
    contextUsage.value = null
  }
})

watch(
  () => [...chat.kbIds],
  async (ids) => {
    if (!sessionPersisted.value) return
    try { await saveSessionKbs(conversationId.value, ids) } catch (e) {
      toast(e.message || '知识库选择未能保存')
    }
  }
)

async function doLogout() {
  const ok = await confirmDanger('退出登录', '确定要退出当前账号吗？', { okLabel: '退出', cancelLabel: '取消' })
  if (!ok) return
  await auth.logout()
  router.replace('/login')
}

onMounted(async () => {
  if (!auth.user) {
    try { await auth.fetchUser() } catch (_) {}
  }
  await Promise.all([loadAgents(), loadRuntimeOptions(), loadKbs(), loadSessions(), loadProjects()])
  if (sessionPersisted.value) {
    run.watchSession(conversationId.value)
    refreshContext()
  }
  contextTimer = setInterval(() => {
    if (sessionPersisted.value) refreshContext()
  }, 10000)
  window.addEventListener('keydown', onGlobalKeydown)
})

function onGlobalKeydown(e) {
  if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'n') {
    e.preventDefault()
    newConversation()
  }
  if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'b') {
    e.preventDefault()
    sidebarCollapsed.value = !sidebarCollapsed.value
  }
}

onBeforeUnmount(() => {
  clearInterval(contextTimer)
  window.removeEventListener('keydown', onGlobalKeydown)
})
</script>

<template>
  <div class="chat">
    <SessionSidebar
      :sessions="sessions"
      :current-session-id="conversationId"
      :loading="sessionLoading"
      :streaming="streaming"
      :user="auth.user"
      :collapsed="sidebarCollapsed"
      :active-nav="currentMainView"
      :projects="projects"
      :project-sessions="projectSessions"
      :expanded-project-ids="expandedProjectIds"
      @new="newConversation"
      @switch="switchSession"
      @delete="deleteSession"
      @logout="doLogout"
      @settings="openSettings('profile')"
      @open-files="currentMainView = 'files'"
      @open-kb="currentMainView = 'kb'"
      @open-resources="currentMainView = 'resources'"
      @toggle-collapse="sidebarCollapsed = !sidebarCollapsed"
      @create-project="openCreateProject"
      @new-project-chat="newProjectChat"
      @toggle-project="toggleProject"
      @edit-project="openEditProject"
      @delete-project="removeProject"
    />

    <main class="chat__main">
      <div :key="currentMainView" class="chat__view app-page-enter">
      <!-- 聊天主视图 -->
      <template v-if="currentMainView === 'chat'">
        <header class="chat__conversation-header">
          <button
            v-if="sidebarCollapsed"
            type="button"
            class="chat__sidebar-expand"
            title="展开侧栏 (⌘B)"
            aria-label="展开侧栏"
            @click="sidebarCollapsed = false"
          >
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <rect x="3" y="3" width="18" height="18" rx="2.5" stroke="currentColor" stroke-width="1.7"/>
              <path d="M9 3v18M13.5 9.2 16.3 12l-2.8 2.8" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </button>
          <strong class="chat__session-title" :title="activeSessionTitle">{{ activeSessionTitle }}</strong>
        </header>

        <div class="chat__stage">
          <!-- 悬浮工作区快捷按钮 - 右边中间 (仅在有会话聊天时显示) -->
          <button
            v-if="turns.length > 0"
            type="button"
            class="chat__workspace-fab"
            :class="{ 'is-active': workspaceOpen }"
            title="会话工作区 (文件列表与下载)"
            @click="workspaceOpen = !workspaceOpen"
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
              <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/>
            </svg>
            <span class="chat__workspace-fab-label">工作区</span>
          </button>
          <ChatBody
            ref="bodyRef"
            :turns="turns"
            :assistant-emoji="assistantEmoji"
            :streaming="streaming"
            :agent-id="chat.agentId"
            :current-session-id="conversationId"
            :kb-ids="chat.kbIds"
            :has-more="timelineHasMore"
            :loading-more="timelineLoadingMore"
            :loading="timelineLoading"
            @regenerate="regenerate"
            @load-older="onLoadOlder"
            @active-change="onActiveTurnChange"
          >
            <template #empty>
              <ChatWelcome
                :current-agent="null"
                :agent-color="agentColor"
                :user="auth.user"
                @use-prompt="usePrompt"
              >
                <template #input>
                  <MessageInput
                    ref="inputRef"
                    :streaming="streaming"
                    :can-send="canSend"
                    :models="chat.models"
                    :model-id="chat.modelId"
                    :skills="chat.skills"
                    :skill-ids="chat.skillIds"
                    :context-usage="contextUsage"
                    :show-meter="false"
                    :session-id="conversationId"
                    :connection-state="run.connectionState.value"
                    :kbs="chat.kbs"
                    :initial-kb-ids="chat.kbIds"
                    :kb-loading="chat.kbLoading"
                    @send="sendMessage"
                    @stop="run.abort"
                    @change-model="onModelChange"
                    @change-skills="onSkillsChange"
                    @change-kbs="onKbsChange"
                    @upload="uploadAttachment"
                    @manage-kb="currentMainView = 'kb'"
                  />
                </template>
              </ChatWelcome>
            </template>
          </ChatBody>

          <ChatTimeline
            :user-messages="userMessages"
            :active-message-id="activeTimelineId"
            @jump="onTimelineJump"
          />
        </div>

        <!-- 对话已有内容时：底部固定输入条；空状态时输入框在 ChatWelcome 的中央插槽中 -->
        <MessageInput
          v-if="turns.length > 0"
          ref="inputRef"
          :streaming="streaming"
          :can-send="canSend"
          :models="chat.models"
          :model-id="chat.modelId"
          :skills="chat.skills"
          :skill-ids="chat.skillIds"
          :context-usage="contextUsage"
          :show-meter="true"
          :session-id="conversationId"
          :connection-state="run.connectionState.value"
          :kbs="chat.kbs"
          :initial-kb-ids="chat.kbIds"
          :kb-loading="chat.kbLoading"
          @send="sendMessage"
          @stop="run.abort"
          @change-model="onModelChange"
          @change-skills="onSkillsChange"
          @change-kbs="onKbsChange"
          @upload="uploadAttachment"
          @manage-kb="currentMainView = 'kb'"
        />
      </template>

      <!-- 文件独立全屏主页面 (1:1 还原 Aivory 视觉: 存储配额与左右双栏预览) -->
      <template v-else-if="currentMainView === 'files'">
        <FilesView @open-chat="currentMainView = 'chat'" />
      </template>

      <!-- 知识库独立页面 (1:1 还原 Aivory 视觉) -->
      <template v-else-if="currentMainView === 'kb'">
        <KbView
          @open-chat="currentMainView = 'chat'"
          @kbs-changed="loadKbs"
        />
      </template>

      <!-- 资源库独立全屏主页面 (1:1 还原 Aivory 视觉: 我的技能与公共目录) -->
      <template v-else-if="currentMainView === 'resources'">
        <ResourceView
          @open-chat="currentMainView = 'chat'"
          @use-skill="useSkillFromResource"
          @skills-changed="loadRuntimeOptions"
        />
      </template>
      </div>
    </main>

    <WorkspaceDrawer
      ref="workspaceDrawerRef"
      :visible="workspaceOpen"
      :session-id="conversationId"
      :session-persisted="sessionPersisted"
      :project-id="pendingProjectId || activeSession?.projectId || null"
      :target-path="targetWorkspacePath"
      @close="workspaceOpen = false"
    />
    <SettingsModal
      :visible="settingsOpen"
      :initial-tab="settingsTab"
      @close="settingsOpen = false"
    />
    <ProjectDialog
      :visible="projectDialogVisible"
      :project="editingProject"
      @close="projectDialogVisible = false"
      @save="saveProject"
    />
  </div>
</template>

<style scoped lang="scss">
@use '../chat-ui/ai-tokens.scss' as *;

.chat {
  height: 100%;
  display: flex;
  background: var(--bg);
}

.chat__main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  padding: 0;
  overflow: hidden;
}

.chat__view {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.chat__conversation-header {
  height: 58px;
  flex: 0 0 58px;
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 0 32px;
  border-bottom: 1px solid var(--divider);
  background: color-mix(in srgb, var(--bg) 92%, var(--bg-raised));
}

.chat__sidebar-expand {
  width: 30px;
  height: 30px;
  flex: 0 0 30px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--text-tertiary);
  cursor: pointer;
  transition: color 140ms ease, background 140ms ease, transform 140ms ease;

  svg {
    width: 18px;
    height: 18px;
  }

  &:hover,
  &:focus-visible {
    color: var(--text);
    background: var(--bg-hover);
    outline: none;
  }

  &:active {
    transform: scale(0.94);
  }
}

.chat__session-title {
  max-width: min(560px, 60vw);
  overflow: hidden;
  color: var(--text);
  font-size: 16px;
  font-weight: 640;
  text-overflow: ellipsis;
  white-space: nowrap;
}


.chat__stage {
  position: relative;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

/* 工作区悬浮侧边按钮 - 右边垂直居中 */
.chat__workspace-fab {
  position: absolute;
  right: 0;
  top: 50%;
  transform: translateY(-50%);
  z-index: 20;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  width: 48px;
  padding: 14px 0;
  border-radius: 12px 0 0 12px;
  border: 1px solid var(--accent-border);
  border-right: none;
  background: var(--accent);
  color: #ffffff;
  cursor: pointer;
  box-shadow: -4px 0 20px rgba(37, 99, 235, 0.25), -2px 0 8px rgba(37, 99, 235, 0.15);
  transition: all 0.22s cubic-bezier(0.16, 1, 0.3, 1);

  &:hover {
    width: 54px;
    background: var(--accent-hover, #1d4ed8);
    box-shadow: -6px 0 28px rgba(37, 99, 235, 0.35), -2px 0 12px rgba(37, 99, 235, 0.2);
  }

  &.is-active {
    background: var(--bg-elevated);
    color: var(--accent);
    border-color: var(--accent-border);
    box-shadow: -4px 0 16px var(--accent-weak), -2px 0 6px var(--accent-weak);
  }
}

.chat__workspace-fab-label {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.5px;
  writing-mode: vertical-rl;
  text-orientation: mixed;
  line-height: 1;
}

</style>
