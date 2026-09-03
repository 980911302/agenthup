<template>
  <nav v-if="userTurns.length" class="chat-timeline" aria-label="消息导航">
    <!-- 音轨:全部「我的消息」一个竖条,可内部滚动 -->
    <div
      ref="trackRef"
      class="chat-timeline__track"
      @scroll.passive="onTrackScroll"
      @mousemove="onTrackMove"
      @mouseenter="cancelClear"
      @mouseleave="scheduleClear"
    >
      <button
        v-for="(m, i) in userTurns"
        :key="m.messageId"
        type="button"
        class="chat-timeline__bar"
        :class="{ 'is-active': i === activeIndex, 'is-hovered': i === hoveredIndex }"
        :style="{ width: barWidth(i) + 'px' }"
        :title="preview(m)"
        @click="emit('jump', m.messageId)"
        @mouseenter="onBarEnter(i)"
        @focus="onBarEnter(i)"
        @blur="scheduleClear"
      />
    </div>

    <!-- 轻量预览:只显示当前悬停这一个点的内容 -->
    <div
      v-if="curRow"
      class="chat-timeline__card"
      :style="previewStyle"
      @mouseenter="cancelClear"
      @mouseleave="scheduleClear"
    >
      <Transition name="card" mode="out-in">
        <button
          :key="curRow.key"
          type="button"
          class="chat-timeline__row is-current"
          :title="curRow.full"
          @click="emit('jump', curRow.key)"
        >
          <span class="chat-timeline__dot-mini" />
          <span class="chat-timeline__time">{{ curRow.time }}</span>
          <span class="chat-timeline__text">{{ curRow.text }}</span>
        </button>
      </Transition>
    </div>
  </nav>
</template>

<script setup name="ChatTimeline">
/**
 * 消息导航条(Rail)—— 音频音轨风格:
 * - 数据源是「全部我的消息」(独立接口,不受聊天区分页影响);
 * - 每条用户消息一个竖条,靠右对齐;鼠标放上当前条向左拉长,两侧变短;
 * - 音轨自身可滚动(几百条也不怕),点击跳转(目标未加载时父级会自动翻页定位)。
 */
import { ref, computed } from 'vue'

const props = defineProps({
  /** 全部用户消息:[{ messageId, content, createTime }] */
  userMessages: { type: Array, default: () => [] },
  /** 当前视口所在轮次的用户消息 id;null 表示暂无 */
  activeMessageId: { type: [String, Number], default: null }
})
const emit = defineEmits(['jump'])

/** 全部用户消息参与导航 */
const userTurns = computed(() =>
  (props.userMessages || []).filter(m => m.messageId != null)
)

const activeIndex = computed(() => {
  if (props.activeMessageId == null) return -1
  const id = String(props.activeMessageId)
  return userTurns.value.findIndex(m => String(m.messageId) === id)
})

// ---------------------------------------------------------------------------
// 悬停 / 波形(向左拉)
// ---------------------------------------------------------------------------
const trackRef = ref(null)
const hoveredIndex = ref(-1)
/** 预览卡片高(px):单条内容 */
const CARD_H = 56
/** 波形参数(宽度):当前条向左拉长 / 每远一格衰减 / 基础宽度 */
const PEAK = 40
const FALL = 10
const BASE = 8
let clearTimer = null

function scheduleClear() {
  clearTimeout(clearTimer)
  clearTimer = setTimeout(() => { hoveredIndex.value = -1 }, 160)
}
function cancelClear() {
  clearTimeout(clearTimer)
}

/** 波形:以 hover 条为中心向左拉长,两侧递减;未 hover 时全部等短 */
function barWidth(i) {
  if (hoveredIndex.value < 0) return BASE
  const d = Math.abs(i - hoveredIndex.value)
  if (d === 0) return PEAK
  return Math.max(BASE, PEAK - d * FALL)
}

/** 最近一次鼠标在音轨上的 y(滚动时重算用) */
let lastClientY = null
/**
 * 用户是否在「本次用户消息列表更新之后」真实移动过鼠标。
 * 切换/刷新会话时,DOM 重建会让浏览器对鼠标下的新 bar 自动触发 mouseenter,
 * 旧 lastClientY 也可能被滚动复用 —— 这些都算不得用户的悬停意图。
 * 只有 onTrackMove(真实 mousemove)才会授权 hover,杜绝一切残留。
 */
let userMoved = false

// 切换会话 / 用户消息列表变化时,旧索引对新列表无效,重置悬停状态
watch(() => props.userMessages, () => {
  hoveredIndex.value = -1
  lastClientY = null
  userMoved = false
})

/** 鼠标进入某个条:切换会话后的自动 mouseenter 没有伴随真实移动,一律忽略 */
function onBarEnter(i) {
  if (!userMoved) return
  hoveredIndex.value = i
}

/** 鼠标在音轨上移动:按 y 找最近条(真实移动 = 授权 hover) */
function onTrackMove(e) {
  userMoved = true
  lastClientY = e.clientY
  pickNearest(e.clientY)
}

/**
 * 音轨滚动(用户滚轮 / 高亮条自动滚入视口)后,条的位置变了,
 * 必须用「最后鼠标位置」重新找最近条,否则最宽的还是滚走的那条。
 */
function onTrackScroll() {
  // 只有用户真实移动过(且鼠标在音轨上)才允许滚动时重算,否则保持重置态
  if (userMoved && lastClientY != null) pickNearest(lastClientY)
}

function pickNearest(clientY) {
  const track = trackRef.value
  if (!track) return
  // 用实时 DOM 查询:Vue 的 v-for ref 数组在会话切换(列表重建)后顺序可能错乱,
  // 导致 hoveredIndex 对不上鼠标下的条(用户实测 bug)。DOM 顺序始终正确。
  const bars = track.querySelectorAll('.chat-timeline__bar')
  if (!bars.length) return
  let best = -1
  let bestDist = Infinity
  bars.forEach((el, i) => {
    const r = el.getBoundingClientRect()
    const d = Math.abs(clientY - (r.top + r.height / 2))
    if (d < bestDist) { bestDist = d; best = i }
  })
  if (best >= 0) hoveredIndex.value = best
}

/** 预览卡片跟随当前条,上下限内不越界 */
const previewStyle = computed(() => {
  const i = hoveredIndex.value
  const track = trackRef.value
  if (i < 0 || !track) return {}
  const el = track.querySelectorAll('.chat-timeline__bar')[i]
  if (!el) return {}
  // 卡片 absolute 相对 .chat-timeline(nav),而 bar 在 track 内;
  // 短音轨时 track 垂直居中,offsetTop(相对 track)与卡片基准(nav)会偏差。
  // 必须用视口坐标算相对 nav 的位置,卡片才和鼠标下的条精确对齐。
  const nav = track.parentElement
  if (!nav) return {}
  const navRect = nav.getBoundingClientRect()
  const elRect = el.getBoundingClientRect()
  let top = (elRect.top - navRect.top) + elRect.height / 2 - CARD_H / 2
  top = Math.max(6, Math.min(top, navRect.height - CARD_H - 6))
  return { top: `${top}px` }
})

// ---------------------------------------------------------------------------
// 预览:只显示当前悬停节点的内容
// ---------------------------------------------------------------------------
function preview(m) {
  const raw = (m.content || '').replace(/\s+/g, ' ').trim()
  return raw.length > 60 ? raw.slice(0, 60) + '…' : raw
}
const curRow = computed(() => {
  const m = userTurns.value[hoveredIndex.value]
  if (!m) return null
  const raw = m.createTime
  let time = ''
  if (raw) {
    const d = new Date(raw)
    if (!Number.isNaN(d.getTime())) {
      const p = n => String(n).padStart(2, '0')
      time = `${p(d.getHours())}:${p(d.getMinutes())}`
    }
  }
  const full = (m.content || '').replace(/\s+/g, ' ').trim()
  return {
    key: String(m.messageId),
    full,
    time,
    text: preview(m)
  }
})
</script>

<style scoped lang="scss">
@use '../../../../assets/styles/ai-tokens.scss' as *;

// 定位到消息区左侧 gutter,热区细窄不挡内容
.chat-timeline {
  position: absolute;
  top: 50%;
  left: 20px;
  transform: translateY(-50%);
  z-index: 8;
  pointer-events: none;
  // 关键:限制在消息区高度内,长会话的音轨才能内部滚动(否则会被内容撑到视口外);
  // flex 让短音轨垂直居中,长音轨由内部滚动
  height: 100%;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: flex-start;

  @media (max-width: 768px) { display: none; }

  // ---- 音轨:自身滚动 ----
  &__track {
    pointer-events: auto;
    position: relative;
    width: 46px;
    display: flex; flex-direction: column; align-items: flex-start;
    gap: 8px;
    max-height: calc(100% - 24px);
    overflow-y: auto;
    padding: 14px 0;
    border-radius: 16px;
    // 长会话仍可用滚轮/触控板滚动,但不画滚动条
    scrollbar-width: none;
    -ms-overflow-style: none;
    transition: background 0.2s $ease;
    &:hover { background: var(--ai-surface-glass); }
    &::-webkit-scrollbar { display: none; width: 0; height: 0; }
  }

  // 波形条:右对齐靠右,默认等短;悬停时当前条向左拉长、两侧递减
  &__bar {
    height: 14px;
    flex-shrink: 0;
    border: none;
    border-radius: 7px;
    background: var(--ai-border-4);
    cursor: pointer;
    padding: 0;
    transition: width 0.2s cubic-bezier(0.22, 0.61, 0.36, 1), background 0.18s $ease, box-shadow 0.18s $ease;
    &:hover, &.is-hovered {
      background: $blue;
      box-shadow: 0 0 8px rgba(10, 132, 255, 0.4);
    }
    &.is-active {
      background: linear-gradient(180deg, $blue, #5E5CE6);
      box-shadow: 0 0 6px rgba(10, 132, 255, 0.35);
    }
  }

  // ---- 轻量预览:单条(玻璃拟态) ----
  &__card {
    position: absolute;
    left: calc(100% + 12px);
    width: 280px;
    height: 56px;
    box-sizing: border-box;
    padding: 8px 6px;
    overflow: hidden;
    border-radius: 14px;
    background: color-mix(in srgb, var(--ai-card-bg, #fff) 78%, transparent);
    border: 1px solid color-mix(in srgb, var(--ai-border-2) 80%, transparent);
    box-shadow: 0 10px 32px var(--ai-hover-strong), inset 0 1px 0 color-mix(in srgb, #fff 30%, transparent);
    backdrop-filter: blur(18px) saturate(1.3);
    -webkit-backdrop-filter: blur(18px) saturate(1.3);
    pointer-events: auto;
  }
  &__row {
    width: 100%;
    height: 38px;
    display: flex; align-items: center; gap: 7px;
    padding: 0 10px;
    border: none; border-radius: 10px;
    background: color-mix(in srgb, var(--ai-user-bubble, #e8f1ff) 55%, transparent);
    font-family: $font; text-align: left; cursor: pointer;
    overflow: hidden;
    transition: background 0.13s $ease;
    &:hover { background: color-mix(in srgb, var(--ai-fill-2) 85%, transparent); }
  }
  &__dot-mini {
    width: 5px; height: 5px; flex-shrink: 0; border-radius: 50%;
    background: $blue;
    box-shadow: 0 0 5px rgba(10, 132, 255, 0.5);
  }
  &__time {
    flex-shrink: 0; width: 34px;
    font-size: 11px; color: $gray; font-variant-numeric: tabular-nums;
  }
  &__text {
    flex: 1; min-width: 0;
    font-size: 12.5px; color: $text; font-weight: 600; line-height: 1.35;
    overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  }

  // 单条轻过渡:先出后进,淡入 + 轻微位移
  :deep(.card-enter-active), :deep(.card-leave-active) {
    transition: opacity 0.12s ease, transform 0.14s $ease;
  }
  :deep(.card-enter-from) { opacity: 0; transform: translateX(5px); }
  :deep(.card-leave-to)   { opacity: 0; transform: translateX(4px); }
}
</style>
