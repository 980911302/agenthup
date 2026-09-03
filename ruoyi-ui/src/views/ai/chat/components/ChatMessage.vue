<template>
  <!-- 用户消息:右对齐气泡 -->
  <div v-if="turn.userMsg" class="chat-msg chat-msg--user" :data-message-id="turn.userMsg.messageId">
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
  <div v-if="turn.steps.length > 0 || !turn.completed || turn.terminalMessage" class="chat-msg chat-msg--assistant" :data-message-id="turn.userMsg && turn.userMsg.messageId">
    <div class="chat-msg__avatar chat-msg__avatar--assistant">{{ assistantEmoji }}</div>
    <div class="chat-msg__content">
      <!-- 过程节点(思考+工具+子agent,折叠) -->
      <ProcessNode :steps="turn.steps" :completed="turn.completed" />

      <!-- 正文(最终回答):不套气泡,直接铺开 -->
      <div v-if="contentStep" class="chat-msg__answer">
        <MarkdownContent :text="contentStep.text" />
        <span v-if="contentStep.streaming" class="chat-typing"><i></i><i></i><i></i></span>
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

      <!-- 工具产出图片(生图等):放在回答之后，过程折叠区之外直接展示。
           留在 ProcessNode 里会被双层折叠藏住；放回答前则显得“插在最上头”。 -->
      <ToolImages :attachments="imageAttachments" />
      <ToolVideos :attachments="videoAttachments" />
      <ToolAudios :attachments="audioAttachments" />

      <!-- 变更属于本轮 Agent 的执行结果，贴在该轮回复下方，默认收起避免打断阅读。 -->
      <TurnChangesSummary
        :changes="turn.workspaceChanges || []"
        :truncated="!!turn.workspaceChangesTruncated"
      />

      <!-- 实时 token 在流式中也显示;复制/重生成仍等完成后出现 -->
      <div v-if="turn.usage && !(turn.completed && contentStep)" class="chat-msg__actions is-live">
        <span class="chat-msg__usage" :title="usageTitle">
          {{ formatTokens(turn.usage.totalTokens) }} tok
          <template v-if="turn.usage.callCount > 1"> · {{ turn.usage.callCount }} 次调用</template>
        </span>
      </div>
      <div v-if="turn.completed && contentStep" class="chat-msg__actions">
        <button type="button" class="chat-msg__act" @click="copyContent" :title="copied ? '已复制' : '复制回答'">
          {{ copied ? '✓ 已复制' : '复制' }}
        </button>
        <button
          v-if="canRegenerate"
          type="button"
          class="chat-msg__act"
          @click="$emit('regenerate', turn)"
        >
          重新生成
        </button>
        <span v-if="turn.usage" class="chat-msg__usage" :title="usageTitle">
          <span v-if="turn.usage.usageSource === '1'" class="chat-msg__approx">≈</span>
          {{ formatTokens(turn.usage.totalTokens) }} tok
          <template v-if="turn.usage.callCount > 1"> · {{ turn.usage.callCount }} 次调用</template>
        </span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import useUserStore from '@/store/modules/user'
import { STEP_TYPES } from '../types/chat'
import ProcessNode from './ProcessNode.vue'
import MarkdownContent from './MarkdownContent.vue'
import ToolImages from './ToolImages.vue'
import ToolVideos from './ToolVideos.vue'
import ToolAudios from './ToolAudios.vue'
import TurnChangesSummary from './TurnChangesSummary.vue'
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

const userStore = useUserStore()
const avatarLoadFailed = ref(false)
const userAvatar = computed(() => userStore.avatar || '')
const userDisplayName = computed(() => userStore.nickName || userStore.name || '我')
const userAvatarText = computed(() => userDisplayName.value.trim().charAt(0) || '我')

// 个人中心更换头像后立即恢复图片渲染，并让历史消息一起跟随当前个人头像。
watch(userAvatar, () => { avatarLoadFailed.value = false })

const showCitations = computed(() => {
  const t = props.turn || {}
  return (t.citationFiles && t.citationFiles.length)
    || (t.citationCount > 0)
    || (t.citationTotal > 0)
    || (t.citations && t.citations.length)
})

/** 消息时间:今天显示 HH:mm,昨天显示「昨天 HH:mm」,更早显示「M月D日 HH:mm」 */
const msgTime = computed(() => formatMsgTime(props.turn.userMsg && props.turn.userMsg.createTime))
function formatMsgTime(v) {
  if (!v) return ''
  // ISO 格式(带 T 和时区)原生可解析；老式 "yyyy-MM-dd HH:mm:ss" 兜底转斜杠
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

/**
 * 递归收集 TOOL 步骤产出的图片附件(含子智能体内部嵌套的步骤)。
 * 渲染提到消息层的原因见模板注释 —— 折叠区里会被双层折叠藏住。
 */
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

/**
 * 用户消息正文。
 *
 * 后端会把附件清单拼进消息正文一起发给模型(见 AiChatController.composeUserText)，
 * 那段是给模型看的；界面上附件已经用 chips 单独展示，这里剥掉，
 * 否则用户会看到自己发的话后面莫名多出一大段文件列表。
 */
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
  !props.turn.completed || props.turn.steps.some(s => s.streaming)
)

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
    // 非 HTTPS / 旧浏览器降级
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
  setTimeout(() => { copied.value = false }, 1500)
}
</script>

<style scoped lang="scss">
@use '../../../../assets/styles/ai-tokens.scss' as *;

.chat-msg {
  display: flex; gap: 12px;
  // 不再按百分比限宽：1920px 屏下 80% 会让行宽到 1120px，每行 90+ 字容易串行。
  // 改为按 em 限制正文本身的行宽（见 __bubble--user / __answer）。
  &--user { align-self: flex-end; flex-direction: row-reverse; max-width: 100%; }
  &__avatar {
    width: 30px; height: 30px; border-radius: 50%; flex-shrink: 0;
    display: flex; align-items: center; justify-content: center;
    overflow: hidden;
    font-size: $ai-fs-6; font-weight: 700; color: #fff;
    &--user { background: linear-gradient(135deg, $blue, #5E5CE6); }
    &--assistant { background: linear-gradient(135deg, $green, #2CB5C6); font-size: $ai-fs-3; }
    img { width: 100%; height: 100%; display: block; object-fit: cover; }
  }
  &__bubble {
    padding: 10px 15px; border-radius: 16px;
    font-size: $ai-fs-4; line-height: $ai-lh-base;
    &--user {
      // 2026 主流：用户消息从高饱和蓝底白字改为柔和浅色块 + 深色文字，
      // 降低视觉冲击，让对话主体（AI 回答）更突出。
      background: var(--ai-user-bubble, #E8F1FF);
      color: var(--ai-text, #1D1D1F);
      border-top-right-radius: 6px;
      box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
      white-space: pre-wrap; word-break: break-word; max-width: $ai-measure-user;
    }
  }
  // AI 回答不套气泡：容器本身就是浅白底，再画一层白底加边框是冗余的边界，
  // 还把正文困在框里。靠头像和留白区分说话人即可。
  // 行宽由外层 .chat-body__inner 统一限制并居中，这里占满剩余空间即可 ——
  // 再压一层 max-width 会让内容偏左、右侧空一大块。
  &__answer {
    font-size: $ai-fs-4; line-height: $ai-lh-base; color: $text;
    flex: 1; min-width: 0; padding-top: 3px;
  }
  &__content { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6px; }
  // 图片块在回答之后时略加顶距，与正文区分
  &__content :deep(.tool-images),
  &__content :deep(.tool-videos),
  &__content :deep(.tool-audios) { padding-top: 6px; }
  &__terminal {
    display: flex; flex-direction: column; gap: 3px;
    padding: 9px 11px; max-width: 680px;
    border-radius: 9px; background: rgba(255, 59, 48, 0.1);
    color: var(--ai-err-text, #9F2D27); font-size: $ai-fs-5;
    &.is-cancelled { background: rgba(142, 142, 147, 0.12); color: $ai-text3; }
    &.is-interrupted { background: rgba(255, 149, 0, 0.12); color: var(--ai-warn-text, #8A5700); }
    &-title { font-weight: 600; }
    &-text { line-height: 1.45; word-break: break-word; }
  }
  // 用户侧：气泡 + 附件 + 时间竖排，整体右对齐
  &__side { display: flex; flex-direction: column; align-items: flex-end; gap: 5px; min-width: 0; }
  &__time { font-size: 11px; line-height: 1; color: $gray; margin-top: 1px; font-variant-numeric: tabular-nums; }
  &__files { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 5px; max-width: $ai-measure-user; }
  &__file {
    display: inline-flex; align-items: center; gap: 5px;
    max-width: 200px; padding: 3px 8px;
    background: var(--ai-border); border-radius: 7px;
    font-size: $ai-fs-6; color: $ai-text3;
    overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    svg { flex-shrink: 0; }
  }
  &__actions {
    display: flex; align-items: center; gap: 12px;
    padding: 2px 2px; min-height: 22px;
    opacity: 0; transition: opacity 0.18s $ease;
  }
  &:hover &__actions { opacity: 1; }
  &__usage { font-size: $ai-fs-6; color: $ai-text3; font-variant-numeric: tabular-nums; }
  &__approx { color: $gray3; margin-right: 1px; }
  &__act {
    border: none; background: transparent; padding: 0; cursor: pointer;
    font-size: $ai-fs-6; color: $ai-text3; font-family: inherit;
    &:hover { color: $blue; }
  }
}

.chat-typing { display: inline-flex; gap: 4px; align-items: center; height: 22px;
  i { width: 6px; height: 6px; border-radius: 50%; background: var(--ai-gray2); animation: typing 1.2s infinite ease-in-out; }
  i:nth-child(2) { animation-delay: 0.2s; }
  i:nth-child(3) { animation-delay: 0.4s; }
}
@keyframes typing {
  0%, 60%, 100% { transform: translateY(0); opacity: 0.4; }
  30% { transform: translateY(-4px); opacity: 1; }
}
</style>
