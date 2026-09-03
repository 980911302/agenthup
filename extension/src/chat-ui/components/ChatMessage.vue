<template>
  <!-- 用户消息:右对齐气泡 -->
  <div v-if="turn.userMsg" class="chat-msg chat-msg--user" :class="{ 'chat-msg--enter': isLast }" :data-message-id="turn.userMsg.messageId">
    <div class="chat-msg__avatar chat-msg__avatar--user" :title="userDisplayName">
      <img
        v-if="userAvatar && !avatarLoadFailed"
        :src="userAvatar"
        :alt="`${userDisplayName}的头像`"
        @error="avatarLoadFailed = true"
      />
      <span v-else aria-hidden="true">{{ userAvatarText }}</span>
    </div>
    <div class="chat-msg__side">
      <!-- 本轮 @ 的技能:注入的技能正文不落库(只进发送版),气泡里看不到任何痕迹,
           所以这里按 run 存下的技能快照显式标出来,否则用户不知道自己那次 @ 生没生效 -->
      <div v-if="turnSkills.length" class="chat-msg__skills">
        <span v-for="skill in turnSkills" :key="skill.id" class="chat-msg__skill">@{{ skill.name }}</span>
      </div>
      <div class="chat-msg__bubble chat-msg__bubble--user">{{ userText }}</div>
      <div v-if="msgTime" class="chat-msg__time" :title="turn.userMsg.createTime">{{ msgTime }}</div>
      <!-- 附件:文件在会话工作区,点开工作区面板可查看内容 -->
      <div v-if="turn.attachments && turn.attachments.length" class="chat-msg__files">
        <span v-for="(a, i) in turn.attachments" :key="i" class="chat-msg__file" :title="a.path">
          <svg v-if="a.mime && a.mime.startsWith('image/')" width="12" height="12" viewBox="0 0 16 16" fill="none"><rect x="1.8" y="2.8" width="12.4" height="10.4" rx="1.6" stroke="currentColor" stroke-width="1.3"/><circle cx="5.8" cy="6.4" r="1.1" stroke="currentColor" stroke-width="1.1"/><path d="M2.2 11.4l3.3-3 3 2.6 2-1.7 3.3 2.9" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/></svg>
          <svg v-else width="12" height="12" viewBox="0 0 16 16" fill="none"><path d="M9 1.8H4.4a1.2 1.2 0 0 0-1.2 1.2v10a1.2 1.2 0 0 0 1.2 1.2h7.2a1.2 1.2 0 0 0 1.2-1.2V5.6z" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/><path d="M9 1.8V5.6h3.8" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/></svg>
          {{ a.name }}
        </span>
      </div>
    </div>
  </div>

  <!-- 助手消息:左对齐,过程 + 正文 -->
  <div v-if="hasAssistantContent" class="chat-msg chat-msg--assistant" :class="{ 'chat-msg--enter': isLast }" :data-message-id="turn.userMsg && turn.userMsg.messageId">
    <div class="chat-msg__avatar chat-msg__avatar--assistant">{{ assistantEmoji }}</div>
    <div class="chat-msg__content">
      <!-- 过程节点(深度思考 + 工具调用 + 子智能体, 统一卡片体系) -->
      <ProcessNode :steps="turn.steps" :completed="turn.completed" />

      <!-- 正文(最终回答):不套气泡,直接铺开 -->
      <div v-if="contentStep" class="chat-msg__answer">
        <MarkdownContent :text="contentStep.text" />
        <span v-if="contentStep.streaming || (isStreaming && !turn.completed)" class="chat-typing-cursor"></span>
      </div>

      <!-- 引用:本轮知识库检索命中的片段,收在最终回答下方(可折叠) -->
      <CitationsView
        v-if="showCitations"
        :citations="turn.citations"
        :files="turn.citationFiles || []"
        :total="turn.citationCount || turn.citationTotal || (turn.citationFiles && turn.citationFiles.length) || 0"
        :kb-ids="kbIds"
        :session-id="sessionId || (turn.userMsg && turn.userMsg.sessionId)"
        :message-id="turn.userMsg && turn.userMsg.messageId"
      />

      <!-- 流式占位(还没出任何内容时) -->
      <div v-else-if="isStreaming && !hasProcess" class="chat-msg__answer">
        <span class="chat-typing"><i></i><i></i><i></i></span>
      </div>

      <!-- 失败/取消/节点中断属于持久化运行终态，不能继续显示“处理中”。 -->
      <div v-if="turn.terminalMessage" class="chat-msg__terminal" :class="`is-${String(turn.runStatus || '').toLowerCase()}`">
        <span class="chat-msg__terminal-title">{{ terminalTitle }}</span>
        <span class="chat-msg__terminal-text">{{ turn.terminalMessage }}</span>
      </div>

      <!-- 工具产出图片(生图等) -->
      <ToolImages :attachments="imageAttachments" />
      <ToolVideos :attachments="videoAttachments" />
      <ToolAudios :attachments="audioAttachments" />

      <!-- 变更属于本轮 Agent 的执行结果 -->
      <TurnChangesSummary
        :changes="fileChanges"
        :truncated="!!turn.workspaceChangesTruncated"
        :session-id="sessionId || (turn.userMsg && turn.userMsg.sessionId)"
      />

      <!-- 实时 token 在流式中也显示;复制/重生成仍等完成后出现 -->
      <div v-if="turn.usage && !(turn.completed && contentStep)" class="chat-msg__actions is-live">
        <span class="chat-msg__usage" :title="usageTitle">
          {{ formatTokens(turn.usage.totalTokens) }} tok
          <template v-if="turn.usage.callCount > 1"> · {{ turn.usage.callCount }} 次调用</template>
        </span>
      </div>
      <div v-if="turn.completed && contentStep" class="chat-msg__actions">
        <button
          type="button"
          class="chat-msg__icon-btn"
          :class="{ 'is-copied': copied }"
          :title="copied ? '已复制' : '复制回答'"
          @click="copyContent"
        >
          <svg v-if="copied" width="13" height="13" viewBox="0 0 16 16" fill="none"><path d="M3 8.5l3.5 3.5 6.5-7" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
          <svg v-else width="13" height="13" viewBox="0 0 16 16" fill="none"><path d="M5.5 2.5h6A1.5 1.5 0 0 1 13 4v7a1.5 1.5 0 0 1-1.5 1.5h-6A1.5 1.5 0 0 1 4 11V4a1.5 1.5 0 0 1 1.5-1.5z" stroke="currentColor" stroke-width="1.3"/><path d="M3 5.5H2.5A1.5 1.5 0 0 0 1 7v6A1.5 1.5 0 0 0 2.5 14.5h6A1.5 1.5 0 0 0 10 13v-.5" stroke="currentColor" stroke-width="1.3"/></svg>
        </button>
        <button
          v-if="canRegenerate"
          type="button"
          class="chat-msg__icon-btn"
          title="重新生成"
          @click="$emit('regenerate', turn)"
        >
          <svg width="13" height="13" viewBox="0 0 16 16" fill="none"><path d="M2.5 8a5.5 5.5 0 1 0 1.5-3.8L1.5 6.5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/><path d="M1.5 2.5v4h4" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </button>
        <span v-if="turn.usage" class="chat-msg__usage" :title="usageTitle">
          <span v-if="turn.usage.usageSource === '1'" class="chat-msg__approx">≈</span>
          {{ formatTokens(turn.usage.totalTokens) }} tok
          <template v-if="turn.usage.callCount > 1"> · {{ turn.usage.callCount }} 次</template>
        </span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, provide, ref, watch } from 'vue'
import { useAuthStore } from '../../stores/auth'
import { useChatStore } from '../../stores/chat'
import { STEP_TYPES } from '../../chat/types'
import ProcessNode from './ProcessNode.vue'
import MarkdownContent from './MarkdownContent.vue'
import ToolImages from './ToolImages.vue'
import ToolVideos from './ToolVideos.vue'
import ToolAudios from './ToolAudios.vue'
import TurnChangesSummary from './TurnChangesSummary.vue'
import { collectFileChangesFromSteps, mergeWorkspaceChanges } from '../composables/workspaceChanges'
import CitationsView from './CitationsView.vue'

const props = defineProps({
  turn: { type: Object, required: true },
  assistantEmoji: { type: String, default: '🤖' },
  isLast: { type: Boolean, default: false },
  canRegenerate: { type: Boolean, default: false },
  kbIds: { type: Array, default: () => [] },
  sessionId: { type: [String, Number], default: null }
})
defineEmits(['regenerate'])

// ToolImages / ToolVideos / ToolAudios 只 inject,不读本组件 props。
// ChatBody 已经把 currentSessionId 传进来,这里再 provide 一层,避免宿主页漏 provide 时生图空白。
const sessionIdRef = computed(() => props.sessionId)
provide('sessionId', sessionIdRef)

const auth = useAuthStore()
const chat = useChatStore()

/**
 * 本轮 @ 过的技能。id 来自 turn.skillIds:实时路径由 useChatRun.send 盖章,
 * 重进会话由时间线的 runs[runId].skillIds 还原(后端已减掉智能体自带技能)。
 * 技能事后被删时列表里查不到,直接跳过,不显示一个空壳。
 */
const turnSkills = computed(() => {
  const ids = Array.isArray(props.turn?.skillIds) ? props.turn.skillIds : []
  if (!ids.length) return []
  const all = chat.skills || []
  return ids
    .map(id => all.find(s => Number(s.skillId) === Number(id)))
    .filter(Boolean)
    .map(s => ({ id: s.skillId, name: s.skillName }))
})
const avatarLoadFailed = ref(false)
const avatarBase = import.meta.env.VITE_APP_BASE_API || '/dev-api'
const userAvatar = computed(() => {
  const raw = auth.user?.avatar || ''
  if (!raw || avatarLoadFailed.value) return ''
  return /^https?:\/\//.test(raw) || raw.startsWith('data:') ? raw : avatarBase + raw
})
const fileChanges = computed(() => {
  const explicit = props.turn.workspaceChanges || []
  const fromSteps = collectFileChangesFromSteps(props.turn.steps || [])
  return mergeWorkspaceChanges(explicit, fromSteps)
})

const userDisplayName = computed(() => auth.user?.nickName || auth.user?.userName || '我')
const userAvatarText = computed(() => userDisplayName.value.trim().charAt(0) || '我')

watch(userAvatar, () => { avatarLoadFailed.value = false })

const showCitations = computed(() => {
  const t = props.turn || {}
  return (t.citationFiles && t.citationFiles.length)
    || (t.citationCount > 0)
    || (t.citationTotal > 0)
    || (t.citations && t.citations.length)
})

const msgTime = computed(() => formatMsgTime(props.turn.userMsg && props.turn.userMsg.createTime))
function formatMsgTime(v) {
  if (!v) return ''
  let t = new Date(v).getTime()
  if (Number.isNaN(t)) t = new Date(String(v).replace(/-/g, '/')).getTime()
  if (Number.isNaN(t)) return ''
  const d = new Date(t)
  const now = new Date()
  const hm = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  const sameDay = d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth() && d.getDate() === now.getDate()
  if (sameDay) return hm
  const yest = new Date(now)
  yest.setDate(now.getDate() - 1)
  const isYest = d.getFullYear() === yest.getFullYear() && d.getMonth() === yest.getMonth() && d.getDate() === yest.getDate()
  if (isYest) return `昨天 ${hm}`
  return `${d.getMonth() + 1}月${d.getDate()}日 ${hm}`
}

function collectAttachmentsByType(steps, type, out) {
  for (const s of steps || []) {
    if (s.type === STEP_TYPES.TOOL && Array.isArray(s.attachments)) {
      for (const a of s.attachments) {
        if (a && a.type === type && a.path) out.push(a)
      }
    }
    if (s.type === STEP_TYPES.AGENT && Array.isArray(s.steps)) {
      collectAttachmentsByType(s.steps, type, out)
    }
  }
  return out
}
const imageAttachments = computed(() => collectAttachmentsByType(props.turn.steps, 'image', []))
const videoAttachments = computed(() => collectAttachmentsByType(props.turn.steps, 'video', []))
const audioAttachments = computed(() => collectAttachmentsByType(props.turn.steps, 'audio', []))

const ATTACH_MARK = '\n\n[本次上传的文件'
const userText = computed(() => {
  const raw = props.turn.userMsg?.content || ''
  const i = raw.indexOf(ATTACH_MARK)
  return i >= 0 ? raw.slice(0, i) : raw
})

const contentStep = computed(() =>
  props.turn.steps.find(s => s.type === STEP_TYPES.CONTENT && s.stepId === 'answer') ||
    [...props.turn.steps].reverse().find(s => s.type === STEP_TYPES.CONTENT)
)

const hasProcess = computed(() =>
  props.turn.steps.some(s => s.type !== STEP_TYPES.CONTENT)
)

const isStreaming = computed(() =>
  (props.isLast && !props.turn.completed) || (props.turn.steps && props.turn.steps.some(s => s.streaming))
)

const hasAssistantContent = computed(() => {
  if (props.turn.steps && props.turn.steps.length > 0) return true
  if (props.turn.terminalMessage) return true
  if (isStreaming.value) return true
  return false
})

const terminalTitle = computed(() => {
  if (props.turn.runStatus === 'CANCELLED') return '已停止'
  if (props.turn.runStatus === 'INTERRUPTED') return '执行中断'
  return '执行失败'
})

const usageTitle = computed(() => {
  const u = props.turn.usage
  if (!u) return ''
  const lines = [
    `输入 ${u.promptTokens || 0} · 输出 ${u.completionTokens || 0}`,
    u.modelName ? `模型 ${u.modelName}` : null,
    u.usageSource === '1' ? '用量为本地估算' : '用量来自上游真实返回'
  ].filter(Boolean)
  return lines.join('\n')
})

function formatTokens(n) {
  const v = Number(n) || 0
  return v >= 1000 ? (v / 1000).toFixed(1).replace(/\.0$/, '') + 'K' : String(v)
}

const copied = ref(false)
async function copyContent() {
  const text = contentStep.value?.text || ''
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
  } catch (e) {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    document.execCommand('copy')
    document.body.removeChild(ta)
  }
  copied.value = true
  setTimeout(() => { copied.value = false }, 1800)
}
</script>

<style scoped lang="scss">
@use '../ai-tokens.scss' as *;

.chat-msg__skills {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  justify-content: flex-end;
  margin-bottom: 4px;
}

.chat-msg__skill {
  font-size: 11px;
  line-height: 1.6;
  padding: 1px 8px;
  border-radius: 999px;
  color: var(--ai-accent, #6ea8fe);
  border: 1px solid currentColor;
  opacity: 0.75;
}


.chat-msg {
  display: flex;
  gap: 14px;
  max-width: 100%;

  &--user {
    align-self: flex-end;
    flex-direction: row-reverse;
  }

  &__avatar {
    width: 32px;
    height: 32px;
    border-radius: 50%;
    flex-shrink: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    overflow: hidden;
    font-size: 13px;
    font-weight: 700;
    color: #fff;
    box-shadow: var(--shadow-card);

    &--user {
      background: var(--accent-gradient);
    }
    &--assistant {
      background: linear-gradient(135deg, #10b981 0%, #06b6d4 100%);
      font-size: 16px;
    }
    img { width: 100%; height: 100%; display: block; object-fit: cover; }
  }

  &__bubble {
    padding: 11px 16px;
    border-radius: 16px;
    font-size: 14.5px;
    line-height: $ai-lh-base;

    &--user {
      background: var(--ai-user-bubble);
      color: var(--ai-user-bubble-text);
      border: 1px solid var(--border);
      border-top-right-radius: 4px;
      box-shadow: var(--shadow-card);
      white-space: pre-wrap;
      word-break: break-word;
      max-width: $ai-measure-user;
    }
  }

  &__answer {
    font-size: 14.5px;
    line-height: $ai-lh-base;
    color: var(--text);
    flex: 1;
    min-width: 0;
    padding-top: 2px;
  }

  &__content {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 6px;
  }

  &__content :deep(.tool-images),
  &__content :deep(.tool-videos),
  &__content :deep(.tool-audios) { padding-top: 6px; }

  &__terminal {
    display: flex;
    flex-direction: column;
    gap: 4px;
    padding: 10px 14px;
    border-radius: 10px;
    font-size: 13px;
    background: var(--danger-weak);
    border: 1px solid rgba(239, 68, 68, 0.25);
    color: var(--danger-text);

    &.is-cancelled {
      background: var(--ai-fill-2);
      border-color: var(--border);
      color: var(--text-tertiary);
    }
    &.is-interrupted {
      background: var(--warn-weak);
      border-color: rgba(245, 158, 11, 0.25);
      color: var(--warn-text);
    }
    &-title { font-weight: 600; }
    &-text { line-height: 1.5; word-break: break-word; }
  }

  &__side {
    display: flex;
    flex-direction: column;
    align-items: flex-end;
    gap: 5px;
    min-width: 0;
  }
  &__time {
    font-size: 11px;
    color: var(--text-tertiary);
    margin-top: 2px;
    font-variant-numeric: tabular-nums;
  }
  &__files {
    display: flex;
    flex-wrap: wrap;
    justify-content: flex-end;
    gap: 5px;
    max-width: $ai-measure-user;
  }
  &__file {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    max-width: 200px;
    padding: 3px 8px;
    background: var(--ai-fill-2);
    border: 1px solid var(--border);
    border-radius: 6px;
    font-size: 11.5px;
    color: var(--text-secondary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    svg { flex-shrink: 0; }
  }

  &__actions {
    display: flex;
    align-items: center;
    gap: 4px;
    margin-top: 5px;
    min-height: 26px;
    opacity: 0.75;
    transition: opacity 0.15s $ease;
  }
  &:hover &__actions,
  &__actions.is-live {
    opacity: 1;
  }

  &__icon-btn {
    width: 26px;
    height: 26px;
    border-radius: 6px;
    border: 1px solid transparent;
    background: transparent;
    color: var(--text-tertiary);
    display: inline-flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    transition: all 0.13s $ease;

    &:hover {
      background: var(--bg-hover);
      color: var(--text);
      border-color: var(--border);
    }

    &.is-copied {
      color: var(--ok, #10b981);
    }
  }

  &__usage {
    font-family: $mono;
    font-size: 11px;
    color: var(--text-tertiary);
    margin-left: 5px;
    font-variant-numeric: tabular-nums;
    user-select: none;
  }
  &__approx { color: var(--text-tertiary); margin-right: 1px; }
}

.chat-typing {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  height: 24px;
  i {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: var(--accent);
    animation: typing 1.2s infinite ease-in-out;
  }
  i:nth-child(2) { animation-delay: 0.2s; }
  i:nth-child(3) { animation-delay: 0.4s; }
}
@keyframes typing {
  0%, 60%, 100% { transform: translateY(0); opacity: 0.35; }
  30% { transform: translateY(-4px); opacity: 1; }
}

.chat-typing-cursor {
  display: inline-block;
  width: 7px;
  height: 15px;
  margin-left: 4px;
  vertical-align: -2px;
  background: var(--accent);
  border-radius: 1.5px;
  animation: typing-blink 0.75s infinite;
}
@keyframes typing-blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}
</style>
