<template>
  <div class="cwb">
    <!-- 工具栏：筛选 + 全选计数 + 操作，单行 -->
    <div class="cwb-toolbar">
      <div class="cwb-toolbar__left">
        <label v-if="filtered.length" class="cwb-selectbar__all">
          <input type="checkbox" :checked="allChecked" @change="toggleAll" />
        </label>
        <input
          v-model="keyword"
          class="cwb-input"
          placeholder="筛选文件名…"
          @input="applyFilter"
        />
        <select v-model="statusFilter" class="cwb-select" @change="applyFilter">
          <option value="">全部状态</option>
          <option value="READY">可用</option>
          <option value="PROCESSING">处理中</option>
          <option value="QUEUED">排队中</option>
          <option value="FAILED">失败</option>
        </select>
        <select v-model="typeFilter" class="cwb-select" @change="applyFilter">
          <option value="">全部类型</option>
          <option v-for="t in typeOptions" :key="t" :value="t">{{ t.toUpperCase() }}</option>
        </select>
        <span v-if="filtered.length" class="cwb-selectbar__count">
          {{ filtered.length }} 个
          <template v-if="selectedIds.length"> · 已选 {{ selectedIds.length }}</template>
        </span>
      </div>
      <div class="cwb-toolbar__right">
        <template v-if="selectedIds.length && canWrite">
          <button type="button" class="cwb-btn" @click="batchReprocess">
            重新处理 ({{ selectedIds.length }})
          </button>
          <button type="button" class="cwb-btn is-danger" @click="batchDelete">
            删除 ({{ selectedIds.length }})
          </button>
        </template>
        <button type="button" class="cwb-btn" v-if="hasActiveBatch" @click="drawerOpen = true">
          上传任务
          <span v-if="batchOpenCount" class="cwb-badge">{{ batchOpenCount }}</span>
        </button>
        <el-upload
          v-if="canWrite"
          :http-request="() => {}"
          :show-file-list="false"
          :auto-upload="false"
          :on-change="onPickFiles"
          multiple
          :accept="acceptTypes"
          class="cwb-upload"
        >
          <button type="button" class="cwb-btn cwb-btn--primary">+ 添加文件</button>
        </el-upload>
      </div>
    </div>

    <!-- 空库拖拽；有文件后不再占一整条拖放带（仍可点添加 / 拖到网格） -->
    <div
      v-if="canWrite && !docs.length && !loading"
      class="cwb-drop"
      :class="{ 'is-over': dragOver }"
      @dragover.prevent="dragOver = true"
      @dragleave.prevent="dragOver = false"
      @drop.prevent="onDrop"
    >
      <div class="cwb-drop__empty">
        <div class="cwb-drop__icon">📄</div>
        <p>拖拽文件到此处，或点击添加文件</p>
        <span>支持 PDF、Word、Excel、PPT、Markdown、HTML、CSV、JSON 等</span>
      </div>
    </div>
    <div
      v-else-if="!canWrite && !docs.length && !loading"
      class="cwb-drop cwb-drop--readonly"
    >
      <div class="cwb-drop__empty">
        <div class="cwb-drop__icon">📄</div>
        <p>暂无文件</p>
        <span>当前角色仅可查看与测试</span>
      </div>
    </div>

    <!-- 有文件时支持拖到整块网格区域 -->
    <div
      v-loading="loading"
      class="cwb-grid-wrap"
      :class="{ 'is-drag': dragOver && canWrite && docs.length }"
      @dragover.prevent="canWrite && docs.length && (dragOver = true)"
      @dragleave.prevent="dragOver = false"
      @drop.prevent="canWrite && onDrop($event)"
    >
      <div v-if="filtered.length" class="cwb-grid">
        <DocCard
          v-for="doc in filtered"
          :key="doc.docId"
          :doc="doc"
          :graph="graphOf(doc.docId)"
          :selected="selectedIds.includes(doc.docId)"
          :can-read="canRead"
          :can-write="canWrite"
          @open="openDetail"
          @toggle-select="toggleRow"
          @reprocess="reprocessOne"
          @download="downloadOne"
          @delete="deleteOne"
          @open-graph="onOpenGraph"
        />
      </div>
      <div v-else-if="!loading && docs.length" class="cwb-empty-filter">没有匹配的文档</div>
    </div>

    <DocDetailDrawer
      :open="detailOpen"
      :kb-id="kbId"
      :doc="detailDoc"
      :access="access"
      :initial-tab="detailInitialTab"
      @close="closeDetail"
      @reprocess="onDetailReprocess"
      @open-graph="onOpenGraph"
    />

    <!-- 上传任务抽屉 -->
    <div class="cwb-drawer" :class="{ 'is-open': drawerOpen }">
      <div class="cwb-drawer__mask" @click="drawerOpen = false" />
      <aside class="cwb-drawer__panel">
        <header class="cwb-drawer__head">
          <div>
            <h3>上传任务</h3>
            <p v-if="batch">
              共 {{ summary.total }} · 成功 {{ summary.success }} · 重复 {{ summary.duplicate }} · 失败 {{ summary.failed }}
            </p>
          </div>
          <button type="button" class="cwb-drawer__close" @click="drawerOpen = false">×</button>
        </header>

        <div class="cwb-drawer__policy" v-if="batch && !uploading">
          <span>重复内容</span>
          <select v-model="onDuplicate" class="cwb-select">
            <option value="skip">跳过（保留已有）</option>
            <option value="force">仍创建新文档</option>
          </select>
        </div>

        <ul class="cwb-drawer__list" v-if="batch">
          <li v-for="item in batch.items" :key="item.uid" class="cwb-item">
            <div class="cwb-item__name" :title="item.name">{{ item.name }}</div>
            <div class="cwb-item__meta">
              <span :class="'st is-' + item.status">{{ itemStatusLabel(item.status) }}</span>
              <span v-if="item.error" class="cwb-item__err" :title="item.error">{{ item.error }}</span>
              <span v-else-if="item.status === 'failed' && !item._file" class="cwb-item__err">
                文件已失效，请重新选择
              </span>
            </div>
            <div class="cwb-item__ops">
              <button
                v-if="item.status === 'failed' || item.status === 'queued'"
                type="button"
                class="link"
                :disabled="uploading"
                @click="retryItem(item)"
              >{{ item._file ? '重试' : '重选文件' }}</button>
              <button
                type="button"
                class="link is-danger"
                :disabled="uploading"
                @click="removeItem(item)"
              >移除</button>
            </div>
          </li>
        </ul>
        <div v-else class="cwb-drawer__empty">暂无上传任务</div>

        <!-- 隐藏的重选文件 input -->
        <input
          ref="repickInputRef"
          type="file"
          class="cwb-hidden-file"
          :accept="acceptTypes"
          @change="onRepickFile"
        />

        <footer class="cwb-drawer__foot">
          <button type="button" class="cwb-btn" :disabled="!batch || uploading || !hasFinished" @click="clearFinished">
            清理已完成
          </button>
          <button type="button" class="cwb-btn is-danger" :disabled="!batch || uploading" @click="clearAllTasks">
            清空任务
          </button>
          <button
            type="button"
            class="cwb-btn cwb-btn--primary"
            :disabled="!batch || uploading || !hasUploadable"
            @click="startUpload"
          >{{ uploading ? '上传中…' : '开始上传' }}</button>
        </footer>
      </aside>
    </div>
  </div>
</template>

<script setup>
import { listKbDoc, uploadKbDoc, delKbDoc, reprocessKbDoc, batchReprocessKbDoc, downloadKbDocument, getKbDocument, graphDocs } from '@/api/ai/kb'
import { blobValidate } from '@/utils/ruoyi'
import { saveAs } from 'file-saver'
import errorCode from '@/utils/errorCode'
import { ElMessage } from 'element-plus'
import {
  loadBatch, saveBatch, clearBatch, newBatchId, batchSummary
} from '@/utils/kb-upload-batch'
import DocDetailDrawer from './DocDetailDrawer.vue'
import DocCard from './DocCard.vue'

const props = defineProps({
  kbId: { type: [Number, String], required: true },
  /** content = 运营工作台；diagnostics = 打开详情时默认诊断 tab */
  mode: { type: String, default: 'content' },
  /** 单库 capability，来自 GET /access */
  access: {
    type: Object,
    default: () => ({})
  }
})
const emit = defineEmits(['doc-count', 'open-graph'])
const { proxy } = getCurrentInstance()

const canWrite = computed(() => !!props.access?.canWrite)
const canRead = computed(() => props.access?.canRead !== false)

const acceptTypes = '.pdf,.doc,.docx,.rtf,.xls,.xlsx,.ppt,.pptx,.txt,.md,.markdown,.html,.htm,.csv,.tsv,.json,.xml'

const loading = ref(false)
const docs = ref([])
const keyword = ref('')
const statusFilter = ref('')
const typeFilter = ref('')
const filtered = ref([])
const selectedIds = ref([])
const dragOver = ref(false)
const detailOpen = ref(false)
const detailDoc = ref(null)
const detailInitialTab = ref('preview')

const drawerOpen = ref(false)
const batch = ref(null)
const onDuplicate = ref('skip')
const uploading = ref(false)
const repickInputRef = ref(null)
/** 当前等待重选文件的任务 uid */
const repickUid = ref(null)

/** 仅轮询，不再使用带 JWT query 的 EventSource */
let progressPollTimer = null
/** 列表请求 in-flight，避免轮询叠请求导致界面抖 */
let docsLoadSeq = 0
let docsLoadingInFlight = false
/** docId -> 图谱状态（与索引进度分离，索引完成后仍会继续抽图谱） */
const graphByDoc = ref({})

const typeOptions = computed(() => {
  const set = new Set()
  for (const d of docs.value) {
    if (d.fileType) set.add(String(d.fileType).toLowerCase())
  }
  return [...set].sort()
})

const summary = computed(() => batchSummary(batch.value?.items))
const hasActiveBatch = computed(() => !!(batch.value && batch.value.items?.length))
const batchOpenCount = computed(() => {
  const s = summary.value
  return s.queued + s.uploading + s.failed
})
/** 有可发起上传的任务：排队中/失败且仍持有 File 对象 */
const hasUploadable = computed(() =>
  (batch.value?.items || []).some(
    i => (i.status === 'queued' || i.status === 'failed') && !!i._file
  )
)
const hasFinished = computed(() =>
  (batch.value?.items || []).some(i => ['success', 'duplicate', 'skipped'].includes(i.status))
)
const allChecked = computed(() => filtered.value.length > 0 && filtered.value.every(d => selectedIds.value.includes(d.docId)))

function extractErrMsg(err) {
  if (!err) return '上传失败'
  if (typeof err === 'string') return err === 'error' ? '上传失败' : err
  return err.msg || err.message || err.response?.data?.msg || '上传失败'
}

function productOf(doc) {
  if (doc.productStatus) return doc.productStatus
  const s = doc.parseStatus
  if (s === 'COMPLETED') return 'READY'
  if (s === 'FAILED') return 'FAILED'
  if (s === 'PENDING' || !s) return 'QUEUED'
  return 'PROCESSING'
}
function isBusy(doc) {
  const p = productOf(doc)
  return p === 'PROCESSING' || p === 'QUEUED'
}
function graphOf(docId) {
  if (docId == null) return null
  return graphByDoc.value[docId] || null
}
function isGraphBusyStatus(s) {
  return ['PENDING', 'EXTRACTING', 'MERGING'].includes(String(s || '').toUpperCase())
}
function hasGraphBusy() {
  return Object.values(graphByDoc.value || {}).some(g => isGraphBusyStatus(g?.graphStatus))
}
function loadGraphStatuses() {
  if (!props.kbId) return Promise.resolve()
  return graphDocs(props.kbId).then(res => {
    const map = {}
    for (const g of (res.data || [])) {
      if (g && g.docId != null) map[g.docId] = g
    }
    graphByDoc.value = map
  }).catch(() => {
    // 无权限或接口失败时不打断列表
  })
}
async function downloadOne(doc) {
  if (!doc?.downloadable || !doc?.docId) return
  try {
    const data = await downloadKbDocument(props.kbId, doc.docId)
    const isBlob = blobValidate(data)
    if (isBlob) {
      saveAs(new Blob([data]), doc.docName || ('doc-' + doc.docId))
    } else {
      const resText = await data.text()
      const rspObj = JSON.parse(resText)
      ElMessage.error(errorCode[rspObj.code] || rspObj.msg || errorCode['default'])
    }
  } catch (e) {
    console.error(e)
    ElMessage.error('下载文件出现错误，请联系管理员！')
  }
}
function itemStatusLabel(s) {
  return ({
    queued: '等待上传',
    uploading: '上传中',
    success: '已提交处理',
    failed: '失败',
    duplicate: '重复已跳过',
    skipped: '已跳过'
  })[s] || s
}

function applyFilter() {
  let list = docs.value.slice()
  const k = (keyword.value || '').trim().toLowerCase()
  if (k) list = list.filter(d => (d.docName || '').toLowerCase().includes(k))
  if (statusFilter.value) list = list.filter(d => productOf(d) === statusFilter.value)
  if (typeFilter.value) list = list.filter(d => String(d.fileType || '').toLowerCase() === typeFilter.value)
  filtered.value = list
}

/**
 * 拉取文档列表。
 * @param {{ silent?: boolean }} [opts]
 *   silent=true：进度轮询用，不打开全屏 v-loading，避免「上传后一直闪刷新」。
 */
function loadDocs(opts = {}) {
  if (!props.kbId) return
  const silent = !!opts.silent
  // 静默轮询时若上一次还在飞，跳过本轮，防止请求堆积抖动
  if (silent && docsLoadingInFlight) return
  docsLoadingInFlight = true
  const seq = ++docsLoadSeq
  // 已有列表时轮询绝不挡交互；仅首次/手动刷新才盖 loading
  if (!silent || !docs.value.length) {
    loading.value = true
  }
  listKbDoc(props.kbId, { pageNum: 1, pageSize: 500 }).then(res => {
    if (seq !== docsLoadSeq) return
    docs.value = res.rows || []
    emit('doc-count', res.total || docs.value.length)
    applyFilter()
    // 同步批次中已关联 doc 的进度
    syncBatchFromDocs()
    loading.value = false
    // 并行拉图谱状态（索引完成后图谱可能仍在抽）
    loadGraphStatuses()
  }).catch(() => {
    if (seq === docsLoadSeq) loading.value = false
  }).finally(() => {
    if (seq === docsLoadSeq) docsLoadingInFlight = false
  })
}

function syncBatchFromDocs() {
  if (!batch.value) return
  const byId = {}
  for (const d of docs.value) byId[d.docId] = d
  let changed = false
  for (const item of batch.value.items) {
    if (!item.docId || !byId[item.docId]) continue
    const d = byId[item.docId]
    if (item.status === 'uploading' || item.status === 'success' || item.status === 'queued') {
      // 上传已完成的，保持 success；不把服务端失败覆盖为失败以外的状态
      if (d.parseStatus === 'FAILED' && item.status === 'success') {
        /* 文档处理失败单独在列表体现 */
      }
    }
    const nextProgress = d.progress || 0
    // 仅进度真变化时写回，避免 deep watch + localStorage 无意义抖动
    if (item.progress !== nextProgress) {
      item.progress = nextProgress
      changed = true
    }
  }
  if (changed) persistBatch()
}

function persistBatch() {
  if (!batch.value) {
    clearBatch(props.kbId)
    return
  }
  batch.value.onDuplicate = onDuplicate.value
  saveBatch(props.kbId, batch.value)
}

function restoreBatch() {
  const b = loadBatch(props.kbId)
  if (b) {
    // localStorage 无法保存 File：queued/uploading 一律标失败，提示重选
    for (const item of b.items || []) {
      item._file = null
      if (item.status === 'uploading' || item.status === 'queued') {
        item.status = 'failed'
        item.error = item.error || '页面刷新后文件句柄已失效，请点「重选文件」'
      }
    }
    batch.value = b
    onDuplicate.value = b.onDuplicate || 'skip'
    persistBatch()
    const s = batchSummary(b.items)
    if (s.failed > 0) drawerOpen.value = true
  }
}

function enqueueFiles(fileList) {
  const files = Array.from(fileList || []).filter(f => f && (f.raw || f.size != null))
  if (!files.length) return
  if (!batch.value) {
    batch.value = { batchId: newBatchId(), createdAt: Date.now(), items: [], onDuplicate: onDuplicate.value }
  }
  for (const f of files) {
    const raw = f.raw || f
    const name = raw.name || f.name
    batch.value.items.push({
      uid: 'f_' + Date.now().toString(36) + '_' + Math.random().toString(36).slice(2, 7),
      name,
      size: raw.size,
      status: 'queued',
      docId: null,
      error: null,
      progress: 0,
      _file: raw
    })
  }
  persistBatch()
  drawerOpen.value = true
}

function onPickFiles(file, fileList) {
  // el-upload on-change 每个文件触发；用 file.raw
  if (file && file.raw) {
    // 避免重复：只在 status 为 ready 时入队一次
    if (file.status === 'ready') {
      enqueueFiles([file])
    }
  }
  // 清空 el-upload 内部列表，避免堆积
  if (fileList && fileList.length > 8) fileList.splice(0, fileList.length)
}

function onDrop(e) {
  dragOver.value = false
  const files = e.dataTransfer?.files
  if (files && files.length) enqueueFiles(files)
}

async function startUpload() {
  if (!batch.value || uploading.value) return
  const uploadable = batch.value.items.filter(
    i => (i.status === 'queued' || i.status === 'failed') && i._file
  )
  const stale = batch.value.items.filter(
    i => (i.status === 'queued' || i.status === 'failed') && !i._file
  )
  for (const item of stale) {
    item.status = 'failed'
    item.error = '页面刷新后文件句柄已失效，请点「重选文件」'
  }
  if (stale.length) persistBatch()
  if (!uploadable.length) {
    if (stale.length) {
      proxy.$modal.msgWarning('没有可上传的文件。请对失败项点「重选文件」后再试')
    }
    return
  }

  uploading.value = true
  const policy = onDuplicate.value
  let ran = 0
  for (const item of batch.value.items) {
    if (item.status !== 'queued' && item.status !== 'failed') continue
    if (!item._file) continue
    ran++
    item.status = 'uploading'
    item.error = null
    persistBatch()
    try {
      const fd = new FormData()
      fd.append('file', item._file, item.name || item._file.name)
      const res = await uploadKbDoc(props.kbId, fd, policy)
      const data = res.data || {}
      const doc = data.doc || data
      item.docId = doc?.docId ?? null
      if (data.duplicate) {
        item.status = 'duplicate'
      } else {
        item.status = 'success'
      }
      persistBatch()
    } catch (err) {
      item.status = 'failed'
      item.error = extractErrMsg(err)
      persistBatch()
    }
  }
  uploading.value = false
  if (ran > 0) {
    const s = batchSummary(batch.value.items)
    const toast = `上传完成：成功 ${s.success}，重复 ${s.duplicate}，失败 ${s.failed}`
    if (s.failed > 0 && s.success + s.duplicate === 0) {
      proxy.$modal.msgError(toast)
    } else {
      proxy.$modal.msgSuccess(toast)
    }
    loadDocs()
  }
}

function retryItem(item) {
  if (!item || uploading.value) return
  // 刷新后 File 丢失：触发系统文件选择，再入队重传
  if (!item._file) {
    repickUid.value = item.uid
    const input = repickInputRef.value
    if (input) {
      input.value = ''
      input.click()
    }
    return
  }
  item.status = 'queued'
  item.error = null
  persistBatch()
  startUpload()
}

function onRepickFile(e) {
  const file = e?.target?.files?.[0]
  const uid = repickUid.value
  repickUid.value = null
  if (!file || !uid || !batch.value) return
  const item = batch.value.items.find(i => i.uid === uid)
  if (!item) return
  item._file = file
  item.name = file.name
  item.size = file.size
  item.status = 'queued'
  item.error = null
  item.docId = null
  persistBatch()
  startUpload()
}

function removeItem(item) {
  if (!batch.value || !item || uploading.value) return
  batch.value = {
    ...batch.value,
    items: batch.value.items.filter(i => i.uid !== item.uid)
  }
  if (!batch.value.items.length) {
    batch.value = null
    clearBatch(props.kbId)
  } else {
    persistBatch()
  }
}

function clearFinished() {
  if (!batch.value || uploading.value) return
  const next = batch.value.items.filter(
    i => !['success', 'duplicate', 'skipped'].includes(i.status)
  )
  if (next.length === batch.value.items.length) {
    proxy.$modal.msgWarning('没有已完成的任务可清理（失败项请用「清空任务」或单条「移除」）')
    return
  }
  if (!next.length) {
    batch.value = null
    clearBatch(props.kbId)
  } else {
    batch.value = { ...batch.value, items: next }
    persistBatch()
  }
}

function clearAllTasks() {
  if (!batch.value || uploading.value) return
  proxy.$modal.confirm('确认清空全部上传任务？未成功上传的文件需重新选择。').then(() => {
    batch.value = null
    clearBatch(props.kbId)
    drawerOpen.value = false
  }).catch(() => {})
}

function toggleRow(doc) {
  const id = doc.docId
  const i = selectedIds.value.indexOf(id)
  if (i >= 0) selectedIds.value.splice(i, 1)
  else selectedIds.value.push(id)
}

function openDetail(doc, tab) {
  if (!canRead.value) return
  detailDoc.value = doc
  // 产品默认只开预览；诊断入口已从详情页移除
  detailInitialTab.value = tab === 'diagnostics' ? 'preview' : (tab || 'preview')
  detailOpen.value = true
}

/**
 * 供搜索命中回跳：不限于当前分页，拉取安全详情后打开抽屉。
 */
async function openDocument(docId) {
  if (!docId || !props.kbId || !canRead.value) return
  const local = (docs.value || []).find(d => d.docId === docId || String(d.docId) === String(docId))
  if (local) {
    openDetail(local, 'preview')
    return
  }
  try {
    const res = await getKbDocument(props.kbId, docId)
    const doc = res.data || res
    if (doc?.docId) openDetail(doc, 'preview')
    else ElMessage.warning('文档不存在或无权查看')
  } catch (e) {
    ElMessage.error(e?.msg || e?.message || '无法打开文档')
  }
}

defineExpose({ openDocument })

function closeDetail() {
  detailOpen.value = false
}

function onDetailReprocess(doc) {
  reprocessOne(doc)
  // 保持抽屉打开，稍后刷新列表后预览会更新
}

function onOpenGraph(doc) {
  emit('open-graph', doc)
}

function toggleAll(e) {
  if (e.target.checked) {
    selectedIds.value = filtered.value.map(d => d.docId)
  } else {
    selectedIds.value = []
  }
}

function deleteOne(doc) {
  proxy.$modal.confirm(`确认删除文档「${doc.docName}」？删除后索引与图谱证据将清理。`).then(() => {
    return delKbDoc(props.kbId, doc.docId)
  }).then(() => {
    proxy.$modal.msgSuccess('已删除')
    selectedIds.value = selectedIds.value.filter(id => id !== doc.docId)
    loadDocs()
  }).catch(() => {})
}

function batchDelete() {
  if (!selectedIds.value.length) return
  const n = selectedIds.value.length
  proxy.$modal.confirm(`确认删除选中的 ${n} 篇文档？将清理对应索引与图谱证据。`).then(() => {
    return delKbDoc(props.kbId, selectedIds.value.join(','))
  }).then(() => {
    proxy.$modal.msgSuccess('已删除')
    selectedIds.value = []
    loadDocs()
  }).catch(() => {})
}

function reprocessOne(doc) {
  if (!doc?.docId) return
  const status = productOf(doc)
  const name = doc.docName || '该文件'
  // 可用态重试会重建索引，必须二次确认；失败态可直接重试
  const run = () => reprocessKbDoc(props.kbId, doc.docId).then(() => {
    proxy.$modal.msgSuccess('已重新提交处理')
    loadDocs()
  }).catch(() => {})

  if (status === 'READY') {
    proxy.$modal.confirm(
      `「${name}」当前状态为可用。重新处理将重新解析并重建索引，期间检索可能短暂不可用。确认继续？`
    ).then(run).catch(() => {})
    return
  }
  run()
}

function batchReprocess() {
  if (!selectedIds.value.length) return
  const selected = (docs.value || []).filter(d => selectedIds.value.includes(d.docId))
  const readyCount = selected.filter(d => productOf(d) === 'READY').length
  const n = selectedIds.value.length
  let tip = `确认重新处理选中的 ${n} 个文件？`
  if (readyCount > 0) {
    tip = `选中 ${n} 个文件，其中 ${readyCount} 个为「可用」。重新处理将重建索引，期间检索可能短暂不可用。确认继续？`
  }
  proxy.$modal.confirm(tip).then(() => {
    return batchReprocessKbDoc(props.kbId, selectedIds.value)
  }).then(() => {
    proxy.$modal.msgSuccess('已批量重新提交')
    loadDocs()
  }).catch(() => {})
}

function hasBusyDocs() {
  return (docs.value || []).some(d => isBusy(d))
}

function hasActiveUpload() {
  if (uploading.value) return true
  const items = batch.value?.items || []
  return items.some(i => i.status === 'uploading' || i.status === 'queued')
}

/** 索引处理中 / 图谱抽取中 / 上传中 → 继续轮询 */
function needsProgressPolling() {
  return !!props.kbId && (hasBusyDocs() || hasActiveUpload() || hasGraphBusy())
}

function ensureProgressPolling() {
  if (!needsProgressPolling()) {
    stopProgressPolling()
    return
  }
  if (progressPollTimer) return
  progressPollTimer = setInterval(() => {
    if (!needsProgressPolling()) {
      // 再拉一次图谱，避免停轮询时漏最终 COMPLETED
      loadGraphStatuses()
      stopProgressPolling()
      return
    }
    // 静默刷新：只更新进度数字，不要每次盖全屏 loading
    if (hasBusyDocs() || hasActiveUpload()) {
      loadDocs({ silent: true })
    } else {
      loadGraphStatuses()
    }
  }, 2800)
}

function stopProgressPolling() {
  if (progressPollTimer) {
    clearInterval(progressPollTimer)
    progressPollTimer = null
  }
}

watch(() => props.kbId, () => {
  selectedIds.value = []
  detailOpen.value = false
  detailDoc.value = null
  batch.value = null
  graphByDoc.value = {}
  restoreBatch()
  stopProgressPolling()
  loadDocs()
}, { immediate: true })

// 文档列表、图谱状态或上传变化后按需启停轮询
watch([docs, uploading, batch, graphByDoc], () => {
  ensureProgressPolling()
}, { deep: true })

onUnmounted(() => stopProgressPolling())
</script>

<style scoped lang="scss">
@use '../../../../assets/styles/ai-tokens.scss' as *;

.cwb {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 0;
  flex: 1;
  font-family: $font;
}
.cwb-toolbar {
  display: flex; flex-wrap: wrap; gap: 8px; justify-content: space-between; align-items: center;
  flex-shrink: 0;
}
.cwb-toolbar__left, .cwb-toolbar__right { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
.cwb-input {
  height: 32px; min-width: 150px; max-width: 220px; border: none; border-radius: 980px;
  padding: 0 12px; background: var(--ai-search-bg); font-size: 13px; color: $text; outline: none;
  font-family: $font; box-shadow: 0 1px 3px var(--ai-border);
  &:focus { box-shadow: 0 0 0 3px rgba(10,132,255,0.12); background: var(--ai-card-bg); }
}
.cwb-select {
  height: 32px; border: none; border-radius: $radius-sm; padding: 0 26px 0 10px;
  background: var(--ai-search-bg); font-size: 12.5px; color: $text; font-family: $font;
  box-shadow: 0 1px 3px var(--ai-border); appearance: none; cursor: pointer;
  background-image: url("data:image/svg+xml,%3Csvg width='10' height='6' viewBox='0 0 10 6' fill='none' xmlns='http://www.w3.org/2000/svg'%3E%3Cpath d='M1 1l4 4 4-4' stroke='%238E8E93' stroke-width='1.5' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E");
  background-repeat: no-repeat; background-position: right 8px center;
}
.cwb-btn {
  height: 32px; border: none; background: var(--ai-card-bg); border-radius: 980px;
  padding: 0 12px; font-size: 12.5px; font-weight: 500; color: $text; cursor: pointer;
  display: inline-flex; align-items: center; gap: 5px; font-family: $font;
  box-shadow: 0 0 0 1px var(--ai-border-2); transition: all 0.18s $ease;
  &:hover { background: var(--ai-fill-1); }
  &:disabled { opacity: 0.5; cursor: not-allowed; }
  &--primary {
    background: $blue; color: #fff; box-shadow: 0 2px 10px rgba(10,132,255,0.28);
    &:hover { background: #0071e3; }
  }
  &.is-danger { color: $red; box-shadow: 0 0 0 1px rgba(255,59,48,0.28); }
}
.cwb-badge {
  min-width: 16px; height: 16px; border-radius: 980px; background: $orange; color: #fff;
  font-size: 10px; display: inline-flex; align-items: center; justify-content: center; padding: 0 4px;
}

.cwb-drop {
  border: 1.5px dashed var(--ai-border-2); border-radius: 14px; background: rgba(10,132,255,0.03);
  transition: border-color .15s, background .15s; padding: 28px 16px;
  &.is-over { border-color: $blue; background: rgba(10,132,255,0.08); }
}
.cwb-drop__empty { text-align: center; color: $gray3; font-size: 13px;
  p { margin: 6px 0 4px; color: $text; font-size: 14px; font-weight: 500; }
}
.cwb-drop__icon { font-size: 26px; }

.cwb-selectbar__all {
  display: inline-flex;
  align-items: center;
  cursor: pointer;
  input { width: 14px; height: 14px; accent-color: $blue; cursor: pointer; margin: 0; }
}
.cwb-selectbar__count {
  font-size: 12px;
  color: $gray;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.cwb-grid-wrap {
  flex: 1;
  min-height: 180px;
  border-radius: 14px;
  transition: background 0.15s $ease, box-shadow 0.15s $ease;
  &.is-drag {
    background: rgba(10, 132, 255, 0.04);
    box-shadow: inset 0 0 0 1.5px rgba(10, 132, 255, 0.35);
  }
}
.cwb-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 12px;
  @media (max-width: 640px) { grid-template-columns: 1fr; }
}
.cwb-empty-filter {
  padding: 36px 20px;
  text-align: center;
  color: $gray3;
  font-size: 13.5px;
  border: 1px dashed var(--ai-border);
  border-radius: 14px;
  background: var(--ai-fill-1);
}

.link {
  border: none; background: transparent; color: $blue; cursor: pointer; font-size: 12.5px; padding: 0 6px 0 0;
  text-decoration: none; font-family: $font;
  &.is-danger { color: $red; }
  &:disabled { opacity: 0.5; }
}

.cwb-diag-hint {
  font-size: 12.5px; color: $gray; padding: 8px 4px;
}

/* drawer */
.cwb-drawer__mask {
  position: fixed; inset: 0; background: var(--ai-overlay); backdrop-filter: blur(5px);
  z-index: 2000; opacity: 0; pointer-events: none; transition: opacity .2s;
}
.cwb-drawer__panel {
  position: fixed; top: 0; right: 0; width: min(400px, 100vw); height: 100vh; background: var(--ai-sheet-bg);
  z-index: 2001; box-shadow: var(--ai-shadow-sheet);
  transform: translateX(100%); transition: transform .22s $ease;
  display: flex; flex-direction: column;
}
.cwb-drawer.is-open {
  .cwb-drawer__mask { opacity: 1; pointer-events: auto; }
  .cwb-drawer__panel { transform: translateX(0); }
}
.cwb-drawer__head {
  display: flex; justify-content: space-between; align-items: flex-start; padding: 18px 18px 12px;
  border-bottom: 1px solid var(--ai-border);
  h3 { margin: 0 0 4px; font-size: 16px; }
  p { margin: 0; font-size: 12px; color: $gray; }
}
.cwb-drawer__close {
  border: none; background: transparent; font-size: 22px; color: $gray; cursor: pointer; line-height: 1;
}
.cwb-drawer__policy {
  display: flex; align-items: center; justify-content: space-between; gap: 10px;
  padding: 10px 18px; font-size: 13px; color: $gray; border-bottom: 1px solid var(--ai-border);
}
.cwb-drawer__list {
  list-style: none; margin: 0; padding: 8px 0; overflow-y: auto; flex: 1;
}
.cwb-item {
  padding: 10px 18px; border-bottom: 1px solid var(--ai-border);
  display: grid; grid-template-columns: 1fr auto; gap: 4px 8px;
}
.cwb-item__name { font-size: 13px; font-weight: 500; color: $text; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; grid-column: 1 / -1; }
.cwb-item__meta { font-size: 12px; color: $gray; }
.cwb-item__err { color: $red; margin-left: 6px; }
.cwb-item__ops { text-align: right; }
.st {
  font-weight: 600;
  &.is-success, &.is-duplicate { color: #248a3d; }
  &.is-failed { color: #ff3b30; }
  &.is-uploading { color: $blue; }
}
.cwb-drawer__empty { padding: 40px; text-align: center; color: $gray3; font-size: 13px; }
.cwb-drawer__foot {
  padding: 12px 18px; border-top: 1px solid var(--ai-border); display: flex; gap: 8px; justify-content: flex-end; flex-wrap: wrap;
}
.cwb-hidden-file { position: absolute; width: 0; height: 0; opacity: 0; pointer-events: none; }
.cwb-item__ops { display: flex; gap: 4px; flex-wrap: wrap; justify-content: flex-end; }
</style>
