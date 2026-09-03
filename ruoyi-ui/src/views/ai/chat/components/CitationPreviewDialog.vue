<template>
  <Teleport to="body">
    <div v-if="visible" class="cite-dlg" @click.self="close">
      <div class="cite-dlg__panel" :class="{ 'is-wide': view === 'file' }" role="dialog" aria-modal="true">
        <header class="cite-dlg__head">
          <div class="cite-dlg__title">
            <svg class="cite-dlg__doc-ic" width="15" height="15" viewBox="0 0 16 16" fill="none" aria-hidden="true">
              <path d="M9 1.8H4.4a1.2 1.2 0 0 0-1.2 1.2v10a1.2 1.2 0 0 0 1.2 1.2h7.2a1.2 1.2 0 0 0 1.2-1.2V5.6z" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/>
              <path d="M9 1.8V5.6h3.8" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/>
            </svg>
            <div class="cite-dlg__title-text">
              <div class="cite-dlg__name" :title="file?.docName">{{ displayName }}</div>
              <div class="cite-dlg__meta">
                <span v-if="file?.kbName">{{ file.kbName }}</span>
                <span v-if="file">命中 {{ file.chunkCount || file.chunks.length }} 段</span>
              </div>
            </div>
          </div>
          <button type="button" class="cite-dlg__icon-btn" title="关闭" @click="close">
            <svg width="14" height="14" viewBox="0 0 14 14" fill="none"><path d="M3 3l8 8M11 3L3 11" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>
          </button>
        </header>

        <div v-if="view !== 'list'" class="cite-dlg__sub">
          <button type="button" class="cite-dlg__back" @click="back">
            <svg width="12" height="12" viewBox="0 0 12 12" fill="none"><path d="M7.5 2.5L4 6l3.5 3.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
            返回{{ view === 'file' ? '' : '列表' }}
          </button>
          <div v-if="view === 'chunk'" class="cite-dlg__nav">
            <span>{{ chunkPos }} / {{ file?.chunks.length || 0 }}</span>
            <button type="button" class="cite-dlg__icon-btn" title="上一段" :disabled="chunkCount <= 1" @click="stepChunk(-1)">
              <svg width="12" height="12" viewBox="0 0 12 12" fill="none"><path d="M7.5 2.5L4 6l3.5 3.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>
            </button>
            <button type="button" class="cite-dlg__icon-btn" title="下一段" :disabled="chunkCount <= 1" @click="stepChunk(1)">
              <svg width="12" height="12" viewBox="0 0 12 12" fill="none"><path d="M4.5 2.5L8 6l-3.5 3.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>
            </button>
          </div>
        </div>

        <div ref="bodyEl" class="cite-dlg__body" :class="{ 'is-flush': view === 'file' }">
          <template v-if="view === 'file'">
            <div v-if="previewLoading" class="cite-dlg__empty">正在加载原文预览…</div>
            <div v-else-if="previewError" class="cite-dlg__empty">{{ previewError }}</div>
            <div v-else-if="preview" class="cite-dlg__preview">
              <aside v-if="preview.outline && preview.outline.length" class="cite-dlg__outline">
                <div class="cite-dlg__outline-title">目录</div>
                <button
                  v-for="(n, i) in preview.outline"
                  :key="i"
                  type="button"
                  class="cite-dlg__outline-item"
                  @click="scrollToPos(n.position)"
                >{{ n.title || '（无标题）' }}</button>
              </aside>
              <div ref="previewRef" class="cite-dlg__preview-main">
                <div
                  v-for="b in (preview.blocks || [])"
                  :key="'b' + b.position"
                  class="cite-dlg__block"
                  :data-pos="b.position"
                  v-html="sanitizeHtml(b.html)"
                />
                <div v-for="t in (preview.tables || [])" :key="'t' + t.position" class="cite-dlg__table" v-html="sanitizeHtml(t.html)" />
                <p v-if="preview.counts && preview.counts.truncated" class="cite-dlg__hint">预览已截断，完整内容已参与检索</p>
              </div>
            </div>
            <div v-else class="cite-dlg__empty">暂无原文预览</div>
          </template>

          <template v-else-if="file && file.chunks.length">
            <div v-if="view === 'list'" class="cite-dlg__list">
              <button
                v-for="(chunk, idx) in file.chunks"
                :key="chunk.chunkId || idx"
                type="button"
                class="cite-dlg__row"
                @click="openChunk(idx)"
              >
                <span class="cite-dlg__badge">#{{ chunk.index || idx + 1 }}</span>
                <span class="cite-dlg__row-text">
                  <span v-if="chunk.headingPath" class="cite-dlg__path">{{ chunk.headingPath }}</span>
                  <span class="cite-dlg__snippet">{{ previewText(chunk.content) }}</span>
                </span>
                <svg width="10" height="10" viewBox="0 0 12 12" fill="none"><path d="M4 2.5L8 6l-4 3.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>
              </button>
            </div>
            <div v-else class="cite-dlg__detail">
              <MarkdownContent :text="activeChunk.content || ''" />
            </div>
          </template>
          <div v-else class="cite-dlg__empty">暂无命中片段</div>
        </div>

        <footer class="cite-dlg__foot">
          <span v-if="actionError" class="cite-dlg__err">{{ actionError }}</span>
          <button v-if="view === 'chunk'" type="button" class="cite-dlg__btn" @click="copyChunk">
            {{ copied ? '已复制' : '复制片段' }}
          </button>
          <button
            v-if="canOpenSource && view !== 'file'"
            type="button"
            class="cite-dlg__btn"
            @click="openFilePreview"
          >预览原文</button>
          <button
            type="button"
            class="cite-dlg__btn cite-dlg__btn--primary"
            :disabled="!canOpenSource || downloading || resolving"
            :title="downloadTitle"
            @click="downloadFile"
          >{{ downloading ? '下载中…' : (resolving ? '定位文档…' : '下载原文件') }}</button>
        </footer>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import DOMPurify from 'dompurify'
import { saveAs } from 'file-saver'
import { ElMessage } from 'element-plus'
import { getKbDocPreview, downloadKbDocument, listKbDoc } from '@/api/ai/kb'
import { blobValidate } from '@/utils/ruoyi'
import errorCode from '@/utils/errorCode'
import MarkdownContent from './MarkdownContent.vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  file: { type: Object, default: null },
  kbIds: { type: Array, default: () => [] }
})
const emit = defineEmits(['update:visible'])

const view = ref('list')
const activeIndex = ref(0)
const bodyEl = ref(null)
const previewRef = ref(null)
const preview = ref(null)
const previewLoading = ref(false)
const previewError = ref('')
const downloading = ref(false)
const resolving = ref(false)
const resolved = ref({ kbId: null, docId: null })
const actionError = ref('')
const copied = ref(false)
let copiedTimer = null

const displayName = computed(() => props.file?.docName || '未知文件')
const chunkCount = computed(() => props.file?.chunks?.length || 0)
const activeChunk = computed(() => props.file?.chunks?.[activeIndex.value] || null)
const chunkPos = computed(() => activeIndex.value + 1)
const sourceIds = computed(() => {
  if (resolved.value.kbId && resolved.value.docId) return resolved.value
  const file = props.file
  if (file?.kbId && file?.docId) return { kbId: file.kbId, docId: file.docId }
  const chunk = (file?.chunks || []).find(c => c.kbId && c.docId)
  return chunk ? { kbId: chunk.kbId, docId: chunk.docId } : { kbId: null, docId: null }
})
const canOpenSource = computed(() => Boolean(sourceIds.value.kbId && sourceIds.value.docId))
const downloadTitle = computed(() => {
  if (resolving.value) return '正在按文件名定位原文档'
  if (canOpenSource.value) return '下载原文件'
  return '缺少文档定位信息，无法下载'
})

function close() {
  emit('update:visible', false)
  actionError.value = ''
}

function scrollTop() {
  nextTick(() => {
    if (bodyEl.value) bodyEl.value.scrollTop = 0
  })
}

function openChunk(idx) {
  activeIndex.value = idx
  view.value = 'chunk'
  scrollTop()
}

function back() {
  if (view.value === 'file') {
    view.value = chunkCount.value === 1 ? 'chunk' : 'list'
  } else {
    view.value = 'list'
  }
  scrollTop()
}

function stepChunk(delta) {
  const total = chunkCount.value
  if (total <= 1) return
  activeIndex.value = (activeIndex.value + delta + total) % total
  scrollTop()
}

function previewText(content, maxLen = 52) {
  const plain = String(content || '')
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/[#*_`>]/g, '')
    .replace(/!?\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/\s+/g, ' ')
    .trim()
  return plain.length > maxLen ? plain.slice(0, maxLen) + '…' : (plain || '（空片段）')
}

function sanitizeHtml(html) {
  return DOMPurify.sanitize(html || '', { ADD_ATTR: ['target', 'data-pos'] })
}

function scrollToPos(pos) {
  const root = previewRef.value
  if (!root) return
  const el = root.querySelector(`[data-pos="${pos}"]`)
  if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

async function copyChunk() {
  if (!activeChunk.value?.content) return
  try {
    await navigator.clipboard.writeText(activeChunk.value.content)
    copied.value = true
    if (copiedTimer) clearTimeout(copiedTimer)
    copiedTimer = setTimeout(() => { copied.value = false }, 1200)
  } catch (_) {
    ElMessage.error('复制失败')
  }
}

async function openFilePreview() {
  if (!canOpenSource.value) return
  view.value = 'file'
  actionError.value = ''
  previewError.value = ''
  scrollTop()
  if (preview.value || previewLoading.value) return
  previewLoading.value = true
  try {
    const { kbId, docId } = sourceIds.value
    const res = await getKbDocPreview(kbId, docId)
    const data = res.data || {}
    const pv = data.preview
    if (!pv || pv.available === false) {
      const reasons = {
        FAILED: '文档处理失败，暂不可预览',
        PROCESSING: '文档仍在处理中',
        NO_IR: '尚无解析产物',
        IR_LOAD_ERROR: '预览暂时不可用'
      }
      previewError.value = reasons[pv?.reason] || pv?.reason || '该文档暂不支持在线预览'
      preview.value = null
    } else {
      preview.value = pv
    }
  } catch (e) {
    previewError.value = e?.message || '加载原文预览失败'
  } finally {
    previewLoading.value = false
  }
}

async function downloadFile() {
  if (!canOpenSource.value || downloading.value) return
  downloading.value = true
  actionError.value = ''
  try {
    const { kbId, docId } = sourceIds.value
    const blobData = await downloadKbDocument(kbId, docId)
    if (blobValidate(blobData)) {
      saveAs(new Blob([blobData]), displayName.value || ('doc-' + docId))
    } else {
      const rsp = JSON.parse(await blobData.text())
      actionError.value = errorCode[rsp.code] || rsp.msg || '下载失败'
    }
  } catch (e) {
    actionError.value = e?.message || '下载失败'
  } finally {
    downloading.value = false
  }
}

function onKey(e) {
  if (e.key === 'Escape' && props.visible) close()
}

async function resolveSource() {
  resolved.value = { kbId: null, docId: null }
  const file = props.file
  if (!file) return
  if (file.kbId && file.docId) {
    resolved.value = { kbId: file.kbId, docId: file.docId }
    return
  }
  const chunk = (file.chunks || []).find(c => c.kbId && c.docId)
  if (chunk) {
    resolved.value = { kbId: chunk.kbId, docId: chunk.docId }
    return
  }
  const name = file.docName
  const kbIds = (props.kbIds || []).filter(Boolean)
  if (!name || !kbIds.length) return
  resolving.value = true
  try {
    for (const kbId of kbIds) {
      const res = await listKbDoc(kbId, { docName: name, pageNum: 1, pageSize: 20 })
      const rows = res.rows || res.data || []
      const hit = rows.find(d => d.docName === name) || rows[0]
      if (hit?.docId) {
        resolved.value = { kbId: hit.kbId || kbId, docId: hit.docId }
        return
      }
    }
  } catch (e) {
    actionError.value = e?.message || '定位原文档失败'
  } finally {
    resolving.value = false
  }
}

watch(() => [props.visible, props.file], () => {
  preview.value = null
  previewError.value = ''
  actionError.value = ''
  copied.value = false
  if (props.file && props.file.chunks && props.file.chunks.length === 1) {
    view.value = 'chunk'
    activeIndex.value = 0
  } else {
    view.value = 'list'
    activeIndex.value = 0
  }
  if (props.visible) resolveSource()
})

watch(() => props.visible, (open) => {
  if (open) window.addEventListener('keydown', onKey)
  else window.removeEventListener('keydown', onKey)
})

onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
</script>

<style scoped lang="scss">
@use '../../../../assets/styles/ai-tokens.scss' as *;

.cite-dlg {
  position: fixed; inset: 0; z-index: 2400;
  display: flex; align-items: center; justify-content: center;
  background: rgba(15, 18, 25, 0.42);
  padding: 24px;
}
.cite-dlg__panel {
  display: flex; flex-direction: column;
  width: min(680px, 100%);
  max-height: min(72vh, 640px);
  background: var(--ai-card-bg, #fff);
  border: 1px solid var(--ai-border, #E3E6EA);
  border-radius: 14px;
  box-shadow: 0 18px 48px rgba(15, 18, 25, 0.18);
  overflow: hidden;
  &.is-wide { width: min(920px, 100%); max-height: min(88vh, 800px); }
}
.cite-dlg__head, .cite-dlg__sub, .cite-dlg__foot {
  display: flex; align-items: center; justify-content: space-between; gap: 10px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--ai-border, #E3E6EA);
}
.cite-dlg__foot { border-bottom: 0; border-top: 1px solid var(--ai-border, #E3E6EA); }
.cite-dlg__title { display: flex; align-items: center; gap: 8px; min-width: 0; }
.cite-dlg__doc-ic { color: $blue; flex-shrink: 0; }
.cite-dlg__title-text { min-width: 0; }
.cite-dlg__name {
  font-size: $ai-fs-5; font-weight: 600; color: $ai-text;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.cite-dlg__meta { display: flex; gap: 8px; margin-top: 2px; font-size: $ai-fs-6; color: $ai-text3; }
.cite-dlg__icon-btn {
  width: 26px; height: 26px; border: 0; border-radius: 7px;
  background: transparent; color: $ai-text3; cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center;
  &:hover { background: var(--ai-fill-2); color: $ai-text; }
  &:disabled { opacity: 0.35; cursor: not-allowed; }
}
.cite-dlg__back {
  display: inline-flex; align-items: center; gap: 4px;
  border: 0; background: transparent; color: $blue; font-size: $ai-fs-5; cursor: pointer;
}
.cite-dlg__nav { display: flex; align-items: center; gap: 6px; font-size: $ai-fs-6; color: $ai-text3; }
.cite-dlg__body { flex: 1; overflow: auto; padding: 12px 14px; min-height: 0; }
.cite-dlg__body.is-flush { padding: 0; overflow: hidden; display: flex; flex-direction: column; }
.cite-dlg__list { display: flex; flex-direction: column; gap: 8px; }
.cite-dlg__row {
  display: flex; align-items: center; gap: 10px; width: 100%; text-align: left;
  padding: 10px 12px; border: 1px solid var(--ai-border, #E3E6EA); border-radius: 10px;
  background: var(--ai-input-bg, #fff); color: $ai-text2; cursor: pointer;
  &:hover { border-color: var(--ai-border-strong, #B9C0CA); }
}
.cite-dlg__badge {
  flex-shrink: 0; font-family: $ai-mono; font-size: $ai-fs-6; font-weight: 600;
  color: $blue; background: rgba(10, 132, 255, 0.08); border-radius: 6px; padding: 2px 6px;
}
.cite-dlg__row-text { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.cite-dlg__path { font-size: $ai-fs-6; color: $ai-text3; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cite-dlg__snippet { font-size: $ai-fs-5; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cite-dlg__detail { font-size: $ai-fs-5; color: $ai-text; }
.cite-dlg__empty { padding: 28px 0; text-align: center; font-size: $ai-fs-5; color: $ai-text3; }
.cite-dlg__preview { flex: 1; min-height: 0; display: flex; overflow: hidden; }
.cite-dlg__outline {
  width: 180px; flex-shrink: 0; overflow: auto; padding: 10px 8px;
  border-right: 1px solid var(--ai-border, #E3E6EA);
}
.cite-dlg__outline-title { font-size: $ai-fs-6; color: $ai-text3; padding: 0 6px 6px; }
.cite-dlg__outline-item {
  display: block; width: 100%; text-align: left; border: 0; background: transparent;
  color: $ai-text2; font-size: $ai-fs-6; padding: 4px 6px; border-radius: 6px; cursor: pointer;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  &:hover { background: var(--ai-fill-2); color: $blue; }
}
.cite-dlg__preview-main { flex: 1; overflow: auto; padding: 14px 16px; }
.cite-dlg__block { font-size: $ai-fs-5; line-height: 1.7; color: $ai-text; margin-bottom: 10px; }
.cite-dlg__table { overflow: auto; margin: 10px 0; font-size: $ai-fs-6; }
.cite-dlg__hint { font-size: $ai-fs-6; color: $ai-text3; }
.cite-dlg__err { margin-right: auto; font-size: $ai-fs-6; color: $ai-red; }
.cite-dlg__btn {
  border: 1px solid var(--ai-border, #E3E6EA); background: transparent;
  color: $ai-text2; border-radius: 8px; padding: 6px 12px; font-size: $ai-fs-5; cursor: pointer;
  &:hover { border-color: $blue; color: $blue; }
  &:disabled { opacity: 0.45; cursor: not-allowed; }
}
.cite-dlg__btn--primary {
  border-color: $blue; color: $blue; background: rgba(10, 132, 255, 0.08);
}
</style>
