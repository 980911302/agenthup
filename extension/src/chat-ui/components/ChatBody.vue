<template>
  <!-- 消息区 -->
  <div class="chat-body" ref="bodyRef" @scroll.passive="onBodyScroll">
    <!-- 内层限宽并居中:与底部输入区严格保持相同宽度 -->
    <div class="chat-body__inner">
      <!-- 首屏加载骨架 -->
      <div v-if="loading" class="chat-skeleton" aria-busy="true" aria-label="正在加载聊天记录">
        <div v-for="i in 3" :key="i" class="chat-skeleton__turn">
          <div class="chat-skeleton__ask">
            <span class="chat-skeleton__bar" style="width: 38%"></span>
          </div>
          <div class="chat-skeleton__reply">
            <span class="chat-skeleton__bar" style="width: 90%"></span>
            <span class="chat-skeleton__bar" style="width: 76%"></span>
            <span class="chat-skeleton__bar" style="width: 52%"></span>
          </div>
        </div>
      </div>

      <!-- 欢迎页：消息为空时由父级通过 #empty 插槽注入 -->
      <slot v-else-if="turns.length === 0" name="empty" />

      <!-- 分页:滚动到顶 / 点击按钮加载更早消息 -->
      <div v-if="turns.length" class="chat-load-older">
        <button
          v-if="hasMore"
          type="button"
          class="chat-load-older__btn"
          :disabled="loadingMore"
          @click="emit('load-older')"
        >
          <svg v-if="loadingMore" class="chat-load-older__spin" width="12" height="12" viewBox="0 0 16 16" fill="none"><circle cx="8" cy="8" r="6" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-dasharray="24 12"/></svg>
          <template v-else>
            <svg width="12" height="12" viewBox="0 0 14 14" fill="none"><path d="M7 12V3M3.5 6.5L7 3l3.5 3.5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </template>
          {{ loadingMore ? '加载中…' : '加载更早消息' }}
        </button>
        <span v-else class="chat-load-older__done">— 已到最早消息 —</span>
      </div>

      <!-- 消息列表(Turn 聚合):跨天自动插入日期分隔条 -->
      <template v-for="(t, i) in turns" :key="t.runId || t.userMsg?.messageId || i">
        <div v-if="showDateDivider(t, i)" class="chat-date">{{ dateLabel(t) }}</div>
        <ChatMessage
          :turn="t"
          :assistant-emoji="assistantEmoji"
          :is-last="i === turns.length - 1"
          :can-regenerate="i === turns.length - 1 && !streaming && !!agentId && !!currentSessionId && !!t.userMsg"
          :kb-ids="kbIds"
          :session-id="currentSessionId"
          @regenerate="emit('regenerate', $event)"
        />
      </template>
    </div>

    <!-- 用户上滑看历史时回到最新入口 -->
    <button
      v-if="showJumpLatest"
      type="button"
      class="chat-jump"
      title="回到最新"
      @click="scrollToBottom(true)"
    >
      <svg width="14" height="14" viewBox="0 0 14 14" fill="none"><path d="M7 2v9M3.5 7.5L7 11l3.5-3.5" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>
      <span>{{ streaming ? '正在生成…' : '回到最新' }}</span>
    </button>
  </div>
</template>

<script setup name="ChatBody">
import { ref, watch, nextTick, computed } from 'vue'
import { useScroll } from '../composables/useScroll'
import ChatMessage from './ChatMessage.vue'

const props = defineProps({
  turns: { type: [Array, Object], default: () => [] },
  assistantEmoji: { type: String, default: '🤖' },
  streaming: { type: Boolean, default: false },
  agentId: { type: [String, Number], default: null },
  currentSessionId: { type: [String, Number], default: null },
  kbIds: { type: Array, default: () => [] },
  hasMore: { type: Boolean, default: false },
  loadingMore: { type: Boolean, default: false },
  loading: { type: Boolean, default: false }
})
const emit = defineEmits(['regenerate', 'load-older', 'active-change'])

const safeTurns = computed(() => {
  if (Array.isArray(props.turns)) return props.turns
  if (props.turns && Array.isArray(props.turns.value)) return props.turns.value
  return []
})

function turnTime(t) {
  const raw = t.userMsg && t.userMsg.createTime
  if (!raw) return NaN
  let ts = new Date(raw).getTime()
  if (Number.isNaN(ts)) ts = new Date(String(raw).replace(/-/g, '/')).getTime()
  return ts
}

function dayKey(ts) {
  if (Number.isNaN(ts)) return null
  const d = new Date(ts)
  return `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`
}

function showDateDivider(t, i) {
  if (i === 0) return true
  const cur = dayKey(turnTime(t))
  if (cur == null) return false
  return cur !== dayKey(turnTime(props.turns[i - 1]))
}

function dateLabel(t) {
  const ts = turnTime(t)
  if (Number.isNaN(ts)) return ''
  const d = new Date(ts)
  const now = new Date()
  const same = (a, b) => a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate()
  if (same(d, now)) return '今天'
  const yest = new Date(now)
  yest.setDate(now.getDate() - 1)
  if (same(d, yest)) return '昨天'
  const md = `${d.getMonth() + 1}月${d.getDate()}日`
  return d.getFullYear() === now.getFullYear() ? md : `${d.getFullYear()}年${md}`
}

const { bodyRef, autoFollow, showJumpLatest, scrollToBottom, FOLLOW_THRESHOLD } = useScroll({
  getTurns: () => safeTurns.value
})

defineExpose({ scrollToBottom, preparePrepend, prependAdjustment, scrollToMessage, scrollByDelta })

function scrollByDelta(delta) {
  const el = bodyRef.value
  if (!el) return
  el.scrollBy({ top: delta, behavior: 'auto' })
}

function findUserNode(messageId) {
  const el = bodyRef.value
  if (!el) return null
  const id = String(messageId)
  let found = null
  el.querySelectorAll('.chat-msg--user[data-message-id]').forEach((n) => {
    if (!found && n.getAttribute('data-message-id') === id) found = n
  })
  return found
}

function computeActiveTurnId() {
  const el = bodyRef.value
  if (!el) return
  const nodes = el.querySelectorAll('.chat-msg--user[data-message-id]')
  if (!nodes.length) return
  if (el.scrollHeight - el.scrollTop - el.clientHeight <= FOLLOW_THRESHOLD) {
    emit('active-change', nodes[nodes.length - 1].getAttribute('data-message-id'))
    return
  }
  if (el.scrollTop <= 60) {
    emit('active-change', nodes[0].getAttribute('data-message-id'))
    return
  }
  const elTop = el.getBoundingClientRect().top
  const mid = el.clientHeight / 2
  let best = null
  let bestDist = Infinity
  for (const n of nodes) {
    const r = n.getBoundingClientRect()
    const d = Math.abs((r.top - elTop) + r.height / 2 - mid)
    if (d < bestDist) { bestDist = d; best = n }
  }
  if (best) emit('active-change', best.getAttribute('data-message-id'))
}

function scrollToMessage(messageId) {
  const el = bodyRef.value
  const node = findUserNode(messageId)
  if (!el || !node) return
  autoFollow.value = false
  showJumpLatest.value = true
  const elRect = el.getBoundingClientRect()
  const nodeRect = node.getBoundingClientRect()
  const targetTop = el.scrollTop + (nodeRect.top - elRect.top) - el.clientHeight / 2 + nodeRect.height / 2
  el.scrollTo({ top: Math.max(0, targetTop), behavior: 'smooth' })
  emit('active-change', String(messageId))
}

watch(
  () => safeTurns.value.map(t => (t.userMsg && t.userMsg.messageId) || '').join(','),
  () => { nextTick(computeActiveTurnId) }
)

let prependState = null
function preparePrepend() {
  const el = bodyRef.value
  if (!el) return
  prependState = { scrollTop: el.scrollTop, scrollHeight: el.scrollHeight }
}
function prependAdjustment() {
  const el = bodyRef.value
  if (!el || !prependState) return
  const state = prependState
  prependState = null
  nextTick(() => {
    el.scrollTop = el.scrollHeight - state.scrollHeight + state.scrollTop
  })
}

let autoLoadLock = false
function onBodyScroll() {
  const el = bodyRef.value
  if (!el) return
  const distance = el.scrollHeight - el.scrollTop - el.clientHeight
  autoFollow.value = distance <= FOLLOW_THRESHOLD
  showJumpLatest.value = !autoFollow.value && el.scrollHeight > el.clientHeight + 40
  computeActiveTurnId()
  if (el.scrollTop <= 60 && props.hasMore && !props.loadingMore && !autoLoadLock) {
    autoLoadLock = true
    emit('load-older')
    setTimeout(() => { autoLoadLock = false }, 800)
  }
}
</script>

<style scoped lang="scss">
@use '../ai-tokens.scss' as *;

/* 消息区:通栏沉浸式 */
.chat-body {
  position: relative;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 16px 20px 24px;
  background: transparent;

  &::-webkit-scrollbar { width: 6px; }
  &::-webkit-scrollbar-thumb {
    background: var(--ai-scrollbar);
    border-radius: 4px;
  }

  &__inner {
    max-width: $ai-content-max;
    margin: 0 auto;
    width: 100%;
    min-height: 100%;
    display: flex;
    flex-direction: column;
    gap: 22px;
  }
}

/* 加载更早 */
.chat-load-older {
  display: flex;
  justify-content: center;
  margin-bottom: 8px;

  &__btn {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 5px 14px;
    border: 1px solid var(--border);
    border-radius: 980px;
    background: var(--bg-elevated);
    font-family: inherit;
    font-size: 12px;
    color: var(--text-secondary);
    cursor: pointer;
    box-shadow: var(--shadow-card);
    transition: background 0.16s $ease, color 0.16s $ease, border-color 0.16s $ease;
    &:hover:not(:disabled) {
      color: var(--accent);
      border-color: var(--accent-border);
      background: var(--accent-weak);
    }
    &:disabled { opacity: 0.6; cursor: default; }
  }
  &__spin { animation: load-older-spin 0.9s linear infinite; }
  &__done { font-size: 11px; color: var(--text-tertiary); padding: 4px 0; letter-spacing: 0.5px; }
}
@keyframes load-older-spin { to { transform: rotate(360deg); } }

/* 骨架 */
.chat-skeleton {
  display: flex; flex-direction: column; gap: 28px; padding: 8px 0;
  &__turn { display: flex; flex-direction: column; gap: 12px; }
  &__ask { display: flex; justify-content: flex-end; }
  &__reply { display: flex; flex-direction: column; gap: 8px; }
  &__bar {
    display: block; height: 12px; border-radius: 6px;
    background: var(--ai-fill-3);
    animation: chat-skeleton-pulse 1.4s $ease infinite;
  }
  &__ask &__bar { height: 32px; border-radius: 16px; }
  &__reply &__bar:nth-child(2) { animation-delay: 0.15s; }
  &__reply &__bar:nth-child(3) { animation-delay: 0.3s; }
}
@keyframes chat-skeleton-pulse {
  0%, 100% { opacity: 0.35; }
  50% { opacity: 0.7; }
}
@media (prefers-reduced-motion: reduce) {
  .chat-skeleton__bar { animation: none; opacity: 0.45; }
}

/* 日期分隔条 */
.chat-date {
  align-self: center;
  font-size: 11px;
  color: var(--text-tertiary);
  background: var(--ai-fill-2);
  border: 1px solid var(--border);
  padding: 2px 10px;
  border-radius: 980px;
  margin: 4px 0;
  user-select: none;
}

/* 回到最新悬浮胶囊 */
.chat-jump {
  position: sticky;
  bottom: 8px;
  margin: 8px auto 0;
  width: fit-content;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px 6px 11px;
  border: 1px solid var(--border-strong);
  border-radius: 980px;
  background: var(--bg-elevated);
  box-shadow: var(--shadow-soft);
  font-size: 12px;
  font-weight: 500;
  color: var(--text-secondary);
  cursor: pointer;
  z-index: 10;
  backdrop-filter: blur(8px);
  transition: background 0.16s $ease, color 0.16s $ease, transform 0.14s $ease;
  &:hover {
    background: var(--accent-weak);
    color: var(--accent);
    border-color: var(--accent-border);
    transform: translateY(-1px);
  }
}
</style>
