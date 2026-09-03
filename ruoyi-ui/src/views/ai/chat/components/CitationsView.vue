<template>
  <div v-if="visible" class="cite-files">
    <button type="button" class="cite-files__title" @click="toggle">
      <svg width="13" height="13" viewBox="0 0 16 16" fill="none" aria-hidden="true">
        <path d="M5.6 5.2H2.8v8h2.8zM8 3.4v9.8M13.2 5.2H10.4v8h2.8z" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/>
        <circle cx="8" cy="3.4" r="1.2" stroke="currentColor" stroke-width="1.2"/>
      </svg>
      <span>{{ titleText }}</span>
      <span v-if="chunkTotal > 0" class="cite-files__sub">{{ chunkTotal }} 个片段</span>
      <span class="cite-files__toggle">{{ expanded ? '收起' : '展开' }}</span>
    </button>
    <div v-if="expanded">
      <div v-if="loading" class="cite-files__hint">正在加载引用…</div>
      <div v-else-if="loadError" class="cite-files__hint">{{ loadError }}</div>
      <div v-else-if="files.length" class="cite-files__row">
        <button
          v-for="(file, idx) in files"
          :key="fileKey(file, idx)"
          type="button"
          class="cite-file"
          @click="openFile(file)"
        >
          <span class="cite-file__idx">{{ idx + 1 }}</span>
          <span class="cite-file__main">
            <span class="cite-file__name" :title="file.docName">{{ file.docName }}</span>
            <span class="cite-file__meta">
              <span v-if="file.kbName">{{ file.kbName }}</span>
              <span>命中 {{ file.chunkCount || (file.chunks && file.chunks.length) || 0 }} 段</span>
            </span>
          </span>
        </button>
      </div>
    </div>
    <CitationPreviewDialog v-model:visible="dialogVisible" :file="selected" :kb-ids="kbIds" />
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { getSpecialEvents } from '@/api/ai/session'
import { UI_ARTIFACT_NAMES } from '../types/chat'
import CitationPreviewDialog from './CitationPreviewDialog.vue'

const props = defineProps({
  citations: { type: Array, default: () => [] },
  files: { type: Array, default: () => [] },
  total: { type: Number, default: 0 },
  kbIds: { type: Array, default: () => [] },
  sessionId: { type: [String, Number], default: null },
  messageId: { type: [String, Number], default: null }
})

const localFiles = ref([])
const localTotal = ref(0)
const loaded = ref(false)
const loading = ref(false)
const loadError = ref('')
const expanded = ref(false)

watch(() => props.files, (next) => {
  if (next && next.length) {
    localFiles.value = next
    loaded.value = true
    // 默认保持折叠，由用户按需点击展开
  }
}, { immediate: true })

const files = computed(() => localFiles.value.length ? localFiles.value : (props.files || []))
const displayTotal = computed(() => {
  if (files.value.length) return files.value.length
  if (localTotal.value > 0) return localTotal.value
  if (props.total > 0) return props.total
  return 0
})
const visible = computed(() => displayTotal.value > 0 || files.value.length > 0)
const titleText = computed(() => `知识库引用 · ${displayTotal.value} 个来源`)
const chunkTotal = computed(() => files.value.reduce((n, file) => {
  const chunks = Number(file.chunkCount) || (file.chunks && file.chunks.length) || 0
  return n + chunks
}, 0))

const dialogVisible = ref(false)
const selected = ref(null)

function fileKey(file, idx) {
  if (file.kbId && file.docId) return `${file.kbId}|${file.docId}`
  return `${file.docName || 'doc'}-${idx}`
}

function openFile(file) {
  selected.value = file
  dialogVisible.value = true
}

async function toggle() {
  expanded.value = !expanded.value
  if (expanded.value) await ensureLoaded()
}

async function ensureLoaded() {
  if (loaded.value || (props.files && props.files.length) || localFiles.value.length) {
    loaded.value = true
    return
  }
  if (!props.sessionId || props.messageId == null) {
    loadError.value = '缺少会话定位,无法加载引用'
    return
  }
  loading.value = true
  loadError.value = ''
  try {
    const res = await getSpecialEvents(props.sessionId, {
      messageId: props.messageId,
      name: UI_ARTIFACT_NAMES.KB_REFERENCES
    })
    const rows = res.data || []
    let next = []
    let total = 0
    for (const row of rows) {
      const version = Number(row && row.schemaVersion)
      if (Number.isFinite(version) && version < 2) continue
      const payload = row && row.payload ? row.payload : {}
      const list = Array.isArray(payload.files) ? payload.files : []
      if (!list.length) continue
      next = list
      total = Number(payload.fileCount) || list.length
    }
    localFiles.value = next
    localTotal.value = total || next.length
    loaded.value = true
  } catch (e) {
    loadError.value = (e && e.message) || '加载引用失败'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped lang="scss">
@use '../../../../assets/styles/ai-tokens.scss' as *;

.cite-files { margin: 8px 0 2px; }
.cite-files__title {
  display: flex; align-items: center; gap: 6px;
  font-size: $ai-fs-6; color: $ai-text3; margin-bottom: 6px;
  padding: 0; border: 0; background: transparent; cursor: pointer;
  svg { color: $blue; flex-shrink: 0; }
}
.cite-files__sub { color: $ai-gray; }
.cite-files__toggle { margin-left: auto; color: $ai-gray; }
.cite-files__hint { font-size: $ai-fs-6; color: $ai-text3; margin: 4px 0 8px; }
.cite-files__row { display: flex; flex-wrap: wrap; gap: 8px; }
.cite-file {
  display: flex; align-items: stretch; max-width: 260px; min-width: 160px;
  padding: 0; border: 1px solid var(--ai-border, #E3E6EA); border-radius: 10px;
  background: var(--ai-card-bg, #fff); overflow: hidden; cursor: pointer; text-align: left;
  transition: border-color 0.15s $ease;
  &:hover { border-color: var(--ai-border-strong, #B9C0CA); }
}
.cite-file__idx {
  display: flex; align-items: center; justify-content: center;
  min-width: 22px; font-size: $ai-fs-6; font-weight: 600; color: $blue;
  background: rgba(10, 132, 255, 0.08);
  border-right: 1px solid var(--ai-border, #E3E6EA);
}
.cite-file__main { min-width: 0; padding: 6px 8px; }
.cite-file__name {
  display: block; font-size: $ai-fs-5; color: $ai-text; font-weight: 500;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.cite-file__meta {
  display: flex; gap: 6px; margin-top: 2px; font-size: $ai-fs-6; color: $ai-text3;
  span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
}
</style>
