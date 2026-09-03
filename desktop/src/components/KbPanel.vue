<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import DOMPurify from 'dompurify'
import { addKb, downloadKbDocBlob, getKbDocPreview, listDesktopKbs, listKbDoc, reprocessKbDoc, uploadKbDoc } from '../api/kb'
import { toast } from '../utils/confirm'

const emit = defineEmits(['close'])

/* ---- 知识库列表 ---- */
const kbs = ref([])
const loading = ref(false)
const activeKbId = ref(null)
const activeKb = computed(() => kbs.value.find(k => k.kbId === activeKbId.value) || null)

async function loadKbs() {
  loading.value = true
  try {
    const res = await listDesktopKbs({ status: '0' })
    kbs.value = res.data || []
    if (!kbs.value.some(k => k.kbId === activeKbId.value)) {
      activeKbId.value = kbs.value[0]?.kbId ?? null
      if (activeKbId.value) loadDocs()
      else docs.value = []
    }
  } catch (e) {
    toast(e.message || '知识库列表加载失败')
  } finally {
    loading.value = false
  }
}

/* ---- 文档列表 ---- */
const docs = ref([])
const docLoading = ref(false)
let docPollTimer = null

async function loadDocs() {
  if (!activeKbId.value) return
  docLoading.value = true
  try {
    const res = await listKbDoc(activeKbId.value, { pageNum: 1, pageSize: 200 })
    docs.value = res.rows || res.data || []
    scheduleDocRefresh()
  } catch (e) {
    docs.value = []
    toast(e.message || '文档列表加载失败')
  } finally {
    docLoading.value = false
  }
}

/** 正在排队或处理的文档会自动刷新，直到状态稳定。 */
function scheduleDocRefresh() {
  if (docPollTimer) {
    window.clearTimeout(docPollTimer)
    docPollTimer = null
  }
  if (docs.value.some(docIsBusy)) {
    docPollTimer = window.setTimeout(() => {
      docPollTimer = null
      loadDocs()
    }, 2500)
  }
}

function selectKb(kb) {
  if (kb.kbId === activeKbId.value) return
  activeKbId.value = kb.kbId
  docs.value = []
  closePreview()
  loadDocs()
}

/* ---- 新建知识库 ---- */
const showCreate = ref(false)
const creating = ref(false)
const createForm = ref({ kbName: '', description: '' })

async function submitCreate() {
  if (!createForm.value.kbName.trim()) {
    toast('请输入知识库名称')
    return
  }
  creating.value = true
  try {
    await addKb({ kbName: createForm.value.kbName.trim(), description: createForm.value.description.trim() })
    createForm.value = { kbName: '', description: '' }
    showCreate.value = false
    toast('知识库已创建')
    await loadKbs()
  } catch (e) {
    toast(e.message || '创建失败')
  } finally {
    creating.value = false
  }
}

/* ---- 上传文档 ---- */
const fileRef = ref(null)
const uploading = ref(false)
const overwrite = ref(false)
const uploadInputKey = ref(0)

async function onPick(e) {
  const files = [...(e.target.files || [])]
  e.target.value = ''
  uploadInputKey.value += 1
  if (!files.length || !activeKbId.value) return
  uploading.value = true
  let ok = 0
  for (const file of files) {
    const fd = new FormData()
    fd.append('file', file)
    try {
      await uploadKbDoc(activeKbId.value, fd, overwrite.value ? 'force' : 'skip')
      ok += 1
    } catch (err) {
      toast(`「${file.name}」上传失败：${err.message || '请重试'}`)
    }
  }
  uploading.value = false
  if (ok) {
    toast(`已上传 ${ok} 个文档，后台将自动解析入库`)
    loadDocs()
  }
}

/* ---- 文档预览 ---- */
const preview = ref(null)
const previewDetail = ref(null)
const previewDoc = ref(null)
const previewBodyRef = ref(null)
const previewName = ref('')
const previewLoading = ref(false)
const previewError = ref('')

function sanitizeHtml(html) {
  return DOMPurify.sanitize(html || '', { ADD_ATTR: ['target', 'data-pos'] })
}

async function openPreview(doc) {
  if (!activeKbId.value || !doc.docId) return
  previewName.value = doc.docName || doc.name || '文档'
  previewDoc.value = doc
  previewDetail.value = null
  preview.value = {}
  previewLoading.value = true
  previewError.value = ''
  try {
    const res = await getKbDocPreview(activeKbId.value, doc.docId)
    const data = res.data || {}
    previewDetail.value = data
    const pv = data.preview
    if (!pv || pv.available === false) {
      const reasons = {
        FAILED: '文档处理失败，暂不可预览',
        PROCESSING: '文档仍在解析中，稍后再试',
        NO_IR: '尚无解析产物',
        IR_LOAD_ERROR: '解析产物加载失败（IR 文件缺失或不可读）'
      }
      preview.value = null
      previewError.value = reasons[pv?.reason] || pv?.reason || '该文档暂不支持在线预览'
    } else {
      preview.value = pv
    }
  } catch (e) {
    preview.value = null
    previewError.value = e?.message || '加载原文预览失败'
  } finally {
    previewLoading.value = false
  }
}

function closePreview() {
  preview.value = null
  previewDetail.value = null
  previewDoc.value = null
  previewError.value = ''
}

const previewStatus = computed(() => String(previewDetail.value?.productStatus || '').toUpperCase())
const previewProgress = computed(() => {
  const value = Number(previewDetail.value?.progress)
  return !Number.isNaN(value) && value >= 0 ? Math.min(100, Math.round(value)) : 0
})
const previewOutline = computed(() => preview.value?.outline || [])
const previewQuality = computed(() => previewDetail.value?.quality || preview.value?.quality || {})

function previewStatusLabel() {
  return ({ READY: '可用', PROCESSING: '处理中', FAILED: '失败', QUEUED: '排队中' })[previewStatus.value] || '—'
}

function previewReason() {
  const reason = preview.value?.reason
  return ({
    FAILED: previewDetail.value?.productError || '文档处理失败，可重新处理后再预览',
    PROCESSING: '文档仍在处理中，完成后即可预览',
    NO_IR: '尚无解析产物，可尝试重新处理',
    IR_LOAD_ERROR: '解析产物暂不可读取，可重新处理后重试'
  })[reason] || '该文档暂不支持在线预览'
}

function scrollToPreviewPosition(position) {
  const root = previewBodyRef.value
  const target = root?.querySelector(`[data-pos="${position}"]`)
  target?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

async function downloadPreviewFile() {
  if (!activeKbId.value || !previewDoc.value?.docId) return
  try {
    await downloadKbDocBlob(activeKbId.value, previewDoc.value.docId, previewName.value)
  } catch (e) {
    toast(e.message || '下载原文件失败')
  }
}

async function retryPreviewDoc() {
  if (!previewDoc.value) return
  await retryDoc(previewDoc.value)
  closePreview()
}

function docName(d) {
  return d.docName || d.name || `文档 #${d.docId}`
}

function docStatus(d) {
  const status = String(d.productStatus || '').toUpperCase()
  if (status === 'READY' || status === 'PROCESSING' || status === 'FAILED' || status === 'QUEUED') return status
  const parse = String(d.parseStatus || '').toUpperCase()
  if (parse === 'COMPLETED') return 'READY'
  if (parse === 'FAILED') return 'FAILED'
  if (parse === 'PENDING' || !parse) return 'QUEUED'
  return 'PROCESSING'
}

function docStatusLabel(d) {
  return ({ READY: '可用', PROCESSING: '处理中', FAILED: '失败', QUEUED: '排队中' })[docStatus(d)] || '—'
}

function docIsBusy(d) {
  return ['PROCESSING', 'QUEUED'].includes(docStatus(d))
}

function docProgress(d) {
  const progress = Number(d.progress)
  if (!Number.isNaN(progress) && progress >= 0) return Math.min(100, Math.round(progress))
  return docStatus(d) === 'QUEUED' ? 0 : 10
}

function docError(d) {
  const text = String(d.errorMsg || d.productError || '').trim()
  if (!text) return '处理失败，请重新处理'
  return text.length > 60 ? text.slice(0, 60) + '…' : text
}

function docMeta(d) {
  if (docStatus(d) === 'READY' && d.chunkCount != null) return `${d.chunkCount} 个切片`
  if (docIsBusy(d) && d.parseStep) return String(d.parseStep)
  return ''
}

async function retryDoc(doc) {
  if (!activeKbId.value || !doc?.docId) return
  try {
    await reprocessKbDoc(activeKbId.value, doc.docId)
    toast('已重新提交处理任务')
    await loadDocs()
  } catch (e) {
    toast(e.message || '重新处理失败')
  }
}

onMounted(loadKbs)
onBeforeUnmount(() => {
  if (docPollTimer) window.clearTimeout(docPollTimer)
})
</script>

<template>
  <Teleport to="body">
    <div class="kbp">
      <div class="kbp__mask" @click="emit('close')"></div>

      <aside class="kbp__panel" role="dialog" aria-label="知识库管理">
        <header class="kbp__head">
          <div class="kbp__head-text">
            <h3 class="kbp__title">知识库管理</h3>
            <p class="kbp__sub">管理文档资产；对话时在输入区勾选即可引用</p>
          </div>
          <div class="kbp__head-actions">
            <button type="button" class="kbp__icon-btn" title="新建知识库" @click="showCreate = !showCreate">
              <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <path d="M8 3v10M3 8h10" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/>
              </svg>
            </button>
            <button type="button" class="kbp__icon-btn" title="关闭" @click="emit('close')">
              <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <path d="M4 4l8 8M12 4l-8 8" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
              </svg>
            </button>
          </div>
        </header>

        <!-- 新建知识库 -->
        <Transition name="kbp-fold">
          <form v-if="showCreate" class="kbp__create" @submit.prevent="submitCreate">
            <input v-model="createForm.kbName" class="kbp__input" placeholder="知识库名称" />
            <input v-model="createForm.description" class="kbp__input" placeholder="用途描述（可选）" />
            <div class="kbp__create-actions">
              <button type="button" class="kbp__ghost-btn" @click="showCreate = false">取消</button>
              <button type="submit" class="kbp__primary-btn" :disabled="creating">
                {{ creating ? '创建中…' : '创建' }}
              </button>
            </div>
          </form>
        </Transition>

        <div class="kbp__body">
          <!-- 知识库列表 -->
          <div class="kbp__col">
            <div v-if="loading" class="kbp__empty">加载中…</div>
            <div v-else-if="!kbs.length" class="kbp__empty">还没有知识库，点右上角 + 新建</div>
            <button
              v-for="k in kbs"
              v-else
              :key="k.kbId"
              type="button"
              class="kbp__kb"
              :class="{ active: k.kbId === activeKbId }"
              @click="selectKb(k)"
            >
              <span class="kbp__kb-icon" aria-hidden="true">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H20v15.5H6.5A2.5 2.5 0 0 0 4 21V5.5z"/>
                  <path d="M4 18.5A2.5 2.5 0 0 1 6.5 16H20"/>
                  <path d="M9 7.5h7"/>
                </svg>
              </span>
              <span class="kbp__kb-text">
                <span class="kbp__kb-name">{{ k.kbName }}</span>
                <span v-if="k.description" class="kbp__kb-desc">{{ k.description }}</span>
              </span>
              <svg class="kbp__kb-chev" width="12" height="12" viewBox="0 0 12 12" fill="none" aria-hidden="true">
                <path d="M4.5 2.5L8 6l-3.5 3.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </button>
          </div>

          <!-- 文档列表 -->
          <div class="kbp__col kbp__col--docs">
            <template v-if="activeKb">
              <div class="kbp__docs-head">
                <span class="kbp__docs-title">{{ activeKb.kbName }}</span>
                <label class="kbp__overwrite" title="同名文档重新上传时覆盖旧版本">
                  <input v-model="overwrite" type="checkbox" />
                  覆盖同名
                </label>
                <button
                  type="button"
                  class="kbp__upload"
                  :disabled="uploading"
                  @click="fileRef?.click()"
                >
                  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                    <path d="M12 16V4"/><path d="M6 9l6-6 6 6"/><path d="M4 20h16"/>
                  </svg>
                  {{ uploading ? '上传中…' : '上传文档' }}
                </button>
              </div>

              <div v-if="docLoading" class="kbp__empty">加载中…</div>
              <div v-else-if="!docs.length" class="kbp__empty">还没有文档，点击「上传文档」导入</div>
              <div v-else class="kbp__docs">
                <div
                  v-for="d in docs"
                  :key="d.docId"
                  class="kbp__doc"
                  :class="'is-' + docStatus(d).toLowerCase()"
                  role="button"
                  tabindex="0"
                  @click="openPreview(d)"
                  @keydown.enter.prevent="openPreview(d)"
                >
                  <span class="kbp__doc-icon" aria-hidden="true">
                    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
                      <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8l-5-5z"/>
                      <path d="M14 3v5h5"/>
                    </svg>
                  </span>
                  <span class="kbp__doc-main">
                    <span class="kbp__doc-name" :title="docName(d)">{{ docName(d) }}</span>
                    <span v-if="docMeta(d)" class="kbp__doc-meta">{{ docMeta(d) }}</span>
                    <span v-if="docStatus(d) === 'FAILED'" class="kbp__doc-error" :title="d.errorMsg || ''">{{ docError(d) }}</span>
                    <span v-else-if="docIsBusy(d)" class="kbp__doc-progress" aria-label="文档处理进度">
                      <i><b :style="{ width: docProgress(d) + '%' }" /></i>{{ docProgress(d) }}%
                    </span>
                  </span>
                  <span class="kbp__doc-status" :class="'is-' + docStatus(d).toLowerCase()">{{ docStatusLabel(d) }}</span>
                  <button
                    v-if="docStatus(d) === 'FAILED'"
                    type="button"
                    class="kbp__doc-retry"
                    title="重新处理"
                    @click.stop="retryDoc(d)"
                  >重试</button>
                  <svg class="kbp__doc-eye" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                    <path d="M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6-10-6-10-6z"/>
                    <circle cx="12" cy="12" r="2.6"/>
                  </svg>
                </div>
              </div>
            </template>
            <div v-else class="kbp__empty">在左侧选择一个知识库</div>
          </div>
        </div>

        <footer class="kbp__foot">对话时在输入区「知识库」勾选即可让智能体检索这里的内容</footer>
      </aside>

      <!-- 文档原文预览 -->
      <Transition name="kbp-fade">
        <div v-if="preview || previewLoading || previewError" class="kbp-preview" @click.self="closePreview">
          <div class="kbp-preview__panel" role="dialog" aria-label="文档预览">
            <header class="kbp-preview__head">
              <div class="kbp-preview__title">
                <span class="kbp-preview__name" :title="previewName">{{ previewName }}</span>
                <span v-if="previewStatus" class="kbp-preview__status" :class="'is-' + previewStatus.toLowerCase()">{{ previewStatusLabel() }}</span>
              </div>
              <div class="kbp-preview__actions">
                <button
                  v-if="previewDetail?.downloadable"
                  type="button"
                  class="kbp__icon-btn"
                  title="下载原文件"
                  @click="downloadPreviewFile"
                >
                  <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M8 2.5v7M5.2 7l2.8 2.8L10.8 7M3 12.5h10" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/></svg>
                </button>
                <button type="button" class="kbp__icon-btn" title="关闭" @click="closePreview">
                  <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                    <path d="M4 4l8 8M12 4l-8 8" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
                  </svg>
                </button>
              </div>
            </header>
            <div class="kbp-preview__body">
              <div v-if="previewLoading" class="kbp__empty">正在加载原文预览…</div>
              <div v-else-if="previewError" class="kbp__empty">{{ previewError }}</div>
              <div v-else-if="previewStatus === 'PROCESSING' || previewStatus === 'QUEUED'" class="kbp-preview__state">
                <p>文档正在处理，完成后可查看解析预览。</p>
                <div class="kbp-preview__progress"><i :style="{ width: previewProgress + '%' }" /></div>
                <span>{{ previewProgress }}%</span>
              </div>
              <div v-else-if="previewStatus === 'FAILED' || preview?.available === false" class="kbp-preview__state is-failed">
                <p>{{ previewReason() }}</p>
                <p v-if="previewDetail?.productSuggestion" class="kbp-preview__suggestion">建议：{{ previewDetail.productSuggestion }}</p>
                <button v-if="previewStatus === 'FAILED'" type="button" class="kbp__primary-btn" @click="retryPreviewDoc">重新处理</button>
              </div>
              <div v-else-if="preview" class="kbp-preview__layout">
                <aside v-if="previewOutline.length" class="kbp-preview__outline">
                  <p>目录</p>
                  <button
                    v-for="(node, index) in previewOutline"
                    :key="index"
                    type="button"
                    :style="{ paddingLeft: (8 + (node.level || 1) * 8) + 'px' }"
                    @click="scrollToPreviewPosition(node.position)"
                  >{{ node.title || '（无标题）' }}</button>
                </aside>
                <div ref="previewBodyRef" class="kbp-preview__content">
                  <div
                    v-for="b in (preview.blocks || [])"
                    :key="'b' + b.position"
                    class="kbp-preview__block"
                    :data-pos="b.position"
                    v-html="sanitizeHtml(b.html)"
                  />
                  <div
                    v-for="t in (preview.tables || [])"
                    :key="'t' + t.position"
                    class="kbp-preview__block"
                    :data-pos="t.position"
                    v-html="sanitizeHtml(t.html)"
                  />
                  <p v-if="preview.counts?.truncated" class="kbp-preview__hint">预览已截断，完整内容已参与检索</p>
                  <div v-if="!(preview.blocks || []).length && !(preview.tables || []).length" class="kbp__empty">暂无可展示的解析内容</div>
                </div>
                <footer class="kbp-preview__meta">
                  <span v-if="preview.counts?.blockCount != null">{{ preview.counts.blockCount }} 个内容块</span>
                  <span v-if="preview.counts?.tableCount">{{ preview.counts.tableCount }} 个表格</span>
                  <span v-if="previewQuality.grade">解析质量：{{ previewQuality.grade }}</span>
                </footer>
              </div>
            </div>
          </div>
        </div>
      </Transition>

      <input ref="fileRef" :key="uploadInputKey" type="file" multiple hidden @change="onPick" />
    </div>
  </Teleport>
</template>

<style scoped>
.kbp {
  position: fixed;
  inset: 0;
  z-index: 900;
}
.kbp__mask {
  position: absolute;
  inset: 0;
  background: var(--ai-overlay);
  backdrop-filter: blur(2px);
}
.kbp__panel {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  width: min(680px, 92vw);
  display: flex;
  flex-direction: column;
  background: var(--bg-raised);
  border-left: 1px solid var(--border);
  box-shadow: var(--shadow);
  animation: kbp-slide 0.24s cubic-bezier(0.22, 1, 0.36, 1) both;
}
@keyframes kbp-slide {
  from { transform: translateX(30px); opacity: 0; }
  to { transform: none; opacity: 1; }
}

.kbp__head {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 18px 20px 14px;
  border-bottom: 1px solid var(--divider);
}
.kbp__title {
  font-size: 16px;
  font-weight: 700;
}
.kbp__sub {
  margin-top: 2px;
  font-size: 12px;
  color: var(--text-tertiary);
}
.kbp__head-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}
.kbp__icon-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 7px;
  color: var(--text-tertiary);
  transition: background 0.14s ease, color 0.14s ease;
}
.kbp__icon-btn:hover {
  background: var(--bg-hover);
  color: var(--text);
}

/* 新建表单 */
.kbp__create {
  flex: none;
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 12px 20px 0;
  padding: 12px;
  border: 1px solid var(--accent-border);
  border-radius: 10px;
  background: var(--accent-weak);
  overflow: hidden;
}
.kbp__input {
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg-input);
  padding: 8px 11px;
  font-size: 13px;
  color: var(--text);
  outline: none;
  transition: border-color 0.14s ease;
}
.kbp__input:focus {
  border-color: var(--accent-border);
}
.kbp__create-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.kbp__ghost-btn {
  padding: 6px 14px;
  border-radius: 8px;
  border: 1px solid var(--border);
  background: transparent;
  color: var(--text-secondary);
  font-size: 12.5px;
}
.kbp__ghost-btn:hover {
  background: var(--bg-hover);
  color: var(--text);
}
.kbp__primary-btn {
  padding: 6px 16px;
  border-radius: 8px;
  background: var(--accent-gradient);
  color: #fff;
  font-size: 12.5px;
  font-weight: 600;
}
.kbp__primary-btn:disabled {
  opacity: 0.55;
}
.kbp-fold-enter-active,
.kbp-fold-leave-active {
  transition: opacity 0.16s ease, transform 0.16s ease;
}
.kbp-fold-enter-from,
.kbp-fold-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}

/* 主体双栏 */
.kbp__body {
  flex: 1;
  min-height: 0;
  display: flex;
  gap: 12px;
  padding: 14px 20px;
}
.kbp__col {
  width: 240px;
  flex: none;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.kbp__col--docs {
  flex: 1;
  min-width: 0;
  width: auto;
  border-left: 1px solid var(--divider);
  padding-left: 12px;
}
.kbp__empty {
  padding: 22px 10px;
  font-size: 12.5px;
  color: var(--text-tertiary);
  text-align: center;
  line-height: 1.7;
}
.kbp__kb {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  padding: 9px 10px;
  border-radius: 9px;
  text-align: left;
  transition: background 0.12s ease;
}
.kbp__kb:hover {
  background: var(--bg-hover);
}
.kbp__kb.active {
  background: var(--bg-active);
}
.kbp__kb-icon {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 8px;
  color: var(--accent-hover);
  background: var(--accent-weak);
  border: 1px solid var(--accent-border);
}
.kbp__kb-text {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}
.kbp__kb-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.kbp__kb-desc {
  font-size: 11px;
  color: var(--text-tertiary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.kbp__kb-chev {
  flex: none;
  color: var(--text-tertiary);
  opacity: 0;
  transition: opacity 0.12s ease;
}
.kbp__kb:hover .kbp__kb-chev,
.kbp__kb.active .kbp__kb-chev {
  opacity: 1;
}

/* 文档列 */
.kbp__docs-head {
  flex: none;
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.kbp__docs-title {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.kbp__overwrite {
  flex: none;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11.5px;
  color: var(--text-tertiary);
  cursor: pointer;
  user-select: none;
}
.kbp__overwrite input {
  accent-color: var(--accent);
}
.kbp__upload {
  flex: none;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 11px;
  border-radius: 8px;
  background: var(--accent-weak);
  border: 1px solid var(--accent-border);
  color: var(--accent);
  font-size: 12px;
  font-weight: 500;
  transition: background 0.14s ease;
}
.kbp__upload:hover:not(:disabled) {
  background: var(--accent-border);
  color: #fff;
}
.kbp__upload:disabled {
  opacity: 0.55;
}
.kbp__docs {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.kbp__doc {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 7px 9px;
  border-radius: 8px;
  text-align: left;
  cursor: pointer;
  transition: background 0.12s ease;
}
.kbp__doc:hover {
  background: var(--bg-hover);
}
.kbp__doc:focus-visible {
  outline: 2px solid var(--accent-border);
  outline-offset: -1px;
}
.kbp__doc-icon {
  flex: none;
  display: flex;
  color: var(--text-tertiary);
}
.kbp__doc-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.kbp__doc-name {
  min-width: 0;
  font-size: 12.5px;
  color: var(--text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.kbp__doc:hover .kbp__doc-name {
  color: var(--text);
}
.kbp__doc-status {
  flex: none;
  padding: 2px 6px;
  border-radius: 999px;
  font-size: 10px;
  line-height: 1.2;
  color: var(--text-tertiary);
  background: var(--bg-hover);
}
.kbp__doc-status.is-ready {
  color: #248a3d;
  background: rgba(52, 199, 89, 0.13);
}
.kbp__doc-status.is-processing,
.kbp__doc-status.is-queued {
  color: var(--accent);
  background: var(--accent-weak);
}
.kbp__doc-status.is-failed {
  color: #c74242;
  background: rgba(255, 69, 58, 0.12);
}
.kbp__doc-meta,
.kbp__doc-error,
.kbp__doc-progress {
  min-width: 0;
  font-size: 10.5px;
  line-height: 1.25;
  color: var(--text-tertiary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.kbp__doc-error {
  color: #c74242;
}
.kbp__doc-progress {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--accent);
}
.kbp__doc-progress i {
  display: block;
  width: 46px;
  height: 3px;
  overflow: hidden;
  border-radius: 99px;
  background: var(--accent-weak);
}
.kbp__doc-progress b {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--accent);
  transition: width 0.25s ease;
}
.kbp__doc-retry {
  flex: none;
  padding: 3px 5px;
  border-radius: 5px;
  color: #c74242;
  font-size: 10.5px;
}
.kbp__doc-retry:hover {
  background: rgba(255, 69, 58, 0.1);
}
.kbp__doc-eye {
  flex: none;
  color: var(--text-tertiary);
  opacity: 0;
  transition: opacity 0.12s ease;
}
.kbp__doc:hover .kbp__doc-eye {
  opacity: 1;
}

.kbp__foot {
  flex: none;
  padding: 10px 20px 14px;
  border-top: 1px solid var(--divider);
  font-size: 11.5px;
  color: var(--text-tertiary);
}

/* 预览浮层 */
.kbp-preview {
  position: fixed;
  inset: 0;
  z-index: 950;
  background: rgba(0, 0, 0, 0.7);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px;
}
.kbp-preview__panel {
  width: 100%;
  max-width: 760px;
  max-height: 84vh;
  display: flex;
  flex-direction: column;
  background: var(--bg-elevated);
  border: 1px solid var(--border-strong);
  border-radius: 14px;
  box-shadow: var(--shadow);
  overflow: hidden;
}
.kbp-preview__head {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 13px 16px;
  border-bottom: 1px solid var(--divider);
}
.kbp-preview__title,
.kbp-preview__actions {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 8px;
}
.kbp-preview__title { flex: 1; }
.kbp-preview__name {
  min-width: 0;
  font-size: 14px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.kbp-preview__status {
  flex: none;
  padding: 3px 7px;
  border-radius: 999px;
  font-size: 10px;
  color: var(--text-tertiary);
  background: var(--bg-hover);
}
.kbp-preview__status.is-ready { color: #248a3d; background: rgba(52, 199, 89, 0.13); }
.kbp-preview__status.is-processing,
.kbp-preview__status.is-queued { color: var(--accent); background: var(--accent-weak); }
.kbp-preview__status.is-failed { color: #c74242; background: rgba(255, 69, 58, 0.12); }
.kbp-preview__body {
  flex: 1;
  min-height: 0;
  display: flex;
  padding: 0;
}
.kbp-preview__layout {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(130px, 190px) minmax(0, 1fr);
  grid-template-rows: minmax(0, 1fr) auto;
}
.kbp-preview__outline {
  min-height: 0;
  overflow-y: auto;
  padding: 14px 8px;
  border-right: 1px solid var(--divider);
  background: var(--bg-raised);
}
.kbp-preview__outline p {
  margin: 0 8px 7px;
  font-size: 11px;
  color: var(--text-tertiary);
}
.kbp-preview__outline button {
  display: block;
  width: 100%;
  padding-top: 5px;
  padding-bottom: 5px;
  overflow: hidden;
  border-radius: 5px;
  color: var(--text-secondary);
  font-size: 11.5px;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.kbp-preview__outline button:hover { background: var(--bg-hover); color: var(--text); }
.kbp-preview__content {
  min-width: 0;
  overflow-y: auto;
  padding: 16px 20px;
}
.kbp-preview__meta {
  grid-column: 1 / -1;
  display: flex;
  gap: 12px;
  padding: 8px 16px;
  border-top: 1px solid var(--divider);
  color: var(--text-tertiary);
  font-size: 11px;
}
.kbp-preview__state {
  flex: 1;
  align-self: center;
  padding: 28px 42px;
  color: var(--text-secondary);
  font-size: 13px;
  text-align: center;
}
.kbp-preview__state.is-failed { color: #c74242; }
.kbp-preview__state .kbp__primary-btn { margin-top: 10px; }
.kbp-preview__suggestion { margin-top: 6px; color: var(--text-tertiary); font-size: 12px; }
.kbp-preview__progress {
  width: min(320px, 100%);
  height: 5px;
  margin: 12px auto 6px;
  overflow: hidden;
  border-radius: 99px;
  background: var(--accent-weak);
}
.kbp-preview__progress i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--accent);
  transition: width 0.25s ease;
}
.kbp-preview__block {
  font-size: 13.5px;
  line-height: 1.8;
  color: var(--text);
  word-break: break-word;
}
.kbp-preview__block + .kbp-preview__block {
  margin-top: 10px;
}
.kbp-preview__block :deep(table) {
  border-collapse: collapse;
  margin: 8px 0;
  font-size: 12.5px;
}
.kbp-preview__block :deep(th),
.kbp-preview__block :deep(td) {
  border: 1px solid var(--border);
  padding: 5px 10px;
}
.kbp-preview__block :deep(img) {
  max-width: 100%;
  border-radius: 8px;
}
.kbp-preview__hint {
  margin-top: 12px;
  font-size: 12px;
  color: var(--warn);
}
@media (max-width: 640px) {
  .kbp-preview { padding: 18px; }
  .kbp-preview__panel { max-height: 90vh; }
  .kbp-preview__layout { grid-template-columns: 1fr; }
  .kbp-preview__outline { display: none; }
}
.kbp-fade-enter-active,
.kbp-fade-leave-active {
  transition: opacity 0.16s ease;
}
.kbp-fade-enter-from,
.kbp-fade-leave-to {
  opacity: 0;
}
</style>
