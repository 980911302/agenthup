<template>
  <div class="chat-page">
    <!-- 左侧会话列表 -->
    <SessionSidebar
      v-if="!sidebarCollapsed"
      :sessions="sessions"
      :current-session-id="currentSessionId"
      :loading="sessionLoading"
      :streaming="streaming"
      :session-type="sessionTypeFilter"
      @new="newConversation"
      @switch="switchSession"
      @delete="delSession"
      @filter="onSessionTypeFilter"
    />

    <!-- 右侧对话区 -->
    <div class="chat-main">
      <!-- 顶部工具栏 -->
      <ChatHeader
        :title="headerTitle"
        :sub="headerSub"
        :connection-label="connectionLabel"
        :connection-tone="connectionTone"
        :connection-tip="connectionTip"
        :streaming="streaming"
        :has-turns="turns.length > 0"
        :sidebar-collapsed="sidebarCollapsed"
        @toggle-workspace="onToggleWorkspace"
        @toggle-sidebar="sidebarCollapsed = !sidebarCollapsed"
        @new-chat="newConversation"
        @command="onHeaderCommand"
      />

      <!-- 消息舞台:chat-body 自身滚动,右侧消息导航条挂在不滚动的舞台上才固定 -->
      <div class="chat-stage">
        <!-- 消息区（含空状态欢迎页插槽与回到最新按钮） -->
        <ChatBody
          ref="bodyRef"
          :turns="turns"
          :assistant-emoji="assistantEmoji"
          :streaming="streaming"
          :agent-id="agentId"
          :current-session-id="currentSessionId"
          :kb-ids="sessionKbIds"
          :has-more="timelineHasMore"
          :loading-more="timelineLoadingMore"
          :loading="timelineLoading"
          @regenerate="regenerate"
          @load-older="onLoadOlder"
          @active-change="onActiveTurnChange"
        >
          <template #empty>
            <ChatWelcome
              :current-agent="currentAgent"
              :agent-color="agentColor"
              @use-prompt="usePrompt"
            />
          </template>
        </ChatBody>

        <!-- 右侧消息导航条:每条用户消息一个圆点,滚动定位 + 点击跳转 -->
        <ChatTimeline
          :user-messages="userMessages"
          :active-message-id="activeTimelineId"
          @jump="onTimelineJump"
        />
      </div>

      <!-- 输入区 -->
      <MessageInput
        ref="inputRef"
        :streaming="streaming"
        :can-send="canSend"
        :agents="agents"
        :agent-id="agentId"
        :context-usage="contextUsage"
        :session-id="conversationId"
        :connection-state="connectionState"
        :kbs="kbOptions"
        :kb-ids="sessionKbIds"
        :kb-loading="kbLoading"
        @send="sendMessage"
        @stop="stopStream"
        @change-agent="onAgentChange"
        @change-kbs="onKbsChange"
        @upload="uploadAttachment"
      />
    </div>

    <WorkspacePanel
      v-if="workspaceVisible"
      ref="workspaceRef"
      :session-id="currentSessionId"
      @close="workspaceVisible = false"
    />

    <TracePanel
      v-if="traceVisible"
      :session-id="currentSessionId"
      @close="traceVisible = false"
    />

  </div>
</template>

<script setup name="AiChat">
/**
 * AI 对话页 —— 编排层。
 *
 * <p>本文件只做三件事：
 * 1. 组装各 composable(useChatRun / useSessions / useAgent / useWorkspace / useChatMeta)；
 * 2. 定义它们之间的协作回调(sendMessage / uploadAttachment / regenerate / clearChat)；
 * 3. 向模板暴露绑定名。
 * 模板由 ChatHeader / ChatBody(+ChatWelcome 插槽) / MessageInput / SessionSidebar 拼装，
 * 每个区块的具体实现都在对应组件里。
 */
import { ref, computed, watch, getCurrentInstance, onMounted } from 'vue'
import { clearSession, rollbackLastTurn } from '@/api/ai/chat'
import { getSessionUserMessages, saveSessionKbs } from '@/api/ai/session'
import { listKbWorkbench } from '@/api/ai/kb'
import { uploadWorkspaceFile } from '@/api/ai/workspace'
import { useChatRun } from './composables/useChatRun'
import { useSessions } from './composables/useSessions'
import { useAgent } from './composables/useAgent'
import { useWorkspace } from './composables/useWorkspace'
import { useChatMeta } from './composables/useChatMeta'
import ChatHeader from './components/ChatHeader.vue'
import ChatBody from './components/ChatBody.vue'
import ChatTimeline from './components/ChatTimeline.vue'
import ChatWelcome from './components/ChatWelcome.vue'
import MessageInput from './components/MessageInput.vue'
import SessionSidebar from './components/SessionSidebar.vue'
import WorkspacePanel from './components/WorkspacePanel.vue'
import TracePanel from './components/TracePanel.vue'

const { proxy } = getCurrentInstance()
const inputRef = ref(null)
/** ChatBody 组件实例：发送/切会话时强制回底 */
const bodyRef = ref(null)

/**
 * 侧栏折叠 = 专注模式。本地记忆，刷新不丢；
 * 折叠时侧栏卸载，「展开侧栏 / 新对话」入口由 ChatHeader 补位。
 */
const sidebarCollapsed = ref(localStorage.getItem('ai.chat.sidebarCollapsed') === '1')
watch(sidebarCollapsed, (v) => {
  localStorage.setItem('ai.chat.sidebarCollapsed', v ? '1' : '0')
})

// ---------------------------------------------------------------------------
// 持久化运行 composable：WebSocket 只负责订阅，页面断开不会终止 Agent。
// 回调里引用的 sessionApi/workspace/meta/agent 在本函数之后才初始化，但事件都来自
// 异步通道(WebSocket / HTTP)，不会在 setup 同步阶段触发，闭包取值时已就绪。
// ---------------------------------------------------------------------------
const run = useChatRun({
  onDone: (doneEv) => {
    sessionApi.loadSessions({ silent: true })
    workspace.scheduleWorkspaceRefresh()
    // 本轮的 USER 行是执行器跑起来之后才落库的,发送那一刻拉音轨必然拉不到它。
    // 收尾时补一次:音轨补上这一条,顺带把 messageId 回填给页面上的这一轮。
    refreshUserMessages()
    // done 事件带 context 时直接更新刻度条
    if (doneEv && doneEv.context) {
      meta.contextUsage.value = {
        ...(meta.contextUsage.value || {}),
        ...doneEv.context
      }
    } else if (currentSessionId.value) {
      meta.loadContextUsage(currentSessionId.value, agent.agentId.value)
    }
  },
  onError: (err) => { proxy.$modal.msgError(err.message || '对话失败') },
  onEvent: (ev) => {
    if (ev.type === 'ui' && ev.name === 'workspace.changes') {
      workspace.notifyWorkspaceChanges(ev.payload)
    }
    if (ev.type === 'tool_end' && workspace.isWriteTool(ev.name)) workspace.scheduleWorkspaceRefresh()
  },
  // 别的标签页在同一会话里结束了一轮：以消息事实表为准重建，不猜流式内容。
  onRemoteTurn: (sessionId) => { sessionApi.reloadTimeline(sessionId) }
})

const workspace = useWorkspace()
const agent = useAgent({
  getStreaming: () => run.status.value === 'streaming',
  getHasTurns: () => run.turns.value.length > 0,
  // 确认切智能体后重置会话：闭包在回调触发时才求值，此时 sessionApi 已初始化。
  onStartNewConversation: () => sessionApi.newConversation()
})
const meta = useChatMeta({
  connectionState: run.connectionState
})
const sessionApi = useSessions({
  run,
  agent,
  // ChatBody 通过组件 ref 暴露滚动能力；useSessions 不关心容器细节，只拿回调。
  scrollToBottom: (force) => bodyRef.value?.scrollToBottom(force),
  meta,
  getTurnCount: () => run.turns.value.length
})

const streaming = computed(() => run.status.value === 'streaming')

// 模板绑定：解构出的 ref 直接可用（workspaceRef 由子组件回填）
const {
  turns, connectionState, setTurns, abort
} = run
const {
  sessions, filteredSessions, sessionSearch, sessionTypeFilter, currentSessionId, conversationId,
  sessionPersisted, sessionKbIds, sessionLoading, headerTitle, headerSub,
  loadSessions, onSessionTypeFilter, switchSession, newConversation,
  deleteSession: delSession, markSessionCreated, markSessionPersisted,
  loadOlderMessages, timelineHasMore, timelineLoadingMore, timelineLoading
} = sessionApi
const {
  agents, agentId, currentAgent, agentColor, assistantEmoji, canSend,
  changeAgent: onAgentChange
} = agent
const {
  workspaceVisible, workspaceRef
} = workspace
const {
  contextUsage, connectionLabel, connectionTone, connectionTip, resetContextUsage
} = meta

/** 链路追踪面板(会话轮次概览 + 单轮瀑布图) */
const traceVisible = ref(false)

/** 会话知识库可选项(服务端已只返回当前用户可访问);一次加载,供输入区下拉复用 */
const kbOptions = ref([])
const kbOptionsLoaded = ref(false)
const kbLoading = ref(false)

function onToggleWorkspace() {
  workspace.toggleWorkspace()
}

/** 右侧消息导航:当前视口所在的用户消息轮次 id */
const activeTimelineId = ref(null)
/** 全部用户消息(音轨全量数据,不受聊天区分页影响) */
const userMessages = ref([])

function onActiveTurnChange(id) {
  activeTimelineId.value = id
}

/**
 * 拉取会话内全部用户消息(音轨数据源)。
 *
 * <p>依赖 sessionPersisted 而不是只看 currentSessionId:新会话在首轮发送前,
 * currentSessionId 已经被占位 id 顶上了,但后端还没有这一行 —— 那时候请求必然
 * 拿到「会话不存在」,全局拦截器还会弹一个红条。等落库确认后再拉,
 * 顺带让新会话首轮发完就有音轨,不必切走再切回。
 */
async function refreshUserMessages() {
  const sid = currentSessionId.value
  if (!sid || !sessionPersisted.value) {
    userMessages.value = []
    return
  }
  try {
    const res = await getSessionUserMessages(sid)
    userMessages.value = (res.data && Array.isArray(res.data) ? res.data : []) || []
    backfillTurnMessageIds()
  } catch (_) {
    // 拉取失败不影响聊天,音轨退化为空
    userMessages.value = []
  }
}

watch([currentSessionId, sessionPersisted], refreshUserMessages)

/**
 * 把音轨里的真实 messageId 回填到本页实时产生的轮次上。
 *
 * <p>本页发出去的一轮,turn.userMsg 是 newTurn() 造的内存对象,只有 content,
 * 没有 messageId —— messageId 要等 ChatTurnRunner 落库才有,而流式收尾
 * (finishTurn)并不会把它带回来。结果是这一轮的 DOM 上没有 data-message-id:
 * 点音轨最新那条永远匹配不上、于是一路往前翻页,最后弹「目标消息在更早的
 * 历史中,未能定位」;滚动高亮也会漏掉这些轮次。
 *
 * <p>对齐键用 runId(音轨接口一并返回),不用内容或顺序:同一句话重复发、
 * 分页只加载了部分历史,这两种情况下顺序/内容对齐都会错位。
 */
function backfillTurnMessageIds() {
  const byRunId = new Map()
  for (const m of userMessages.value) {
    if (m.runId && m.messageId != null) byRunId.set(String(m.runId), m)
  }
  if (!byRunId.size) return
  for (const t of turns.value) {
    if (!t.userMsg || t.userMsg.messageId != null || !t.runId) continue
    const hit = byRunId.get(String(t.runId))
    if (!hit) continue
    t.userMsg.messageId = hit.messageId
    if (!t.userMsg.createTime) t.userMsg.createTime = hit.createTime
  }
}

/**
 * 点击音轨跳转:目标已在当前分页里 → 直接跳;
 * 否则往前翻页(复用时间线分页)直到加载到该轮,再跳。
 */
async function onTimelineJump(id) {
  if (jumpToLoaded(id)) return
  proxy.$modal.loading('正在定位历史消息…')
  try {
    // 先补一次回填:目标多半是本页刚聊出来的那几轮,它们已经在屏幕上,
    // 只是还没拿到 messageId —— 往前翻页找它是南辕北辙。
    await refreshUserMessages()
    if (jumpToLoaded(id)) return
    // 目标不在「更早」的方向上,翻页翻不出来,别白翻 40 页
    if (!worthPagingBack(id)) {
      proxy.$modal.msgWarning('暂时定位不到这条消息,稍后再试')
      return
    }
    let guard = 0
    while (guard++ < 40 && timelineHasMore.value) {
      await loadOlderMessages()
      if (jumpToLoaded(id)) return
    }
    proxy.$modal.msgWarning('目标消息在更早的历史中,未能定位')
  } finally {
    proxy.$modal.closeLoading()
  }
}

/** 目标已在当前 turns 里就滚过去,返回是否命中 */
function jumpToLoaded(id) {
  const hit = turns.value.some(t => t.userMsg && String(t.userMsg.messageId) === String(id))
  if (hit) bodyRef.value?.scrollToMessage(id)
  return hit
}

/**
 * 往前翻页找这条消息值不值得。
 *
 * <p>只有目标确实比已加载的最早一轮更早才值得。另外没有任何一轮带 messageId 时
 * 也是白翻 —— loadOlderMessages 的游标就取自「第一个带 messageId 的轮次」,
 * 找不到锚点它直接 return,循环会空转到 40 次上限。
 */
function worthPagingBack(id) {
  const loaded = turns.value
    .map(t => t.userMsg && t.userMsg.messageId)
    .filter(v => v != null)
    .map(Number)
  if (!loaded.length) return false
  return Number(id) < Math.min(...loaded)
}

function usePrompt(p) {
  inputRef.value?.setText(p)
}

/** 加载更早消息:先记录滚动位置,加载完成后补偿,视口不跳 */
function onLoadOlder() {
  bodyRef.value?.preparePrepend?.()
  loadOlderMessages().finally(() => {
    bodyRef.value?.prependAdjustment?.()
  })
}

async function sendMessage(text, attachments) {
  if (!agentId.value) {
    proxy.$modal.msgWarning('请先选择智能体')
    return
  }
  const payload = { sessionId: conversationId.value, agentId: agentId.value, message: text }
  if (attachments && attachments.length) payload.attachments = attachments
  // 会话级知识库选择：始终携带(空数组=清空)。首轮后端 create() 里随会话落库，
  // 老会话则是整组替换(服务端 requireKb 校验后删旧插新)。
  payload.kbIds = sessionKbIds.value
  // 新会话首轮发送后由后端建行，这里先占上 currentSessionId，让它出现在列表里、刻度条能刷新
  markSessionCreated()
  // 用户主动发消息 = 明确想看最新，强制回到底部并恢复跟随
  bodyRef.value?.scrollToBottom(true)
  const result = await run.send(text, payload)
  if (!result) return
  // 后端创建运行时已经持久化会话 —— 这是「会话确实存在」的确认点。
  // 新会话:sessionPersisted 由 false 翻真，watch 会拉一次音轨，这里不能再拉(否则重复请求)。
  // 老会话:标志本来就是真、不产生变化，watch 不触发，得显式刷新一次。
  // 注意这一拉未必带得回本轮 —— USER 行由执行器异步落库，跟这里是竞态;
  // 本轮进音轨的确定时点是 onDone 里那次刷新。
  const wasPersisted = sessionPersisted.value
  markSessionPersisted()
  if (wasPersisted) refreshUserMessages()
  // 新会话要立刻补进侧栏，切走后才找得回。
  // 已在列表里的老会话不必这时候拉 —— 本轮结束时那次刷新会带上新的标题和 token 数。
  // 注意 sessions 是 ref，必须 .value 取数组：直接 sessions.some(...) 会抛 TypeError，
  // 新会话反而永远补不进侧栏。
  if (!sessions.value.some(s => s.sessionId === currentSessionId.value)) {
    loadSessions({ silent: true })
  }
}

/**
 * 上传附件。文件先落到会话工作区，成功后把服务端返回的路径回填到 MessageInput 的
 * 待发送列表上 —— 真正告知模型是在发送那一刻，随消息的 attachments 一起过去。
 */
function uploadAttachment(file, item) {
  uploadWorkspaceFile(conversationId.value, file)
    .then((res) => {
      const d = res.data || {}
      item.path = d.path
      item.name = d.name || item.name
      item.mime = d.mime || item.mime
      item.size = d.size != null ? d.size : item.size
      item.uploading = false
      // 上传会顺带把会话行建出来（后端 requireOrCreateSession）。
      // 这里同步本地状态，否则侧边栏看不到这个会话，用户会以为文件传丢了。
      if (!currentSessionId.value) {
        markSessionCreated()
        loadSessions({ silent: true })
      }
      // 上传成功 = 后端这一行已经存在，无论会话是新是旧。
      markSessionPersisted()
      // 传完文件工作区就变了，面板开着的话刷一下
      workspace.scheduleWorkspaceRefresh()
    })
    .catch((e) => {
      item.uploading = false
      proxy.$modal.msgError('「' + item.name + '」上传失败：' + (e.message || '请重试'))
      // 失败的条目留在列表里会误导用户以为传成功了，摘掉它
      inputRef.value?.dropPending(item)
    })
}

/** 重新生成：回滚最后一轮消息后用原文重发 */
async function regenerate(turn) {
  const text = turn?.userMsg?.content
  if (!text || streaming.value) return
  try {
    await rollbackLastTurn(currentSessionId.value, agentId.value)
  } catch (e) {
    proxy.$modal.msgError('回滚失败，请重试')
    return
  }
  // 回滚成功后移除本地这一轮，再用原文重发
  turns.value.splice(turns.value.length - 1, 1)
  await sendMessage(text)
}

function stopStream() {
  abort()
}

/** 顶栏「更多」菜单 */
function onHeaderCommand(cmd) {
  if (cmd === 'traces') {
    traceVisible.value = true
  } else if (cmd === 'clear') {
    clearChat()
  }
}

/**
 * 加载当前用户可访问的知识库列表。
 *
 * <p>必须和“知识库”页面共用 workbench 接口：该接口会在 SQL 预筛后再按
 * {@code KbAccessPolicy} 校验，不能使用历史 /list 接口，否则会出现下拉可选、
 * 实际检索无权使用的假可见项。
 */
async function loadKbOptions() {
  if (kbOptionsLoaded.value || kbLoading.value) return
  kbLoading.value = true
  try {
    const res = await listKbWorkbench({ pageNum: 1, pageSize: 100 })
    kbOptions.value = (res.data?.rows || []).filter(k => k && k.kbId != null)
    kbOptionsLoaded.value = true
  } catch (_) {
    // 加载失败不影响聊天,下拉退化为空;下次打开时重试
  } finally {
    kbLoading.value = false
  }
}

/**
 * 输入区知识库多选变化(勾选/取消即时生效)。
 *
 * <p>已落库的会话直接调后端 PUT 整组替换;新会话(首轮前)后端还没有这一行,
 * 只更新本地 state —— 首条消息随 payload 一起由 create() 落库,两端保持同一份。
 * 失败(流中 requireNoActiveRun 拦截 / 网络)回滚勾选并提示。
 */
async function onKbsChange(ids) {
  const prev = sessionKbIds.value
  sessionKbIds.value = ids
  if (!conversationId.value || !(sessionPersisted.value && currentSessionId.value)) return
  try {
    await saveSessionKbs(currentSessionId.value, ids)
  } catch (e) {
    sessionKbIds.value = prev
    proxy.$modal.msgError('保存知识库失败：' + (e.message || '请重试'))
  }
}

function clearChat() {
  if (streaming.value) return
  proxy.$modal.confirm('确认清空当前对话？将同时清除后端记忆。').then(() => {
    return clearSession(conversationId.value)
  }).then(() => {
    setTurns([])
    userMessages.value = []
    resetContextUsage()
    proxy.$modal.msgSuccess('已清空')
  }).catch(() => {})
}

onMounted(() => {
  agent.loadAgents()
  sessionApi.loadSessions()
  loadKbOptions()
  // 会话监听不等 loadSessions：即使停在一个还没落库的新对话上，
  // 别处一旦在同一 sessionId 上开跑，这里也要立刻跟上。
  run.watchSession(conversationId.value)
})
</script>

<style scoped lang="scss">
@use '../../../assets/styles/ai-tokens.scss' as *;

.chat-page {
  font-family: $font;
  display: flex; flex-direction: row;
  height: calc(100vh - 84px);
  padding: 20px 32px 0;
  gap: 14px;
  // 沉浸式对话页：盖掉全局渐变光晕，用干净的极浅底色，
  // 让消息与输入区成为唯一视觉焦点（2026 主流做法）。
  background: var(--ai-page-base);
  -webkit-font-smoothing: antialiased;
  @media (max-width: 768px) { padding: 16px 16px 0; .session-sidebar { display: none; } .workspace-panel { display: none; } }
}

.chat-main { flex: 1; min-width: 0; display: flex; flex-direction: column; }

/* 消息舞台:包裹滚动消息区,自身不滚动 —— 右侧消息导航条 absolute 挂在这里才固定 */
.chat-stage {
  position: relative;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
</style>
