<template>
  <!-- 消息区 -->
  <div class="chat-body" ref="bodyRef" @scroll.passive="onBodyScroll">
    <!-- 内层限宽并居中:限宽压在容器上而不是每条消息上,
         否则宽屏下内容全挤在左边、右侧空一大块 -->
    <div class="chat-body__inner">
      <!-- 首屏加载骨架：占位形状照着「用户问 + 助手答」排，
           让加载完成后的布局跳动尽量小；空态必须让位给它,否则会闪一下欢迎页 -->
      <div v-if="loading" class="chat-skeleton" aria-busy="true" aria-label="正在加载聊天记录">
        <div v-for="i in 3" :key="i" class="chat-skeleton__turn">
          <div class="chat-skeleton__ask">
            <span class="chat-skeleton__bar" style="width: 42%"></span>
          </div>
          <div class="chat-skeleton__reply">
            <span class="chat-skeleton__bar" style="width: 88%"></span>
            <span class="chat-skeleton__bar" style="width: 74%"></span>
            <span class="chat-skeleton__bar" style="width: 56%"></span>
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
        <span v-else class="chat-load-older__done">— 已到最早 —</span>
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

    <!-- 用户上滑看历史时,自动跟随会暂停,给一个回到最新的入口 -->
    <button
      v-if="showJumpLatest"
      type="button"
      class="chat-jump"
      title="回到最新"
      @click="scrollToBottom(true)"
    >
      <svg width="14" height="14" viewBox="0 0 14 14" fill="none"><path d="M7 2v9M3.5 7.5L7 11l3.5-3.5" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>
      {{ streaming ? '正在生成' : '回到最新' }}
    </button>
  </div>
</template>

<script setup name="ChatBody">
import { ref, watch, nextTick } from 'vue'
import { useScroll } from '../composables/useScroll'
import ChatMessage from './ChatMessage.vue'

const props = defineProps({
  turns: { type: Array, default: () => [] },
  assistantEmoji: { type: String, default: '🤖' },
  streaming: { type: Boolean, default: false },
  agentId: { type: [String, Number], default: null },
  currentSessionId: { type: [String, Number], default: null },
  kbIds: { type: Array, default: () => [] },
  /** 是否还有更早消息(时间线分页) */
  hasMore: { type: Boolean, default: false },
  loadingMore: { type: Boolean, default: false },
  /** 首屏加载中(切会话拉历史)。与 loadingMore(加载更早)区分开 */
  loading: { type: Boolean, default: false }
})
const emit = defineEmits(['regenerate', 'load-older', 'active-change'])

/** 取某轮消息的时间戳(用户消息优先) */
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

/** 与上一条消息不同天(或列表第一条)时显示分隔条 */
function showDateDivider(t, i) {
  if (i === 0) return true
  const cur = dayKey(turnTime(t))
  if (cur == null) return false
  return cur !== dayKey(turnTime(props.turns[i - 1]))
}

/** 今天 / 昨天 / M月D日 / YYYY年M月D日 */
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

// 滚动跟随只属于消息区：容器本体在这里，watch/阈值/回到最新都在 useScroll 内。
const { bodyRef, autoFollow, showJumpLatest, scrollToBottom, FOLLOW_THRESHOLD } = useScroll({
  getTurns: () => props.turns
})

// 父级在「用户发消息 / 切会话」时要强制回底，通过组件 ref 调用。
defineExpose({ scrollToBottom, preparePrepend, prependAdjustment, scrollToMessage, scrollByDelta })

/** 按增量滚动消息区:右侧 rail 上的滚轮事件转发到这里,外面滚动条跟着动 */
function scrollByDelta(delta) {
  const el = bodyRef.value
  if (!el) return
  el.scrollBy({ top: delta, behavior: 'auto' })
}

// ---------------------------------------------------------------------------
// 右侧消息导航(Rail)：滚动时把「当前视口所在的用户消息轮次」emit 给父级，
// 由父级渲染 ChatTimeline 圆点；点击圆点跳转通过本组件的 scrollToMessage。
// ---------------------------------------------------------------------------

/** 找到用户消息根节点(按 messageId 遍历,避免 selector 转义问题) */
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

/** 根据滚动位置计算当前视口所在轮次,emit 给父级高亮对应圆点 */
function computeActiveTurnId() {
  const el = bodyRef.value
  if (!el) return
  const nodes = el.querySelectorAll('.chat-msg--user[data-message-id]')
  if (!nodes.length) return
  // 贴底(或内容不满一屏)时高亮最新一条
  if (el.scrollHeight - el.scrollTop - el.clientHeight <= FOLLOW_THRESHOLD) {
    emit('active-change', nodes[nodes.length - 1].getAttribute('data-message-id'))
    return
  }
  // 触顶时明确高亮最早一条(此时中心线可能在中间轮次,不符合直觉)
  if (el.scrollTop <= 60) {
    emit('active-change', nodes[0].getAttribute('data-message-id'))
    return
  }
  // 否则取「中心线」附近最近的一条用户消息
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

/** 点击圆点跳转到对应轮次:平滑滚到该轮中央,并暂停自动跟随 */
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

// 轮次增减(新消息 / 分页 unshift)后重算高亮;内容不满一屏时滚动事件不触发,
// 这里兜底保证首条消息也能有高亮。
// 盯的是 messageId 序列而不是 length:本页实时发出的轮次要等落库后才被回填
// messageId,那一刻 data-message-id 才出现在 DOM 上、才轮得到它参与高亮,
// 而这个变化不改变轮次数量。
watch(
  () => props.turns.map(t => (t.userMsg && t.userMsg.messageId) || '').join(','),
  () => { nextTick(computeActiveTurnId) }
)

// ---- 加载更早:滚动位置补偿 ----
// 顶部插入新内容后,浏览器会保持 scrollTop 不变,导致视口内容整体下移。
// 记录加载前的滚动高度,渲染完成后把 scrollTop 补回差值,视口看起来不动。
let prependState = null

/** 发起加载前调用:记录当前滚动位置 */
function preparePrepend() {
  const el = bodyRef.value
  if (!el) return
  prependState = { scrollTop: el.scrollTop, scrollHeight: el.scrollHeight }
}

/** 新内容渲染完成后调用:补偿滚动位置 */
function prependAdjustment() {
  const el = bodyRef.value
  if (!el || !prependState) return
  const state = prependState
  prependState = null
  nextTick(() => {
    el.scrollTop = el.scrollHeight - state.scrollHeight + state.scrollTop
  })
}

// ---- 滚动到顶自动加载更早(带防抖,避免连续触发) ----
let autoLoadLock = false
function onBodyScroll() {
  const el = bodyRef.value
  if (!el) return
  const distance = el.scrollHeight - el.scrollTop - el.clientHeight
  autoFollow.value = distance <= FOLLOW_THRESHOLD
  showJumpLatest.value = !autoFollow.value && el.scrollHeight > el.clientHeight + 40
  computeActiveTurnId()
  // 触顶且还有更早时自动加载
  if (el.scrollTop <= 60 && props.hasMore && !props.loadingMore && !autoLoadLock) {
    autoLoadLock = true
    emit('load-older')
    setTimeout(() => { autoLoadLock = false }, 800)
  }
}
</script>

<style scoped lang="scss">
@use '../../../../assets/styles/ai-tokens.scss' as *;

/* 消息区 */
.chat-body {
  position: relative;
  flex: 1; min-height: 0; overflow-y: auto;
  // 信息密度优先：保留极轻的容器感(不是厚重卡片，也不是完全无界)，
  // 让长对话在宽屏下也能一眼看到内容边界。
  padding: 20px 22px 14px;
  background: var(--ai-surface-glass);
  border: 1px solid var(--ai-border);
  border-radius: 20px;
  box-shadow: 0 1px 3px var(--ai-fill-2);
  &::-webkit-scrollbar { width: 6px; } &::-webkit-scrollbar-thumb { background: var(--ai-border-4); border-radius: 3px; }

  // 限宽 + 居中放在这层：压在单条消息上会让内容全靠左、右侧空一大块
  &__inner {
    max-width: $ai-content-max;
    margin: 0 auto;
    min-height: 100%;
    display: flex; flex-direction: column; gap: 20px;
  }
}

/* 加载更早:顶部居中的小按钮 / 已到最早提示 */
.chat-load-older {
  display: flex; justify-content: center;
  &__btn {
    display: inline-flex; align-items: center; gap: 6px;
    padding: 6px 16px; border: 1px solid var(--ai-border-3);
    border-radius: 980px; background: var(--ai-card-bg);
    font-family: $font; font-size: $ai-fs-5; color: $ai-text3;
    cursor: pointer; transition: all 0.16s $ease;
    &:hover:not(:disabled) { color: $blue; border-color: rgba(10, 132, 255, 0.35); background: rgba(10, 132, 255, 0.05); }
    &:disabled { opacity: 0.6; cursor: default; }
  }
  &__spin { animation: load-older-spin 0.9s linear infinite; }
  &__done { font-size: $ai-fs-6; color: var(--ai-text3, #5A5C64); padding: 4px 0; }
}
@keyframes load-older-spin { to { transform: rotate(360deg); } }

/* 首屏加载骨架:形状对齐真实消息(右侧问、左侧答),减少加载完成后的布局跳动 */
.chat-skeleton {
  display: flex; flex-direction: column; gap: 28px; padding: 8px 0;
  &__turn { display: flex; flex-direction: column; gap: 12px; }
  &__ask { display: flex; justify-content: flex-end; }
  &__reply { display: flex; flex-direction: column; gap: 8px; }
  &__bar {
    display: block; height: 12px; border-radius: 6px;
    background: var(--ai-border-4, #3A3C44);
    /* 用透明度呼吸而不是位移扫光:后者在长列表上每帧都要重绘一大片 */
    animation: chat-skeleton-pulse 1.4s $ease infinite;
  }
  &__ask &__bar { height: 30px; border-radius: 15px; }
  /* 三条错开,避免整片同时明灭显得死板 */
  &__reply &__bar:nth-child(2) { animation-delay: 0.15s; }
  &__reply &__bar:nth-child(3) { animation-delay: 0.3s; }
}
@keyframes chat-skeleton-pulse {
  0%, 100% { opacity: 0.35; }
  50% { opacity: 0.7; }
}
/* 降低动效偏好下不闪烁,保留静态占位 */
@media (prefers-reduced-motion: reduce) {
  .chat-skeleton__bar { animation: none; opacity: 0.45; }
}

/* 日期分隔条:跨天消息之间插入,今天/昨天/日期居中显示 */
.chat-date {
  align-self: center;
  font-size: 11px; color: var(--ai-text3, #5A5C64);
  background: var(--ai-fill-2);
  padding: 3px 12px; border-radius: 980px;
  margin: 2px 0;
  user-select: none;
}

/* 回到最新:仅在用户上滑离开底部时出现。
   用 margin auto 居中而非 left:50%+transform —— sticky 元素仍在正常流里，
   left 的参照系是它自己的流位置，配 transform 容易偏。 */
.chat-jump {
  position: sticky; bottom: 2px;
  margin: 8px auto 0; width: fit-content;
  display: flex; align-items: center; gap: 5px;
  padding: 6px 13px 6px 10px; border: 1px solid var(--ai-fill-4);
  border-radius: 980px; background: var(--ai-jump-bg);
  backdrop-filter: blur(8px);
  box-shadow: 0 3px 14px var(--ai-hover-strong);
  font-family: $font; font-size: $ai-fs-6; color: $text2;
  cursor: pointer; z-index: 5;
  transition: background 0.16s $ease, color 0.16s $ease;
  &:hover { background: var(--ai-card-bg); color: $blue; }
}
</style>
