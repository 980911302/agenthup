<template>
  <transition name="app-modal">
    <div v-if="visible" class="ws-preview-mask" @click.self="onClose">
      <div class="ws-preview-modal" data-dialog-surface :class="{ 'is-fullscreen': fullscreen }">
        <!-- 弹窗头部 (1:1 对齐 PC) -->
        <header class="ws-preview-modal__head">
          <span class="ws-preview-modal__path" :title="path">{{ path || '未选择文件' }}</span>
          <div class="ws-preview-modal__actions">
            <button type="button" class="ws-preview-modal__btn" title="下载此文件" :disabled="!path" @click="onDownload">
              <svg width="13" height="13" viewBox="0 0 14 14" fill="none">
                <path d="M7 1.5v7M4 6l3 3 3-3" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>
                <path d="M2 10.5v1.2a.8.8 0 0 0 .8.8h8.4a.8.8 0 0 0 .8-.8v-1.2" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/>
              </svg>
            </button>
            <button type="button" class="ws-preview-modal__btn ws-preview-modal__btn--danger" title="删除此文件" :disabled="!path" @click="onDelete">
              <svg width="13" height="13" viewBox="0 0 14 14" fill="none">
                <path d="M3 4.5h8M5.5 2.5h3l-1 9h-4l-1-9M6 6v4M8 6v4" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </button>
            <button type="button" class="ws-preview-modal__btn" :title="fullscreen ? '还原窗口' : '最大化'" @click="fullscreen = !fullscreen">
              <svg v-if="!fullscreen" width="13" height="13" viewBox="0 0 14 14" fill="none">
                <path d="M2 5V2.8a.8.8 0 0 1 .8-.8H5M9 2h2.2a.8.8 0 0 1 .8.8V5M12 9v2.2a.8.8 0 0 1-.8.8H9M5 12H2.8a.8.8 0 0 1-.8-.8V9" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
              </svg>
              <svg v-else width="13" height="13" viewBox="0 0 14 14" fill="none">
                <path d="M5 2v2.2a.8.8 0 0 1-.8.8H2M9 2v2.2a.8.8 0 0 0 .8.8H12M5 12V9.8a.8.8 0 0 0-.8-.8H2M9 12V9.8a.8.8 0 0 1 .8-.8H12" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
              </svg>
            </button>
            <button type="button" class="ws-preview-modal__btn" title="关闭" @click="onClose">
              <svg width="13" height="13" viewBox="0 0 14 14" fill="none">
                <path d="M3.5 3.5l7 7M10.5 3.5l-7 7" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/>
              </svg>
            </button>
          </div>
        </header>

        <!-- 弹窗内容主体 (1:1 对齐 PC) -->
        <main class="ws-preview-modal__body">
          <div v-if="loading" class="ws-preview-modal__hint">
            <span class="ws-preview-spinner"></span>
            <span>加载文件中…</span>
          </div>
          <img v-else-if="mediaUrl && isImageFile" :src="mediaUrl" class="ws-preview-modal__media ws-preview-modal__img" alt="预览图片" />
          <video v-else-if="mediaUrl && isVideoFile" :src="mediaUrl" class="ws-preview-modal__media ws-preview-modal__video" controls preload="metadata" />
          <audio v-else-if="mediaUrl && isAudioFile" :src="mediaUrl" class="ws-preview-modal__media ws-preview-modal__audio" controls preload="metadata" />
          <div v-else-if="errorText" class="ws-preview-modal__hint is-error">{{ errorText }}</div>
          <div v-else-if="binary" class="ws-preview-modal__hint">二进制文件，不支持预览 (可点击右上角下载)</div>
          <div v-else-if="tooLarge" class="ws-preview-modal__hint">文件超过 200KB，不支持预览 (可点击右上角下载)</div>
          <div v-else-if="isMarkdownFile" class="ws-preview-modal__md">
            <MarkdownContent :text="content" />
          </div>
          <pre v-else class="ws-preview-modal__code">{{ content }}</pre>
        </main>
      </div>
    </div>
  </transition>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import {
  deleteWorkspaceFile,
  downloadWorkspaceFileBlob,
  getWorkspaceFile,
  workspaceFileDownloadUrl
} from '../api/workspace'
import { getToken } from '../utils/auth'
import { confirmDanger, toast } from '../utils/confirm'
import MarkdownContent from '../chat-ui/components/MarkdownContent.vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  sessionId: { type: String, default: null },
  projectId: { type: Number, default: null },
  path: { type: String, default: '' }
})

const emit = defineEmits(['close', 'update:visible'])

const fullscreen = ref(false)
const loading = ref(false)
const content = ref('')
const errorText = ref('')
const binary = ref(false)
const tooLarge = ref(false)
const mediaUrl = ref('')
let mediaObjectUrl = null

const IMAGE_EXT = /\.(png|jpe?g|gif|webp|svg|bmp|ico)$/i
const VIDEO_EXT = /\.(mp4|webm|mov|m4v)$/i
const AUDIO_EXT = /\.(mp3|wav|ogg|m4a|aac)$/i

const isImageFile = computed(() => IMAGE_EXT.test(props.path || ''))
const isVideoFile = computed(() => VIDEO_EXT.test(props.path || ''))
const isAudioFile = computed(() => AUDIO_EXT.test(props.path || ''))
const isMarkdownFile = computed(() => /\.md$/i.test(props.path || ''))

function reset() {
  content.value = ''
  errorText.value = ''
  binary.value = false
  tooLarge.value = false
  revokeMedia()
}

function revokeMedia() {
  if (mediaObjectUrl) {
    URL.revokeObjectURL(mediaObjectUrl)
    mediaObjectUrl = null
  }
  mediaUrl.value = ''
}

async function loadMedia(path) {
  const base = import.meta.env.VITE_APP_BASE_API || '/dev-api'
  const resp = await fetch(base + workspaceFileDownloadUrl(props.sessionId, path, props.projectId), {
    headers: { Authorization: 'Bearer ' + getToken() }
  })
  if (!resp.ok) throw new Error('HTTP ' + resp.status)
  const blob = await resp.blob()
  mediaObjectUrl = URL.createObjectURL(blob)
  mediaUrl.value = mediaObjectUrl
}

async function load() {
  const { sessionId, path } = props
  if (!sessionId || !path) {
    reset()
    return
  }
  reset()
  loading.value = true
  try {
    if (IMAGE_EXT.test(path) || VIDEO_EXT.test(path) || AUDIO_EXT.test(path)) {
      await loadMedia(path)
      return
    }
    const res = await getWorkspaceFile(sessionId, path, props.projectId)
    const raw = res.data !== undefined ? (res.data || {}) : res
    if (res.tooLarge || raw.tooLarge) {
      tooLarge.value = true
    } else if (res.binary || raw.binary) {
      binary.value = true
    } else {
      const text = res.content !== undefined ? res.content : raw.content
      content.value = text != null ? String(text) : ''
    }
  } catch (e) {
    errorText.value = e?.message || '读取文件失败'
  } finally {
    loading.value = false
  }
}

function onClose() {
  revokeMedia()
  emit('close')
  emit('update:visible', false)
}

watch([() => props.visible, () => props.path, () => props.sessionId, () => props.projectId], ([vis]) => {
  if (vis) load()
}, { immediate: true })

async function onDelete() {
  if (!props.sessionId || !props.path) return
  const filename = props.path.split('/').pop() || props.path
  const ok = await confirmDanger('删除文件', `确定要删除「${filename}」吗？删除后不可恢复。`, { okLabel: '删除', cancelLabel: '取消' })
  if (!ok) return
  try {
    await deleteWorkspaceFile(props.sessionId, props.path, props.projectId)
    toast('删除成功')
    emit('deleted', props.path)
    onClose()
  } catch (e) {
    toast(e.message || '删除失败')
  }
}

async function onDownload() {
  if (!props.sessionId || !props.path) return
  const filename = String(props.path).split('/').pop() || 'file'
  await downloadWorkspaceFileBlob(props.sessionId, props.path, filename, props.projectId)
}
</script>

<style scoped lang="scss">
@use '../chat-ui/ai-tokens.scss' as *;

.ws-preview-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.65);
  backdrop-filter: blur(4px);
  z-index: 2000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}

.ws-preview-modal {
  width: min(920px, 94vw);
  height: min(680px, 86vh);
  background: var(--bg-elevated, #ffffff);
  border: 1px solid var(--border-strong, rgba(0, 0, 0, 0.12));
  border-radius: 12px;
  box-shadow: var(--shadow, 0 20px 60px rgba(0, 0, 0, 0.25));
  display: flex;
  flex-direction: column;
  overflow: hidden;
  transition: all 0.24s cubic-bezier(0.16, 1, 0.3, 1);

  &.is-fullscreen {
    width: 100vw;
    height: 100vh;
    border-radius: 0;
    border: none;
    max-width: 100vw;
    max-height: 100vh;
  }

  &__head {
    height: 48px;
    padding: 0 16px;
    border-bottom: 1px solid var(--border, rgba(0, 0, 0, 0.08));
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    background: var(--bg-elevated, #ffffff);
    flex-shrink: 0;
  }

  &__path {
    font-size: 13px;
    font-weight: 500;
    color: var(--text, #1c1c1e);
    font-family: $mono;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__actions {
    display: flex;
    align-items: center;
    gap: 4px;
    flex-shrink: 0;
  }

  &__btn {
    width: 28px;
    height: 28px;
    border: none;
    background: transparent;
    color: var(--text-tertiary, #8e8e93);
    border-radius: 6px;
    cursor: pointer;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    transition: all 0.16s ease;

    &:hover:not(:disabled) {
      background: var(--accent-weak, rgba(10, 132, 255, 0.08));
      color: var(--accent, #007aff);
    }

    &--danger:hover:not(:disabled) {
      background: var(--danger-weak, rgba(255, 59, 48, 0.12));
      color: var(--danger, #ff3b30);
    }

    &:disabled {
      opacity: 0.3;
      cursor: not-allowed;
    }
  }

  &__body {
    flex: 1 1 auto;
    min-height: 0;
    overflow: auto;
    display: flex;
    flex-direction: column;
    background: var(--bg, #f8f9fa);
  }

  &__md {
    padding: 20px 24px;
    background: var(--bg-raised, #ffffff);
    color: var(--text, #0f172a);
    min-height: 100%;
  }

  &__code {
    flex: 1 1 auto;
    min-height: 0;
    overflow: auto;
    margin: 0;
    padding: 16px 20px;
    background: var(--bg-code, #0b0d13);
    color: var(--text-code, #f1f5f9);
    font-family: $mono;
    font-size: 12.5px;
    line-height: 1.65;
    white-space: pre-wrap;
    word-break: break-word;
    tab-size: 4;

    &::-webkit-scrollbar {
      width: 6px;
      height: 6px;
    }
    &::-webkit-scrollbar-thumb {
      background: var(--border-strong, rgba(255, 255, 255, 0.2));
      border-radius: 3px;
    }
  }

  &__media {
    display: block;
    margin: auto;
    max-width: 100%;
    max-height: 100%;
    object-fit: contain;
  }

  &__img {
    background: repeating-conic-gradient(var(--bg-hover, #1b2132) 0% 25%, transparent 0% 50%) 50% / 16px 16px;
  }

  &__audio {
    width: min(100%, 480px);
    margin: auto;
  }

  &__hint {
    margin: auto;
    font-size: 13px;
    color: var(--text-tertiary, #8e8e93);
    display: flex;
    align-items: center;
    gap: 8px;

    &.is-error {
      color: var(--danger, #ff3b30);
    }
  }
}

.ws-preview-spinner {
  width: 16px;
  height: 16px;
  border: 2px solid var(--accent-weak, rgba(0, 122, 255, 0.2));
  border-top-color: var(--accent, #007aff);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

@keyframes modal-pop {
  from { opacity: 0; transform: scale(0.96); }
  to { opacity: 1; transform: scale(1); }
}

.preview-fade-enter-active,
.preview-fade-leave-active {
  transition: opacity 0.18s ease;
}
.preview-fade-enter-from,
.preview-fade-leave-to {
  opacity: 0;
}
</style>
