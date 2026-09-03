<template>
  <transition name="app-modal">
    <div v-if="visible" class="uf-preview-mask" @click.self="onClose">
      <div ref="surfaceRef" class="uf-preview" data-dialog-surface :class="{ 'is-fullscreen': fullscreen }">
        <header class="uf-preview__head">
          <span class="uf-preview__symbol" aria-hidden="true"><component :is="icon" /></span>
          <div class="uf-preview__ident">
            <strong :title="file?.name">{{ file?.name || '未选择文件' }}</strong>
            <span>{{ metaText }}</span>
          </div>
          <div class="uf-preview__actions">
            <button type="button" class="icon-button" title="下载" :disabled="!file" @click="onDownload">
              <DownloadIcon />
            </button>
            <button
              type="button"
              class="icon-button"
              :title="fullscreen ? '还原窗口' : '最大化'"
              @click="fullscreen = !fullscreen"
            >
              <component :is="fullscreen ? MinimizeIcon : MaximizeIcon" />
            </button>
            <button type="button" class="icon-button" title="关闭" @click="onClose">
              <CloseIcon />
            </button>
          </div>
        </header>

        <main class="uf-preview__body">
          <AppPageLoader v-if="loading" compact label="正在加载预览…" />
          <img v-else-if="imageUrl" :src="imageUrl" class="uf-preview__img" :alt="file?.name" />
          <div v-else-if="errorText" class="uf-preview__hint is-error">{{ errorText }}</div>
          <div v-else-if="isMarkdown" class="uf-preview__md"><MarkdownContent :text="text" /></div>
          <pre v-else-if="text" class="uf-preview__code">{{ text }}</pre>
          <div v-else class="uf-preview__hint">
            <span class="uf-preview__hint-icon" aria-hidden="true"><component :is="icon" /></span>
            <p>这种格式暂不支持预览</p>
            <button type="button" class="secondary-button" @click="onDownload">
              <DownloadIcon />
              下载文件
            </button>
          </div>
        </main>
      </div>
    </div>
  </transition>
</template>

<script setup>
import { computed, h, ref, watch } from 'vue'
import { downloadUserFileBlob, getUserFilePreviewUrl, readUserFileText } from '../api/userFile'
import { fileIconFor, isPreviewableText } from '../utils/fileKind'
import AppPageLoader from './AppPageLoader.vue'
import MarkdownContent from '../chat-ui/components/MarkdownContent.vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  file: { type: Object, default: null }
})

const emit = defineEmits(['close'])

const svg = (paths, attrs = {}) => ({
  render() {
    return h('svg', { width: 18, height: 18, viewBox: '0 0 24 24', fill: 'none', 'aria-hidden': 'true', ...attrs },
      paths.map(d => h('path', { d, stroke: 'currentColor', 'stroke-width': 1.8, 'stroke-linecap': 'round', 'stroke-linejoin': 'round' })))
  }
})
const DownloadIcon = svg(['M12 3v12', 'm7 11 5 5 5-5', 'M4 20h16'])
const CloseIcon = svg(['m6 6 12 12M18 6 6 18'])
const MaximizeIcon = svg(['M4 9V5.8A1.8 1.8 0 0 1 5.8 4H9', 'M15 4h3.2A1.8 1.8 0 0 1 20 5.8V9', 'M20 15v3.2a1.8 1.8 0 0 1-1.8 1.8H15', 'M9 20H5.8A1.8 1.8 0 0 1 4 18.2V15'])
const MinimizeIcon = svg(['M9 4v3.2A1.8 1.8 0 0 1 7.2 9H4', 'M15 4v3.2A1.8 1.8 0 0 0 16.8 9H20', 'M9 20v-3.2A1.8 1.8 0 0 0 7.2 15H4', 'M15 20v-3.2a1.8 1.8 0 0 1 1.8-1.8H20'])

const surfaceRef = ref(null)
const fullscreen = ref(false)
const loading = ref(false)
const text = ref('')
const imageUrl = ref('')
const errorText = ref('')

const icon = computed(() => fileIconFor(props.file?.name, props.file?.mime))
const isMarkdown = computed(() => /\.(md|markdown)$/i.test(props.file?.name || ''))

const metaText = computed(() => {
  if (!props.file) return ''
  return [formatSize(props.file.size), formatDate(props.file.createTime)].filter(Boolean).join(' · ')
})

function formatSize(bytes = 0) {
  if (!bytes) return '0 B'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  if (bytes < 1024 * 1024 * 1024) return (bytes / 1048576).toFixed(1) + ' MB'
  return (bytes / 1073741824).toFixed(2) + ' GB'
}

function formatDate(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  if (Number.isNaN(d.getTime())) return ''
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function reset() {
  text.value = ''
  imageUrl.value = ''
  errorText.value = ''
}

async function load() {
  const file = props.file
  reset()
  if (!file) return
  loading.value = true
  try {
    if (file.image) {
      // 图片走预签名直链：浏览器直连对象存储，不占后端线程做转发
      const res = await getUserFilePreviewUrl(file.fileId)
      imageUrl.value = res.url || ''
    } else if (isPreviewableText(file.name, file.mime)) {
      // 文本走后端 blob：预览要读内容，跨域直取会被 CORS 挡住，后端转发天然同源
      const body = await readUserFileText(file.fileId)
      if (body == null) errorText.value = '文件超过 200KB，不支持在线预览'
      else text.value = body
    }
  } catch (_) {
    errorText.value = '加载预览失败'
  } finally {
    loading.value = false
  }
}

async function onDownload() {
  if (!props.file) return
  try {
    await downloadUserFileBlob(props.file.fileId, props.file.name)
  } catch (_) {}
}

function onClose() {
  emit('close')
}

watch(() => props.visible, open => {
  if (open) load()
  else { fullscreen.value = false; reset() }
})

watch(() => props.file?.fileId, () => { if (props.visible) load() })
</script>

<style scoped>
.uf-preview-mask {
  position: fixed;
  z-index: 60;
  inset: 0;
  display: grid;
  place-items: center;
  padding: 32px;
  background: rgba(9, 15, 25, .52);
  backdrop-filter: blur(2px);
}

.uf-preview {
  width: min(920px, 100%);
  max-height: min(78vh, 760px);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: 14px;
  background: var(--bg-elevated);
  box-shadow: 0 24px 64px rgba(15, 23, 42, .28);
  animation: app-dialog-enter var(--duration-base) var(--ease-out) both;
}

.uf-preview.is-fullscreen {
  width: 100%;
  max-height: 100%;
  height: 100%;
}

.uf-preview__head {
  display: flex;
  align-items: center;
  gap: 11px;
  flex: none;
  padding: 12px 14px 12px 16px;
  border-bottom: 1px solid var(--divider);
}

.uf-preview__symbol {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  flex: none;
  border-radius: 9px;
  color: var(--text-tertiary);
  background: var(--bg-hover);
}

.uf-preview__symbol svg { width: 16px; height: 16px; }

.uf-preview__ident { min-width: 0; flex: 1; }

.uf-preview__ident strong {
  display: block;
  overflow: hidden;
  color: var(--text);
  font-size: 13.5px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.uf-preview__ident span {
  display: block;
  margin-top: 3px;
  color: var(--text-tertiary);
  font-size: 11.5px;
  font-variant-numeric: tabular-nums;
}

.uf-preview__actions { display: flex; align-items: center; gap: 2px; flex: none; }

.uf-preview__body {
  flex: 1;
  min-height: 220px;
  overflow: auto;
  padding: 20px;
}

.uf-preview__img {
  display: block;
  max-width: 100%;
  max-height: 100%;
  margin: 0 auto;
  border-radius: 10px;
}

.uf-preview__code {
  margin: 0;
  color: var(--text-secondary);
  font-family: var(--font-mono, ui-monospace, Menlo, Consolas, monospace);
  font-size: 12.5px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
}

.uf-preview__md { color: var(--text); font-size: 13.5px; }

.uf-preview__hint {
  min-height: 200px;
  display: grid;
  place-content: center;
  justify-items: center;
  gap: 10px;
  color: var(--text-tertiary);
  font-size: 13px;
  text-align: center;
}

.uf-preview__hint.is-error { color: var(--danger); }

.uf-preview__hint-icon {
  width: 44px;
  height: 44px;
  display: grid;
  place-items: center;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--bg-hover);
  color: var(--text-tertiary);
}

.uf-preview__hint-icon svg { width: 20px; height: 20px; }
.uf-preview__hint p { margin: 0; }
</style>
