<template>
  <div class="ctx-wrap">
    <button type="button" class="ctx-meter" :class="levelClass" :title="tooltipText" @click="toggle">
      <svg class="ctx-meter__ring" viewBox="0 0 18 18" width="18" height="18" aria-hidden="true">
        <circle
            class="ctx-meter__track"
            cx="9" cy="9" r="7"
            fill="none"
            stroke-width="3"
          />
          <circle
            class="ctx-meter__fill"
            cx="9" cy="9" r="7"
            fill="none"
            stroke-width="3"
            :stroke-dasharray="miniDash"
            transform="rotate(-90 9 9)"
          />
        </svg>
    </button>

    <Transition name="ctx-pop">
      <div v-if="open" class="ctx-meter-popper">
      <div class="ctx-panel">
      <div class="ctx-panel__head">
        <span class="ctx-panel__title">当前上下文</span>
        <span class="ctx-panel__nums">
          <span v-if="!trustworthy" class="ctx-panel__approx" title="含估算值">≈</span>
          {{ formatK(used) }} / {{ formatK(budget) }} · {{ percentDisplay }}%
        </span>
      </div>

      <div class="ctx-panel__donut-wrap">
        <svg class="ctx-panel__donut" viewBox="0 0 132 132" width="132" height="132">
          <!-- 底轨:全周 -->
          <circle
            cx="66" cy="66" r="52"
            fill="none"
            stroke="var(--ai-fill-4)"
            stroke-width="15"
          />
          <!-- 分段:按 budget 全周比例绘制 -->
          <g transform="rotate(-90 66 66)">
            <circle
              v-for="(seg, i) in donutSegments"
              :key="seg.key + '-' + i"
              cx="66" cy="66" r="52"
              fill="none"
              :stroke="seg.color"
              stroke-width="15"
              :stroke-dasharray="seg.dasharray"
              :stroke-dashoffset="seg.dashoffset"
              stroke-linecap="butt"
            />
          </g>
          <text x="66" y="62" text-anchor="middle" class="ctx-panel__center-pct">
            {{ percentDisplay }}%
          </text>
          <text x="66" y="78" text-anchor="middle" class="ctx-panel__center-label">
            当前已用
          </text>
        </svg>
      </div>

      <ul v-if="segments.length" class="ctx-panel__legend">
        <li v-for="(seg, i) in segments" :key="seg.key + '-' + i" class="ctx-panel__legend-row">
          <i class="ctx-panel__dot" :style="{ background: colorAt(i) }" />
          <span class="ctx-panel__legend-label">{{ seg.label }}</span>
          <span class="ctx-panel__legend-pct">{{ segmentPct(seg) }}%</span>
        </li>
      </ul>

      <div class="ctx-panel__summary">
        <div class="ctx-panel__summary-row">
          <span class="ctx-panel__summary-label">本会话总 Token</span>
          <span class="ctx-panel__summary-value">{{ formatK(spendTotal) }}</span>
        </div>
        <div class="ctx-panel__summary-row">
          <span class="ctx-panel__summary-label">Token 命中率</span>
          <span
            class="ctx-panel__summary-value"
            :class="{ 'is-success': cacheHitMetric?.tone === 'success' }"
          >{{ cacheHitRateDisplay }}</span>
        </div>
      </div>
    </div>
      </div>
    </Transition>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

/* ---- 弹出面板(替代后台版的 el-popover) ---- */
const open = ref(false)
function toggle() { open.value = !open.value }
function onDocClick(e) {
  if (open.value && !e.target.closest('.ctx-wrap')) open.value = false
}
onMounted(() => document.addEventListener('mousedown', onDocClick))
onBeforeUnmount(() => document.removeEventListener('mousedown', onDocClick))

/** 单一蓝色调七阶;数组下标取色,后端已按 tokens 降序 */
const SEGMENT_COLORS = [
  '#0A84FF', '#4A9EFF', '#6FB2FF', '#8FC4FF', '#AAD3FF', '#C2E0FF', '#D8ECFF'
]

const DONUT_R = 52
const DONUT_C = 2 * Math.PI * DONUT_R
const MINI_R = 7
const MINI_C = 2 * Math.PI * MINI_R
/** 段间 1px 视觉间隔对应的弧长(近似) */
const GAP = 1.2

const props = defineProps({
  /** { used, budget, threshold, percent, segments, ... } */
  usage: { type: Object, default: null }
})

const hasData = computed(() => !!props.usage && props.usage.budget > 0)

const used = computed(() => Number(props.usage?.used || 0))
const budget = computed(() => {
  const b = Number(props.usage?.budget)
  return Number.isFinite(b) && b > 0 ? b : 128000
})
const threshold = computed(() => Number(props.usage?.threshold || budget.value * 0.8))
const trustworthy = computed(() => props.usage?.trustworthy !== false)

const percent = computed(() => {
  if (props.usage?.percent != null) return Number(props.usage.percent)
  return budget.value > 0 ? (used.value * 100) / budget.value : 0
})

const percentDisplay = computed(() => {
  const p = percent.value
  return Number.isFinite(p) ? (Math.round(p * 10) / 10) : 0
})

/** 阈值相对 budget 的比例，与后端 compactThreshold 联动 */
const warnRatio = computed(() => {
  if (!budget.value) return 0.8
  return Math.min(0.99, Math.max(0.1, threshold.value / budget.value))
})

const levelClass = computed(() => {
  const p = percent.value / 100
  if (p >= warnRatio.value) return 'is-danger'
  if (p >= warnRatio.value * 0.75) return 'is-warn'
  return 'is-ok'
})

const miniDash = computed(() => {
  const p = Math.min(1, Math.max(0, percent.value / 100))
  const len = p * MINI_C
  return `${len} ${MINI_C}`
})

const segments = computed(() => {
  const raw = props.usage?.segments
  return Array.isArray(raw) ? raw.filter(s => Number(s?.tokens) > 0) : []
})

/** 累计消耗保留总量，不在客户端拆分主智能体与子智能体。 */
const spendTotal = computed(() => Number(props.usage?.spend?.totalTokens || 0))

/** 后端同时返回总、主、子三种命中率；客户端只展示全会话汇总值。 */
const cacheHitMetric = computed(() => {
  const raw = props.usage?.metrics
  if (!Array.isArray(raw)) return null
  return raw.find(metric => metric?.key === 'cacheHitRate') || null
})

const cacheHitRateDisplay = computed(() => {
  if (!cacheHitMetric.value) return '--'
  const value = Number(cacheHitMetric.value.value)
  return `${Number.isFinite(value) ? Math.round(value * 10) / 10 : 0}%`
})

const donutSegments = computed(() => {
  if (!budget.value || !segments.value.length) return []
  let offset = 0
  return segments.value.map((seg, i) => {
    const tokens = Number(seg.tokens) || 0
    let len = (tokens / budget.value) * DONUT_C
    // 段间留缝,避免浅色糊在一起;极短段不挖缝以免消失
    if (len > GAP * 2) len -= GAP
    const dasharray = `${Math.max(0, len)} ${Math.max(0, DONUT_C - len)}`
    const dashoffset = -offset
    offset += (tokens / budget.value) * DONUT_C
    return {
      key: seg.key || String(i),
      color: colorAt(i),
      dasharray,
      dashoffset
    }
  })
})

const tooltipText = computed(() => {
  if (!hasData.value) return ''
  return `上下文 ${percentDisplay.value}%`
})

function colorAt(i) {
  if (i < SEGMENT_COLORS.length) return SEGMENT_COLORS[i]
  return SEGMENT_COLORS[SEGMENT_COLORS.length - 1]
}

function segmentPct(seg) {
  if (!used.value) return '0'
  const p = (Number(seg.tokens) || 0) * 100 / used.value
  return (Math.round(p * 10) / 10).toFixed(1).replace(/\.0$/, '')
}

function formatK(n) {
  const v = Number(n) || 0
  if (v >= 1000000) return (v / 1000000).toFixed(1).replace(/\.0$/, '') + 'M'
  if (v >= 1000) return (v / 1000).toFixed(1).replace(/\.0$/, '') + 'K'
  return String(Math.round(v))
}
</script>

<style scoped lang="scss">
@use '../ai-tokens.scss' as *;

.ctx-meter {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  cursor: pointer;
  user-select: none;
  border-radius: 6px;
  transition: background 0.15s ease;

  &:hover {
    background: var(--ai-fill-3, rgba(0, 0, 0, 0.04));
  }

  &__ring {
    display: block;
  }
  &__track {
    stroke: var(--ai-fill-4);
  }
  &__fill {
    stroke: $blue;
    transition: stroke-dasharray 0.2s ease, stroke 0.15s ease;
  }
  &.is-ok .ctx-meter__fill { stroke: $blue; }
  &.is-warn .ctx-meter__fill { stroke: $orange; }
  &.is-danger .ctx-meter__fill { stroke: $red; }
}

.ctx-panel {
  font-family: $font;
  color: $text;
  padding: 2px 0;

  &__head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 8px;
    margin-bottom: 12px;
  }
  &__title {
    font-size: $ai-fs-5;
    font-weight: 600;
    color: $text;
  }
  &__nums {
    font-size: $ai-fs-6;
    color: $text2;
    font-variant-numeric: tabular-nums;
    white-space: nowrap;
  }
  &__approx {
    color: $gray3;
    margin-right: 1px;
  }

  &__donut-wrap {
    display: flex;
    flex-direction: column;
    align-items: center;
    margin-bottom: 14px;
  }
  &__donut {
    display: block;
  }
  &__center-pct {
    font-size: 18px;
    font-weight: 600;
    fill: $text;
    font-variant-numeric: tabular-nums;
  }
  &__center-label {
    font-size: 11px;
    fill: $text2;
  }

  &__legend {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: 6px;
  }
  &__legend-row {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: $ai-fs-6;
    line-height: $ai-lh-meta;
  }
  &__dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    flex-shrink: 0;
    border: 0.5px solid var(--border);
    box-sizing: border-box;
  }
  &__legend-label {
    flex: 1;
    color: $text2;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  &__legend-pct {
    color: $text;
    font-variant-numeric: tabular-nums;
    font-weight: 500;
  }

  &__summary {
    margin-top: 12px;
    padding-top: 10px;
    border-top: 1px solid var(--ai-fill-4);
    display: flex;
    flex-direction: column;
    gap: 7px;
  }
  &__summary-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    font-size: $ai-fs-6;
    line-height: $ai-lh-meta;
  }
  &__summary-label {
    color: $text2;
  }
  &__summary-value {
    color: $text;
    font-weight: 500;
    font-variant-numeric: tabular-nums;
  }
  &__summary-value.is-success {
    color: $green;
  }

}

/* 弹出容器:面板向上展开、右对齐 */
.ctx-wrap {
  position: relative;
  flex-shrink: 0;
}
.ctx-meter {
  border: none;
  background: transparent;
  padding: 0;
}
.ctx-meter-popper {
  position: absolute;
  bottom: calc(100% + 8px);
  right: 0;
  width: 300px;
  z-index: 70;
  background: var(--ai-card-bg);
  border: 1px solid var(--ai-border-3);
  border-radius: 12px;
  box-shadow: var(--shadow-card);
  padding: 14px 16px;
}
.ctx-pop-enter-active,
.ctx-pop-leave-active {
  transition: opacity 0.14s ease, transform 0.14s ease;
}
.ctx-pop-enter-from,
.ctx-pop-leave-to {
  opacity: 0;
  transform: translateY(4px);
}
</style>
