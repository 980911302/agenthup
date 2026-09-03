<template>
  <el-dialog
    :model-value="true"
    width="1200px"
    class="trace-dialog"
    append-to-body
    destroy-on-close
    :close-on-click-modal="false"
    @close="$emit('close')"
  >
    <template #header>
      <span class="trace-dialog__title">对话链路</span>
    </template>

    <div class="trace-dialog__body" v-loading="loading">
      <!-- 空态 -->
      <div v-if="!sessionId" class="trace-empty">当前会话尚未开始</div>
      <div v-else-if="!loading && !overview.length" class="trace-empty">暂无链路数据</div>

      <!-- 轮次概览 -->
      <template v-else-if="!current">
        <div class="trace-overview">
          <div
            v-for="r in overview"
            :key="r.runId"
            class="trace-run"
            :class="{ 'is-failed': r.status === 'failed' }"
            @click="openRun(r.runId)"
          >
            <span class="trace-run__time">{{ fmtTime(r.startedAt) }}</span>
            <span class="trace-run__stats">
              <span class="tp-stat"><i class="tp-dot tp-dot--llm"></i>{{ r.llmCount || 0 }}</span>
              <span class="tp-stat"><i class="tp-dot tp-dot--tool"></i>{{ r.toolCount || 0 }}</span>
              <span v-if="r.subagentCount" class="tp-stat"><i class="tp-dot tp-dot--agent"></i>{{ r.subagentCount }}</span>
            </span>
            <span class="trace-run__dur">{{ fmtMs(r.durationMs) }}</span>
            <span class="trace-run__tok">{{ fmtTok(r.totalTokens) }} tok</span>
            <svg class="trace-run__arrow" width="14" height="14" viewBox="0 0 14 14" fill="none"><path d="M5 3l4 4-4 4" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </div>
        </div>
      </template>

      <!-- 单轮:画布流程图 -->
      <template v-else>
        <div class="trace-head">
          <button type="button" class="trace-back" @click="current = null" title="返回">
            <svg width="12" height="12" viewBox="0 0 14 14" fill="none"><path d="M9 3L5 7l4 4" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </button>
          <span class="trace-head__time">{{ fmtTime(current.startedAt) }}</span>
          <span class="trace-head__stats">
            <span class="tp-stat"><i class="tp-dot tp-dot--llm"></i>{{ stats.llm }}</span>
            <span class="tp-stat"><i class="tp-dot tp-dot--tool"></i>{{ stats.tool }}</span>
            <span v-if="stats.agent" class="tp-stat"><i class="tp-dot tp-dot--agent"></i>{{ stats.agent }}</span>
          </span>
          <span class="trace-head__dur">{{ fmtMs(stats.totalMs) }}</span>
          <span class="trace-head__tok">{{ fmtTok(stats.totalTokens) }} tok</span>
        </div>

        <!-- 树形节点列表 -->
        <div class="trace-tree">
          <div
            v-for="n in treeRows"
            :key="n.spanId"
            class="trow"
            :class="['is-' + n.spanType, { '_last': n._last, 'is-failed': n.status === 'failed' }]"
            :style="{ '--indent': (n._depth * 24 + 16) + 'px' }"
            @mouseenter="hoverNode = n"
            @mouseleave="hoverNode = null"
          >
            <button
              v-if="n.children.length"
              type="button"
              class="trow__toggle"
              :title="collapsed.has(n.spanId) ? '展开' : '折叠'"
              @click.stop="toggleNode(n)"
            >
              <svg v-if="collapsed.has(n.spanId)" width="10" height="10" viewBox="0 0 12 12" fill="none"><path d="M4.5 2.5l4 3.5-4 3.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
              <svg v-else width="10" height="10" viewBox="0 0 12 12" fill="none"><path d="M2.5 4.5l3.5 4 3.5-4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
            </button>
            <div class="trow__icon">
              <svg v-if="n.spanType === 'llm'" width="14" height="14" viewBox="0 0 16 16" fill="none"><path d="M8 1.6 9.7 6.3l4.7 1.7-4.7 1.7L8 14.4 6.3 9.7 1.6 8l4.7-1.7z" fill="currentColor"/></svg>
              <svg v-else-if="n.spanType === 'tool'" width="14" height="14" viewBox="0 0 16 16" fill="none"><circle cx="8" cy="8" r="2.6" fill="currentColor"/><path d="M8 1.6v2.2M8 12.2v2.2M1.6 8h2.2M12.2 8h2.2M3.8 3.8l1.5 1.5M10.7 10.7l1.5 1.5M12.2 3.8l-1.5 1.5M5.3 10.7l-1.5 1.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>
              <svg v-else-if="n.spanType === 'subagent'" width="14" height="14" viewBox="0 0 16 16" fill="none"><circle cx="3.2" cy="8" r="1.6" fill="currentColor"/><circle cx="12.8" cy="8" r="1.6" fill="currentColor"/><path d="M4.8 8h3.4a2.6 2.6 0 0 0 2.6-2.6V3M11.2 8v2.6A2.6 2.6 0 0 1 8.6 13H5.4" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/></svg>
              <svg v-else width="14" height="14" viewBox="0 0 16 16" fill="none"><circle cx="8" cy="8" r="2.2" fill="currentColor"/><path d="M8 1.6v2M8 12.4v2M1.6 8h2M12.4 8h2" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/></svg>
            </div>
            <span class="trow__name" :title="tipTitle(n)">{{ nameOf(n) }}</span>
            <span v-if="n.status === 'failed'" class="trow__fail" title="失败">失败</span>
            <span class="trow__meta">
              <span class="trow__dur">{{ fmtMs(n.durationMs) }}</span>
              <span v-if="n.totalTokens" class="trow__tok">{{ fmtTok(n.totalTokens) }}</span>
            </span>
          </div>

        </div>
      </template>
    </div>
  </el-dialog>
</template>

<script setup name="TracePanel">
/**
 * 对话链路弹框 —— 画布式流程图(参考 Dify workflow):
 * 节点卡片沿水平流程排布,并行工具垂直成组,贝塞尔连线;
 * 画布支持滚轮缩放 + 拖拽平移,节点悬停看详情。
 */
import { ref, computed, watch, nextTick } from 'vue'
import { getSessionTraces, getRunTrace } from '@/api/ai/session'


const props = defineProps({
  sessionId: { type: [String, Number], default: null }
})
defineEmits(['close'])

const loading = ref(false)
const overview = ref([])
const current = ref(null)
const spans = ref([])
const hoverNode = ref(null)


watch(() => props.sessionId, () => {
  current.value = null
  spans.value = []
  if (props.sessionId) loadOverview()
}, { immediate: true })

async function loadOverview() {
  if (!props.sessionId) return
  loading.value = true
  try {
    const res = await getSessionTraces(props.sessionId)
    overview.value = (res.data && Array.isArray(res.data) ? res.data : []) || []
  } finally {
    loading.value = false
  }
}

async function openRun(runId) {
  loading.value = true
  try {
    const res = await getRunTrace(props.sessionId, runId)
    const data = res.data || {}
    spans.value = data.spans || []
    current.value = overview.value.find(r => r.runId === runId) || {
      runId, startedAt: data.root && data.root.startedAt, durationMs: null, totalTokens: null
    }
    hoverNode.value = null
    collapsed.clear()
  } finally {
    loading.value = false
  }
}

// ---------------------------------------------------------------------------
// 树 → 流程步骤(折叠 turn/tool_batch,子智能体折叠内部)
// ---------------------------------------------------------------------------
const tree = computed(() => {
  const map = new Map()
  spans.value.forEach(s => map.set(s.spanId, { ...s, children: [] }))
  const roots = []
  spans.value.forEach(s => {
    const node = map.get(s.spanId)
    const parent = s.parentSpanId != null ? map.get(s.parentSpanId) : null
    if (parent) parent.children.push(node)
    else roots.push(node)
  })
  return roots
})

const turnNode = computed(() => root.value)

// 树构建:tool_batch 折叠(子提升),子智能体保留内部子树
const root = computed(() => {
  const turn = spans.value.find(x => x.spanType === 'turn')
  if (!turn) return null
  const map = new Map()
  spans.value.forEach(s => map.set(s.spanId, { ...s, children: [] }))
  spans.value.forEach(s => {
    const node = map.get(s.spanId)
    const parent = s.parentSpanId != null ? map.get(s.parentSpanId) : null
    if (parent) parent.children.push(node)
  })
  // 折叠 tool_batch:它的子(工具 + 后续思考/子智能体)提升到父级
  const convert = (nodes) => {
    const out = []
    nodes.forEach(n => {
      if (n.spanType === 'tool_batch') {
        out.push(...convert(n.children))
        return
      }
      n.children = convert(n.children)
      out.push(n)
    })
    return out
  }
  const t = map.get(turn.spanId)
  t.children = convert(t.children)
  return t
})

// 树形列表:展平带层级,支持折叠
const collapsed = ref(new Set())

/** 展平为行(带 isLast,画树线) */
const treeRows = computed(() => {
  const out = []
  const walk = (n, depth, isLast) => {
    out.push({ ...n, _depth: depth, _last: isLast })
    if (collapsed.value.has(n.spanId)) return
    n.children.forEach((c, i) => walk(c, depth + 1, i === n.children.length - 1))
  }
  const r = root.value
  if (r) walk(r, 0, true)
  return out
})

function toggleNode(n) {
  const set = new Set(collapsed.value)
  if (set.has(n.spanId)) set.delete(n.spanId)
  else set.add(n.spanId)
  collapsed.value = set
}

/** 统计(不含 tool_batch) */
const stats = computed(() => {
  const list = spans.value.filter(s => s.spanType !== 'tool_batch' && s.spanType !== 'turn')
  const totalMs = current.value && current.value.durationMs
    ? Number(current.value.durationMs)
    : (spans.value.length ? Math.round(timeRange.value.total) : 0)
  const totalTokens = current.value && current.value.totalTokens
    ? Number(current.value.totalTokens)
    : list.reduce((acc, s) => acc + (Number(s.totalTokens) || 0), 0)
  return {
    llm: list.filter(s => s.spanType === 'llm').length,
    tool: list.filter(s => s.spanType === 'tool').length,
    agent: list.filter(s => s.spanType === 'subagent').length,
    totalMs,
    totalTokens
  }
})

const timeRange = computed(() => {
  const list = spans.value
  if (!list.length) return { start: 0, total: 1 }
  const starts = list.map(s => new Date(s.startedAt).getTime()).filter(Number.isFinite)
  const ends = list.map(s => s.finishedAt ? new Date(s.finishedAt).getTime() : null).filter(Number.isFinite)
  const start = Math.min(...starts)
  const end = Math.max(...ends, ...starts)
  return { start, total: Math.max(1, end - start) }
})

function nodePct(n) {
  const d = Number(n.durationMs) || 0
  if (!d) return 0
  return Math.max(3, (d / timeRange.value.total) * 100)
}

// ---------------------------------------------------------------------------
// 画布交互:滚轮缩放 + 拖拽平移
// ---------------------------------------------------------------------------


// ---------------------------------------------------------------------------
// 悬浮详情
// ---------------------------------------------------------------------------
const tipX = computed(() => {
  const n = hoverNode.value
  return n ? Math.min(n._depth * 24 + 180, 640) : 0
})
const tipY = computed(() => {
  return 0
})
function tipColor(n) {
  if (n.spanType === 'llm') return 'llm'
  if (n.spanType === 'tool') return 'tool'
  if (n.spanType === 'subagent') return 'agent'
  return 'llm'
}
function tipTitle(n) {
  if (n.spanType === 'llm') return 'AI 思考'
  if (n.spanType === 'tool') return '工具 ' + (n.toolName || '')
  if (n.spanType === 'subagent') return '子智能体 ' + (n.toolName || '')
  return nameOf(n)
}
function nameOf(n) {
  if (n.spanType === 'turn') return '本轮'
  if (n.spanType === 'llm') return n.modelName || '模型调用'
  if (n.spanType === 'tool') return n.toolName || '工具调用'
  if (n.spanType === 'subagent') return n.toolName || '子智能体'
  return '节点'
}

function fmtMs(v) {
  const n = Number(v) || 0
  if (n < 1000) return n + 'ms'
  if (n < 60000) return (n / 1000).toFixed(1).replace(/\.0$/, '') + 's'
  return Math.floor(n / 60000) + 'm' + Math.round((n % 60000) / 1000) + 's'
}
function fmtTok(v) {
  const n = Number(v) || 0
  return n >= 1000 ? (n / 1000).toFixed(1).replace(/\.0$/, '') + 'K' : String(n)
}
function fmtTime(v) {
  if (!v) return ''
  const d = new Date(v)
  if (Number.isNaN(d.getTime())) return ''
  const p = x => String(x).padStart(2, '0')
  return `${p(d.getMonth() + 1)}月${p(d.getDate())}日 ${p(d.getHours())}:${p(d.getMinutes())}`
}
</script>

<style scoped lang="scss">
@use '../../../../assets/styles/ai-tokens.scss' as *;

:deep(.trace-dialog) {
  .el-dialog__header { padding-bottom: 2px; }
  .el-dialog__body { padding-top: 6px; }
}
.trace-dialog__title { font-size: $ai-fs-2; font-weight: 600; color: $text; }

.trace-dialog__body {
  height: calc(78vh - 64px);
  min-height: 460px;
  overflow: hidden;
  display: flex; flex-direction: column;
}

.trace-empty {
  display: flex; align-items: center; justify-content: center;
  padding: 70px 20px; font-size: $ai-fs-5; color: $ai-text3;
}

/* 概览 */
.trace-overview { display: flex; flex-direction: column; gap: 6px; }
.trace-run {
  display: flex; align-items: center; gap: 12px;
  padding: 11px 14px; border-radius: 12px;
  border: 1px solid var(--ai-border);
  cursor: pointer;
  transition: all 0.16s $ease;
  &:hover { border-color: rgba(10, 132, 255, 0.35); background: rgba(10, 132, 255, 0.04); }
  &__time { font-size: $ai-fs-6; color: $ai-text3; font-variant-numeric: tabular-nums; flex-shrink: 0; width: 108px; }
  &__stats { flex: 1; display: flex; gap: 12px; }
  &__dur { font-size: $ai-fs-5; font-weight: 600; color: $text; font-variant-numeric: tabular-nums; }
  &__tok { font-size: $ai-fs-6; color: $ai-text3; font-variant-numeric: tabular-nums; }
  &__arrow { color: $ai-text3; transition: transform 0.16s $ease; }
  &:hover &__arrow { transform: translateX(2px); color: $blue; }
}

.tp-stat { display: inline-flex; align-items: center; gap: 5px; font-size: $ai-fs-6; color: $text2; font-variant-numeric: tabular-nums; }
.tp-dot { width: 7px; height: 7px; border-radius: 50%; display: inline-block;
  &--llm { background: $blue; box-shadow: 0 0 0 3px rgba(10, 132, 255, 0.14); }
  &--tool { background: $green; box-shadow: 0 0 0 3px rgba(52, 199, 89, 0.14); }
  &--agent { background: $ai-orange; box-shadow: 0 0 0 3px rgba(255, 159, 10, 0.16); }
}

/* 单轮头部 */
.trace-head {
  display: flex; align-items: center; gap: 12px;
  padding: 8px 12px; margin-bottom: 10px;
  background: var(--ai-fill-1); border-radius: 12px;
  &__time { font-size: $ai-fs-6; color: $ai-text3; font-variant-numeric: tabular-nums; }
  &__stats { display: flex; gap: 12px; }
  &__dur { font-size: $ai-fs-5; font-weight: 600; color: $text; font-variant-numeric: tabular-nums; }
  &__tok { font-size: $ai-fs-6; color: $ai-text3; font-variant-numeric: tabular-nums; }
  &__hint { margin-left: auto; font-size: 11px; color: var(--ai-gray3); user-select: none; }
}
.trace-back {
  display: inline-flex; align-items: center; justify-content: center;
  width: 24px; height: 24px;
  border: none; border-radius: 7px; background: transparent;
  color: $ai-text3; cursor: pointer;
  &:hover { color: $blue; background: var(--ai-fill-2); }
}

/* 树形列表 */
.trace-tree {
  position: relative;
  flex: 1; min-height: 420px;
  overflow-y: auto;
  border: 1px solid var(--ai-border);
  border-radius: 14px;
  background: var(--ai-fill-1);
  padding: 12px 6px 16px;
}
.trow {
  position: relative;
  display: flex; align-items: center; gap: 8px;
  height: 44px;
  padding: 0 14px 0 var(--indent);
  border-radius: 9px;
  cursor: default;
  transition: background 0.14s $ease;
  &:hover { background: var(--ai-card-bg); box-shadow: 0 2px 10px var(--ai-hover-strong); }

  // 树线:竖线(延续父线)+ 横线
  &::before, &::after {
    content: '';
    position: absolute;
    background: var(--ai-border-3);
  }
  &::before {
    left: calc(var(--indent) - 14px); top: 0; bottom: 0;
    width: 1px;
  }
  &::after {
    left: calc(var(--indent) - 14px); top: 50%;
    width: 14px; height: 1px;
  }
  &._last::before { bottom: 50%; }
  &._last::after { left: calc(var(--indent) - 14px); }

  &__toggle {
    flex-shrink: 0; width: 18px; height: 18px;
    border: none; border-radius: 5px; background: transparent;
    color: $gray; cursor: pointer;
    display: flex; align-items: center; justify-content: center;
    &:hover { background: var(--ai-fill-2); color: $text2; }
  }
  &__icon {
    flex-shrink: 0; width: 26px; height: 26px; border-radius: 8px;
    display: flex; align-items: center; justify-content: center;
    color: #fff;
  }
  &__name {
    flex: 1; min-width: 0;
    font-size: $ai-fs-5; font-weight: 500; color: $text;
    white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
  }
  &__fail {
    flex-shrink: 0; font-size: 10px; color: $red;
    border: 1px solid rgba(255, 59, 48, 0.35); border-radius: 999px; padding: 1px 6px;
  }
  &__meta { display: flex; align-items: center; gap: 10px; flex-shrink: 0; }
  &__dur { font-size: $ai-fs-6; font-weight: 600; color: $text2; font-variant-numeric: tabular-nums; }
  &__tok { font-size: 11px; color: var(--ai-gray3); font-variant-numeric: tabular-nums; }

  // 类型配色
  &.is-turn .trow__icon { background: var(--ai-fill-3); color: $text2; }
  &.is-llm .trow__icon { background: linear-gradient(135deg, $blue, #5E5CE6); }
  &.is-tool .trow__icon { background: linear-gradient(135deg, $green, #2CB5C6); }
  &.is-subagent .trow__icon { background: linear-gradient(135deg, $ai-orange, #FFB340); }
}
</style>
