<template>
  <div
    class="chat-input"
    :class="{ 'is-focus': focused, 'is-drag': dragging }"
    @dragover.prevent="onDragOver"
    @dragleave="onDragLeave"
    @drop.prevent="onDrop"
  >
    <!-- 待发送附件:发送前可逐个移除。文件此刻已经在工作区了,
         移除只是不随这条消息告知模型,不删磁盘文件(工作区面板里仍可见)。 -->
    <div v-if="pending.length" class="chat-att">
      <div v-for="(a, i) in pending" :key="a.path || i" class="chat-att__item" :class="{ 'is-loading': a.uploading }">
        <span class="chat-att__icon">
          <svg v-if="a.uploading" class="chat-att__spin" width="13" height="13" viewBox="0 0 16 16" fill="none"><circle cx="8" cy="8" r="6" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-dasharray="24 12"/></svg>
          <svg v-else-if="isImg(a)" width="13" height="13" viewBox="0 0 16 16" fill="none"><rect x="1.8" y="2.8" width="12.4" height="10.4" rx="1.6" stroke="currentColor" stroke-width="1.25"/><circle cx="5.8" cy="6.4" r="1.1" stroke="currentColor" stroke-width="1.1"/><path d="M2.2 11.4l3.3-3 3 2.6 2-1.7 3.3 2.9" stroke="currentColor" stroke-width="1.25" stroke-linejoin="round"/></svg>
          <svg v-else width="13" height="13" viewBox="0 0 16 16" fill="none"><path d="M9 1.8H4.4a1.2 1.2 0 0 0-1.2 1.2v10a1.2 1.2 0 0 0 1.2 1.2h7.2a1.2 1.2 0 0 0 1.2-1.2V5.6z" stroke="currentColor" stroke-width="1.25" stroke-linejoin="round"/><path d="M9 1.8V5.6h3.8" stroke="currentColor" stroke-width="1.25" stroke-linejoin="round"/></svg>
        </span>
        <span class="chat-att__name" :title="a.name">{{ a.name }}</span>
        <span v-if="!a.uploading && a.size" class="chat-att__size">{{ fmtSize(a.size) }}</span>
        <button type="button" class="chat-att__del" title="移除" @click="removePending(i)">✕</button>
      </div>
    </div>

    <textarea
      ref="textareaRef"
      v-model="text"
      class="chat-input__textarea"
      rows="1"
      placeholder="输入消息，Enter 发送 / Shift+Enter 换行，可拖入文件"
      @keydown="onKeydown"
      @input="autoResize"
      @focus="focused = true"
      @blur="focused = false"
      @paste="onPaste"
    />
    <input ref="fileRef" type="file" multiple class="chat-input__file" @change="onPickFile" />
    <div class="chat-input__bar">
      <!-- 智能体选择:选智能体是「发送前的最后一个决定」，和输入是同一个动作序列，
           放在这里手不用离开输入区。原先在顶栏，视线要跑一趟来回。 -->
      <div class="agent-pick" :class="{ 'is-open': pickerOpen }" ref="pickerRef">
        <button
          type="button"
          class="agent-pick__btn"
          :disabled="streaming"
          @click="pickerOpen = !pickerOpen"
        >
          <span class="agent-pick__emoji">{{ currentAgent ? (currentAgent.icon || '🤖') : '🤖' }}</span>
          <span class="agent-pick__name">{{ currentAgent ? currentAgent.agentName : '选择智能体' }}</span>
          <span class="agent-pick__chev">
            <svg width="10" height="10" viewBox="0 0 12 12" fill="none"><path d="M2.5 4.5L6 8l3.5-3.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </span>
        </button>
        <div v-if="pickerOpen" class="agent-pick__menu">
          <div v-if="!agents.length" class="agent-pick__empty">暂无可用智能体</div>
          <button
            v-for="a in agents"
            :key="a.agentId"
            type="button"
            class="agent-pick__item"
            :class="{ 'is-on': a.agentId === agentId }"
            @click="choose(a)"
          >
            <span class="agent-pick__emoji">{{ a.icon || '🤖' }}</span>
            <span class="agent-pick__text">
              <span class="agent-pick__item-name">{{ a.agentName }}</span>
              <span class="agent-pick__item-desc">{{ describeAgent(a) }}</span>
            </span>
            <span v-if="a.agentId === agentId" class="agent-pick__check">
              <svg width="13" height="13" viewBox="0 0 14 14" fill="none"><path d="M2.5 7.5l3 3 6-6.5" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>
            </span>
          </button>
        </div>
      </div>

      <!-- 知识库选择：会话级多选，与智能体并排放在输入区 —— 发送前把检索范围定好。
           勾选即时生效：老会话立即落库，新会话随首条消息由后端建行。 -->
      <div class="kb-pick" :class="{ 'is-open': kbPickerOpen }" ref="kbPickerRef">
        <button
          type="button"
          class="kb-pick__btn"
          :disabled="streaming"
          title="选择知识库"
          @click="kbPickerOpen = !kbPickerOpen"
        >
          <svg class="kb-pick__icon" width="14" height="14" viewBox="0 0 16 16" fill="none"><path d="M1.8 3.4c0-.9.7-1.6 1.6-1.6h9.2c.9 0 1.6.7 1.6 1.6v9.2c0 .9-.7 1.6-1.6 1.6H3.4c-.9 0-1.6-.7-1.6-1.6z" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/><path d="M8 1.8v12.4M5 5.2h2.2M5 7.8h2.2" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/></svg>
          <span class="kb-pick__name">知识库</span>
          <span v-if="kbIds.length" class="kb-pick__count">{{ kbIds.length }}</span>
          <span class="kb-pick__chev">
            <svg width="10" height="10" viewBox="0 0 12 12" fill="none"><path d="M2.5 4.5L6 8l3.5-3.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </span>
        </button>
        <div v-if="kbPickerOpen" class="kb-pick__menu">
          <p class="kb-pick__tip">检索工具只在所选知识库中查询，选择归属当前会话，可随时修改（下一轮生效）。</p>
          <div v-if="kbLoading" class="kb-pick__loading">加载中…</div>
          <template v-else-if="kbs.length">
            <button
              v-for="k in kbs"
              :key="k.kbId"
              type="button"
              class="kb-pick__item"
              :class="{ 'is-on': kbIds.includes(k.kbId) }"
              :disabled="k.status === '1'"
              @click="toggleKb(k)"
            >
              <span class="kb-pick__check">
                <svg v-if="kbIds.includes(k.kbId)" width="12" height="12" viewBox="0 0 14 14" fill="none"><path d="M2.5 7.5l3 3 6-6.5" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>
              </span>
              <span class="kb-pick__text">
                <span class="kb-pick__item-name">{{ k.kbName }}</span>
                <span v-if="k.description" class="kb-pick__item-desc">{{ k.description }}</span>
              </span>
            </button>
          </template>
          <div v-else class="kb-pick__empty">没有可访问的知识库</div>
        </div>
      </div>

      <button
        type="button"
        class="chat-input__attach"
        :disabled="streaming || !sessionReady"
        title="上传文件"
        @click="fileRef?.click()"
      >
        <svg width="15" height="15" viewBox="0 0 16 16" fill="none"><path d="M13.4 7.3l-5.6 5.6a3.3 3.3 0 0 1-4.7-4.7l5.8-5.8a2.2 2.2 0 0 1 3.1 3.1l-5.8 5.8a1.1 1.1 0 0 1-1.6-1.6l5.3-5.3" stroke="currentColor" stroke-width="1.35" stroke-linecap="round" stroke-linejoin="round"/></svg>
      </button>

      <span v-if="streaming" class="chat-input__status">
        <i class="chat-input__dot chat-input__dot--run"></i>{{ connectionState === 'reconnecting' ? '连接恢复中…' : '正在生成…' }}
      </span>
      <span v-else-if="text.length" class="chat-input__status">{{ text.length }} 字</span>

      <ContextMeter class="chat-input__meter" :usage="contextUsage" />

      <button
        v-if="!streaming"
        type="button"
        class="chat-input__send"
        :disabled="!text.trim() || !canSend"
        title="发送"
        @click="emitSend"
      >
        <svg width="14" height="14" viewBox="0 0 14 14" fill="none"><path d="M1.5 7h10M7.5 3l4 4-4 4" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"/></svg>
      </button>
      <button v-else type="button" class="chat-input__send chat-input__send--stop" title="停止生成" @click="emitStop">
        <svg width="12" height="12" viewBox="0 0 14 14" fill="none"><rect x="2" y="2" width="10" height="10" rx="1.5" fill="currentColor"/></svg>
      </button>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import ContextMeter from './ContextMeter.vue'

const textareaRef = ref(null)

/** 单行起、随内容增高(主流输入框行为);发送后文本清空自动回到单行 */
function autoResize() {
  const el = textareaRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 160) + 'px'
}

const props = defineProps({
  streaming: Boolean,
  canSend: Boolean,
  /** 可选智能体列表 */
  agents: { type: Array, default: () => [] },
  /** 当前选中的智能体 ID */
  agentId: { type: [Number, String], default: null },
  /** 上下文用量快照，透传给 ContextMeter */
  contextUsage: { type: Object, default: null },
  /** 当前会话 ID —— 上传必须落到具体会话的工作区，没有就不能传 */
  sessionId: { type: String, default: null },
  /** JSON-RPC 连接状态；断线时 Agent 仍在后端运行 */
  connectionState: { type: String, default: 'closed' },
  /** 会话知识库列表(服务端已过滤当前用户可访问) */
  kbs: { type: Array, default: () => [] },
  /** 当前会话已选知识库 id */
  kbIds: { type: Array, default: () => [] },
  /** 知识库列表加载中 */
  kbLoading: { type: Boolean, default: false }
})
const emit = defineEmits(['send', 'stop', 'change-agent', 'change-kbs', 'upload'])

const text = ref('')
// 单行起、随内容增高；发送后文本清空自动回到单行
watch(text, () => autoResize())
const focused = ref(false)
const pickerOpen = ref(false)
const pickerRef = ref(null)
const kbPickerOpen = ref(false)
const kbPickerRef = ref(null)
const fileRef = ref(null)
const dragging = ref(false)
/** 已上传、待随下一条消息发出的附件 */
const pending = ref([])

// 会话行由上传接口在不存在时自动创建，所以这里只要有 sessionId 就能传，
// 不必等用户先发一条消息（「新开对话 -> 先传文件 -> 再提问」是常见用法）
const sessionReady = computed(() => !!props.sessionId)

const currentAgent = computed(() =>
  props.agents.find(a => a.agentId === props.agentId) || null
)

function describeAgent(a) {
  const parts = []
  const tools = (a.toolIds || []).length
  if (tools) parts.push(`${tools} 个工具`)
  if (a.agentRole) parts.push(String(a.agentRole).slice(0, 20))
  return parts.join(' · ') || '无工具'
}

function choose(a) {
  pickerOpen.value = false
  if (a.agentId === props.agentId) return
  emit('change-agent', a.agentId)
}

/** 勾选/取消一个知识库：立即把新数组抛给父级持久化(老会话 PUT、新会话本地待首轮落库) */
function toggleKb(k) {
  const id = k.kbId
  const next = props.kbIds.includes(id)
    ? props.kbIds.filter(x => x !== id)
    : [...props.kbIds, id]
  emit('change-kbs', next)
}

function emitSend() {
  const t = text.value.trim()
  if (!t || props.streaming) return
  // 还有文件在上传时不放行：附件清单必须完整地跟消息一起到达，
  // 否则模型只会看到一半文件，用户却以为都传了。
  if (pending.value.some(a => a.uploading)) return
  const attachments = pending.value
    .filter(a => a.path)
    .map(a => ({ name: a.name, path: a.path, mime: a.mime, size: a.size }))
  emit('send', t, attachments)
  text.value = ''
  pending.value = []
}

/* ---- 上传 ---- */
function isImg(a) {
  return a.mime && a.mime.startsWith('image/')
}

function fmtSize(bytes) {
  const v = Number(bytes) || 0
  if (v >= 1024 * 1024) return (v / 1024 / 1024).toFixed(1) + 'MB'
  if (v >= 1024) return (v / 1024).toFixed(0) + 'KB'
  return v + 'B'
}

function removePending(i) {
  pending.value.splice(i, 1)
}

/** 交给父组件真正调上传接口 —— 组件本身不碰 API，保持只做展示与交互 */
function pushFiles(files) {
  if (!files || !files.length || !props.sessionId) return
  for (const file of files) {
    const item = reactive({ name: file.name, size: file.size, mime: file.type, uploading: true, path: null })
    pending.value.push(item)
    emit('upload', file, item)
  }
}

function onPickFile(e) {
  pushFiles(Array.from(e.target.files || []))
  e.target.value = ''   // 允许连续选同一个文件
}

function onDragOver() {
  if (!props.streaming && props.sessionId) dragging.value = true
}
function onDragLeave() {
  dragging.value = false
}
function onDrop(e) {
  dragging.value = false
  if (props.streaming || !props.sessionId) return
  pushFiles(Array.from(e.dataTransfer?.files || []))
}

/** 粘贴板里的图片(截图后直接 Ctrl+V)也当附件处理 */
function onPaste(e) {
  const items = Array.from(e.clipboardData?.items || [])
  const files = items
    .filter(it => it.kind === 'file')
    .map(it => it.getAsFile())
    .filter(Boolean)
  if (files.length && props.sessionId) {
    e.preventDefault()
    pushFiles(files)
  }
}
function emitStop() { emit('stop') }
function onKeydown(e) {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    emitSend()
  }
}

// 点外部关闭下拉
function onDocClick(e) {
  if (pickerRef.value && !pickerRef.value.contains(e.target)) pickerOpen.value = false
  if (kbPickerRef.value && !kbPickerRef.value.contains(e.target)) kbPickerOpen.value = false
}
onMounted(() => document.addEventListener('click', onDocClick))
onBeforeUnmount(() => document.removeEventListener('click', onDocClick))

// 供父组件设置输入(快捷提示等)与在上传失败时摘掉对应条目
defineExpose({
  setText(t) { text.value = t },
  /** 上传失败时把该条从待发送列表移除 —— 留着会让用户以为已经传好了 */
  dropPending(item) {
    const i = pending.value.indexOf(item)
    if (i >= 0) pending.value.splice(i, 1)
  }
})
</script>

<style scoped lang="scss">
@use '../../../../assets/styles/ai-tokens.scss' as *;

.chat-input {
  margin: 10px 0 16px;
  padding: 9px 12px 7px;
  background: var(--ai-card-bg);
  border: 1px solid var(--ai-border-3);
  // 2026 主流：输入区大圆角、柔和阴影，像悬浮在页面上的一块输入板
  border-radius: 18px;
  box-shadow: 0 4px 24px rgba(0, 0, 0, 0.05);
  transition: border-color 0.18s $ease, box-shadow 0.18s $ease, transform 0.18s $ease;
  &.is-focus {
    border-color: rgba(10, 132, 255, 0.40);
    box-shadow: 0 0 0 4px rgba(10, 132, 255, 0.08), 0 10px 34px rgba(10, 132, 255, 0.08);
  }

  &__textarea {
    width: 100%; min-height: 38px; max-height: 160px;
    border: none; outline: none; resize: none; padding: 2px 3px;
    font-family: $font; font-size: $ai-fs-4; line-height: 1.5;
    color: $text; background: transparent;
    &::placeholder { color: $gray2; }
  }

  &.is-drag {
    border-color: rgba(10, 132, 255, 0.55);
    background: rgba(10, 132, 255, 0.03);
  }
  &__file { display: none; }
  // 底栏控件:智能体/附件在左,状态与刻度居中,发送按钮恒贴最右
  &__bar { display: flex; align-items: center; gap: 8px; padding-top: 5px; }
  &__attach {
    width: 28px; height: 28px; border-radius: 8px; border: none; flex-shrink: 0;
    background: transparent; color: $ai-text3; cursor: pointer;
    display: flex; align-items: center; justify-content: center;
    transition: background 0.16s $ease, color 0.16s $ease;
    &:hover:not(:disabled) { background: var(--ai-border); color: $text; }
    &:disabled { opacity: 0.35; cursor: not-allowed; }
  }
  &__status {
    font-size: $ai-fs-6; color: $ai-text3; font-variant-numeric: tabular-nums;
    display: inline-flex; align-items: center; gap: 5px; flex-shrink: 0;
  }
  &__dot {
    width: 6px; height: 6px; border-radius: 50%; background: $gray3; display: inline-block;
    &--run { background: $blue; animation: input-pulse 1.2s infinite ease-in-out; }
  }
  &__meter { margin-left: 4px; }

  &__send {
    width: 30px; height: 30px; border-radius: 50%; border: none; flex-shrink: 0;
    margin-left: auto;
    background: linear-gradient(135deg, $blue, #5E5CE6); color: #fff; cursor: pointer;
    display: flex; align-items: center; justify-content: center;
    box-shadow: 0 2px 8px rgba(10, 132, 255, 0.28);
    transition: filter 0.16s $ease, background 0.16s $ease, box-shadow 0.16s $ease, transform 0.16s $ease;
    &:hover:not(:disabled) { filter: brightness(1.06); transform: translateY(-1px); }
    &:active:not(:disabled) { transform: translateY(0); }
    // 未输入时中性灰占位，输入后变品牌蓝 —— 按钮位置固定在最右，状态一眼可辨
    &:disabled {
      background: var(--ai-fill-3); color: $ai-text2;
      cursor: not-allowed; box-shadow: none;
    }
    &--stop { background: $red; box-shadow: 0 2px 8px rgba(255, 59, 48, 0.25); &:hover { filter: brightness(1.05); } }
  }
}

@keyframes input-pulse { 0%, 100% { opacity: 0.35; } 50% { opacity: 1; } }

/* ---- 待发送附件 ---- */
.chat-att {
  display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 8px;
  &__item {
    display: inline-flex; align-items: center; gap: 6px;
    max-width: 240px; padding: 4px 6px 4px 8px;
    background: var(--ai-fill-2); border-radius: 8px;
    font-size: $ai-fs-6; color: $text;
    &.is-loading { opacity: 0.65; }
  }
  &__icon { display: flex; color: $ai-text3; flex-shrink: 0; }
  &__spin { animation: att-spin 0.9s linear infinite; }
  &__name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  &__size { color: $ai-text3; flex-shrink: 0; font-variant-numeric: tabular-nums; }
  &__del {
    border: none; background: transparent; color: $gray; cursor: pointer;
    padding: 0 1px; font-size: 11px; line-height: 1; flex-shrink: 0;
    &:hover { color: $red; }
  }
}
@keyframes att-spin { to { transform: rotate(360deg); } }

/* ---- 智能体选择器 ---- */
.agent-pick {
  position: relative; flex-shrink: 0;

  &__btn {
    display: flex; align-items: center; gap: 6px;
    padding: 4px 8px 4px 6px;
    border: 1px solid var(--ai-hover-strong); border-radius: 980px;
    background: var(--ai-card-bg); cursor: pointer; font-family: $font;
    font-size: $ai-fs-5; color: $text; max-width: 210px;
    transition: background 0.16s $ease, border-color 0.16s $ease;
    &:hover:not(:disabled) { background: var(--ai-fill-1); border-color: var(--ai-border-4); }
    &:disabled { opacity: 0.5; cursor: not-allowed; }
  }
  &__emoji { font-size: 14px; line-height: 1; flex-shrink: 0; }
  &__name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  &__chev { color: $gray; display: flex; flex-shrink: 0; transition: transform 0.2s $ease; }
  &.is-open &__chev { transform: rotate(180deg); }

  &__menu {
    position: absolute; bottom: calc(100% + 7px); left: 0;
    width: 276px; max-height: 320px; overflow-y: auto;
    background: var(--ai-card-bg); border: 1px solid var(--ai-border-3);
    border-radius: 12px; box-shadow: var(--ai-shadow-card);
    padding: 5px; z-index: 30;
  }
  &__empty { padding: 14px 10px; text-align: center; font-size: $ai-fs-5; color: $gray; }

  &__item {
    display: flex; align-items: center; gap: 9px; width: 100%;
    padding: 7px 10px; border: none; border-radius: 8px; background: transparent;
    cursor: pointer; text-align: left; font-family: $font;
    transition: background 0.14s $ease;
    &:hover { background: rgba(10, 132, 255, 0.07); }
    &.is-on { background: rgba(10, 132, 255, 0.11); }
  }
  &__text { min-width: 0; flex: 1; display: flex; flex-direction: column; }
  &__item-name {
    font-size: $ai-fs-5; font-weight: 500; color: $text;
    overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  }
  &__item-desc {
    font-size: $ai-fs-6; color: $ai-text3;
    overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  }
  &__check { color: $blue; display: flex; flex-shrink: 0; }
}

/* ---- 知识库选择器(会话级多选,与智能体并排) ---- */
.kb-pick {
  position: relative; flex-shrink: 0;

  &__btn {
    display: flex; align-items: center; gap: 6px;
    padding: 4px 8px 4px 6px;
    border: 1px solid var(--ai-hover-strong); border-radius: 980px;
    background: var(--ai-card-bg); cursor: pointer; font-family: $font;
    font-size: $ai-fs-5; color: $text;
    transition: background 0.16s $ease, border-color 0.16s $ease;
    &:hover:not(:disabled) { background: var(--ai-fill-1); border-color: var(--ai-border-4); }
    &:disabled { opacity: 0.5; cursor: not-allowed; }
  }
  &__icon { color: $ai-text3; flex-shrink: 0; display: flex; }
  &__name { white-space: nowrap; }
  &__count {
    min-width: 17px; height: 17px; padding: 0 5px; border-radius: 9px;
    background: rgba(10, 132, 255, 0.14); color: $blue;
    font-size: 11px; line-height: 17px; text-align: center;
    font-variant-numeric: tabular-nums; flex-shrink: 0;
  }
  &__chev { color: $gray; display: flex; flex-shrink: 0; transition: transform 0.2s $ease; }
  &.is-open &__chev { transform: rotate(180deg); }

  &__menu {
    position: absolute; bottom: calc(100% + 7px); left: 0;
    width: 300px; max-height: 340px; overflow-y: auto;
    background: var(--ai-card-bg); border: 1px solid var(--ai-border-3);
    border-radius: 12px; box-shadow: var(--ai-shadow-card);
    padding: 6px; z-index: 30;
  }
  &__tip {
    margin: 2px 6px 8px; font-size: $ai-fs-6; color: $ai-text3; line-height: 1.6;
  }
  &__loading, &__empty {
    padding: 16px 10px; text-align: center; font-size: $ai-fs-5; color: $gray;
  }
  &__item {
    display: flex; align-items: flex-start; gap: 9px; width: 100%;
    padding: 7px 8px; border: none; border-radius: 8px; background: transparent;
    cursor: pointer; text-align: left; font-family: $font;
    transition: background 0.14s $ease;
    &:hover:not(:disabled) { background: rgba(10, 132, 255, 0.07); }
    &.is-on { background: rgba(10, 132, 255, 0.11); }
    &:disabled { opacity: 0.5; cursor: not-allowed; }
  }
  &__check {
    width: 14px; height: 14px; margin-top: 1px; flex-shrink: 0;
    border: 1px solid var(--ai-border-4); border-radius: 4px;
    display: flex; align-items: center; justify-content: center;
    color: $blue; background: var(--ai-card-bg);
    .is-on & { border-color: $blue; background: $blue; color: #fff; }
  }
  &__text { min-width: 0; flex: 1; display: flex; flex-direction: column; }
  &__item-name {
    font-size: $ai-fs-5; font-weight: 500; color: $text;
    overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  }
  &__item-desc {
    font-size: $ai-fs-6; color: $ai-text3; line-height: 1.45;
    overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  }
}
</style>
