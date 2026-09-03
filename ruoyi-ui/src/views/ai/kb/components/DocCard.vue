<template>
  <article
    class="doc-card"
    :class="{
      'is-selected': selected,
      'is-failed': statusKey === 'failed',
      'is-busy': statusKey === 'processing' || statusKey === 'queued'
    }"
    role="button"
    tabindex="0"
    @click="$emit('open', doc)"
    @keydown.enter.prevent="$emit('open', doc)"
  >
    <span class="doc-card__rail" aria-hidden="true"></span>

    <!-- 多选 -->
    <label class="doc-card__check" @click.stop>
      <input
        type="checkbox"
        :checked="selected"
        @change="$emit('toggle-select', doc)"
      />
    </label>

    <div class="doc-card__head">
      <div class="doc-card__avatar" :class="'is-' + typeTone">
        {{ icon }}
      </div>
      <div class="doc-card__ident">
        <h3 class="doc-card__name" :title="doc.docName">{{ doc.docName || '未命名' }}</h3>
        <div class="doc-card__sub">
          <span class="doc-card__type">{{ typeLabel }}</span>
          <span class="doc-card__status" :class="'is-' + statusKey">
            <i></i>{{ statusLabel }}
          </span>
          <span
            v-if="graphBadge"
            class="doc-card__graph"
            :class="'is-' + graphBadge.tone"
            :title="graphBadge.title"
          >{{ graphBadge.text }}</span>
        </div>
      </div>
    </div>

    <p v-if="statusKey === 'failed' && errorText" class="doc-card__err" :title="errorText">
      {{ errorText }}
    </p>
    <p v-else-if="graphBusy && graphError" class="doc-card__err" :title="graphError">
      图谱：{{ graphError }}
    </p>
    <p v-else class="doc-card__desc">
      <template v-if="graphBusy">
        {{ graphPhaseLabel }}
        <span v-if="graphMeta"> · {{ graphMeta }}</span>
      </template>
      <template v-else-if="graphDone">
        <span v-if="doc.chunkCount">{{ doc.chunkCount }} 切片</span>
        <span v-if="graphEntityHint"> · {{ graphEntityHint }}</span>
        <span v-else-if="!doc.chunkCount">可参与检索 · 图谱已就绪</span>
      </template>
      <template v-else>
        <span v-if="doc.chunkCount">{{ doc.chunkCount }} 切片</span>
        <span v-else-if="statusKey === 'queued'">等待处理</span>
        <span v-else-if="statusKey === 'processing'">索引构建中</span>
        <span v-else>可参与检索</span>
      </template>
    </p>

    <!-- 索引进度 -->
    <div v-if="isBusy" class="doc-card__progress">
      <div class="doc-card__progress-track">
        <div class="doc-card__progress-bar" :style="{ width: (doc.progress || 0) + '%' }" />
      </div>
      <span class="doc-card__progress-n">{{ doc.progress || 0 }}%</span>
    </div>
    <!-- 图谱进度：索引完成后仍会继续抽实体，单独展示 -->
    <div v-else-if="graphBusy" class="doc-card__progress is-graph">
      <div class="doc-card__progress-track">
        <div class="doc-card__progress-bar is-graph" :style="{ width: graphProgress + '%' }" />
      </div>
      <span class="doc-card__progress-n">{{ graphProgress }}%</span>
    </div>

    <div class="doc-card__foot">
      <div class="doc-card__meta-row">
        <span class="doc-card__meta">{{ formatSize(doc.fileSize) }}</span>
        <span
          class="doc-card__meta doc-card__meta--time"
          :title="formatFullTime(doc.updateTime || doc.createTime)"
        >{{ formatRelativeTime(doc.updateTime || doc.createTime) }}</span>
      </div>
      <!-- 操作放底栏，避免叠在文件名上 -->
      <div class="doc-card__actions" @click.stop>
        <button
          v-if="canRead"
          type="button"
          class="doc-card__action"
          title="打开"
          @click="$emit('open', doc)"
        >
          <svg width="14" height="14" viewBox="0 0 16 16" fill="none"><path d="M2 8s2.5-4.5 6-4.5S14 8 14 8s-2.5 4.5-6 4.5S2 8 2 8z" stroke="currentColor" stroke-width="1.3"/><circle cx="8" cy="8" r="1.8" stroke="currentColor" stroke-width="1.3"/></svg>
        </button>
        <button
          v-if="canWrite && (statusKey === 'failed' || statusKey === 'ready')"
          type="button"
          class="doc-card__action"
          title="重新处理"
          @click="$emit('reprocess', doc)"
        >
          <svg width="14" height="14" viewBox="0 0 16 16" fill="none"><path d="M3 8a5 5 0 019.5-2.2M13 8a5 5 0 01-9.5 2.2" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/><path d="M12.5 2.5v3.2H9.3M3.5 13.5v-3.2h3.2" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </button>
        <button
          v-if="canRead && statusKey === 'ready'"
          type="button"
          class="doc-card__action"
          :title="graphBusy ? '图谱抽取中…' : '知识图谱'"
          :disabled="graphBusy"
          @click="$emit('open-graph', doc)"
        >
          <svg width="14" height="14" viewBox="0 0 16 16" fill="none"><circle cx="4" cy="4" r="1.6" stroke="currentColor" stroke-width="1.2"/><circle cx="12" cy="5" r="1.6" stroke="currentColor" stroke-width="1.2"/><circle cx="8" cy="12" r="1.6" stroke="currentColor" stroke-width="1.2"/><path d="M5.4 4.8l5.2.6M5 5.4l2.4 5.2M11.2 6.2L9 10.6" stroke="currentColor" stroke-width="1.15" stroke-linecap="round"/></svg>
        </button>
        <button
          v-if="canRead && doc.downloadable"
          type="button"
          class="doc-card__action"
          title="下载"
          @click="$emit('download', doc)"
        >
          <svg width="14" height="14" viewBox="0 0 16 16" fill="none"><path d="M8 2.5v7M5 7l3 3 3-3M3 13h10" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </button>
        <button
          v-if="canWrite"
          type="button"
          class="doc-card__action doc-card__action--danger"
          title="删除"
          @click="$emit('delete', doc)"
        >
          <svg width="14" height="14" viewBox="0 0 16 16" fill="none"><path d="M3 4.5h10M6.5 2.5h3M4.5 4.5l.5 9h6l.5-9M6.5 7v4M9.5 7v4" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </button>
      </div>
    </div>
  </article>
</template>

<script setup>
const props = defineProps({
  doc: { type: Object, required: true },
  /** { graphStatus, progress, graphStep, entityCount, relationCount, errorMsg, chunkDone, chunkTotal } */
  graph: { type: Object, default: null },
  selected: { type: Boolean, default: false },
  canRead: { type: Boolean, default: true },
  canWrite: { type: Boolean, default: false }
})

defineEmits(['open', 'toggle-select', 'reprocess', 'download', 'delete', 'open-graph'])

const statusKey = computed(() => {
  const s = props.doc.productStatus || mapParse(props.doc.parseStatus)
  return String(s || 'queued').toLowerCase()
})

const statusLabel = computed(() => ({
  ready: '可用',
  processing: '处理中',
  failed: '失败',
  queued: '排队中'
})[statusKey.value] || '—')

const isBusy = computed(() => statusKey.value === 'processing' || statusKey.value === 'queued')

const graphStatus = computed(() => String(props.graph?.graphStatus || '').toUpperCase())
const graphBusy = computed(() =>
  ['PENDING', 'EXTRACTING', 'MERGING'].includes(graphStatus.value)
)
const graphDone = computed(() => graphStatus.value === 'COMPLETED')
const graphFailed = computed(() => graphStatus.value === 'FAILED')
const graphProgress = computed(() => {
  const p = Number(props.graph?.progress)
  if (!Number.isNaN(p) && p >= 0) return Math.min(100, Math.round(p))
  // 无 progress 时用 chunk 进度估一个
  const done = Number(props.graph?.chunkDone)
  const total = Number(props.graph?.chunkTotal)
  if (total > 0 && done >= 0) return Math.min(99, Math.round((done / total) * 100))
  return graphStatus.value === 'MERGING' ? 85 : graphStatus.value === 'PENDING' ? 5 : 35
})
const graphPhaseLabel = computed(() => {
  const m = {
    PENDING: '图谱排队中',
    EXTRACTING: '正在抽取实体与关系',
    MERGING: '正在合并图谱'
  }
  return m[graphStatus.value] || '图谱处理中'
})
const graphMeta = computed(() => {
  const g = props.graph || {}
  if (g.chunkTotal != null && g.chunkDone != null && g.chunkTotal > 0) {
    return `${g.chunkDone}/${g.chunkTotal} 段`
  }
  if (g.graphStep) return String(g.graphStep)
  return ''
})
const graphEntityHint = computed(() => {
  const e = props.graph?.entityCount
  const r = props.graph?.relationCount
  if (e == null && r == null) return ''
  const parts = []
  if (e != null) parts.push(e + ' 实体')
  if (r != null) parts.push(r + ' 关系')
  return parts.join(' · ')
})
const graphError = computed(() => {
  const s = String(props.graph?.errorMsg || '').trim()
  if (!s) return graphFailed.value ? '抽取失败' : ''
  return s.length > 72 ? s.slice(0, 72) + '…' : s
})
const graphBadge = computed(() => {
  if (!graphStatus.value) return null
  if (graphBusy.value) {
    return {
      text: '图谱 ' + graphProgress.value + '%',
      tone: 'busy',
      title: graphPhaseLabel.value + (graphMeta.value ? ' · ' + graphMeta.value : '')
    }
  }
  if (graphDone.value) {
    return {
      text: '图谱就绪',
      tone: 'ok',
      title: graphEntityHint.value || '知识图谱已完成'
    }
  }
  if (graphFailed.value) {
    return {
      text: '图谱失败',
      tone: 'fail',
      title: graphError.value || '图谱抽取失败'
    }
  }
  return null
})

const icon = computed(() => {
  const t = String(props.doc.fileType || '').toLowerCase()
  if (t === 'pdf') return '📕'
  if (['doc', 'docx', 'rtf'].includes(t)) return '📘'
  if (['xls', 'xlsx', 'csv', 'tsv'].includes(t)) return '📊'
  if (['ppt', 'pptx'].includes(t)) return '📙'
  if (['html', 'htm'].includes(t)) return '🌐'
  if (['json', 'xml'].includes(t)) return '🧩'
  if (['txt', 'md', 'markdown'].includes(t)) return '📝'
  return '📄'
})

const typeLabel = computed(() => (props.doc.fileType || '文件').toString().toUpperCase())

const typeTone = computed(() => {
  const t = String(props.doc.fileType || '').toLowerCase()
  if (t === 'pdf') return 'pdf'
  if (['doc', 'docx', 'rtf'].includes(t)) return 'doc'
  if (['xls', 'xlsx', 'csv', 'tsv'].includes(t)) return 'sheet'
  if (['ppt', 'pptx'].includes(t)) return 'slide'
  if (['txt', 'md', 'markdown', 'html', 'htm'].includes(t)) return 'text'
  return 'file'
})

const errorText = computed(() => {
  const s = String(props.doc.errorMsg || props.doc.productError || '').trim()
  if (!s) return ''
  return s.length > 72 ? s.slice(0, 72) + '…' : s
})

function mapParse(s) {
  if (s === 'COMPLETED') return 'READY'
  if (s === 'FAILED') return 'FAILED'
  if (s === 'PENDING' || !s) return 'QUEUED'
  return 'PROCESSING'
}

function formatFullTime(s) {
  if (!s) return '—'
  return String(s).replace('T', ' ').slice(0, 19)
}

/** 底栏用短相对时间，完整时间放 title 悬停可见 */
function formatRelativeTime(s) {
  if (!s) return '—'
  const t = new Date(String(s).replace('T', ' ').replace(/-/g, '/'))
  if (Number.isNaN(t.getTime())) return formatFullTime(s)
  const diff = Date.now() - t.getTime()
  if (diff < 0) return formatFullTime(s)
  const m = Math.floor(diff / 60000)
  if (m < 1) return '刚刚'
  if (m < 60) return m + ' 分钟前'
  const h = Math.floor(m / 60)
  if (h < 24) return h + ' 小时前'
  const d = Math.floor(h / 24)
  if (d < 30) return d + ' 天前'
  // 更久的用月-日，仍比完整时间戳短
  const mm = String(t.getMonth() + 1).padStart(2, '0')
  const dd = String(t.getDate()).padStart(2, '0')
  return `${t.getFullYear()}-${mm}-${dd}`
}

function formatSize(n) {
  if (n == null || n === '') return '—'
  const v = Number(n)
  if (Number.isNaN(v)) return '—'
  if (v < 1024) return v + ' B'
  if (v < 1024 * 1024) return (v / 1024).toFixed(1) + ' KB'
  return (v / 1024 / 1024).toFixed(1) + ' MB'
}
</script>

<style scoped lang="scss">
@use '../../../../assets/styles/ai-tokens.scss' as *;

.doc-card {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 18px 18px 14px;
  background: var(--ai-card-bg);
  border: 1px solid var(--ai-border);
  border-radius: 18px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
  transition: all 0.28s $ease;
  overflow: hidden;
  cursor: pointer;
  font-family: $font;
  outline: none;

  &:hover {
    box-shadow: 0 10px 28px rgba(0, 0, 0, 0.08);
    transform: translateY(-3px);
    border-color: var(--ai-border-3);
    .doc-card__actions { opacity: 1; transform: translateY(0); }
    .doc-card__rail { opacity: 1; }
  }
  &:active { transform: translateY(-1px) scale(0.995); }
  &:focus-visible { box-shadow: 0 0 0 3px rgba(10, 132, 255, 0.25); }

  &.is-selected {
    border-color: rgba(10, 132, 255, 0.45);
    background: rgba(10, 132, 255, 0.04);
    box-shadow: 0 0 0 1px rgba(10, 132, 255, 0.2), 0 1px 2px var(--ai-fill-2);
  }
  &.is-failed {
    border-color: rgba(255, 59, 48, 0.22);
  }

  &__rail {
    position: absolute;
    left: 0; top: 0; bottom: 0;
    width: 4px;
    border-radius: 0 4px 4px 0;
    background: $blue;
    opacity: 0;
    transition: opacity 0.28s $ease;
  }
  &.is-failed &__rail { background: $red; opacity: 0.85; }
  &.is-busy &__rail { background: $blue; opacity: 0.7; }
  &.is-selected &__rail { opacity: 1; }

  &__graph {
    display: inline-flex;
    align-items: center;
    font-size: 10.5px;
    font-weight: 600;
    padding: 1.5px 7px;
    border-radius: 980px;
    flex-shrink: 0;
    &.is-busy {
      color: $blue;
      background: rgba(10, 132, 255, 0.1);
    }
    &.is-ok {
      color: #248A3D;
      background: rgba(52, 199, 89, 0.12);
    }
    &.is-fail {
      color: $red;
      background: rgba(255, 59, 48, 0.1);
    }
  }

  &__check {
    position: absolute;
    top: 12px;
    left: 12px;
    z-index: 2;
    width: 20px;
    height: 20px;
    display: flex;
    align-items: center;
    justify-content: center;
    input {
      width: 15px;
      height: 15px;
      accent-color: $blue;
      cursor: pointer;
    }
  }

  &__head {
    display: flex;
    align-items: center;
    gap: 11px;
    padding-left: 22px;
    /* 不再为右上角绝对定位按钮预留空间，名称可铺满 */
    padding-right: 0;
    min-width: 0;
  }

  &__avatar {
    width: 42px;
    height: 42px;
    border-radius: 14px;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 19px;
    flex-shrink: 0;
    background: var(--ai-fill-2);
    box-shadow: 0 3px 10px rgba(0, 0, 0, 0.08);
    &.is-pdf { background: linear-gradient(135deg, #ff6b6b, #ee5a5a); }
    &.is-doc { background: linear-gradient(135deg, #4dabf7, #339af0); }
    &.is-sheet { background: linear-gradient(135deg, #51cf66, #37b24d); }
    &.is-slide { background: linear-gradient(135deg, #ff922b, #f76707); }
    &.is-text { background: linear-gradient(135deg, #20c997, #0ea5e9); }
    &.is-file { background: linear-gradient(135deg, #868e96, #495057); }
  }

  &__ident { flex: 1; min-width: 0; }

  &__name {
    margin: 0 0 4px;
    font-size: 15px;
    font-weight: 600;
    letter-spacing: -0.1px;
    color: $text;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    line-height: 1.3;
  }

  &__sub {
    display: flex;
    align-items: center;
    gap: 7px;
    min-width: 0;
    flex-wrap: wrap;
  }

  &__type {
    font-family: $mono;
    font-size: 10.5px;
    color: $gray;
    background: var(--ai-fill-2);
    padding: 1.5px 6px;
    border-radius: 4px;
  }

  &__status {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    font-size: 11px;
    flex-shrink: 0;
    i {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      display: inline-block;
    }
    &.is-ready { color: #248A3D; i { background: $green; box-shadow: 0 0 0 2.5px rgba(52,199,89,0.18); } }
    &.is-processing { color: $blue; i { background: $blue; box-shadow: 0 0 0 2.5px rgba(10,132,255,0.18); } }
    &.is-queued { color: $gray; i { background: $gray2; } }
    &.is-failed { color: $red; i { background: $red; box-shadow: 0 0 0 2.5px rgba(255,59,48,0.18); } }
  }

  &__desc {
    margin: 0;
    padding-left: 22px;
    font-size: 12.5px;
    color: $text2;
    line-height: 1.45;
    min-height: 18px;
  }

  &__err {
    margin: 0;
    padding-left: 22px;
    font-size: 12px;
    color: $red;
    line-height: 1.4;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }

  &__progress {
    display: flex;
    align-items: center;
    gap: 8px;
    padding-left: 22px;
  }
  &__progress-track {
    flex: 1;
    height: 4px;
    border-radius: 980px;
    background: var(--ai-fill-3);
    overflow: hidden;
    min-width: 0;
  }
  &__progress-bar {
    height: 100%;
    border-radius: 980px;
    background: $blue;
    transition: width 0.3s $ease;
    &.is-graph {
      background: $blue;
    }
  }
  &__progress.is-graph &__progress-n { color: $blue; }
  &__progress-n {
    font-size: 11px;
    color: $gray;
    font-variant-numeric: tabular-nums;
    flex-shrink: 0;
    min-width: 28px;
    text-align: right;
  }

  &__foot {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    padding: 11px 0 0 22px;
    border-top: 1px solid var(--ai-fill-3);
    margin-top: 4px;
    min-width: 0;
    flex-wrap: wrap;
  }
  &__meta-row {
    display: flex;
    align-items: center;
    gap: 10px;
    flex: 1 1 auto;
    min-width: 0;
  }
  &__meta {
    font-size: 11.5px;
    color: $gray;
    white-space: nowrap;
    flex-shrink: 0;
    &--time {
      font-variant-numeric: tabular-nums;
      cursor: default;
    }
  }

  &__actions {
    display: flex;
    gap: 2px;
    flex-shrink: 0;
    margin-left: auto;
    opacity: 0.72;
    transition: opacity 0.18s $ease;
  }
  &:hover &__actions { opacity: 1; }

  &__action {
    width: 26px;
    height: 26px;
    border: none;
    border-radius: 7px;
    background: transparent;
    color: $text2;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    transition: all 0.18s;
    &:hover:not(:disabled) { background: rgba(10, 132, 255, 0.12); color: $blue; }
    &:disabled { opacity: 0.4; cursor: not-allowed; }
    &--danger:hover:not(:disabled) { background: rgba(255, 59, 48, 0.12); color: $red; }
  }
}

@media (hover: none) {
  .doc-card__actions { opacity: 1; }
}
</style>
