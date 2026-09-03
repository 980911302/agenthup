<template>
  <div v-if="visible" class="cite-section">
    <button type="button" class="cite-toggle" :class="{ 'is-open': expanded }" @click="toggle">
      <span class="cite-toggle__icon">📚</span>
      <span class="cite-toggle__title">{{ titleText }}</span>
      <span v-if="chunkTotal > 0" class="cite-toggle__sub">共命中 {{ chunkTotal }} 个片段</span>
      <svg class="cite-toggle__chev" width="9" height="9" viewBox="0 0 12 12" fill="none"><path d="M2.5 4.5L6 8l3.5-3.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
    </button>

    <div v-if="expanded" class="cite-body">
      <div v-if="loading" class="cite-hint">正在加载引用来源…</div>
      <div v-else-if="loadError" class="cite-hint cite-hint--err">{{ loadError }}</div>
      <div v-else-if="files.length" class="cite-grid">
        <button
          v-for="(file, idx) in files"
          :key="fileKey(file, idx)"
          type="button"
          class="cite-card"
          @click="openFile(file)"
        >
          <span class="cite-card__icon">
            <svg width="14" height="14" viewBox="0 0 16 16" fill="none">
              <path d="M9.2 1.8H4.6A1.6 1.6 0 0 0 3 3.4v9.2a1.6 1.6 0 0 0 1.6 1.6h6.8a1.6 1.6 0 0 0 1.6-1.6V5.6z" stroke="currentColor" stroke-width="1.25" stroke-linejoin="round"/>
              <path d="M9.2 1.8v3.8H13" stroke="currentColor" stroke-width="1.25" stroke-linejoin="round"/>
            </svg>
          </span>
          <span class="cite-card__main">
            <span class="cite-card__name" :title="file.docName">{{ file.docName }}</span>
            <span class="cite-card__meta">
              <span v-if="file.kbName" class="cite-card__kb">{{ file.kbName }}</span>
              <span>命中 {{ file.chunkCount || (file.chunks && file.chunks.length) || 0 }} 段</span>
            </span>
          </span>
          <span class="cite-card__idx">{{ idx + 1 }}</span>
        </button>
      </div>
    </div>
    <CitationPreviewDialog v-model:visible="dialogVisible" :file="selected" :kb-ids="kbIds" />
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { getSpecialEvents } from '../../api/session'
import { UI_ARTIFACT_NAMES } from '../../chat/types'
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
@use '../ai-tokens.scss' as *;

.cite-section {
  margin: 8px 0 4px;
}

.cite-toggle {
  display: inline-flex;
  align-items: center;
  gap: 5.5px;
  height: 26px;
  padding: 0 9px;
  border-radius: 6px;
  background: var(--bg-hover);
  border: 1px solid var(--border);
  color: var(--text-secondary);
  font-size: 11.5px;
  font-weight: 500;
  cursor: pointer;
  list-style: none;
  user-select: none;
  transition: all 0.14s $ease;

  &:hover {
    background: var(--accent-weak);
    color: var(--accent);
    border-color: var(--accent-border);
  }

  &__icon {
    font-size: 12px;
    line-height: 1;
    display: inline-flex;
  }

  &__title {
    font-size: 11.5px;
  }

  &__sub {
    font-size: 11px;
    color: var(--text-tertiary);
    font-weight: normal;
  }

  &__chev {
    color: var(--text-tertiary);
    transition: transform 0.18s $ease;
    margin-left: 2px;
  }

  &.is-open &__chev {
    transform: rotate(180deg);
  }
}

.cite-body {
  margin-top: 6px;
  animation: fade-slide 0.16s cubic-bezier(0.16, 1, 0.3, 1) both;
}

.cite-hint {
  font-size: 11.5px;
  color: var(--text-tertiary);
  padding: 4px 0;

  &--err {
    color: var(--danger-text, #ef4444);
  }
}

.cite-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.cite-card {
  display: flex;
  align-items: center;
  gap: 7px;
  max-width: 280px;
  min-width: 170px;
  padding: 6px 9px;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--bg-card, var(--bg-elevated));
  cursor: pointer;
  text-align: left;
  transition: all 0.14s $ease;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.02);

  &:hover {
    border-color: var(--accent-border, rgba(10, 132, 255, 0.3));
    background: var(--bg-hover);
    box-shadow: 0 2px 4px rgba(0, 0, 0, 0.04);
  }

  &__icon {
    width: 22px;
    height: 22px;
    border-radius: 5px;
    background: var(--ai-fill-2);
    border: 1px solid var(--border);
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--accent, #0A84FF);
    flex-shrink: 0;
  }

  &__main {
    min-width: 0;
    flex: 1;
  }

  &__name {
    display: block;
    font-size: 11.5px;
    font-weight: 600;
    color: var(--text);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__meta {
    display: flex;
    align-items: center;
    gap: 5px;
    margin-top: 1.5px;
    font-size: 10.5px;
    color: var(--text-tertiary);

    span {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }

  &__kb {
    background: var(--bg-code, #f1f5f9);
    padding: 0 4px;
    border-radius: 3px;
    max-width: 100px;
  }

  &__idx {
    font-family: $mono;
    font-size: 10.5px;
    font-weight: 600;
    color: var(--text-tertiary);
    flex-shrink: 0;
  }
}

@keyframes fade-slide {
  from { opacity: 0; transform: translateY(-4px); }
  to { opacity: 1; transform: translateY(0); }
}
</style>
