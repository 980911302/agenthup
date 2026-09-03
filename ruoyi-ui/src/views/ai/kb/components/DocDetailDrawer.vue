<template>
  <div class="ddd" :class="{ 'is-open': open }">
    <div class="ddd__mask" @click="$emit('close')" />
    <aside
      class="ddd__panel"
      v-loading="loading"
      element-loading-background="var(--ai-sheet-bg, rgba(0,0,0,0.45))"
    >
      <header class="ddd__head">
        <div class="ddd__head-main">
          <div class="ddd__title-row">
            <span class="ddd__file-icon" :class="'is-' + fileTone">{{ fileIcon }}</span>
            <div class="ddd__title-block">
              <h2 class="ddd__title" :title="doc?.docName">{{ doc?.docName || '文档详情' }}</h2>
              <div class="ddd__tags">
                <span class="prod" :class="'is-' + productStatus.toLowerCase()">{{ productLabel }}</span>
                <span v-if="doc?.fileType">{{ String(doc.fileType).toUpperCase() }}</span>
                <span v-if="data?.fileSize != null">{{ formatSize(data.fileSize) }}</span>
                <span v-if="data?.chunkCount != null">{{ data.chunkCount }} 切片</span>
              </div>
            </div>
          </div>
        </div>
        <div class="ddd__head-actions">
          <button
            v-if="canRead && productStatus === 'READY'"
            type="button"
            class="ddd-icon-btn"
            title="查看知识图谱"
            :disabled="graphBusy"
            @click="$emit('open-graph', data || doc)"
          >
            <svg width="15" height="15" viewBox="0 0 16 16" fill="none"><circle cx="4" cy="4" r="1.7" stroke="currentColor" stroke-width="1.3"/><circle cx="12" cy="5" r="1.7" stroke="currentColor" stroke-width="1.3"/><circle cx="8" cy="12" r="1.7" stroke="currentColor" stroke-width="1.3"/><path d="M5.4 4.8l5.2.6M5 5.4l2.4 5.2M11.2 6.2L9 10.6" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/></svg>
          </button>
          <button
            v-if="canDownload"
            type="button"
            class="ddd-icon-btn"
            title="下载原文件"
            @click="downloadFile"
          >
            <svg width="15" height="15" viewBox="0 0 16 16" fill="none"><path d="M8 1.5v8M5 6.5l3 3 3-3" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/><path d="M2.5 11v2a.8.8 0 0 0 .8.8h9.4a.8.8 0 0 0 .8-.8v-2" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/></svg>
          </button>
          <button
            v-if="canRead && (productStatus === 'FAILED' || productStatus === 'READY')"
            type="button"
            class="ddd-icon-btn"
            title="重新处理"
            @click="$emit('reprocess', doc)"
          >
            <svg width="15" height="15" viewBox="0 0 16 16" fill="none"><path d="M3 8a5 5 0 019.5-2.2M13 8a5 5 0 01-9.5 2.2" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/><path d="M12.5 2.5v3.2H9.3M3.5 13.5v-3.2h3.2" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </button>
          <button type="button" class="ddd__close" aria-label="关闭" @click="$emit('close')">
            <svg width="15" height="15" viewBox="0 0 16 16" fill="none"><path d="M3.5 3.5l9 9M12.5 3.5l-9 9" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>
          </button>
        </div>
      </header>

      <nav class="ddd__tabs">
        <button
          v-for="t in tabs"
          :key="t.key"
          type="button"
          class="ddd__tab"
          :class="{ 'is-active': tab === t.key }"
          @click="tab = t.key"
        >{{ t.label }}</button>
      </nav>

      <div class="ddd__body">
        <!-- 预览 -->
        <div v-show="tab === 'preview'" class="ddd-pane">
          <div v-if="productStatus === 'FAILED'" class="ddd-fail">
            <p class="ddd-fail__msg">{{ data?.productError || '处理失败' }}</p>
            <p v-if="data?.productSuggestion" class="ddd-fail__tip">建议：{{ data.productSuggestion }}</p>
            <button type="button" class="ddd-btn" @click="$emit('reprocess', doc)" v-hasPermi="['ai:kb:edit']">重新处理</button>
          </div>
          <div v-else-if="productStatus === 'PROCESSING' || productStatus === 'QUEUED'" class="ddd-busy">
            <p>文档处理中 {{ data?.progress || doc?.progress || 0 }}%</p>
            <div class="ddd-progress"><div :style="{ width: (data?.progress || doc?.progress || 0) + '%' }" /></div>
          </div>
          <div v-else-if="graphBusy" class="ddd-busy ddd-busy--graph">
            <p>{{ graphPhaseLabel }} {{ graphProgress }}%</p>
            <div class="ddd-progress is-graph"><div :style="{ width: graphProgress + '%' }" /></div>
            <p v-if="graphMeta" class="ddd-busy__meta">{{ graphMeta }}</p>
          </div>
          <div v-else-if="!preview?.available" class="ddd-empty">
            {{ previewReason }}
          </div>
          <div v-else class="ddd-preview-layout">
            <aside class="ddd-outline" v-if="outline.length">
              <div class="ddd-outline__title">目录</div>
              <button
                v-for="(n, i) in outline"
                :key="i"
                type="button"
                class="ddd-outline__item"
                :style="{ paddingLeft: (8 + (n.level || 1) * 10) + 'px' }"
                @click="scrollToPos(n.position)"
              >
                {{ n.title || '（无标题）' }}
                <span v-if="n.pageNumber" class="ddd-outline__pg">p.{{ n.pageNumber }}</span>
              </button>
            </aside>
            <div class="ddd-preview" ref="previewRef">
              <div
                v-for="b in previewBlocks"
                :key="'b' + b.position"
                class="ddd-block"
                :data-pos="b.position"
                v-html="b.html"
              />
              <div v-for="t in previewTables" :key="'t' + t.position" class="ddd-table-wrap">
                <div v-if="t.caption" class="ddd-table-cap">{{ t.caption }}</div>
                <div class="ddd-table" v-html="t.html" />
              </div>
              <p v-if="preview?.counts?.truncated" class="ddd-trunc">预览已截断，完整内容已参与索引</p>
            </div>
          </div>
        </div>

        <!-- 质量 -->
        <div v-show="tab === 'quality'" class="ddd-pane">
          <div class="ddd-q-hero">
            <svg class="ddd-ring" viewBox="0 0 80 80" aria-hidden="true">
              <circle class="ddd-ring__track" cx="40" cy="40" r="34" />
              <circle
                class="ddd-ring__bar" :class="'is-' + gradeKey"
                cx="40" cy="40" r="34"
                :stroke-dasharray="ringDash"
                :stroke-dashoffset="ringOffset"
              />
            </svg>
            <div class="ddd-q-hero__text">
              <span class="ddd-grade" :class="'is-' + gradeKey">{{ gradeLabel(quality.grade) }}</span>
              <span class="muted">状态 {{ quality.status || '—' }}</span>
              <span class="ddd-q-hero__hint">基于解析结构、内容完整度与可索引性综合评估</span>
            </div>
          </div>
          <div class="ddd-metrics">
            <div class="ddd-metric"><b>{{ quality.blockCount ?? counts.blockCount ?? '—' }}</b><span>文本块</span></div>
            <div class="ddd-metric"><b>{{ quality.tableCount ?? counts.tableCount ?? '—' }}</b><span>表格</span></div>
            <div class="ddd-metric"><b>{{ quality.pageCount ?? counts.pageCount ?? '—' }}</b><span>页</span></div>
            <div class="ddd-metric"><b>{{ quality.sheetCount ?? counts.sheetCount ?? '—' }}</b><span>工作表</span></div>
            <div class="ddd-metric"><b>{{ quality.slideCount ?? counts.slideCount ?? '—' }}</b><span>幻灯片</span></div>
            <div class="ddd-metric"><b>{{ quality.headingCount ?? counts.headingCount ?? '—' }}</b><span>标题</span></div>
          </div>
          <div v-if="(quality.warnings || []).length" class="ddd-warns">
            <h4>告警</h4>
            <ul>
              <li v-for="(w, i) in quality.warnings" :key="i">{{ w }}</li>
            </ul>
          </div>
          <div v-if="(quality.suggestions || []).length" class="ddd-tips">
            <h4>处理建议</h4>
            <ul>
              <li v-for="(s, i) in quality.suggestions" :key="i">{{ s }}</li>
            </ul>
          </div>
          <p v-if="!(quality.warnings || []).length && quality.status === 'PASS'" class="muted">
            解析质量良好，可正常参与检索。
          </p>
        </div>

        <!-- 文件信息 -->
        <div v-show="tab === 'info'" class="ddd-pane">
          <div class="ddd-summary">
            <span class="ddd-summary__icon" :class="'is-' + fileTone">{{ fileIcon }}</span>
            <div class="ddd-summary__body">
              <b class="ddd-summary__name">{{ data?.docName || doc?.docName }}</b>
              <div class="ddd-summary__meta">
                <span>{{ (data?.fileType || doc?.fileType || '文件').toString().toUpperCase() }}</span>
                <span>{{ formatSize(data?.fileSize ?? doc?.fileSize) }}</span>
                <span>{{ data?.chunkCount ?? doc?.chunkCount ?? 0 }} 切片</span>
              </div>
            </div>
            <span class="prod" :class="'is-' + productStatus.toLowerCase()">{{ productLabel }}</span>
          </div>
          <dl class="ddd-dl">
            <div><dt>名称</dt><dd>{{ data?.docName || doc?.docName }}</dd></div>
            <div><dt>类型</dt><dd>{{ (data?.fileType || doc?.fileType || '—').toString().toUpperCase() }}</dd></div>
            <div><dt>大小</dt><dd>{{ formatSize(data?.fileSize ?? doc?.fileSize) }}</dd></div>
            <div><dt>上传人</dt><dd>{{ data?.createBy || doc?.createBy || '—' }}</dd></div>
            <div><dt>创建</dt><dd>{{ formatTime(data?.createTime || doc?.createTime) }}</dd></div>
            <div><dt>更新</dt><dd>{{ formatTime(data?.updateTime || doc?.updateTime) }}</dd></div>
            <div><dt>切片数</dt><dd>{{ data?.chunkCount ?? doc?.chunkCount ?? 0 }}</dd></div>
            <div><dt>业务状态</dt><dd>{{ productLabel }}</dd></div>
            <div v-if="graphLabel"><dt>知识图谱</dt><dd>{{ graphLabel }}</dd></div>
          </dl>
          <div class="ddd-info-actions">
            <button
              v-if="canRead && productStatus === 'READY'"
              type="button"
              class="ddd-btn ddd-btn--ghost"
              :disabled="graphBusy"
              :title="graphBusy ? '图谱仍在抽取，完成后可查看' : ''"
              @click="$emit('open-graph', data || doc)"
            >{{ graphBusy ? '图谱抽取中…' : '查看知识图谱' }}</button>
            <button
              v-if="canDownload"
              type="button"
              class="ddd-btn ddd-btn--ghost"
              @click="downloadFile"
            >下载原文件</button>
          </div>
        </div>
      </div>
    </aside>
  </div>
</template>

<script setup>
import { getKbDocPreview, downloadKbDocument, graphDocs } from '@/api/ai/kb'
import { blobValidate } from '@/utils/ruoyi'
import { saveAs } from 'file-saver'
import errorCode from '@/utils/errorCode'
import { ElMessage } from 'element-plus'

const props = defineProps({
  open: { type: Boolean, default: false },
  kbId: { type: [Number, String], required: true },
  doc: { type: Object, default: null },
  access: { type: Object, default: () => ({}) },
  initialTab: { type: String, default: 'preview' }
})
defineEmits(['close', 'reprocess', 'open-graph'])

const loading = ref(false)
const data = ref(null)
const tab = ref('preview')
const previewRef = ref(null)
const graphInfo = ref(null)
let graphPollTimer = null

// 产品抽屉只保留预览 / 解析质量 / 文件信息，不再暴露技术诊断
const tabs = computed(() => [
  { key: 'preview', label: '预览' },
  { key: 'quality', label: '质量' },
  { key: 'info', label: '文件信息' }
])

const productStatus = computed(() => {
  return data.value?.productStatus
    || productOf(props.doc)
    || 'QUEUED'
})
const productLabel = computed(() => ({
  READY: '可用', PROCESSING: '处理中', FAILED: '失败', QUEUED: '排队中'
})[productStatus.value] || productStatus.value)

const graphStatus = computed(() => String(graphInfo.value?.graphStatus || '').toUpperCase())
const graphBusy = computed(() =>
  ['PENDING', 'EXTRACTING', 'MERGING'].includes(graphStatus.value)
)
const graphProgress = computed(() => {
  const p = Number(graphInfo.value?.progress)
  if (!Number.isNaN(p) && p >= 0) return Math.min(100, Math.round(p))
  const done = Number(graphInfo.value?.chunkDone)
  const total = Number(graphInfo.value?.chunkTotal)
  if (total > 0) return Math.min(99, Math.round((done / total) * 100))
  return graphStatus.value === 'MERGING' ? 85 : 30
})
const graphPhaseLabel = computed(() => ({
  PENDING: '图谱排队中',
  EXTRACTING: '正在抽取实体与关系',
  MERGING: '正在合并图谱'
})[graphStatus.value] || '图谱处理中')
const graphMeta = computed(() => {
  const g = graphInfo.value || {}
  if (g.chunkTotal > 0) return `${g.chunkDone || 0}/${g.chunkTotal} 段`
  return g.graphStep || ''
})
const graphLabel = computed(() => {
  if (!graphStatus.value) return ''
  if (graphBusy.value) return `${graphPhaseLabel.value} ${graphProgress.value}%`
  if (graphStatus.value === 'COMPLETED') {
    const e = graphInfo.value?.entityCount
    const r = graphInfo.value?.relationCount
    if (e != null || r != null) return `已完成（${e ?? 0} 实体 · ${r ?? 0} 关系）`
    return '已完成'
  }
  if (graphStatus.value === 'FAILED') return '失败' + (graphInfo.value?.errorMsg ? '：' + graphInfo.value.errorMsg : '')
  return graphStatus.value
})

/** 文件类型图标与色调(与 DocCard 一致) */
const fileTone = computed(() => {
  const t = String(docOf()?.fileType || '').toLowerCase()
  if (t === 'pdf') return 'pdf'
  if (['doc', 'docx', 'rtf'].includes(t)) return 'doc'
  if (['xls', 'xlsx', 'csv', 'tsv'].includes(t)) return 'sheet'
  if (['ppt', 'pptx'].includes(t)) return 'slide'
  if (['txt', 'md', 'markdown', 'html', 'htm'].includes(t)) return 'text'
  return 'file'
})
const fileIcon = computed(() => {
  const t = String(docOf()?.fileType || '').toLowerCase()
  if (t === 'pdf') return '📕'
  if (['doc', 'docx', 'rtf'].includes(t)) return '📘'
  if (['xls', 'xlsx', 'csv', 'tsv'].includes(t)) return '📊'
  if (['ppt', 'pptx'].includes(t)) return '📙'
  if (['html', 'htm'].includes(t)) return '🌐'
  if (['json', 'xml'].includes(t)) return '🧩'
  if (['txt', 'md', 'markdown'].includes(t)) return '📝'
  return '📄'
})
function docOf() {
  return data.value || props.doc
}

/** 质量评分圆环 */
const GRADE_SCORE = { GOOD: 90, OK: 75, FAIR: 60, THIN: 45, POOR: 30, UNKNOWN: 0 }
const gradeScore = computed(() => GRADE_SCORE[String(quality.value.grade || '').toUpperCase()] ?? 0)
const gradeKey = computed(() => String(quality.value.grade || 'unknown').toLowerCase())
const RING_LEN = 2 * Math.PI * 34
const ringDash = computed(() => String(RING_LEN))
const ringOffset = computed(() => String(RING_LEN * (1 - gradeScore.value / 100)))

const preview = computed(() => data.value?.preview || {})
const quality = computed(() => data.value?.quality || {})
const counts = computed(() => preview.value?.counts || {})
const outline = computed(() => preview.value?.outline || [])
const previewBlocks = computed(() => preview.value?.blocks || [])
const previewTables = computed(() => preview.value?.tables || [])
const previewReason = computed(() => {
  const r = preview.value?.reason
  if (r === 'NO_IR') return '暂无解析预览，可尝试重新处理'
  if (r === 'IR_LOAD_ERROR') return '预览加载失败'
  if (r === 'PROCESSING') return '处理完成后可预览'
  return '暂无预览'
})
const canRead = computed(() => props.access?.canRead !== false)
const canDownload = computed(() => {
  return !!(data.value?.downloadable || props.doc?.downloadable)
})

async function downloadFile() {
  const docId = props.doc?.docId || data.value?.docId
  if (!docId || !canDownload.value) return
  try {
    const blobData = await downloadKbDocument(props.kbId, docId)
    const isBlob = blobValidate(blobData)
    if (isBlob) {
      const name = data.value?.docName || props.doc?.docName || ('doc-' + docId)
      saveAs(new Blob([blobData]), name)
    } else {
      const resText = await blobData.text()
      const rspObj = JSON.parse(resText)
      ElMessage.error(errorCode[rspObj.code] || rspObj.msg || errorCode['default'])
    }
  } catch (e) {
    console.error(e)
    ElMessage.error('下载文件出现错误，请联系管理员！')
  }
}

function productOf(doc) {
  if (!doc) return 'QUEUED'
  if (doc.productStatus) return doc.productStatus
  const s = doc.parseStatus
  if (s === 'COMPLETED') return 'READY'
  if (s === 'FAILED') return 'FAILED'
  if (s === 'PENDING' || !s) return 'QUEUED'
  return 'PROCESSING'
}
function gradeLabel(g) {
  return ({ GOOD: '良好', OK: '可用', FAIR: '一般', THIN: '内容偏少', POOR: '较差', UNKNOWN: '未知' })[g] || g || '—'
}
function formatSize(n) {
  if (n == null || n === '') return '—'
  const v = Number(n)
  if (v < 1024) return v + ' B'
  if (v < 1024 * 1024) return (v / 1024).toFixed(1) + ' KB'
  return (v / 1024 / 1024).toFixed(1) + ' MB'
}
function formatTime(s) {
  if (!s) return '—'
  return String(s).replace('T', ' ').slice(0, 16)
}

function scrollToPos(pos) {
  const root = previewRef.value
  if (!root) return
  const el = root.querySelector(`[data-pos="${pos}"]`)
  if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function stopGraphPoll() {
  if (graphPollTimer) {
    clearInterval(graphPollTimer)
    graphPollTimer = null
  }
}

function loadGraph() {
  if (!props.open || !props.doc?.docId || !props.kbId) return
  graphDocs(props.kbId).then(res => {
    const list = res.data || []
    const hit = list.find(g => Number(g.docId) === Number(props.doc.docId))
    graphInfo.value = hit || null
    // 图谱忙时短轮询
    if (hit && ['PENDING', 'EXTRACTING', 'MERGING'].includes(String(hit.graphStatus || '').toUpperCase())) {
      if (!graphPollTimer) {
        graphPollTimer = setInterval(() => {
          if (!props.open) {
            stopGraphPoll()
            return
          }
          loadGraph()
        }, 2800)
      }
    } else {
      stopGraphPoll()
    }
  }).catch(() => {
    graphInfo.value = null
  })
}

function load() {
  if (!props.open || !props.doc?.docId || !props.kbId) return
  loading.value = true
  data.value = null
  graphInfo.value = null
  stopGraphPoll()
  const t = props.initialTab || 'preview'
  tab.value = ['preview', 'quality', 'info'].includes(t) ? t : 'preview'

  getKbDocPreview(props.kbId, props.doc.docId).then(res => {
    data.value = res.data || {}
    loading.value = false
    loadGraph()
  }).catch(() => {
    loading.value = false
    loadGraph()
  })
}

watch(() => [props.open, props.doc?.docId], () => {
  if (props.open) load()
  else {
    stopGraphPoll()
    graphInfo.value = null
  }
})

onUnmounted(() => stopGraphPoll())
</script>

<style scoped lang="scss">
@use '../../../../assets/styles/ai-tokens.scss' as *;

.ddd__mask {
  position: fixed; inset: 0; background: var(--ai-overlay); backdrop-filter: blur(5px); z-index: 2100;
  opacity: 0; pointer-events: none; transition: opacity .2s;
}
.ddd__panel {
  position: fixed; top: 0; right: 0; width: min(760px, 100vw); height: 100vh; background: var(--ai-sheet-bg);
  z-index: 2101; box-shadow: var(--ai-shadow-sheet);
  transform: translateX(100%); transition: transform .22s $ease;
  font-family: $font;
  display: flex; flex-direction: column;
}
.ddd.is-open {
  .ddd__mask { opacity: 1; pointer-events: auto; }
  .ddd__panel { transform: translateX(0); }
}
.ddd__head {
  display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; padding: 18px 22px 14px;
  border-bottom: 1px solid var(--ai-border);
}
.ddd__head-main { min-width: 0; }
.ddd__title-row { display: flex; align-items: center; gap: 12px; min-width: 0; }
.ddd__file-icon {
  width: 44px; height: 44px; flex-shrink: 0; border-radius: 14px;
  display: flex; align-items: center; justify-content: center; font-size: 20px;
  box-shadow: 0 3px 10px rgba(0, 0, 0, 0.08);
  &.is-pdf { background: linear-gradient(135deg, #ff6b6b, #ee5a5a); }
  &.is-doc { background: linear-gradient(135deg, #4dabf7, #339af0); }
  &.is-sheet { background: linear-gradient(135deg, #51cf66, #37b24d); }
  &.is-slide { background: linear-gradient(135deg, #ff922b, #f76707); }
  &.is-text { background: linear-gradient(135deg, #20c997, #0ea5e9); }
  &.is-file { background: linear-gradient(135deg, #868e96, #495057); }
}
.ddd__title-block { min-width: 0; }
.ddd__title {
  margin: 0 0 6px; font-size: 18px; font-weight: 700; letter-spacing: -0.2px; color: $text;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 520px;
}
.ddd__tags { display: flex; flex-wrap: wrap; gap: 8px; font-size: 12px; color: $gray; align-items: center; }
.ddd__head-actions { display: flex; align-items: center; gap: 2px; flex-shrink: 0; }
.ddd-icon-btn {
  width: 32px; height: 32px; border: none; border-radius: 9px; background: transparent;
  color: $gray; cursor: pointer; display: flex; align-items: center; justify-content: center;
  transition: background 0.16s $ease, color 0.16s $ease;
  &:hover:not(:disabled) { background: var(--ai-fill-3); color: $blue; }
  &:disabled { opacity: 0.4; cursor: not-allowed; }
}
.ddd__close {
  width: 32px; height: 32px; border: none; border-radius: 9px; background: transparent;
  color: $gray; cursor: pointer; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  transition: background 0.16s $ease, color 0.16s $ease;
  &:hover { background: var(--ai-fill-3); color: $text; }
}
.prod {
  font-size: 12px; font-weight: 600; padding: 2px 8px; border-radius: 980px; background: var(--ai-fill-2);
  &.is-ready { color: #248a3d; background: rgba(52,199,89,0.12); }
  &.is-processing, &.is-queued { color: $blue; background: rgba(10,132,255,0.1); }
  &.is-failed { color: #ff3b30; background: rgba(255,59,48,0.1); }
}
.ddd__tabs {
  display: flex; gap: 6px; padding: 10px 16px 0; border-bottom: 1px solid var(--ai-border);
}
.ddd__tab {
  border: none; background: transparent; padding: 7px 16px; font-size: 13px; font-weight: 550;
  color: $gray; cursor: pointer; border-radius: 980px;
  transition: background 0.16s $ease, color 0.16s $ease;
  &:hover { background: var(--ai-fill-2); color: $text; }
  &.is-active { background: rgba(10, 132, 255, 0.10); color: $blue; }
}
.ddd__body { flex: 1; min-height: 0; overflow: hidden; display: flex; flex-direction: column; }
.ddd-pane { flex: 1; overflow: auto; padding: 18px 22px 28px; }

.ddd-fail, .ddd-busy, .ddd-empty {
  padding: 24px 8px; text-align: center; color: $gray; font-size: 13.5px;
}
.ddd-fail__msg { color: $red; font-weight: 600; margin: 0 0 8px; }
.ddd-fail__tip { margin: 0 0 14px; font-size: 13px; }
.ddd-busy--graph p { color: $blue; font-weight: 550; margin: 0; }
.ddd-busy__meta { margin-top: 8px !important; font-size: 12px; color: $gray; font-weight: 400 !important; }
.ddd-progress {
  height: 4px; border-radius: 980px; background: var(--ai-fill-3); max-width: 240px; margin: 12px auto 0;
  div { height: 100%; background: $blue; border-radius: 980px; transition: width 0.3s $ease; }
  &.is-graph div { background: $blue; }
}
.ddd-btn:disabled {
  opacity: 0.55; cursor: not-allowed;
}
.ddd-btn {
  border: none; background: $blue; color: #fff; border-radius: 980px; padding: 8px 16px;
  font-size: 13.5px; font-weight: 500; font-family: $font; cursor: pointer;
  box-shadow: 0 2px 10px rgba(10,132,255,0.28); transition: all 0.18s $ease;
  &:hover { background: #0071e3; }
  &--ghost {
    background: transparent; color: $blue; box-shadow: 0 0 0 1.5px rgba(10,132,255,0.35);
    display: inline-flex; align-items: center; text-decoration: none;
    &:hover { background: rgba(10,132,255,0.06); }
  }
}
.ddd-info-actions {
  display: flex; flex-wrap: wrap; gap: 10px; margin-top: 14px;
}

.ddd-preview-layout {
  display: grid;
  grid-template-columns: 180px 1fr;
  gap: 12px;
  min-height: 360px;
  /* 避免暗色下某一列继承到亮色底 */
  background: transparent;
  color: var(--ai-text, #{$text});
  @media (max-width: 640px) { grid-template-columns: 1fr; }
}
.ddd-outline {
  /* 左侧目录：显式主题色，暗色不再露白底 */
  background: var(--ai-fill-1);
  border: 1px solid var(--ai-border);
  border-radius: 12px;
  padding: 10px 8px;
  max-height: calc(100vh - 200px);
  overflow: auto;
  color: var(--ai-text, #{$text});
  &__title {
    font-size: 12px;
    font-weight: 600;
    color: var(--ai-gray, #{$gray});
    margin-bottom: 8px;
    padding: 0 4px;
  }
  &__item {
    display: block;
    width: 100%;
    text-align: left;
    border: none;
    /* 覆盖全局/浏览器对 button 的默认白底 */
    background: transparent !important;
    background-color: transparent !important;
    font-size: 12px;
    color: var(--ai-text, #{$text}) !important;
    padding: 6px 6px;
    cursor: pointer;
    border-radius: 8px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-family: $font;
    line-height: 1.4;
    &:hover {
      background: var(--ai-fill-2) !important;
      background-color: var(--ai-fill-2) !important;
    }
  }
  &__pg {
    color: var(--ai-gray2, #{$gray3});
    margin-left: 4px;
    font-size: 11px;
  }
}
.ddd-preview {
  max-height: calc(100vh - 200px);
  overflow: auto;
  padding: 4px 6px 4px 2px;
  background: transparent;
  color: var(--ai-text, #{$text});
  :deep(.kb-prev-h) {
    margin: 16px 0 8px;
    font-size: 16px;
    color: var(--ai-text, #{$text});
    background: transparent;
  }
  :deep(.kb-prev-p) {
    margin: 0 0 10px;
    font-size: 13.5px;
    line-height: 1.65;
    color: var(--ai-text, #{$text});
    background: transparent;
  }
  :deep(.kb-prev-li) {
    margin: 0 0 6px;
    font-size: 13.5px;
    line-height: 1.55;
    color: var(--ai-text, #{$text});
    background: transparent;
  }
  :deep(.kb-prev-code) {
    background: var(--ai-code-bg, var(--ai-fill-2));
    color: var(--ai-text, #{$text});
    padding: 10px 12px;
    border-radius: $radius-sm;
    font-size: 12px;
    overflow: auto;
    margin: 0 0 12px;
    border: 1px solid var(--ai-border);
  }
}
.ddd-table-wrap { margin: 12px 0; }
.ddd-table-cap { font-size: 12px; color: var(--ai-gray, #{$gray}); margin-bottom: 4px; }
.ddd-table {
  overflow: auto;
  border: 1px solid var(--ai-border);
  border-radius: $radius-sm;
  background: var(--ai-fill-1);
  :deep(table) {
    border-collapse: collapse;
    width: 100%;
    font-size: 12.5px;
    background: transparent !important;
    color: var(--ai-text, #{$text});
  }
  :deep(td), :deep(th) {
    border: 1px solid var(--ai-border);
    padding: 6px 8px;
    background: transparent !important;
    color: var(--ai-text, #{$text}) !important;
  }
  /* 表格 HTML 常带 bgcolor/white 内联样式，强制跟主题 */
  :deep([bgcolor]),
  :deep([style*="background"]) {
    background: transparent !important;
    background-color: transparent !important;
  }
}
.ddd-trunc { font-size: 12px; color: $gray3; margin-top: 16px; }

.ddd-q-hero {
  display: flex; align-items: center; gap: 18px; margin-bottom: 18px;
  padding: 18px 20px;
  border: 1px solid var(--ai-border); border-radius: 16px;
  background: var(--ai-card-bg);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.03);
}
.ddd-ring {
  width: 84px; height: 84px; flex-shrink: 0;
  &__track { fill: none; stroke: var(--ai-fill-3); stroke-width: 8; }
  &__bar {
    fill: none; stroke: $blue; stroke-width: 8; stroke-linecap: round;
    transform: rotate(-90deg); transform-origin: 50% 50%;
    transition: stroke-dashoffset 0.5s $ease;
    &.is-good { stroke: $green; }
    &.is-fair { stroke: #e67e22; }
    &.is-poor, &.is-thin { stroke: $red; }
  }
}
.ddd-q-hero__text {
  display: flex; flex-direction: column; align-items: flex-start; gap: 5px;
}
.ddd-q-hero__hint { font-size: 12px; color: $gray2; }
.ddd-grade {
  font-size: 14px; font-weight: 700; padding: 4px 12px; border-radius: 980px; background: var(--ai-fill-2);
  &.is-good { color: #248a3d; background: rgba(52,199,89,0.12); }
  &.is-ok { color: $blue; background: rgba(10,132,255,0.1); }
  &.is-fair { color: #d35400; background: rgba(230,126,34,0.12); }
  &.is-poor, &.is-thin { color: $red; background: rgba(255,59,48,0.1); }
}
.ddd-metrics {
  display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; margin-bottom: 18px;
}
.ddd-metric {
  border: 1px solid var(--ai-border); border-radius: 14px; padding: 14px 10px 12px; text-align: center;
  background: var(--ai-card-bg);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.03);
  transition: border-color 0.16s $ease, transform 0.16s $ease;
  &:hover { border-color: var(--ai-border-3); transform: translateY(-1px); }
  b { display: block; font-size: 20px; color: $text; font-variant-numeric: tabular-nums; margin-bottom: 2px; }
  span { font-size: 11.5px; color: $gray; }
}
.ddd-warns, .ddd-tips {
  h4 { margin: 0 0 8px; font-size: 13px; }
  ul { margin: 0; padding-left: 18px; font-size: 13px; color: $text; line-height: 1.5; }
}
.ddd-warns li { color: $red; }
.muted { color: $gray; font-size: 13px; }

.ddd-summary {
  display: flex; align-items: center; gap: 14px;
  padding: 16px 18px; margin-bottom: 14px;
  border: 1px solid var(--ai-border); border-radius: 16px;
  background: var(--ai-card-bg);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.03);
  &__icon {
    width: 48px; height: 48px; flex-shrink: 0; border-radius: 14px;
    display: flex; align-items: center; justify-content: center; font-size: 22px;
    box-shadow: 0 3px 10px rgba(0, 0, 0, 0.08);
    &.is-pdf { background: linear-gradient(135deg, #ff6b6b, #ee5a5a); }
    &.is-doc { background: linear-gradient(135deg, #4dabf7, #339af0); }
    &.is-sheet { background: linear-gradient(135deg, #51cf66, #37b24d); }
    &.is-slide { background: linear-gradient(135deg, #ff922b, #f76707); }
    &.is-text { background: linear-gradient(135deg, #20c997, #0ea5e9); }
    &.is-file { background: linear-gradient(135deg, #868e96, #495057); }
  }
  &__body { flex: 1; min-width: 0; }
  &__name {
    display: block; font-size: 15px; font-weight: 650; color: $text;
    overflow: hidden; text-overflow: ellipsis; white-space: nowrap; margin-bottom: 5px;
  }
  &__meta { display: flex; flex-wrap: wrap; gap: 10px; font-size: 12px; color: $gray; }
  .prod { flex-shrink: 0; }
}

.ddd-dl {
  display: grid; gap: 0; margin: 0 0 14px;
  border: 1px solid var(--ai-border); border-radius: 14px; overflow: hidden;
  background: var(--ai-card-bg);
  div {
    display: grid; grid-template-columns: 110px 1fr; gap: 10px; font-size: 13px;
    padding: 11px 14px;
    &:not(:last-child) { border-bottom: 1px solid var(--ai-fill-2); }
    &:hover { background: var(--ai-fill-1); }
  }
  dt { color: $gray; }
  dd { margin: 0; color: $text; word-break: break-all; }
  &--compact div { grid-template-columns: 130px 1fr; }
  .mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11.5px; }
}
.ddd-stack, .ddd-diag-q {
  margin-top: 14px;
  h4 { margin: 0 0 6px; font-size: 13px; }
  pre {
    margin: 0; padding: 10px; background: var(--ai-block-bg-2); color: $text; border: 1px solid var(--ai-border);
    border-radius: $radius-sm; font-family: $mono;
    font-size: 11.5px; max-height: 240px; overflow: auto; white-space: pre-wrap; word-break: break-all;
  }
}
.ddd-chunks { margin-top: 16px;
  h4 { margin: 0 0 8px; font-size: 13px; }
}
</style>
