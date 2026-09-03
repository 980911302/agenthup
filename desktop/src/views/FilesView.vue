<template>
  <section class="files-view">
    <!-- 头部：与 ResourceView / KbView 一致的 56px 标题栏。
         刻意不放主题切换 —— 那是全局设置，正式入口在 SettingsModal，
         其他页面也都没有页面级的主题按钮。 -->
    <header class="files-header">
      <div class="files-header__text">
        <h1>文件</h1>
        <p>你的个人文件空间，与具体某次对话无关。对话工作区里的产出也可以存到这里长期保存。</p>
      </div>
      <button
        class="primary-button"
        type="button"
        :disabled="uploading || !storageEnabled"
        @click="pickFile"
      >
        <UploadIcon />
        {{ uploading ? `上传中 ${uploadPercent}%` : '上传文件' }}
      </button>
      <input ref="fileInputRef" type="file" multiple class="files-file-input" @change="onPickFile" />
    </header>

    <main
      class="files-content"
      :class="{ 'is-dragging': dragging }"
      @dragover.prevent="onDragOver"
      @dragleave="onDragLeave"
      @drop.prevent="onDrop"
    >
      <!-- 存储配额 -->
      <section class="files-quota">
        <div class="files-quota__head">
          <span class="files-quota__label">
            <DatabaseIcon />
            存储空间
          </span>
          <span class="files-quota__value">{{ usedText }} / {{ quotaText }}</span>
        </div>
        <div class="files-quota__track">
          <b :style="{ width: storagePercent + '%' }" :class="{ 'is-warn': storagePercent >= 85 }"></b>
        </div>
        <p class="files-quota__foot">
          共 {{ quota.fileCount }} 个文件<template v-if="quota.maxFiles"> · 上限 {{ quota.maxFiles }} 个</template>
          <template v-if="quota.maxFileBytes"> · 单文件不超过 {{ formatSize(quota.maxFileBytes) }}</template>
        </p>
      </section>

      <!-- 工具栏 -->
      <div class="files-toolbar">
        <label class="files-search">
          <SearchIcon />
          <input v-model="searchQuery" type="search" placeholder="搜索文件名" />
          <button v-if="searchQuery" type="button" aria-label="清空搜索" @click="searchQuery = ''">
            <CloseIcon />
          </button>
        </label>

        <div class="files-sort" role="tablist" aria-label="排序方式">
          <button
            v-for="opt in SORT_OPTIONS"
            :key="opt.value"
            type="button"
            role="tab"
            :aria-selected="sortMode === opt.value"
            :class="{ active: sortMode === opt.value }"
            @click="sortMode = opt.value"
          >
            {{ opt.label }}
          </button>
        </div>
      </div>

      <!-- 对象存储未配置：给引导而不是空列表，否则用户以为自己的文件丢了 -->
      <div v-if="!storageEnabled" class="files-state">
        <span class="files-state__icon"><AlertIcon /></span>
        <h2>存储未配置</h2>
        <p>管理员需先开启 <code>ruoyi.ai.storage</code> 并填写对象存储连接信息，才能使用个人文件。</p>
      </div>

      <AppPageLoader v-else-if="loading && !files.length" compact label="正在加载文件…" />

      <!-- 空状态 -->
      <div v-else-if="!filteredFiles.length" class="files-state files-state--empty app-page-enter">
        <span class="files-state__icon"><component :is="searchQuery ? SearchIcon : FolderIcon" /></span>
        <h2>{{ searchQuery ? '没有匹配的文件' : '还没有文件' }}</h2>
        <p v-if="searchQuery">换一个关键词试试，或清除搜索条件。</p>
        <p v-else>上传的文件会一直保存在这里，与具体某次对话无关，随时可以取用。</p>
        <button v-if="searchQuery" class="secondary-button" type="button" @click="searchQuery = ''">
          清除搜索
        </button>
        <button v-else class="secondary-button" type="button" :disabled="uploading" @click="pickFile">
          <UploadIcon />
          上传第一个文件
        </button>
      </div>

      <!-- 文件列表 -->
      <div v-else class="files-list app-page-enter">
        <article v-for="file in filteredFiles" :key="file.fileId" class="file-row">
          <div class="file-summary" @click="openPreview(file)">
            <div class="file-title-line">
              <span class="file-symbol" aria-hidden="true"><component :is="iconOf(file)" /></span>
              <strong :title="file.name">{{ file.name }}</strong>
              <em class="file-badge">{{ kindLabel(file.name) }}</em>
            </div>
            <p class="file-meta">{{ formatSize(file.size) }} · {{ formatDate(file.createTime) }}</p>
          </div>

          <div class="file-actions">
            <button type="button" @click="openPreview(file)"><EyeIcon />预览</button>
            <button type="button" @click="downloadFile(file)"><DownloadIcon />下载</button>
            <button type="button" @click="renameFile(file)"><EditIcon />重命名</button>
            <button type="button" class="danger-text" @click="confirmDeleteFile(file)"><TrashIcon />删除</button>
          </div>
        </article>
      </div>

      <!-- 拖拽提示条：整页都是投放区，但只在拖拽时显形 -->
      <div v-if="dragging" class="files-drop-hint">
        <UploadIcon />
        松开即可上传
      </div>
    </main>

    <UserFilePreviewModal :visible="previewVisible" :file="previewFile" @close="previewVisible = false" />
  </section>
</template>

<script setup>
import { computed, h, onMounted, ref } from 'vue'
import {
  listUserFiles,
  getUserFileQuota,
  uploadUserFile,
  renameUserFile,
  deleteUserFile,
  downloadUserFileBlob
} from '../api/userFile'
import { fileIconFor, fileKindLabel } from '../utils/fileKind'
import { confirmDanger, toast } from '../utils/confirm'
import AppPageLoader from '../components/AppPageLoader.vue'
import UserFilePreviewModal from '../components/UserFilePreviewModal.vue'

const emit = defineEmits(['open-chat'])

/* 图标：与 ResourceView 同一套工厂写法，24 viewBox / 1.8 描边 / round 端点 */
const icon = paths => ({
  render() {
    return h('svg', { width: 18, height: 18, viewBox: '0 0 24 24', fill: 'none', 'aria-hidden': 'true' },
      paths.map(d => h('path', {
        d, stroke: 'currentColor', 'stroke-width': 1.8,
        'stroke-linecap': 'round', 'stroke-linejoin': 'round'
      })))
  }
})
const UploadIcon = icon(['M12 20V8', 'm7 12 5-4 5 4', 'M4 4h16'])
const SearchIcon = icon(['m21 21-4.35-4.35', 'M19 11a8 8 0 1 1-16 0 8 8 0 0 1 16 0Z'])
const CloseIcon = icon(['m6 6 12 12M18 6 6 18'])
const EyeIcon = icon(['M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12Z', 'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z'])
const DownloadIcon = icon(['M12 3v12', 'm7 11 5 5 5-5', 'M4 20h16'])
const EditIcon = icon(['M12 20h9', 'M16.5 3.5a2.1 2.1 0 0 1 3 3L8 18l-4 1 1-4Z'])
const TrashIcon = icon(['M4 7h16M9 7V4h6v3M7 7l1 13h8l1-13M10 11v5M14 11v5'])
const FolderIcon = icon(['M3.5 6.8A1.8 1.8 0 0 1 5.3 5h3.4l2 2.5h8A1.8 1.8 0 0 1 20.5 9.3v8.2a1.8 1.8 0 0 1-1.8 1.8H5.3a1.8 1.8 0 0 1-1.8-1.8Z'])
const DatabaseIcon = icon(['M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3Z', 'M4 6v6c0 1.7 3.6 3 8 3s8-1.3 8-3V6M4 12v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6'])
const AlertIcon = icon(['M10.3 2.9 1.8 17a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 2.9a2 2 0 0 0-3.4 0Z', 'M12 9v4M12 17h.01'])

const SORT_OPTIONS = [
  { value: 'date', label: '最近' },
  { value: 'name', label: '名称' },
  { value: 'size', label: '大小' }
]

/* ---- 状态 ---- */
const searchQuery = ref('')
const sortMode = ref('date')
const files = ref([])
const loading = ref(false)
const uploading = ref(false)
const uploadPercent = ref(0)
const dragging = ref(false)
const fileInputRef = ref(null)
const storageEnabled = ref(true)
const previewVisible = ref(false)
const previewFile = ref(null)

/* 配额分母来自后端 ruoyi.ai.user-file.user-quota-bytes，不写死 */
const quota = ref({ usedBytes: 0, quotaBytes: 0, fileCount: 0, maxFiles: 0, maxFileBytes: 0 })

const usedText = computed(() => formatSize(quota.value.usedBytes))
const quotaText = computed(() => formatSize(quota.value.quotaBytes))

const storagePercent = computed(() => {
  const total = quota.value.quotaBytes
  if (!total) return 0
  return Math.min(100, Math.round((quota.value.usedBytes / total) * 100))
})

/**
 * 过滤与排序在前端做：单用户上限 2000 条元数据，一次拉全量后本地过滤是瞬时的，
 * 比每次输入都往返后端体验好。后端同样支持 keyword/orderBy，数据量涨上来时可切换。
 */
const filteredFiles = computed(() => {
  const q = searchQuery.value.trim().toLowerCase()
  const list = files.value.filter(f => !q || (f.name || '').toLowerCase().includes(q))
  const sorted = [...list]
  if (sortMode.value === 'name') sorted.sort((a, b) => (a.name || '').localeCompare(b.name || ''))
  else if (sortMode.value === 'size') sorted.sort((a, b) => (b.size || 0) - (a.size || 0))
  else sorted.sort((a, b) => toTime(b.createTime) - toTime(a.createTime))
  return sorted
})

const iconOf = file => fileIconFor(file.name, file.mime)
const kindLabel = name => fileKindLabel(name)

function toTime(value) {
  if (!value) return 0
  const t = new Date(value).getTime()
  return Number.isNaN(t) ? 0 : t
}

function formatSize(bytes = 0) {
  if (!bytes) return '0 B'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  if (bytes < 1024 * 1024 * 1024) return (bytes / 1048576).toFixed(1) + ' MB'
  return (bytes / 1073741824).toFixed(2) + ' GB'
}

function formatDate(ts) {
  if (!ts) return '刚刚'
  const d = new Date(ts)
  if (Number.isNaN(d.getTime())) return '刚刚'
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/* ---- 数据 ---- */
async function loadFiles() {
  loading.value = true
  try {
    const res = await listUserFiles()
    files.value = res.files || []
    storageEnabled.value = res.storageEnabled !== false
  } catch (_) {
    files.value = []
  } finally {
    loading.value = false
  }
}

async function loadQuota() {
  try {
    const res = await getUserFileQuota()
    quota.value = {
      usedBytes: res.usedBytes || 0,
      quotaBytes: res.quotaBytes || 0,
      fileCount: res.fileCount || 0,
      maxFiles: res.maxFiles || 0,
      maxFileBytes: res.maxFileBytes || 0
    }
    storageEnabled.value = res.storageEnabled !== false
  } catch (_) {}
}

const refresh = () => Promise.all([loadFiles(), loadQuota()])

/* ---- 上传 ---- */
function pickFile() {
  if (!storageEnabled.value) {
    toast('对象存储未配置，请联系管理员开启 ruoyi.ai.storage')
    return
  }
  fileInputRef.value?.click()
}

async function onPickFile(e) {
  const list = Array.from(e.target.files || [])
  e.target.value = ''
  await uploadFiles(list)
}

function onDragOver() {
  if (storageEnabled.value && !uploading.value) dragging.value = true
}

function onDragLeave(e) {
  // 只在真正离开主区域时收起提示，否则划过子元素就会闪烁
  if (e.currentTarget.contains(e.relatedTarget)) return
  dragging.value = false
}

async function onDrop(e) {
  dragging.value = false
  if (!storageEnabled.value) return
  await uploadFiles(Array.from(e.dataTransfer?.files || []))
}

async function uploadFiles(list) {
  if (!list.length) return
  uploading.value = true
  let ok = 0
  try {
    // 串行上传：并行发多个大文件会互相抢带宽，进度条也给不出有意义的数字
    for (const file of list) {
      uploadPercent.value = 0
      try {
        await uploadUserFile(file, p => { uploadPercent.value = p })
        ok++
      } catch (_) {
        // 单个失败(超配额/超限)已由 request 拦截器 toast，继续传剩下的
      }
    }
  } finally {
    uploading.value = false
    uploadPercent.value = 0
  }
  if (ok) {
    toast(ok === list.length ? `已上传 ${ok} 个文件` : `已上传 ${ok}/${list.length} 个文件`)
    await refresh()
  }
}

/* ---- 操作 ---- */
function openPreview(file) {
  previewFile.value = file
  previewVisible.value = true
}

async function downloadFile(file) {
  if (!file) return
  try {
    await downloadUserFileBlob(file.fileId, file.name)
    toast(`开始下载「${file.name}」`)
  } catch (_) {}
}

async function renameFile(file) {
  if (!file) return
  const name = window.prompt('重命名文件', file.name)
  if (name == null) return
  const trimmed = name.trim()
  if (!trimmed || trimmed === file.name) return
  try {
    await renameUserFile(file.fileId, trimmed)
    toast('已重命名')
    await loadFiles()
  } catch (_) {}
}

async function confirmDeleteFile(file) {
  if (!file) return
  const ok = await confirmDanger('确认删除文件？', `确定删除「${file.name}」吗？此操作不可撤销。`, {
    okLabel: '删除',
    cancelLabel: '取消'
  })
  if (!ok) return
  try {
    await deleteUserFile(file.fileId)
    if (previewFile.value?.fileId === file.fileId) previewVisible.value = false
    toast('已删除文件')
    await refresh()
  } catch (_) {}
}

onMounted(refresh)
</script>

<style scoped>
.files-view {
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--bg);
}

/* ---- 头部：与 ResourceView 的 56px 标题栏一致 ---- */
.files-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  flex: 0 0 auto;
  padding: 24px 32px 16px;
}

.files-header__text { min-width: 0; }

.files-header h1 {
  margin: 0;
  color: var(--text);
  font-size: 22px;
  font-weight: 660;
  letter-spacing: -.01em;
}

.files-header p {
  max-width: 620px;
  margin: 7px 0 0;
  color: var(--text-tertiary);
  font-size: 13px;
  line-height: 1.55;
}

.files-file-input { display: none; }

.files-content {
  flex: 1;
  overflow-y: auto;
  padding: 4px 32px 32px;
  position: relative;
}

.files-content.is-dragging::after {
  content: '';
  position: absolute;
  inset: 12px;
  border: 1.5px dashed var(--accent);
  border-radius: 14px;
  background: var(--accent-weak);
  opacity: .5;
  pointer-events: none;
}

/* ---- 配额卡片 ---- */
.files-quota {
  padding: 14px 16px;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--bg-elevated);
}

.files-quota__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.files-quota__label {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: var(--text-secondary);
  font-size: 12.5px;
  font-weight: 550;
}

.files-quota__label svg { width: 15px; height: 15px; color: var(--text-tertiary); }

.files-quota__value {
  color: var(--text);
  font-size: 12.5px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.files-quota__track {
  height: 5px;
  margin-top: 10px;
  overflow: hidden;
  border-radius: 99px;
  background: var(--bg-hover);
}

.files-quota__track b {
  display: block;
  height: 100%;
  min-width: 2px;
  border-radius: inherit;
  background: var(--accent);
  transition: width .28s var(--ease-out, ease);
}

.files-quota__track b.is-warn { background: var(--warn, #f59e0b); }

.files-quota__foot {
  margin: 8px 0 0;
  color: var(--text-tertiary);
  font-size: 11.5px;
  font-variant-numeric: tabular-nums;
}

/* ---- 工具栏 ---- */
.files-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 18px 0 12px;
}

.files-search {
  min-width: 0;
  flex: 1;
  max-width: 340px;
  height: 34px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 10px;
  border: 1px solid var(--border);
  border-radius: 9px;
  background: var(--bg-elevated);
  transition: border-color .15s;
}

.files-search:focus-within { border-color: var(--accent); }
.files-search svg { width: 15px; height: 15px; flex: none; color: var(--text-tertiary); }

.files-search input {
  min-width: 0;
  flex: 1;
  border: 0;
  outline: none;
  background: transparent;
  color: var(--text);
  font: inherit;
  font-size: 13px;
}

.files-search input::-webkit-search-cancel-button { display: none; }

.files-search button {
  display: grid;
  place-items: center;
  flex: none;
  padding: 2px;
  border: 0;
  border-radius: 5px;
  color: var(--text-tertiary);
  background: transparent;
  cursor: pointer;
}

.files-search button:hover { color: var(--text); background: var(--bg-hover); }
.files-search button svg { width: 13px; height: 13px; }

.files-sort {
  display: inline-flex;
  flex: none;
  padding: 3px;
  border-radius: 9px;
  background: var(--bg-hover);
}

.files-sort button {
  height: 26px;
  padding: 0 12px;
  border: 0;
  border-radius: 7px;
  color: var(--text-tertiary);
  background: transparent;
  font: inherit;
  font-size: 12px;
  font-weight: 550;
  cursor: pointer;
  transition: color .14s, background .14s;
}

.files-sort button:hover { color: var(--text); }
.files-sort button.active { color: var(--text); background: var(--bg-elevated); box-shadow: 0 1px 2px rgba(15, 23, 42, .08); }

/* ---- 列表：与 KbView 的 document-list 同构 ---- */
.files-list {
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--bg-elevated);
  box-shadow: 0 1px 2px rgba(15, 50, 100, .025);
}

.file-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 16px;
  padding: 13px 18px;
  border-bottom: 1px solid var(--divider);
  transition: background .14s ease;
}

.file-row:last-child { border-bottom: 0; }
.file-row:hover { background: var(--bg-hover); }

/* 裸图标，不套背景容器 —— 对齐 KbView 文档行的做法 */
.file-symbol {
  display: inline-grid;
  place-items: center;
  flex: none;
  color: var(--text-tertiary);
}

.file-row:hover .file-symbol { color: var(--accent); }
.file-symbol svg { width: 16px; height: 16px; }

.file-summary { min-width: 0; cursor: pointer; }

.file-title-line {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 7px;
}

.file-title-line strong {
  min-width: 0;
  overflow: hidden;
  color: var(--text);
  font-size: 13.5px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-badge {
  flex: none;
  padding: 1px 6px;
  border-radius: 99px;
  color: var(--text-tertiary);
  background: var(--bg-hover);
  font-size: 10px;
  font-style: normal;
  font-weight: 600;
  letter-spacing: .02em;
}

.file-row:hover .file-badge { background: var(--bg); }

.file-meta {
  margin: 6px 0 0 23px;
  color: var(--text-tertiary);
  font-size: 11.5px;
  font-variant-numeric: tabular-nums;
}

.file-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 2px;
  flex: none;
}

/* 图标+文字，与 KbView 的 document-row__actions 同构 */
.file-actions button {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 6px 8px;
  border: 0;
  border-radius: 6px;
  color: var(--text-secondary);
  background: transparent;
  font: inherit;
  font-size: 11.5px;
  cursor: pointer;
  transition: .14s ease;
}

.file-actions button:hover { color: var(--accent); background: var(--accent-weak); }
.file-actions .danger-text:hover { color: var(--danger); background: rgba(220, 70, 70, .06); }
.file-actions svg { width: 14px; height: 14px; }

button:disabled { opacity: .5; cursor: not-allowed; }

/* ---- 空状态：与 KbView 的 state-box 同构 ---- */
.files-state {
  min-height: 300px;
  display: grid;
  place-content: center;
  justify-items: center;
  gap: 10px;
  padding: 40px 16px;
  border: 1px dashed var(--border-strong);
  border-radius: 12px;
  color: var(--text-tertiary);
  font-size: 13px;
  text-align: center;
}

.files-state__icon {
  width: 44px;
  height: 44px;
  display: grid;
  place-items: center;
  border: 1px solid var(--border);
  border-radius: 12px;
  color: var(--accent);
  background: var(--bg-hover);
}

.files-state__icon svg { width: 20px; height: 20px; }
.files-state h2 { margin: 4px 0 0; color: var(--text); font-size: 16px; font-weight: 620; }
.files-state p { max-width: 400px; margin: 0; line-height: 1.6; }
.files-state code { padding: 1px 5px; border-radius: 4px; background: var(--bg-hover); font-size: 12px; }
.files-state .secondary-button { margin-top: 6px; }

/* ---- 全局按钮类的本页实现（与 ResourceView 一致的尺寸与配色）---- */
.primary-button,
.secondary-button {
  height: 34px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  padding: 0 14px;
  border: 1px solid transparent;
  border-radius: 9px;
  font: inherit;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: color .15s, border-color .15s, background .15s, box-shadow .15s;
}

.primary-button {
  color: #fff;
  background: var(--accent);
  box-shadow: 0 1px 2px rgba(37, 99, 235, .2);
}

.primary-button:hover:not(:disabled) { filter: brightness(1.06); }

.secondary-button {
  border-color: var(--border);
  color: var(--text);
  background: var(--bg-elevated);
}

.secondary-button:hover:not(:disabled) { background: var(--bg-hover); }
.primary-button svg,
.secondary-button svg { width: 15px; height: 15px; }

/* ---- 拖拽提示 ---- */
.files-drop-hint {
  position: sticky;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin-top: 14px;
  padding: 10px;
  border-radius: 10px;
  color: var(--accent);
  background: var(--accent-weak);
  font-size: 12.5px;
  font-weight: 600;
  pointer-events: none;
}

.files-drop-hint svg { width: 15px; height: 15px; }
</style>
