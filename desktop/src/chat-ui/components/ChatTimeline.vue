<template>
  <nav v-if="userTurns.length" class="chat-timeline" aria-label="消息导航">
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

    <!-- 轻量预览 -->
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
import { ref, computed, watch } from 'vue'

const props = defineProps({
  userMessages: { type: Array, default: () => [] },
  activeMessageId: { type: [String, Number], default: null }
})
const emit = defineEmits(['jump'])

const userTurns = computed(() =>
  (props.userMessages || []).filter(m => m.messageId != null)
)

const activeIndex = computed(() => {
  if (props.activeMessageId == null) return -1
  const id = String(props.activeMessageId)
  return userTurns.value.findIndex(m => String(m.messageId) === id)
})

const trackRef = ref(null)
const hoveredIndex = ref(-1)
const CARD_H = 56
const PEAK = 36
const FALL = 8
const BASE = 7
let clearTimer = null

function scheduleClear() {
  clearTimeout(clearTimer)
  clearTimer = setTimeout(() => { hoveredIndex.value = -1 }, 160)
}
function cancelClear() {
  clearTimeout(clearTimer)
}

function barWidth(i) {
  if (hoveredIndex.value < 0) return BASE
  const d = Math.abs(i - hoveredIndex.value)
  if (d === 0) return PEAK
  return Math.max(BASE, PEAK - d * FALL)
}

let lastClientY = null
let userMoved = false

watch(() => props.userMessages, () => {
  hoveredIndex.value = -1
  lastClientY = null
  userMoved = false
})

function onBarEnter(i) {
  if (!userMoved) return
  hoveredIndex.value = i
}

function onTrackMove(e) {
  userMoved = true
  lastClientY = e.clientY
  pickNearest(e.clientY)
}

function onTrackScroll() {
  if (userMoved && lastClientY != null) pickNearest(lastClientY)
}

function pickNearest(clientY) {
  const track = trackRef.value
  if (!track) return
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

const previewStyle = computed(() => {
  const i = hoveredIndex.value
  const track = trackRef.value
  if (i < 0 || !track) return {}
  const el = track.querySelectorAll('.chat-timeline__bar')[i]
  if (!el) return {}
  const nav = track.parentElement
  if (!nav) return {}
  const navRect = nav.getBoundingClientRect()
  const elRect = el.getBoundingClientRect()
  let top = (elRect.top - navRect.top) + elRect.height / 2 - CARD_H / 2
  top = Math.max(6, Math.min(top, navRect.height - CARD_H - 6))
  return { top: `${top}px` }
})

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
@use '../ai-tokens.scss' as *;

.chat-timeline {
  position: absolute;
  top: 50%;
  left: 14px;
  transform: translateY(-50%);
  z-index: 8;
  pointer-events: none;
  height: 100%;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: flex-start;

  @media (max-width: 860px) { display: none; }

  &__track {
    pointer-events: auto;
    position: relative;
    width: 42px;
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 7px;
    max-height: calc(100% - 32px);
    overflow-y: auto;
    padding: 14px 0;
    border-radius: 14px;
    scrollbar-width: none;
    transition: background 0.2s $ease;
    &:hover { background: var(--ai-fill-1); }
    &::-webkit-scrollbar { display: none; width: 0; height: 0; }
  }

  &__bar {
    height: 12px;
    flex-shrink: 0;
    border: none;
    border-radius: 6px;
    background: var(--ai-fill-3);
    cursor: pointer;
    padding: 0;
    transition: width 0.2s cubic-bezier(0.22, 0.61, 0.36, 1), background 0.18s $ease;
    &:hover, &.is-hovered {
      background: var(--accent);
      box-shadow: 0 0 8px var(--accent-weak);
    }
    &.is-active {
      background: var(--accent-gradient);
      box-shadow: 0 0 6px var(--accent-weak);
    }
  }

  &__card {
    position: absolute;
    left: calc(100% + 10px);
    width: 270px;
    height: 54px;
    box-sizing: border-box;
    padding: 6px;
    overflow: hidden;
    border-radius: 12px;
    background: var(--bg-elevated);
    border: 1px solid var(--border-strong);
    box-shadow: var(--shadow);
    backdrop-filter: blur(14px);
    pointer-events: auto;
  }
  &__row {
    width: 100%;
    height: 38px;
    display: flex;
    align-items: center;
    gap: 7px;
    padding: 0 10px;
    border: none;
    border-radius: 8px;
    background: var(--ai-fill-1);
    font-family: inherit;
    text-align: left;
    cursor: pointer;
    overflow: hidden;
    transition: background 0.13s $ease;
    &:hover { background: var(--ai-fill-2); }
  }
  &__dot-mini {
    width: 5px;
    height: 5px;
    flex-shrink: 0;
    border-radius: 50%;
    background: var(--accent);
  }
  &__time {
    flex-shrink: 0;
    width: 32px;
    font-size: 11px;
    color: var(--text-tertiary);
    font-variant-numeric: tabular-nums;
  }
  &__text {
    flex: 1;
    min-width: 0;
    font-size: 12px;
    color: var(--text);
    font-weight: 550;
    line-height: 1.35;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  :deep(.card-enter-active), :deep(.card-leave-active) {
    transition: opacity 0.12s ease, transform 0.14s $ease;
  }
  :deep(.card-enter-from) { opacity: 0; transform: translateX(-5px); }
  :deep(.card-leave-to)   { opacity: 0; transform: translateX(-4px); }
}
</style>
