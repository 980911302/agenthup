<template>
  <section class="knowledge-view" :class="{ 'knowledge-view--detail': activeKb }">
    <template v-if="!activeKb">
      <header class="knowledge-header">
        <div>
          <h1>知识库</h1>
          <p>管理可在对话中引用的资料。上传完成后，在输入框的「知识库」中选择即可使用。</p>
        </div>
        <button type="button" class="primary-button" @click="openCreate">
          <span aria-hidden="true">＋</span> 新建知识库
        </button>
      </header>

      <div class="knowledge-content">
        <AppPageLoader v-if="loading" compact label="正在加载知识库…" />
        <div v-else-if="!kbs.length" class="state-box state-box--empty app-page-enter">
          <span class="state-box__icon" aria-hidden="true">▤</span>
          <h2>还没有知识库</h2>
          <p>先创建一个知识库，再上传要让 AI 检索的文档。</p>
          <button type="button" class="secondary-button" @click="openCreate">创建第一个知识库</button>
        </div>
        <div v-else class="kb-grid app-page-enter" aria-label="知识库列表">
          <article v-for="kb in kbs" :key="kb.kbId" class="kb-card" @click="openDetail(kb)">
            <div class="kb-card__head">
              <span class="kb-row__icon" aria-hidden="true">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none"><ellipse cx="12" cy="5" rx="8.5" ry="3"/><path d="M3.5 5v14c0 1.7 3.8 3 8.5 3s8.5-1.3 8.5-3V5M3.5 12c0 1.7 3.8 3 8.5 3s8.5-1.3 8.5-3"/></svg>
              </span>
              <div class="kb-card__title"><strong :title="kb.kbName">{{ kb.kbName }}</strong><small>{{ visibilityLabel(kb.visibility) }}</small></div>
              <div class="kb-row__menu" @click.stop>
              <button type="button" class="icon-button" :aria-label="`操作 ${kb.kbName}`" @click="menuKbId = menuKbId === kb.kbId ? null : kb.kbId">•••</button>
              <div v-if="menuKbId === kb.kbId" class="row-menu">
                <button type="button" @click="openEdit(kb)">编辑知识库</button>
                <button type="button" class="row-menu__danger" @click="confirmDeleteKb(kb)">删除知识库</button>
              </div>
              </div>
            </div>
            <p class="kb-card__description">{{ kb.description || '暂无描述，点击进入后可上传和管理知识文件。' }}</p>
            <footer class="kb-card__footer"><span>资料与索引管理</span><b>管理文件 <i>→</i></b></footer>
          </article>
        </div>
      </div>
    </template>

    <template v-else>
      <header class="detail-header">
        <div class="detail-header__inner">
          <button type="button" class="back-button" @click="closeDetail">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="m15 18-6-6 6-6"/></svg>
            <span>知识库</span>
          </button>
          <i class="detail-header__divider" aria-hidden="true"></i>
          <h1>{{ activeKb.kbName }}</h1>
          <div class="detail-header__actions">
            <input ref="fileInput" type="file" multiple hidden @change="onPickFiles">
            <button type="button" class="secondary-button graph-entry" @click="openKnowledgeGraph()">
              <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true"><circle cx="4" cy="4" r="1.6"/><circle cx="12" cy="5" r="1.6"/><circle cx="8" cy="12" r="1.6"/><path d="m5.4 4.8 5.2.6M5 5.4l2.4 5.2m3.8-4.4L9 10.6"/></svg>
              知识图谱
            </button>
            <button type="button" class="primary-button" :disabled="uploading" @click="fileInput?.click()">
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M12 5v14M5 12h14"/></svg>
              {{ uploading ? '上传中…' : '上传文档' }}
            </button>
            <div class="kb-row__menu" @click.stop>
              <button type="button" class="icon-button detail-more" aria-label="更多操作" @click="menuKbId = menuKbId === activeKb.kbId ? null : activeKb.kbId">
                <svg width="17" height="17" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><circle cx="5" cy="12" r="1.5"/><circle cx="12" cy="12" r="1.5"/><circle cx="19" cy="12" r="1.5"/></svg>
              </button>
              <div v-if="menuKbId === activeKb.kbId" class="row-menu">
                <button type="button" @click="openEdit(activeKb)">编辑知识库</button>
                <button type="button" class="row-menu__danger" @click="confirmDeleteKb(activeKb)">删除知识库</button>
              </div>
            </div>
          </div>
        </div>
      </header>

      <div class="detail-content">
        <section class="detail-intro">
          <p>{{ activeKb.description || '上传资料后，可在对话中选择此知识库，让 AI 检索并引用其中的内容。' }}</p>
          <div class="access-meta"><span>{{ visibilityLabel(activeKb.visibility) }}</span><small>创建者：{{ ownerDisplayName }}</small></div>
        </section>

        <div class="documents-filter-row">
          <div class="documents-filter">
            <svg width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true"><circle cx="7" cy="7" r="4.5" stroke="currentColor" stroke-width="1.5"/><path d="m10.5 10.5 3 3" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>
            <input v-model.trim="documentQuery" type="search" placeholder="按名称搜索文件">
            <button v-if="documentQuery" type="button" title="清除搜索" @click="documentQuery = ''">×</button>
          </div>
          <select v-model="uploaderFilter" class="uploader-filter" aria-label="按上传用户筛选">
            <option value="all">全部上传用户</option>
            <option v-for="name in uploaders" :key="name" :value="name">{{ name }}</option>
          </select>
        </div>

        <AppPageLoader v-if="docsLoading" compact label="正在加载文件…" />
        <div v-else-if="!docs.length" class="state-box state-box--empty documents-empty app-page-enter" @dragover.prevent @drop.prevent="onDropFiles">
          <span class="state-box__icon" aria-hidden="true">⌑</span><h2>这个知识库还没有文件</h2><p>点击下方按钮或把文件拖到这里，即可开始上传和解析。</p><button type="button" class="secondary-button" @click="fileInput?.click()">上传第一个文件</button>
        </div>
        <div v-else-if="!filteredDocs.length" class="state-box state-box--empty documents-empty">
          <span class="state-box__icon" aria-hidden="true">⌕</span><h2>没有匹配的文件</h2><p>换一个关键词试试，或清除搜索条件。</p><button type="button" class="secondary-button" @click="documentQuery = ''">清除搜索</button>
        </div>
        <div v-else class="document-list app-page-enter" @dragover.prevent @drop.prevent="onDropFiles">
          <article v-for="doc in filteredDocs" :key="doc.docId" class="document-row">
            <div class="document-row__main">
              <div class="document-row__name">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8Z"/><path d="M14 2v6h6M8 13h8M8 17h6"/></svg>
                <span class="document-row__title"><strong :title="docName(doc)">{{ docName(doc) }}</strong><em class="status-badge" :class="`status-badge--${docStatus(doc).toLowerCase()}`">{{ docStatusLabel(doc) }}</em></span>
              </div>
              <div class="document-row__meta">{{ formatFileSize(doc.fileSize) || '—' }} · {{ doc.chunkCount ?? 0 }} 个切片 · 创建于 {{ formatRelativeDate(doc.createTime) }}</div>
              <div class="document-row__uploader">由 {{ doc.createBy || ownerDisplayName }} 上传</div>
              <div v-if="docStatus(doc) === 'FAILED'" class="document-row__error" :title="docError(doc)">
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M10.3 2.9 1.8 17a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 2.9a2 2 0 0 0-3.4 0Z"/><path d="M12 9v4M12 17h.01"/></svg>
                <span>{{ docError(doc) }}</span>
              </div>
              <div v-else-if="isBusy(doc)" class="document-row__progress"><i><b :style="{ width: docProgress(doc) + '%' }"></b></i></div>
            </div>
            <div class="document-row__actions">
              <button type="button" :disabled="graphBusy(doc)" :title="graphBusy(doc) ? graphStatusText(doc) : '查看文件知识图谱'" @click="openKnowledgeGraph(doc)"><svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true"><circle cx="4" cy="4" r="1.5"/><circle cx="12" cy="5" r="1.5"/><circle cx="8" cy="12" r="1.5"/><path d="m5.3 4.8 5.3.5M5 5.3l2.3 5.3m3.8-4.3-2.2 4.3"/></svg>{{ graphBusy(doc) ? graphStatusText(doc) : '图谱' }}</button>
              <button type="button" @click="openPreview(doc)"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/></svg>预览</button>
              <button v-if="docStatus(doc) === 'FAILED'" type="button" @click="retryDoc(doc)"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M20 7v5h-5M4 17v-5h5"/><path d="M6.1 9a7 7 0 0 1 11.5-2L20 12M4 12l2.4 5a7 7 0 0 0 11.5-2"/></svg>重试</button>
              <button type="button" @click="openRenameDoc(doc)"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M12 20h9M16.5 3.5a2.1 2.1 0 0 1 3 3L8 18l-4 1 1-4Z"/></svg>重命名</button>
              <button type="button" class="danger-text" @click="confirmDeleteDoc(doc)"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M3 6h18M8 6V4h8v2M19 6l-1 15H6L5 6M10 11v6M14 11v6"/></svg>删除</button>
            </div>
          </article>
        </div>
      </div>
    </template>

    <Teleport to="body">
      <Transition name="app-modal">
        <div v-if="showEditor" ref="editorDialogRoot" class="modal-mask" @click.self="closeEditor">
          <form class="editor-modal" data-dialog-surface @submit.prevent="saveKb">
        <header><h2>{{ editingKb ? '编辑知识库' : '新建知识库' }}</h2><button type="button" class="icon-button" @click="closeEditor">×</button></header>
        <label>名称<input v-model="editor.kbName" maxlength="100" autofocus placeholder="例如：产品资料库"></label>
        <label>描述（可选）<textarea v-model="editor.description" maxlength="200" rows="3" placeholder="说明这个知识库包含哪些资料"></textarea></label>
        <footer><button type="button" class="secondary-button" @click="closeEditor">取消</button><button type="submit" class="primary-button" :disabled="saving || !editor.kbName.trim()">{{ saving ? '保存中…' : '保存' }}</button></footer>
          </form>
        </div>
      </Transition>
    </Teleport>

    <Teleport to="body">
      <Transition name="app-modal">
        <div v-if="showRenameDoc" ref="renameDialogRoot" class="modal-mask" @click.self="closeRenameDoc">
          <form class="editor-modal rename-modal" data-dialog-surface @submit.prevent="saveDocName">
        <header><h2>重命名文档</h2><button type="button" class="icon-button" @click="closeRenameDoc">×</button></header>
        <label>文档名称<input v-model="renameDocName" maxlength="255" autofocus></label>
        <footer><button type="button" class="secondary-button" @click="closeRenameDoc">取消</button><button type="submit" class="primary-button" :disabled="renamingDoc || !renameDocName.trim()">{{ renamingDoc ? '保存中…' : '保存' }}</button></footer>
          </form>
        </div>
      </Transition>
    </Teleport>

    <Teleport to="body">
      <Transition name="app-modal">
        <div v-if="preview.open" ref="previewDialogRoot" class="modal-mask preview-mask" @click.self="closePreview">
          <section class="preview-modal" data-dialog-surface>
        <header><div><h2 :title="preview.name">{{ preview.name }}</h2><p>{{ activeKb?.kbName }}</p></div><div><button v-if="preview.downloadable" type="button" class="secondary-button" @click="downloadDoc(preview.doc)">下载原文件</button><button type="button" class="icon-button" @click="closePreview">×</button></div></header>
        <div v-if="preview.loading" class="state-box">正在加载解析预览…</div>
        <div v-else-if="preview.error" class="state-box state-box--empty"><h2>暂时无法预览</h2><p>{{ preview.error }}</p><button v-if="preview.doc && docStatus(preview.doc) === 'FAILED'" type="button" class="secondary-button" @click="retryPreview">重新处理</button></div>
        <div v-else class="preview-modal__body">
          <aside v-if="preview.outline.length"><strong>目录</strong><button v-for="(item, index) in preview.outline" :key="index" type="button" :style="{ paddingLeft: `${8 + (item.level || 1) * 8}px` }" @click="scrollPreview(item.position)">{{ item.title || '未命名段落' }}</button></aside>
          <main ref="previewBody"><template v-for="block in preview.blocks" :key="block.position"><div class="preview-block" :data-position="block.position" v-html="sanitize(block.html)"></div></template><template v-for="table in preview.tables" :key="`table-${table.position}`"><div class="preview-block" :data-position="table.position" v-html="sanitize(table.html)"></div></template><p v-if="!preview.blocks.length && !preview.tables.length" class="state-box">暂无可展示的解析内容</p></main>
        </div>
          </section>
        </div>
      </Transition>
    </Teleport>

    <Suspense v-if="activeKb">
      <KnowledgeGraphViewer
        v-model:open="graphViewer.open"
        :kb-id="activeKb.kbId"
        :doc-id="graphViewer.docId"
        :doc-name="graphViewer.docName"
      />
      <template #fallback>
        <Teleport to="body">
          <div v-if="graphViewer.open" class="modal-mask graph-chunk-loading-mask">
            <div class="graph-chunk-loading-card app-page-enter" data-dialog-surface>
              <AppPageLoader compact label="正在打开知识图谱…" />
            </div>
          </div>
        </Teleport>
      </template>
    </Suspense>
  </section>
</template>

<script setup>
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, ref } from 'vue'
import DOMPurify from 'dompurify'
import { addKb, delKb, delKbDoc, downloadKbDocBlob, getKbDocPreview, getKbDocument, graphDocs, listDesktopKbs, listKbDoc, renameKbDoc, reprocessKbDoc, subscribeKbDocumentEvents, updateKb, uploadKbDoc } from '../api/kb'
import { useAuthStore } from '../stores/auth'
import { confirmDanger, toast } from '../utils/confirm'
import AppPageLoader from '../components/AppPageLoader.vue'
import { useDialogLifecycle } from '../composables/useDialogLifecycle'

const emit = defineEmits(['open-chat', 'kbs-changed'])
const KnowledgeGraphViewer = defineAsyncComponent(() => import('../components/KnowledgeGraphViewer.vue'))
const auth = useAuthStore()
const kbs = ref([])
const docs = ref([])
const graphByDoc = ref({})
const loading = ref(false)
const docsLoading = ref(false)
const documentQuery = ref('')
const uploaderFilter = ref('all')
const activeKb = ref(null)
const menuKbId = ref(null)
const fileInput = ref(null)
const uploading = ref(false)
const overwrite = ref(false)
const showEditor = ref(false)
const editingKb = ref(null)
const saving = ref(false)
const editor = ref({ kbName: '', description: '' })
const showRenameDoc = ref(false)
const renamingDoc = ref(false)
const renameTarget = ref(null)
const renameDocName = ref('')
const previewBody = ref(null)
const preview = ref({ open: false, loading: false, error: '', name: '', doc: null, downloadable: false, outline: [], blocks: [], tables: [] })
const editorDialogRoot = ref(null)
const renameDialogRoot = ref(null)
const previewDialogRoot = ref(null)
useDialogLifecycle(showEditor, closeEditor, editorDialogRoot)
useDialogLifecycle(showRenameDoc, closeRenameDoc, renameDialogRoot)
useDialogLifecycle(computed(() => preview.value.open), closePreview, previewDialogRoot)
const graphViewer = ref({ open: false, docId: null, docName: '' })
let progressController = null

const filteredDocs = computed(() => {
  const query = documentQuery.value.trim().toLowerCase()
  return docs.value.filter(doc => {
    const matchesName = !query || docName(doc).toLowerCase().includes(query)
    const matchesUploader = uploaderFilter.value === 'all' || (doc.createBy || ownerDisplayName.value) === uploaderFilter.value
    return matchesName && matchesUploader
  })
})
const ownerDisplayName = computed(() => auth.user?.nickName || auth.user?.userName || '我')
const uploaders = computed(() => [...new Set(docs.value.map(doc => doc.createBy || ownerDisplayName.value).filter(Boolean))])

async function loadKbs() {
  loading.value = true
  try {
    const res = await listDesktopKbs({ status: '0' })
    kbs.value = res.data || []
    if (activeKb.value) activeKb.value = kbs.value.find(item => Number(item.kbId) === Number(activeKb.value.kbId)) || activeKb.value
  } catch (error) {
    kbs.value = []
    toast(error.message || '加载知识库失败')
  } finally { loading.value = false }
}

async function openDetail(kb) {
  stopProgressStream()
  activeKb.value = kb
  docs.value = []
  documentQuery.value = ''
  uploaderFilter.value = 'all'
  await loadDocs()
  if (Number(activeKb.value?.kbId) === Number(kb.kbId)) startProgressStream()
}
function closeDetail() { stopProgressStream(); activeKb.value = null; docs.value = []; graphByDoc.value = {}; documentQuery.value = ''; uploaderFilter.value = 'all'; graphViewer.value.open = false; closePreview() }
async function loadDocs() {
  if (!activeKb.value) return
  docsLoading.value = true
  try {
    const res = await listKbDoc(activeKb.value.kbId, { pageNum: 1, pageSize: 200 })
    docs.value = res.rows || res.data || []
    await loadGraphStates()
  } catch (error) {
    docs.value = []
    toast(error.message || '加载文件失败')
  } finally { docsLoading.value = false }
}

function stopProgressStream() {
  progressController?.abort()
  progressController = null
}

function upsertDocument(next) {
  if (!next?.docId) return
  const index = docs.value.findIndex(item => Number(item.docId) === Number(next.docId))
  if (index >= 0) docs.value[index] = { ...docs.value[index], ...next }
  else docs.value = [next, ...docs.value]
}

function statusProduct(status) {
  const value = String(status || '').toUpperCase()
  if (value === 'COMPLETED' || value === 'READY') return 'READY'
  if (value === 'FAILED') return 'FAILED'
  if (value === 'PENDING' || value === 'QUEUED') return 'QUEUED'
  return 'PROCESSING'
}

async function refreshDocument(docId, kbId = activeKb.value?.kbId) {
  if (!docId || !kbId) return
  try {
    const res = await getKbDocument(kbId, docId)
    if (Number(activeKb.value?.kbId) !== Number(kbId)) return
    const doc = res.data || res
    upsertDocument({ ...doc, productStatus: doc.productStatus || statusProduct(doc.parseStatus) })
  } catch (_) { /* 状态流断续时保留最后一次有效状态 */ }
}

async function reconcileProgress(kbId) {
  if (Number(activeKb.value?.kbId) !== Number(kbId)) return
  const pendingIds = docs.value.filter(isBusy).map(doc => doc.docId)
  await Promise.all(pendingIds.map(docId => refreshDocument(docId, kbId)))
  if (Number(activeKb.value?.kbId) === Number(kbId)) await loadGraphStates()
}

function applyProgressEvent(event, kbId) {
  if (!event?.docId || Number(activeKb.value?.kbId) !== Number(kbId)) return
  const status = String(event.status || '').toUpperCase()
  if (status.startsWith('GRAPH_')) {
    const graphStatus = status.slice(6)
    const key = String(event.docId)
    graphByDoc.value = {
      ...graphByDoc.value,
      [key]: {
        ...(graphByDoc.value[key] || {}),
        docId: event.docId,
        graphStatus,
        graphStep: event.step,
        progress: event.progress
      }
    }
    if (graphStatus === 'COMPLETED' || graphStatus === 'FAILED') loadGraphStates()
    return
  }

  const index = docs.value.findIndex(item => Number(item.docId) === Number(event.docId))
  if (index >= 0) {
    docs.value[index] = {
      ...docs.value[index],
      parseStatus: status,
      parseStep: event.step || status,
      productStatus: statusProduct(status),
      progress: event.progress,
      ...(event.chunkCount != null ? { chunkCount: event.chunkCount } : {}),
      ...(event.errorMsg ? { errorMsg: event.errorMsg } : {})
    }
  }
  if (event.type === 'kb_completed' || event.type === 'kb_failed' || status === 'COMPLETED' || status === 'FAILED') {
    refreshDocument(event.docId, kbId)
  }
}

function startProgressStream() {
  const kbId = activeKb.value?.kbId
  if (!kbId) return
  stopProgressStream()
  const controller = new AbortController()
  progressController = controller
  subscribeKbDocumentEvents(kbId, {
    signal: controller.signal,
    onConnected: () => reconcileProgress(kbId),
    onEvent: event => applyProgressEvent(event, kbId)
  }).catch(error => {
    if (!controller.signal.aborted && Number(activeKb.value?.kbId) === Number(kbId)) {
      toast(error.message || '文档状态连接已断开，重新进入知识库即可恢复')
    }
  }).finally(() => {
    if (progressController === controller) progressController = null
  })
}
async function loadGraphStates() {
  if (!activeKb.value) return
  try {
    const res = await graphDocs(activeKb.value.kbId)
    const list = res.data || []
    graphByDoc.value = Object.fromEntries(list.map(item => [String(item.docId), item]))
  } catch (_) { graphByDoc.value = {} }
}
function graphInfo(doc) { return graphByDoc.value[String(doc?.docId)] || null }
function graphBusy(doc) { return ['PENDING', 'EXTRACTING', 'MERGING'].includes(String(graphInfo(doc)?.graphStatus || '').toUpperCase()) }
function graphStatusText(doc) {
  const info = graphInfo(doc)
  const status = String(info?.graphStatus || '').toUpperCase()
  const label = { PENDING: '图谱排队中', EXTRACTING: '图谱抽取中', MERGING: '图谱合并中' }[status] || '图谱处理中'
  const progress = Number(info?.progress)
  return Number.isFinite(progress) ? `${label} ${Math.max(0, Math.min(100, progress))}%` : label
}
function openKnowledgeGraph(doc = null) { graphViewer.value = { open: true, docId: doc?.docId || null, docName: doc ? docName(doc) : '' } }
function docName(doc) { return doc.docName || doc.name || `文件 #${doc.docId}` }
function docStatus(doc) {
  const current = String(doc.productStatus || doc.parseStatus || '').toUpperCase()
  if (current === 'READY' || current === 'COMPLETED') return 'READY'
  if (current === 'FAILED') return 'FAILED'
  if (current === 'PROCESSING' || ['PARSING', 'CHUNKING', 'EMBEDDING'].includes(current)) return 'PROCESSING'
  return 'QUEUED'
}
function docStatusLabel(doc) { return { READY: '就绪', FAILED: '失败', PROCESSING: '索引中…', QUEUED: '排队中' }[docStatus(doc)] }
function isBusy(doc) { return ['PROCESSING', 'QUEUED'].includes(docStatus(doc)) }
function docProgress(doc) { const value = Number(doc.progress); return Number.isFinite(value) && value >= 0 ? Math.min(value, 100) : docStatus(doc) === 'QUEUED' ? 0 : 10 }
function docError(doc) { const value = String(doc.errorMsg || doc.productError || '文件解析失败，请重新处理'); return value.length > 70 ? `${value.slice(0, 70)}…` : value }
function formatDate(value) { if (!value) return '—'; const date = new Date(value); if (Number.isNaN(date.getTime())) return String(value); return `${date.getFullYear()}/${String(date.getMonth() + 1).padStart(2, '0')}/${String(date.getDate()).padStart(2, '0')}` }
function formatRelativeDate(value) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return formatDate(value)
  const start = new Date(); start.setHours(0, 0, 0, 0)
  const target = new Date(date); target.setHours(0, 0, 0, 0)
  const days = Math.round((start.getTime() - target.getTime()) / 86400000)
  if (days === 0) return '今天'
  if (days === 1) return '昨天'
  if (days > 1 && days < 7) return `${days} 天前`
  return formatDate(value)
}
function formatFileSize(value) { const n = Number(value); if (!Number.isFinite(n) || n <= 0) return ''; if (n < 1024) return `${n} B`; if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`; return `${(n / 1024 / 1024).toFixed(1)} MB` }
function visibilityLabel(value) { return ({ ORG: '组织知识库', DEPT: '部门知识库', MEMBERS: '成员共享' })[String(value || '').toUpperCase()] || '我的知识库' }

async function uploadFiles(files) {
  if (!files?.length || !activeKb.value || uploading.value) return
  uploading.value = true
  let complete = 0
  for (const file of files) {
    const formData = new FormData(); formData.append('file', file)
    try {
      const res = await uploadKbDoc(activeKb.value.kbId, formData, overwrite.value ? 'force' : 'skip')
      const uploaded = (res.data || {}).doc
      if (uploaded) upsertDocument(uploaded)
      complete += 1
    }
    catch (error) { toast(`「${file.name}」上传失败：${error.message || '请重试'}`) }
  }
  uploading.value = false
  if (complete) { toast(`已上传 ${complete} 个文件，正在后台解析`); await loadKbs() }
}
function onPickFiles(event) { const files = Array.from(event.target.files || []); event.target.value = ''; uploadFiles(files) }
function onDropFiles(event) { uploadFiles(Array.from(event.dataTransfer?.files || [])) }
async function retryDoc(doc) {
  try {
    await reprocessKbDoc(activeKb.value.kbId, doc.docId)
    upsertDocument({ ...doc, parseStatus: 'PENDING', parseStep: 'PENDING', productStatus: 'QUEUED', progress: 0, errorMsg: null })
    if (!progressController) startProgressStream()
    toast('已重新提交处理')
  } catch (error) { toast(error.message || '重新处理失败') }
}
async function confirmDeleteDoc(doc) {
  const yes = await confirmDanger('删除文件', `确认从「${activeKb.value.kbName}」移除「${docName(doc)}」吗？相关检索切片也会一并删除。`, { okLabel: '删除', cancelLabel: '取消' })
  if (!yes) return
  try { await delKbDoc(activeKb.value.kbId, doc.docId); toast('文件已删除'); await Promise.all([loadDocs(), loadKbs()]) } catch (error) { toast(error.message || '删除失败') }
}
async function downloadDoc(doc) { try { await downloadKbDocBlob(activeKb.value.kbId, doc.docId, docName(doc)) } catch (error) { toast(error.message || '下载失败') } }

function openRenameDoc(doc) { renameTarget.value = doc; renameDocName.value = docName(doc); showRenameDoc.value = true }
function closeRenameDoc() { if (!renamingDoc.value) { showRenameDoc.value = false; renameTarget.value = null; renameDocName.value = '' } }
async function saveDocName() {
  const name = renameDocName.value.trim()
  if (!name || !renameTarget.value || renamingDoc.value) return
  renamingDoc.value = true
  try {
    await renameKbDoc(activeKb.value.kbId, renameTarget.value.docId, name)
    toast('文档已重命名')
    showRenameDoc.value = false
    renameTarget.value = null
    await loadDocs()
  } catch (error) { toast(error.message || '重命名失败') } finally { renamingDoc.value = false }
}

function openCreate() { menuKbId.value = null; editingKb.value = null; editor.value = { kbName: '', description: '' }; showEditor.value = true }
function openEdit(kb) { menuKbId.value = null; editingKb.value = kb; editor.value = { kbName: kb.kbName || '', description: kb.description || '' }; showEditor.value = true }
function closeEditor() { if (!saving.value) showEditor.value = false }
async function saveKb() {
  const data = { kbName: editor.value.kbName.trim(), description: editor.value.description.trim() }
  if (!data.kbName || saving.value) return
  saving.value = true
  try {
    if (editingKb.value) { await updateKb({ kbId: editingKb.value.kbId, ...data }); toast('知识库已更新') }
    else { await addKb(data); toast('知识库已创建') }
    showEditor.value = false; await loadKbs(); emit('kbs-changed')
  } catch (error) { toast(error.message || '保存失败') } finally { saving.value = false }
}
async function confirmDeleteKb(kb) {
  menuKbId.value = null
  const yes = await confirmDanger('删除知识库', `确认删除「${kb.kbName}」吗？其中的所有文件和检索索引都会被删除，且无法恢复。`, { okLabel: '删除', cancelLabel: '取消' })
  if (!yes) return
  try { await delKb(kb.kbId); toast('知识库已删除'); if (activeKb.value?.kbId === kb.kbId) closeDetail(); await loadKbs(); emit('kbs-changed') } catch (error) { toast(error.message || '删除失败') }
}

function sanitize(html) { return DOMPurify.sanitize(html || '', { ADD_ATTR: ['data-position', 'target'] }) }
async function openPreview(doc) {
  if (!activeKb.value || !doc?.docId) return
  preview.value = { open: true, loading: true, error: '', name: docName(doc), doc, downloadable: !!doc.downloadable, outline: [], blocks: [], tables: [] }
  try {
    const res = await getKbDocPreview(activeKb.value.kbId, doc.docId)
    const data = res.data || {}; const content = data.preview || data
    preview.value.downloadable = !!(data.downloadable ?? doc.downloadable)
    if (!content || content.available === false) {
      const reason = content?.reason || data.productError
      preview.value.error = ({ PROCESSING: '文件仍在处理中，完成后可预览。', QUEUED: '文件正在排队等待处理。', FAILED: data.productError || '文件解析失败，请重新处理。', NO_IR: '尚未生成可预览的解析内容。' })[reason] || reason || '该文件暂不支持在线预览。'
    } else {
      preview.value.outline = content.outline || []
      preview.value.blocks = content.blocks || (content.html ? [{ position: 0, html: content.html }] : [])
      preview.value.tables = content.tables || []
    }
  } catch (error) { preview.value.error = error.message || '加载预览失败' } finally { preview.value.loading = false }
}
function closePreview() { preview.value.open = false }
function scrollPreview(position) { previewBody.value?.querySelector(`[data-position="${position}"]`)?.scrollIntoView({ behavior: 'smooth', block: 'start' }) }
async function retryPreview() { const doc = preview.value.doc; closePreview(); if (doc) await retryDoc(doc) }
function onPageClick(event) { if (!event.target.closest('.kb-row__menu')) menuKbId.value = null }
onMounted(() => { loadKbs(); document.addEventListener('click', onPageClick) })
onBeforeUnmount(() => { stopProgressStream(); document.removeEventListener('click', onPageClick) })
</script>

<style scoped lang="scss">
.knowledge-view { height: 100%; overflow: auto; padding: 34px 48px 56px; background: var(--bg); color: var(--text); }
.knowledge-header, .detail-header { width: min(100%, 1040px); margin: 0 auto; display: flex; justify-content: space-between; gap: 24px; }
.knowledge-header { align-items: flex-start; padding-bottom: 26px; }
h1, h2, p { margin: 0; }
h1 { font-size: 22px; letter-spacing: -.02em; line-height: 1.25; }
.knowledge-header p, .detail-header p { max-width: 620px; margin-top: 8px; color: var(--text-secondary); font-size: 13px; line-height: 1.65; }
button { border: 0; cursor: pointer; font: inherit; } button:disabled { cursor: default; opacity: .55; }
.primary-button, .secondary-button { min-height: 34px; padding: 0 14px; border-radius: 8px; font-size: 13px; font-weight: 600; white-space: nowrap; transition: .16s ease; }
.primary-button { background: var(--accent); color: #fff; box-shadow: 0 1px 2px rgba(22, 91, 201, .18); }.primary-button:hover:not(:disabled) { filter: brightness(.95); transform: translateY(-1px); }
.secondary-button { border: 1px solid var(--border-strong); color: var(--text); background: var(--bg-elevated); }.secondary-button:hover { background: var(--bg-hover); border-color: var(--accent-border); color: var(--accent); }
.icon-button { width: 32px; height: 32px; border-radius: 8px; color: var(--text-tertiary); background: transparent; font-size: 15px; letter-spacing: 1px; }.icon-button:hover { color: var(--text); background: var(--bg-hover); }
.knowledge-content { width: min(100%, 1040px); margin: 0 auto; }.kb-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 14px; }
.kb-card { min-height: 164px; display: flex; flex-direction: column; padding: 17px; border: 1px solid var(--border); border-radius: 12px; background: var(--bg-elevated); cursor: pointer; box-shadow: 0 1px 2px rgba(15, 50, 100, .025); transition: border-color .16s ease, box-shadow .16s ease, transform .16s ease; }.kb-card:hover { border-color: var(--accent-border); box-shadow: 0 10px 24px rgba(30, 80, 150, .08); transform: translateY(-2px); }.kb-card__head { display: flex; align-items: center; gap: 10px; }.kb-card__title { flex: 1; min-width: 0; }.kb-card__title strong, .kb-card__title small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.kb-card__title strong { color: var(--text); font-size: 14px; font-weight: 650; }.kb-card__title small { margin-top: 3px; color: var(--text-tertiary); font-size: 11px; }.kb-card__description { flex: 1; display: -webkit-box; margin-top: 14px; overflow: hidden; color: var(--text-secondary); font-size: 12.5px; line-height: 1.55; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }.kb-card__footer { display: flex; align-items: center; justify-content: space-between; margin-top: 15px; padding-top: 12px; border-top: 1px solid var(--divider); color: var(--text-tertiary); font-size: 11px; }.kb-card__footer b { color: var(--accent); font-size: 12px; font-weight: 600; }.kb-card__footer i { font-size: 14px; font-style: normal; transition: transform .16s ease; }.kb-card:hover .kb-card__footer i { display: inline-block; transform: translateX(2px); }
.kb-row__icon { width: 32px; height: 32px; display: grid; place-items: center; flex: none; border-radius: 8px; background: var(--accent-weak); color: var(--accent); }.kb-row__icon svg { stroke: currentColor; stroke-width: 1.65; stroke-linecap: round; stroke-linejoin: round; }
.kb-row__menu { position: relative; }.kb-row:hover .kb-row__menu > .icon-button { color: var(--text); }.row-menu { position: absolute; z-index: 5; top: 34px; right: 0; min-width: 132px; overflow: hidden; padding: 4px; border: 1px solid var(--border-strong); border-radius: 9px; background: var(--bg-elevated); box-shadow: var(--shadow); }.row-menu button { display: block; width: 100%; padding: 8px 9px; border-radius: 6px; color: var(--text-secondary); text-align: left; font-size: 12.5px; }.row-menu button:hover { color: var(--text); background: var(--bg-hover); }.row-menu .row-menu__danger { color: var(--danger); }
.state-box { min-height: 240px; display: grid; place-content: center; justify-items: center; gap: 10px; color: var(--text-tertiary); font-size: 13px; text-align: center; }.state-box--empty { min-height: 360px; }.state-box h2 { color: var(--text); font-size: 16px; }.state-box p { max-width: 370px; line-height: 1.6; }.state-box__icon { width: 44px; height: 44px; display: grid; place-items: center; border: 1px solid var(--border); border-radius: 12px; background: var(--bg-hover); color: var(--accent); font-size: 23px; }
.knowledge-view--detail { padding: 0; }
.detail-header { width: 100%; height: 56px; display: block; border-bottom: 1px solid var(--divider); background: var(--bg); }.detail-header__inner { width: min(100%, 1216px); height: 100%; display: flex; align-items: center; gap: 12px; margin: 0 auto; padding: 0 32px; box-sizing: border-box; }.back-button { display: inline-flex; align-items: center; gap: 7px; padding: 6px 0; background: transparent; color: var(--text-secondary); font-size: 13px; }.back-button svg, .detail-header__actions svg, .document-row svg { stroke: currentColor; stroke-width: 1.8; stroke-linecap: round; stroke-linejoin: round; }.back-button:hover { color: var(--text); }.detail-header__divider { width: 1px; height: 20px; background: var(--divider); }.detail-header h1 { min-width: 0; overflow: hidden; font-size: 17px; font-weight: 650; text-overflow: ellipsis; white-space: nowrap; }.detail-header__actions { display: flex; align-items: center; gap: 8px; margin-left: auto; flex: none; }.detail-header__actions .primary-button { height: 32px; min-height: 32px; display: inline-flex; align-items: center; gap: 6px; }.detail-more { display: grid; place-items: center; letter-spacing: 0; }.detail-header .row-menu { top: 38px; }
.detail-header__actions .graph-entry { height: 32px; min-height: 32px; display: inline-flex; align-items: center; gap: 6px; }
.detail-content { width: min(100%, 1216px); margin: 0 auto; padding: 31px 32px 72px; box-sizing: border-box; }.detail-intro > p { max-width: 60ch; color: var(--text-secondary); font-size: 14px; line-height: 1.75; }.access-meta { min-height: 22px; display: flex; align-items: center; gap: 9px; margin-top: 10px; color: var(--text-tertiary); }.access-meta span { padding: 2px 7px; border: 1px solid var(--border); border-radius: 99px; background: var(--bg-elevated); font-size: 10.5px; line-height: 1.35; }.access-meta small { font-size: 11.5px; }
.documents-filter-row { display: grid; grid-template-columns: minmax(0, 1fr) 218px; gap: 10px; margin: 32px 0 12px; }.documents-filter { height: 40px; display: flex; align-items: center; gap: 9px; padding: 0 12px; border: 1px solid var(--border); border-radius: 9px; background: var(--bg-elevated); color: var(--text-tertiary); transition: border-color .15s ease, box-shadow .15s ease; }.documents-filter:focus-within, .uploader-filter:focus { border-color: var(--accent-border); box-shadow: var(--ring); }.documents-filter input { min-width: 0; flex: 1; border: 0; outline: 0; background: transparent; color: var(--text); font: inherit; font-size: 12.5px; }.documents-filter input::placeholder { color: var(--text-tertiary); }.documents-filter button { width: 20px; height: 20px; border-radius: 5px; color: var(--text-tertiary); background: transparent; font-size: 16px; line-height: 1; }.documents-filter button:hover { color: var(--text); background: var(--bg-hover); }.uploader-filter { height: 40px; padding: 0 34px 0 12px; border: 1px solid var(--border); border-radius: 9px; outline: 0; color: var(--text); background: var(--bg-elevated); font: inherit; font-size: 12.5px; }
.document-list { overflow: hidden; border: 1px solid var(--border); border-radius: 12px; background: var(--bg-elevated); box-shadow: 0 1px 2px rgba(15, 50, 100, .025); }.document-row { min-height: 98px; display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 18px; padding: 14px 20px; border-bottom: 1px solid var(--divider); transition: background .14s ease; }.document-row:last-child { border-bottom: 0; }.document-row:hover { background: var(--bg-hover); }.document-row__main { min-width: 0; }.document-row__name { min-width: 0; display: flex; align-items: center; gap: 8px; color: var(--text); }.document-row__name > svg { flex: none; color: var(--text-tertiary); }.document-row__title { min-width: 0; display: flex; align-items: center; gap: 7px; }.document-row__title strong { display: block; overflow: hidden; color: var(--text); font-size: 13.5px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }.document-row__meta, .document-row__uploader { margin-top: 7px; overflow: hidden; color: var(--text-tertiary); font-size: 11.5px; line-height: 1.3; text-overflow: ellipsis; white-space: nowrap; }.document-row__meta { font-variant-numeric: tabular-nums; }.document-row__uploader { margin-top: 6px; }.document-row__error { display: flex; align-items: flex-start; gap: 6px; margin-top: 8px; color: var(--danger); font-size: 11.5px; line-height: 1.45; }.document-row__error svg { margin-top: 1px; flex: none; }.document-row__progress { height: 4px; margin-top: 9px; overflow: hidden; border-radius: 99px; background: var(--accent-weak); }.document-row__progress i { display: block; width: 100%; height: 100%; }.document-row__progress b { display: block; height: 100%; border-radius: inherit; background: var(--accent); transition: width .25s ease; }.status-badge { width: max-content; flex: none; padding: 2px 7px; border: 1px solid transparent; border-radius: 99px; color: var(--text-tertiary); background: var(--bg-hover); font-size: 10.5px; font-style: normal; font-weight: 500; line-height: 1.35; }.status-badge--ready { border-color: rgba(40, 160, 77, .18); color: #21853b; background: rgba(40, 160, 77, .11); }.status-badge--failed { border-color: rgba(220, 70, 70, .18); color: var(--danger); background: rgba(220, 70, 70, .1); }.status-badge--processing, .status-badge--queued { border-color: var(--accent-border); color: var(--accent); background: var(--accent-weak); }.document-row__actions { display: flex; align-items: center; justify-content: flex-end; gap: 2px; flex: none; }.document-row__actions button { display: inline-flex; align-items: center; gap: 5px; padding: 6px 8px; border-radius: 6px; color: var(--text-secondary); background: transparent; font-size: 11.5px; transition: .14s ease; }.document-row__actions button:hover:not(:disabled) { color: var(--accent); background: var(--accent-weak); }.document-row__actions button:disabled { cursor: default; opacity: .5; }.document-row__actions .danger-text:hover { color: var(--danger); background: rgba(220, 70, 70, .06); }.documents-empty { min-height: 320px; border: 1px dashed var(--border-strong); border-radius: 12px; }
.modal-mask { position: fixed; z-index: 300; inset: 0; display: grid; place-items: center; padding: 22px; background: var(--ai-overlay); }.editor-modal, .preview-modal { width: min(100%, 460px); border: 1px solid var(--border-strong); border-radius: 12px; background: var(--bg-elevated); box-shadow: var(--shadow); }.editor-modal { padding: 20px; }.editor-modal header, .preview-modal header { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; }.editor-modal h2, .preview-modal h2 { font-size: 16px; }.editor-modal label { display: grid; gap: 7px; margin-top: 17px; color: var(--text-secondary); font-size: 12px; }.editor-modal input, .editor-modal textarea { width: 100%; box-sizing: border-box; padding: 9px 10px; border: 1px solid var(--border); border-radius: 7px; outline: none; color: var(--text); background: var(--bg-input); font: inherit; font-size: 13px; }.editor-modal input:focus, .editor-modal textarea:focus { border-color: var(--accent); }.editor-modal footer { display: flex; justify-content: flex-end; gap: 9px; margin-top: 20px; }
.preview-mask { z-index: 320; }.preview-modal { width: min(960px, 100%); height: min(80vh, 720px); display: flex; flex-direction: column; overflow: hidden; }.preview-modal header { flex: none; padding: 16px 18px; border-bottom: 1px solid var(--divider); }.preview-modal header > div:last-child { display: flex; align-items: center; gap: 5px; }.preview-modal h2 { max-width: 600px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.preview-modal p { margin-top: 4px; color: var(--text-tertiary); font-size: 12px; }.preview-modal__body { flex: 1; min-height: 0; display: grid; grid-template-columns: 200px minmax(0, 1fr); }.preview-modal__body aside { overflow: auto; padding: 15px 9px; border-right: 1px solid var(--divider); background: var(--bg-raised); }.preview-modal__body aside strong { display: block; padding: 0 8px 8px; color: var(--text-tertiary); font-size: 11px; }.preview-modal__body aside button { display: block; width: 100%; padding-top: 6px; padding-bottom: 6px; overflow: hidden; border-radius: 5px; color: var(--text-secondary); background: transparent; font-size: 11.5px; text-align: left; text-overflow: ellipsis; white-space: nowrap; }.preview-modal__body aside button:hover { color: var(--text); background: var(--bg-hover); }.preview-modal__body main { overflow: auto; padding: 20px 25px; }.preview-block { color: var(--text); font-size: 13px; line-height: 1.8; word-break: break-word; }.preview-block + .preview-block { margin-top: 14px; }.preview-block :deep(img) { max-width: 100%; }.preview-block :deep(table) { max-width: 100%; border-collapse: collapse; }.preview-block :deep(th), .preview-block :deep(td) { border: 1px solid var(--border); padding: 5px 8px; }
.graph-chunk-loading-mask { z-index: 1180; }.graph-chunk-loading-card { width: min(360px, calc(100vw - 40px)); overflow: hidden; border: 1px solid var(--border); border-radius: 14px; background: var(--bg-elevated); box-shadow: var(--shadow); }
@media (max-width: 760px) { .knowledge-view { padding: 24px 18px 40px; }.knowledge-view--detail { padding: 0; }.detail-header__inner { padding: 0 14px; }.back-button span { display: none; }.detail-content { padding: 24px 16px 48px; }.documents-filter-row { grid-template-columns: 1fr; }.document-row { grid-template-columns: 1fr; padding: 14px; }.document-row__actions { justify-content: flex-start; flex-wrap: wrap; }.preview-modal__body { grid-template-columns: 1fr; }.preview-modal__body aside { display: none; } }
</style>
