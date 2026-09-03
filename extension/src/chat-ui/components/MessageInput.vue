<template>
  <div class="chat-input-wrapper" :class="{ 'chat-input-wrapper--compact': compact }">
    <!-- Slash 指令快捷弹出菜单 (支持键盘上下选择与回车选用) -->
    <div v-if="showSlashMenu" class="slash-menu" ref="slashMenuRef">
      <div class="slash-menu__head">
        <span>快捷技能与指令</span>
        <span v-if="!compact" class="slash-menu__tip">按 ↑↓ 切换，Enter 选用，Esc 关闭</span>
      </div>
      <div class="slash-menu__list">
        <button
          v-for="(cmd, idx) in filteredSlashCommands"
          :key="cmd.key"
          type="button"
          class="slash-menu__item"
          :class="{ 'is-selected': idx === slashIndex }"
          @click="applySlash(cmd)"
          @mouseenter="slashIndex = idx"
        >
          <div class="slash-menu__icon">{{ cmd.icon }}</div>
          <div class="slash-menu__content">
            <div class="slash-menu__title">
              <span class="slash-menu__key">/{{ cmd.key }}</span>
              <span class="slash-menu__label">{{ cmd.label }}</span>
            </div>
            <div class="slash-menu__desc">{{ cmd.desc }}</div>
          </div>
        </button>
      </div>
    </div>

    <div
      class="chat-input"
      :class="{ 'is-focus': focused, 'is-drag': dragging }"
      @dragover.prevent="onDragOver"
      @dragleave="onDragLeave"
      @drop.prevent="onDrop"
    >
      <!-- 待发送附件 -->
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

      <div v-if="!compact && selectedSkills.length" class="chat-skill-chips">
        <span v-for="skill in selectedSkills" :key="skill.skillId" class="chat-skill-chip">
          <span>@{{ skill.skillName }}</span>
          <button type="button" :disabled="streaming" :title="`移除 ${skill.skillName}`" @click="toggleSkill(skill)">×</button>
        </span>
      </div>

      <textarea
        ref="textareaRef"
        v-model="text"
        class="chat-input__textarea"
        rows="1"
        :placeholder="compact ? '问点什么…' : '随便问点什么——输入 @ 唤出技能，输入 / 使用快捷指令'"
        @keydown="onKeydown"
        @input="onInput"
        @focus="focused = true"
        @blur="focused = false"
        @paste="onPaste"
      />
      <input ref="fileRef" type="file" multiple class="chat-input__file" @change="onPickFile" />

      <!-- 现代精炼底栏 (对齐参考图 Aivory 视觉) -->
      <div class="chat-input__bar">
        <!-- 左侧工具组 (+ / @技能 / 知识库 / 容量指示) -->
        <div class="chat-input__tools">
          <!-- 1. 附件上传 (+) -->
          <button
            type="button"
            class="tool-btn__icon-only tool-btn--plus"
            :disabled="streaming || !sessionReady"
            title="添加附件 (支持拖拽文件与图片)"
            @click="fileRef?.click()"
          >
            <svg width="15" height="15" viewBox="0 0 16 16" fill="none"><path d="M8 3.5v9M3.5 8h9" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>
          </button>

          <!-- 2. @ 技能：只展示当前用户自己的技能 -->
          <div v-if="!compact" class="tool-btn skill-pick" :class="{ 'is-open': skillPickerOpen, 'is-active': selectedSkills.length }" ref="skillPickerRef">
            <button
              type="button"
              class="tool-btn__trigger"
              :class="{ 'is-active': selectedSkills.length }"
              :disabled="streaming"
              :title="selectedSkills.length ? `已选择 ${selectedSkills.length} 个技能` : '添加本轮技能'"
              @click="skillPickerOpen = !skillPickerOpen"
            >
              <span class="tool-btn__at">@</span>
              <span class="tool-btn__text">技能</span>
              <span v-if="selectedSkills.length" class="tool-btn__badge">{{ selectedSkills.length }}</span>
              <svg class="tool-btn__chev" width="9" height="9" viewBox="0 0 12 12" fill="none"><path d="M2.5 4.5L6 8l3.5-3.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
            </button>
            <div v-if="showSkillMenu" class="skill-pick__menu">
              <div class="skill-pick__head">
                <span>{{ skillQuery !== null ? `@${skillQuery || '选择技能'}` : '本轮技能' }}</span>
                <span class="skill-pick__tip">仅显示我的技能</span>
              </div>
              <div v-if="!filteredSkills.length" class="skill-pick__empty">没有匹配的可用技能</div>
              <div v-else class="skill-pick__list">
                <button
                  v-for="skill in filteredSkills"
                  :key="skill.skillId"
                  type="button"
                  class="skill-pick__item"
                  :class="{ 'is-on': selectedSkillIds.includes(skill.skillId) }"
                  @click="toggleSkill(skill)"
                >
                  <span class="skill-pick__item-at">@</span>
                  <span class="skill-pick__item-copy">
                    <strong>{{ skill.skillName }}</strong>
                    <small>{{ skill.description || skill.category || '技能' }}</small>
                  </span>
                  <em>我的</em>
                  <span v-if="selectedSkillIds.includes(skill.skillId)" class="skill-pick__check">✓</span>
                </button>
              </div>
            </div>
          </div>

          <!-- 5. 知识库挂载入口 (带数量胶囊) -->
          <div v-if="!compact" class="tool-btn kb-pick" :class="{ 'is-open': kbPickerOpen, 'is-active': kbIds.length > 0 }" ref="kbPickerRef">
            <button
              type="button"
              class="tool-btn__trigger"
              :class="{ 'is-active': kbIds.length > 0 }"
              :disabled="streaming"
              :title="kbIds.length ? `已挂载 ${kbIds.length} 个知识库` : '关联私有知识库'"
              @click="kbPickerOpen = !kbPickerOpen"
            >
              <svg class="tool-btn__icon" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H20v15.5H6.5A2.5 2.5 0 0 0 4 21V5.5z"/><path d="M4 18.5A2.5 2.5 0 0 1 6.5 16H20"/><path d="M9 7.5h7"/></svg>
              <span class="tool-btn__text">知识库</span>
              <span v-if="kbIds.length" class="tool-btn__badge">{{ kbIds.length }}</span>
              <svg class="tool-btn__chev" width="9" height="9" viewBox="0 0 12 12" fill="none"><path d="M2.5 4.5L6 8l3.5-3.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
            </button>
            <div v-if="kbPickerOpen" class="kb-pick__menu">
              <div class="kb-pick__head">知识库</div>
              <div v-if="kbLoading" class="kb-pick__loading">加载中…</div>
              <template v-else-if="kbs.length">
                <p v-if="!compact" class="kb-pick__tip">勾选后智能体将优先检索指定知识库中的文档资产</p>
                <div class="kb-pick__list">
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
                </div>
                <div class="kb-pick__foot">
                  <button type="button" class="kb-pick__manage-btn" @click="openManageKb">
                    <svg width="13" height="13" viewBox="0 0 16 16" fill="none"><path d="M8 3.5v9M3.5 8h9" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>
                    <span>管理知识库</span>
                  </button>
                </div>
              </template>
              <!-- 暂无知识库空状态 (1:1 像素级对齐参考图) -->
              <div v-else class="kb-pick__empty-box">
                <div class="kb-pick__empty-msg">还没有知识库。</div>
                <button type="button" class="kb-pick__manage-btn" @click="openManageKb">
                  <svg width="13" height="13" viewBox="0 0 16 16" fill="none"><path d="M8 3.5v9M3.5 8h9" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>
                  <span>管理知识库</span>
                </button>
              </div>
            </div>
          </div>

          <!-- 6. 上下文容量与压缩指示环 (仅在有会话聊天时显示) -->
          <ContextMeter v-if="showMeter && contextUsage" class="chat-input__meter" :usage="contextUsage" />
        </div>

        <!-- 右侧状态与操作组 (胶囊模型选择 + 语音波纹/发送按钮) -->
        <div class="chat-input__actions">
          <!-- 模型胶囊选择入口；默认智能体仍在后端作为工具与策略基座 -->
          <div class="tool-btn agent-pick" :class="{ 'is-open': pickerOpen }" ref="pickerRef">
            <button
              type="button"
              class="tool-btn__trigger tool-btn__trigger--pill"
              :disabled="streaming"
              title="选择本轮对话模型"
              @click="pickerOpen = !pickerOpen"
            >
              <span class="tool-btn__sparkle">✦</span>
              <span class="tool-btn__text tool-btn__text--model">{{ currentModel?.displayName || '默认模型' }}</span>
              <svg class="tool-btn__chev" width="9" height="9" viewBox="0 0 12 12" fill="none"><path d="M2.5 4.5L6 8l3.5-3.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
            </button>
            <div v-if="pickerOpen" class="agent-pick__menu">
              <div class="agent-pick__head">
                <span>本轮模型</span>
                <span v-if="models.length" class="agent-pick__count">{{ models.length }} 个</span>
              </div>
              <button type="button" class="agent-pick__item" :class="{ 'is-on': !modelId }" @click="chooseModel(null)">
                <div class="agent-pick__avatar"><span>✦</span></div>
                <span class="agent-pick__item-name">默认模型</span>
                <span v-if="!modelId" class="agent-pick__check">✓</span>
              </button>
              <div v-if="!models.length" class="agent-pick__empty">暂无可用模型</div>
              <div v-else class="agent-pick__list">
                <button
                  v-for="model in models"
                  :key="model.modelId"
                  type="button"
                  class="agent-pick__item"
                  :class="{ 'is-on': model.modelId === modelId }"
                  @click="chooseModel(model.modelId)"
                >
                  <div class="agent-pick__avatar"><span>✦</span></div>
                  <span class="agent-pick__item-name">{{ model.displayName }}</span>
                  <span v-if="model.modelId === modelId" class="agent-pick__check">✓</span>
                </button>
              </div>
            </div>
          </div>

          <span v-if="streaming" class="chat-input__status">
            <i class="chat-input__dot chat-input__dot--run"></i>{{ streamingLabel }}
          </span>
          <span v-else-if="text.length" class="chat-input__status">{{ text.length }} 字</span>

          <!-- 发送 / 停止生成按钮 (语音波形模式已暂时注释) -->
          <!--
          <button
            v-if="!streaming"
            type="button"
            class="chat-input__send"
            :class="{ 'chat-input__send--voice': !text.trim() }"
            :disabled="!canSend"
            :title="text.trim() ? '发送消息 (Enter)' : '按住说话或直接输入 (Enter 发送)'"
            @click="text.trim() ? emitSend() : focusInput()"
          >
            <svg v-if="text.trim()" width="13" height="13" viewBox="0 0 14 14" fill="none"><path d="M1.5 7h10M7.5 3l4 4-4 4" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
            <div v-else class="chat-input__voice-bars" aria-hidden="true">
              <span class="vbar vbar--1"></span>
              <span class="vbar vbar--2"></span>
              <span class="vbar vbar--3"></span>
              <span class="vbar vbar--4"></span>
            </div>
          </button>
          -->
          <button
            v-if="!streaming"
            type="button"
            class="chat-input__send"
            :disabled="!text.trim() || !canSend"
            title="发送消息 (Enter)"
            @click="emitSend"
          >
            <svg width="13" height="13" viewBox="0 0 14 14" fill="none"><path d="M1.5 7h10M7.5 3l4 4-4 4" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </button>
          <button v-else type="button" class="chat-input__send chat-input__send--stop" title="停止生成" @click="emitStop">
            <svg width="10" height="10" viewBox="0 0 14 14" fill="none"><rect x="2" y="2" width="10" height="10" rx="1.5" fill="currentColor"/></svg>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import ContextMeter from './ContextMeter.vue'
import { listKbOptions } from '../../api/kb'

const props = defineProps({
  streaming: { type: Boolean, default: false },
  models: { type: Array, default: () => [] },
  modelId: { type: [Number, String], default: null },
  skills: { type: Array, default: () => [] },
  skillIds: { type: Array, default: () => [] },
  sessionReady: { type: Boolean, default: true },
  canSend: { type: Boolean, default: true },
  sessionId: { type: [String, Number], default: null },
  // 默认按在线算:漏传时宁可少报一次断线,也不要凭空吓人。取值见 api/chatRpc.js 的 emitState。
  connectionState: { type: String, default: 'open' },
  contextUsage: { type: Object, default: null },
  initialKbIds: { type: Array, default: () => [] },
  showMeter: { type: Boolean, default: false },
  compact: { type: Boolean, default: false }
})

const emit = defineEmits([
  'send',
  'stop',
  'change-model',
  'change-skills',
  'change-kb',
  'upload',
  'manage-kb'
])

// 断线时这一轮仍在后端跑,但只写「生成中…」会让用户以为是模型卡住了。
// 管理端 MessageInput 早就这么区分,插件这份副本漏掉了。
const streamingLabel = computed(() => {
  if (props.connectionState === 'connecting' || props.connectionState === 'reconnecting') {
    return '连接恢复中…'
  }
  return props.connectionState === 'closed' ? '连接已断开…' : '生成中…'
})

const text = ref('')
const focused = ref(false)
const dragging = ref(false)
const textareaRef = ref(null)
const fileRef = ref(null)

/* ---- 模型选择（智能体仅作为服务端默认能力基座，不在客户端展示） ---- */
const pickerRef = ref(null)
const pickerOpen = ref(false)
const currentModel = computed(() => {
  return (props.models || []).find(m => String(m.modelId) === String(props.modelId)) || null
})

function chooseModel(modelId) {
  emit('change-model', modelId)
  pickerOpen.value = false
  focusInput()
}

/* ---- @ 技能选择；公共目录技能添加到“我的”后才能出现在这里 ---- */
const skillPickerRef = ref(null)
const skillPickerOpen = ref(false)
const selectedSkillIds = computed(() => (props.skillIds || []).map(Number))
const ownedSkills = computed(() => (props.skills || []).filter(skill => {
  return String(skill?.visibility || '').toUpperCase() === 'PRIVATE'
}))
const selectedSkills = computed(() => {
  const selected = new Set(selectedSkillIds.value)
  return ownedSkills.value.filter(skill => selected.has(Number(skill.skillId)))
})
const skillQuery = computed(() => {
  if (props.compact) return null
  const match = text.value.match(/(?:^|\s)@([^\s@]*)$/)
  return match ? match[1] : null
})
const showSkillMenu = computed(() => {
  if (props.compact) return false
  return !props.streaming && (skillPickerOpen.value || skillQuery.value !== null)
})
const filteredSkills = computed(() => {
  const query = String(skillQuery.value ?? '').trim().toLowerCase()
  if (!query) return ownedSkills.value
  return ownedSkills.value.filter(skill => {
    return [skill.skillName, skill.category, skill.description]
      .filter(Boolean).some(value => String(value).toLowerCase().includes(query))
  })
})

function toggleSkill(skill) {
  if (!skill?.skillId || props.streaming) return
  const id = Number(skill.skillId)
  const next = new Set(selectedSkillIds.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  emit('change-skills', [...next])
  // @ 只负责选择，不污染发送给模型的用户原文。
  if (skillQuery.value !== null) {
    text.value = text.value.replace(/(?:^|\s)@([^\s@]*)$/, (part) => part.startsWith(' ') ? ' ' : '')
    nextTick(() => {
      autoResize()
      textareaRef.value?.focus()
    })
  }
  skillPickerOpen.value = false
  focusInput()
}

/* ---- 知识库选择 ---- */
const kbPickerRef = ref(null)
const kbPickerOpen = ref(false)
const kbs = ref([])
const kbLoading = ref(false)
const kbIds = ref([])

watch(() => props.initialKbIds, (arr) => {
  if (Array.isArray(arr)) kbIds.value = [...arr]
}, { immediate: true })

async function loadKbs() {
  if (kbs.value.length || kbLoading.value) return
  kbLoading.value = true
  try {
    const res = await listKbOptions({ status: '0' })
    kbs.value = (res.data || []).filter(k => k.status !== '1')
  } catch (_) {
    kbs.value = []
  } finally {
    kbLoading.value = false
  }
}

watch(kbPickerOpen, (open) => {
  if (open) loadKbs()
})

function toggleKb(k) {
  const id = k.kbId
  const idx = kbIds.value.indexOf(id)
  if (idx >= 0) {
    kbIds.value.splice(idx, 1)
  } else {
    kbIds.value.push(id)
  }
  emit('change-kb', [...kbIds.value])
}

function openManageKb() {
  kbPickerOpen.value = false
  emit('manage-kb')
}

/* ---- 附件待发送管理 ---- */
const pending = ref([])

function isImg(a) {
  return a.mime && a.mime.startsWith('image/')
}

function fmtSize(bytes) {
  if (!bytes) return ''
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(1) + ' MB'
}

function removePending(idx) {
  pending.value.splice(idx, 1)
}

function autoResize() {
  const el = textareaRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, 180)}px`
}

/* ---- Slash 指令面板 ---- */
const slashMenuRef = ref(null)
const slashIndex = ref(0)
const slashDismissed = ref(false)

const slashCommands = [
  {
    key: 'name',
    icon: '🪄',
    label: '为一件事命名',
    desc: '根据背景构思独特、专业且有辨识度的名称与寓意',
    prompt: '请帮我为以下项目/产品/专栏构思5个有创意的名字，并给出命名寓意与定位分析：\n'
  },
  {
    key: 'outline',
    icon: '📝',
    label: '为长文拟提纲',
    desc: '快速生成层次清晰的深度文章或分析报告提纲',
    prompt: '请帮我拟定一份详细的大纲，包含引言、核心论点、分论点与结语：\n'
  },
  {
    key: 'code',
    icon: '💻',
    label: '陪我读一段代码',
    desc: '分析代码逻辑、排查报错或提出性能重构方案',
    prompt: '请帮我分析以下代码，指出潜在问题并给出重构优化建议：\n```\n\n```'
  },
  {
    key: 'kb',
    icon: '📚',
    label: '检索私有知识库',
    desc: '结合已挂载的企业知识库进行深度问答与要点萃取',
    prompt: '请结合已关联的知识库文档，详细解答以下问题：\n'
  },
  {
    key: 'summary',
    icon: '⚡',
    label: '核心要点提炼',
    desc: '提炼长文本、会议纪要或文档的核心精华与行动项',
    prompt: '请提炼以下内容的核心观点与关键行动清单：\n'
  },
  {
    key: 'translate',
    icon: '🌐',
    label: '专业润色与翻译',
    desc: '地道流畅的多语言翻译与学术/商务表达润色',
    prompt: '请将以下内容进行地道的中英双语润色，保持专业优雅的语气：\n'
  }
]

const showSlashMenu = computed(() => {
  if (slashDismissed.value || props.streaming) return false
  const t = text.value
  return t.startsWith('/') && !t.includes('\n')
})

const filteredSlashCommands = computed(() => {
  const query = text.value.slice(1).trim().toLowerCase()
  if (!query) return slashCommands
  return slashCommands.filter(c =>
    c.key.toLowerCase().includes(query) ||
    c.label.toLowerCase().includes(query) ||
    c.desc.toLowerCase().includes(query)
  )
})

function applySlash(cmd) {
  if (!cmd) return
  text.value = cmd.prompt
  slashDismissed.value = true
  nextTick(() => {
    autoResize()
    textareaRef.value?.focus()
  })
}

let focusFrame = 0

async function focusInput() {
  await nextTick()
  if (focusFrame) cancelAnimationFrame(focusFrame)
  focusFrame = requestAnimationFrame(() => {
    focusFrame = 0
    textareaRef.value?.focus({ preventScroll: true })
  })
}

function onInput() {
  slashDismissed.value = false
  autoResize()
}

function emitSend() {
  const content = text.value.trim()
  if (!content || props.streaming || !props.canSend) return
  const atts = pending.value.filter(a => !a.uploading)
  emit('send', { content, attachments: atts, kbIds: [...kbIds.value] })
  text.value = ''
  pending.value = []
  slashDismissed.value = false
  nextTick(autoResize)
}

function pushFiles(files) {
  if (!files || !files.length) return
  for (const file of files) {
    const item = {
      name: file.name,
      size: file.size,
      mime: file.type,
      uploading: true,
      path: null
    }
    pending.value.push(item)
    emit('upload', file, item)
  }
}

function onPickFile(e) {
  pushFiles(Array.from(e.target.files || []))
  e.target.value = ''
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
  if (showSlashMenu.value && filteredSlashCommands.value.length) {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      slashIndex.value = (slashIndex.value + 1) % filteredSlashCommands.value.length
      return
    }
    if (e.key === 'ArrowUp') {
      e.preventDefault()
      slashIndex.value = (slashIndex.value - 1 + filteredSlashCommands.value.length) % filteredSlashCommands.value.length
      return
    }
    if ((e.key === 'Enter' || e.key === 'Tab') && !e.shiftKey) {
      e.preventDefault()
      applySlash(filteredSlashCommands.value[slashIndex.value] || filteredSlashCommands.value[0])
      return
    }
    if (e.key === 'Escape') {
      e.preventDefault()
      slashDismissed.value = true
      return
    }
  }

  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    emitSend()
  }
}

function onDocClick(e) {
  if (pickerRef.value && !pickerRef.value.contains(e.target)) pickerOpen.value = false
  if (skillPickerRef.value && !skillPickerRef.value.contains(e.target) && textareaRef.value !== e.target) {
    skillPickerOpen.value = false
  }
  if (kbPickerRef.value && !kbPickerRef.value.contains(e.target)) kbPickerOpen.value = false
  if (slashMenuRef.value && !slashMenuRef.value.contains(e.target) && textareaRef.value !== e.target) {
    slashDismissed.value = true
  }
}
watch(() => props.sessionId, () => focusInput(), { flush: 'post' })

onMounted(() => {
  document.addEventListener('click', onDocClick)
  focusInput()
})
onBeforeUnmount(() => {
  document.removeEventListener('click', onDocClick)
  if (focusFrame) cancelAnimationFrame(focusFrame)
})

defineExpose({
  focus() {
    focusInput()
  },
  setText(t) {
    text.value = t
    nextTick(() => {
      autoResize()
      focusInput()
      const end = text.value.length
      textareaRef.value?.setSelectionRange(end, end)
    })
  },
  dropPending(item) {
    const i = pending.value.indexOf(item)
    if (i >= 0) pending.value.splice(i, 1)
  }
})
</script>

<style scoped lang="scss">
@use '../ai-tokens.scss' as *;

.chat-input-wrapper {
  max-width: $ai-content-max;
  width: 100%;
  margin: 0 auto 18px;
  padding: 0 16px;
}

.chat-input {
  padding: 12px 14px 10px 16px;
  background: var(--bg-elevated);
  border: 1px solid var(--border-strong);
  border-radius: 20px;
  box-shadow: 0 4px 20px -2px rgba(15, 23, 42, 0.05), 0 2px 6px -1px rgba(15, 23, 42, 0.02);
  backdrop-filter: blur(16px);
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);

  &.is-focus {
    border-color: var(--accent);
    box-shadow: 0 0 0 3px var(--accent-weak), 0 10px 32px -4px rgba(37, 99, 235, 0.14);
  }

  &.is-drag {
    border-color: var(--accent);
    background: var(--accent-weak);
  }

  &__textarea {
    width: 100%;
    min-height: 44px;
    max-height: 180px;
    border: none;
    outline: none;
    resize: none;
    padding: 2px 2px 8px;
    font-family: inherit;
    font-size: 14.5px;
    line-height: 1.55;
    color: var(--text);
    background: transparent;
    &::placeholder {
      color: var(--text-tertiary);
      font-size: 14px;
    }
  }

  &__file { display: none; }

  /* 现代灵动底栏 (去生硬横线，弹性分布) */
  &__bar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    padding-top: 4px;
  }

  &__tools {
    display: flex;
    align-items: center;
    gap: 6px;
  }

  &__actions {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-left: auto;
  }

  &__status {
    font-size: 11.5px;
    color: var(--text-tertiary);
    font-variant-numeric: tabular-nums;
    display: inline-flex;
    align-items: center;
    gap: 5px;
    flex-shrink: 0;
  }

  &__dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: var(--text-tertiary);
    display: inline-block;
    &--run { background: var(--accent); animation: input-pulse 1.2s infinite ease-in-out; }
  }

  &__meter { margin-left: 2px; }

  &__send {
    width: 32px;
    height: 32px;
    border-radius: 9px;
    border: none;
    flex-shrink: 0;
    background: var(--accent-gradient);
    color: #fff;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    box-shadow: 0 2px 8px rgba(37, 99, 235, 0.26);
    transition: all 0.16s $ease;

    &:hover:not(:disabled) {
      filter: brightness(1.08);
      transform: translateY(-1px);
      box-shadow: 0 4px 14px rgba(37, 99, 235, 0.34);
    }
    &:active:not(:disabled) { transform: translateY(0); }
    &:disabled {
      background: var(--ai-fill-3);
      color: var(--text-tertiary);
      cursor: not-allowed;
      box-shadow: none;
      opacity: 0.6;
    }
    &--stop {
      background: var(--danger);
      box-shadow: 0 2px 8px rgba(239, 68, 68, 0.25);
      &:hover { filter: brightness(1.08); }
    }
  }
}

@keyframes input-pulse { 0%, 100% { opacity: 0.35; } 50% { opacity: 1; } }

/* ============================================================
   统一轻量 Ghost 控件语法 (.tool-btn)
   ============================================================ */
.tool-btn {
  position: relative;
  flex-shrink: 0;

  &--plus {
    width: 28px;
    height: 28px;
    border-radius: 8px;
    background: var(--ai-fill-1);
    border: 1px solid var(--border);
    color: var(--text-secondary);

    &:hover:not(:disabled) {
      background: var(--bg-hover);
      color: var(--text);
      border-color: var(--border-strong);
    }
  }

  &__trigger {
    height: 28px;
    padding: 0 8px 0 6px;
    border-radius: 8px;
    border: 1px solid transparent;
    background: transparent;
    color: var(--text-secondary);
    font-size: 12px;
    font-weight: 500;
    display: inline-flex;
    align-items: center;
    gap: 5px;
    cursor: pointer;
    transition: all 0.14s $ease;

    &:hover:not(:disabled) {
      background: var(--bg-hover);
      color: var(--text);
      border-color: var(--border);
    }

    &:disabled {
      opacity: 0.45;
      cursor: not-allowed;
    }

    &.is-active {
      background: var(--accent-weak);
      color: var(--accent);
      border-color: var(--accent-border);
      font-weight: 600;
    }

    /* 胶囊 Pill 样式 (模型/智能体选择器) */
    &--pill {
      height: 28px;
      padding: 0 10px 0 8px;
      border-radius: 990px;
      background: var(--ai-fill-1);
      border: 1px solid var(--border);
      font-weight: 550;

      &:hover:not(:disabled) {
        background: var(--bg-hover);
        border-color: var(--border-strong);
      }
    }
  }

  &__sparkle {
    font-size: 12px;
    line-height: 1;
    display: inline-flex;
  }

  &__at {
    font-size: 14px;
    font-weight: 700;
    line-height: 1;
  }

  &__emoji {
    font-size: 13px;
    line-height: 1;
    display: inline-flex;
  }

  &__icon {
    display: inline-flex;
    color: inherit;
    flex-shrink: 0;
  }

  &__text {
    max-width: 120px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__badge {
    min-width: 15px;
    height: 15px;
    padding: 0 4.5px;
    border-radius: 990px;
    background: var(--accent);
    color: #fff;
    font-size: 10px;
    font-weight: 700;
    line-height: 15px;
    text-align: center;
  }

  &__chev {
    color: var(--text-tertiary);
    transition: transform 0.16s $ease;
    flex-shrink: 0;
  }

  &.is-open &__chev {
    transform: rotate(180deg);
  }

  &__icon-only {
    width: 28px;
    height: 28px;
    border-radius: 7px;
    border: 1px solid transparent;
    background: transparent;
    color: var(--text-secondary);
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    transition: all 0.14s $ease;

    &:hover:not(:disabled) {
      background: var(--bg-hover);
      color: var(--text);
      border-color: var(--border);
    }

    &:disabled {
      opacity: 0.4;
      cursor: not-allowed;
    }
  }
}

/* 智能体下拉菜单 (Linear / macOS 现代极简风格) */
.agent-pick__menu {
  position: absolute;
  bottom: calc(100% + 8px);
  left: 0;
  width: 240px;
  max-height: 320px;
  background: var(--bg-elevated);
  border: 1px solid var(--border-strong);
  border-radius: 12px;
  box-shadow: 0 16px 36px rgba(0, 0, 0, 0.16);
  padding: 5px;
  z-index: 60;
  backdrop-filter: blur(20px);
  animation: menu-pop 0.16s cubic-bezier(0.16, 1, 0.3, 1) both;
  display: flex;
  flex-direction: column;
}

.agent-pick__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 5px 8px 5px;
  font-size: 11px;
  font-weight: 600;
  color: var(--text-tertiary);
  border-bottom: 1px solid var(--divider);
  margin-bottom: 3px;
}

.agent-pick__count {
  font-size: 10.5px;
  font-weight: normal;
  color: var(--text-tertiary);
}

.agent-pick__list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  overflow-y: auto;
  max-height: 260px;
}

.agent-pick__empty {
  padding: 14px 10px;
  text-align: center;
  font-size: 12px;
  color: var(--text-tertiary);
}

.agent-pick__item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  height: 36px;
  padding: 0 8px;
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  cursor: pointer;
  text-align: left;
  transition: all 0.12s $ease;

  &:hover {
    background: var(--bg-hover);
    border-color: var(--border);
  }

  &.is-on {
    background: var(--ai-fill-2);
    border-color: var(--accent-border);

    .agent-pick__item-name {
      color: var(--accent);
      font-weight: 600;
    }
  }
}

.agent-pick__avatar {
  width: 24px;
  height: 24px;
  border-radius: 6px;
  background: var(--ai-fill-2);
  border: 1px solid var(--border);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13.5px;
  flex-shrink: 0;
}

.agent-pick__item-name {
  flex: 1;
  min-width: 0;
  font-size: 12.5px;
  font-weight: 500;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.agent-pick__check {
  color: var(--accent);
  display: flex;
  align-items: center;
  flex-shrink: 0;
  font-weight: 700;
}

.skill-pick__menu {
  position: absolute;
  bottom: calc(100% + 10px);
  left: 0;
  width: 300px;
  max-height: 330px;
  padding: 8px;
  overflow: hidden;
  background: var(--bg-elevated);
  border: 1px solid var(--border-strong);
  border-radius: 14px;
  box-shadow: 0 16px 36px rgba(15, 23, 42, 0.16);
  z-index: 61;
  animation: menu-pop 0.16s cubic-bezier(0.16, 1, 0.3, 1) both;
}

.skill-pick__head {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  padding: 4px 5px 8px;
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 650;
  border-bottom: 1px solid var(--divider);
}

.skill-pick__tip,
.skill-pick__item em,
.skill-pick__item-copy small {
  color: var(--text-tertiary);
  font-size: 10.5px;
  font-style: normal;
}

.skill-pick__list {
  max-height: 260px;
  overflow-y: auto;
  padding-top: 4px;
}

.skill-pick__empty {
  padding: 18px 8px 10px;
  text-align: center;
  color: var(--text-tertiary);
  font-size: 12px;
}

.skill-pick__item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 6px;
  border: 1px solid transparent;
  border-radius: 9px;
  background: transparent;
  color: var(--text);
  text-align: left;
  cursor: pointer;

  &:hover,
  &.is-on {
    background: var(--ai-fill-2);
    border-color: var(--accent-border);
  }
}

.skill-pick__item-at {
  display: grid;
  width: 23px;
  height: 23px;
  place-items: center;
  border-radius: 7px;
  background: var(--accent-weak);
  color: var(--accent);
  font-weight: 700;
}

.skill-pick__item-copy {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;

  strong,
  small {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  strong { font-size: 12.5px; }
}

.skill-pick__check { color: var(--accent); font-weight: 800; }

.chat-skill-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  padding: 0 0 8px;
}

.chat-skill-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 5px 3px 7px;
  border: 1px solid var(--accent-border);
  border-radius: 999px;
  background: var(--accent-weak);
  color: var(--accent);
  font-size: 11.5px;
  font-weight: 600;

  button {
    width: 15px;
    height: 15px;
    border: 0;
    border-radius: 50%;
    background: transparent;
    color: inherit;
    cursor: pointer;
    font-size: 14px;
    line-height: 1;
    padding: 0;

    &:hover:not(:disabled) { background: rgba(0, 0, 0, 0.08); }
  }
}

/* 知识库下拉菜单 (1:1 像素级对齐参考图) */
.kb-pick__menu {
  position: absolute;
  bottom: calc(100% + 10px);
  left: 0;
  width: 280px;
  background: var(--bg-elevated);
  border: 1px solid var(--border-strong);
  border-radius: 16px;
  box-shadow: 0 16px 36px rgba(15, 23, 42, 0.12), 0 2px 8px rgba(15, 23, 42, 0.04);
  padding: 16px 18px 16px;
  z-index: 60;
  backdrop-filter: blur(20px);
  animation: menu-pop 0.16s cubic-bezier(0.16, 1, 0.3, 1) both;
  display: flex;
  flex-direction: column;
}

.kb-pick__head {
  font-size: 11.5px;
  font-weight: 500;
  color: var(--text-tertiary);
  margin-bottom: 10px;
}

.kb-pick__empty-box {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 14px;
}

.kb-pick__empty-msg {
  font-size: 14px;
  font-weight: 500;
  color: var(--text);
  line-height: 1.4;
}

.kb-pick__manage-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--accent, #6366f1);
  font-size: 13.5px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.14s ease;

  &:hover {
    filter: brightness(1.15);
    transform: translateX(1px);
  }
}

.kb-pick__foot {
  padding-top: 10px;
  margin-top: 6px;
  border-top: 1px solid var(--divider);
}

.kb-pick__list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: 220px;
  overflow-y: auto;
}

.kb-pick__tip {
  margin: 0 0 8px;
  font-size: 11px;
  color: var(--text-tertiary);
  line-height: 1.45;
}

.kb-pick__loading {
  padding: 16px 10px;
  text-align: center;
  font-size: 12px;
  color: var(--text-tertiary);
}

.kb-pick__item {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  width: 100%;
  padding: 7px 8px;
  border: none;
  border-radius: 8px;
  background: transparent;
  cursor: pointer;
  text-align: left;
  transition: background 0.12s $ease;
  &:hover:not(:disabled) { background: var(--bg-hover); }
  &.is-on { background: var(--accent-weak); }
  &:disabled { opacity: 0.5; cursor: not-allowed; }
}

.kb-pick__check {
  width: 14px;
  height: 14px;
  margin-top: 2px;
  flex-shrink: 0;
  border: 1px solid var(--border-strong);
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  background: var(--bg);
  transition: all 0.12s $ease;
}

.kb-pick__item.is-on .kb-pick__check {
  background: var(--accent);
  border-color: var(--accent);
}

.kb-pick__text {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.kb-pick__item-name {
  font-size: 12.5px;
  font-weight: 550;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kb-pick__item-desc {
  font-size: 11px;
  color: var(--text-tertiary);
  margin-top: 2px;
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* 待发送附件 */
.chat-att {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;

  &__item {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    max-width: 240px;
    padding: 3px 8px;
    background: var(--ai-fill-2);
    border: 1px solid var(--border);
    border-radius: 7px;
    font-size: 11.5px;
    color: var(--text);
    &.is-loading { opacity: 0.65; }
  }
  &__icon { display: flex; color: var(--text-tertiary); flex-shrink: 0; }
  &__spin { animation: att-spin 0.9s linear infinite; }
  &__name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  &__size { color: var(--text-tertiary); flex-shrink: 0; font-variant-numeric: tabular-nums; }
  &__del {
    border: none;
    background: transparent;
    color: var(--text-tertiary);
    cursor: pointer;
    padding: 0 2px;
    font-size: 11px;
    line-height: 1;
    flex-shrink: 0;
    &:hover { color: var(--danger); }
  }
}
@keyframes att-spin { to { transform: rotate(360deg); } }

/* Slash 指令快捷菜单 (浮动玻璃面板) */
.slash-menu {
  position: absolute;
  bottom: calc(100% + 10px);
  left: 16px;
  right: 16px;
  max-width: 520px;
  background: var(--bg-elevated);
  border: 1px solid var(--border-strong);
  border-radius: 14px;
  box-shadow: 0 16px 40px rgba(15, 23, 42, 0.12);
  backdrop-filter: blur(20px);
  z-index: 70;
  animation: menu-pop 0.18s cubic-bezier(0.16, 1, 0.3, 1) both;
  overflow: hidden;

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 7px 12px;
    font-size: 11.5px;
    font-weight: 600;
    color: var(--text-tertiary);
    border-bottom: 1px solid var(--divider);
    background: var(--ai-fill-1);
  }

  &__tip {
    font-size: 10.5px;
    font-weight: normal;
    color: var(--text-tertiary);
  }

  &__list {
    padding: 4px;
    max-height: 240px;
    overflow-y: auto;
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  &__item {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 7px 10px;
    border-radius: 8px;
    border: 1px solid transparent;
    background: transparent;
    cursor: pointer;
    text-align: left;
    transition: all 0.12s $ease;

    &:hover, &.is-selected {
      background: var(--bg-hover);
      border-color: var(--border);

      .slash-menu__key {
        color: var(--accent);
      }
    }
  }

  &__icon {
    font-size: 14px;
    width: 28px;
    height: 28px;
    display: flex;
    align-items: center;
    justify-content: center;
    background: var(--ai-fill-2);
    border-radius: 7px;
    flex-shrink: 0;
  }

  &__content {
    flex: 1;
    min-width: 0;
  }

  &__title {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 13px;
    font-weight: 600;
    color: var(--text);
  }

  &__key {
    color: var(--accent);
    font-family: $ai-mono;
    font-size: 12px;
  }

  &__label {
    font-size: 12.5px;
  }

  &__desc {
    font-size: 11px;
    color: var(--text-tertiary);
    margin-top: 1px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
}

/* 符号类快捷工具 (∑, 中) */
.tool-btn--math, .tool-btn--lang {
  font-family: $ai-mono;
  font-weight: 700;
  font-size: 13px;
}

.tool-btn__symbol {
  line-height: 1;
  font-size: 12.5px;
}

/* 语音波纹状态样式 (对齐参考图右侧川形波纹) */
.chat-input__send--voice {
  background: var(--ai-fill-2) !important;
  color: var(--text-secondary) !important;
  box-shadow: none !important;

  &:hover {
    background: var(--ai-fill-3) !important;
    color: var(--text) !important;
    transform: translateY(-1px);

    .vbar {
      background: var(--accent);
    }
  }
}

.chat-input__voice-bars {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 2.5px;
  height: 14px;

  .vbar {
    width: 2px;
    border-radius: 2px;
    background: currentColor;
    display: block;
    transition: height 0.15s ease, background 0.15s ease;

    &--1 { height: 6px; }
    &--2 { height: 12px; }
    &--3 { height: 14px; }
    &--4 { height: 8px; }
  }
}

@keyframes voice-pulse {
  0% { transform: scaleY(0.6); }
  100% { transform: scaleY(1.3); }
}

@keyframes menu-pop {
  from { opacity: 0; transform: translateY(4px) scale(0.97); }
  to { opacity: 1; transform: translateY(0) scale(1); }
}

.chat-input-wrapper--compact {
  max-width: none;
  margin: 0;
  padding: 0 8px 8px;

  .chat-input {
    padding: 8px 10px 8px;
    border-radius: 16px;
  }

  .chat-input__textarea {
    min-height: 36px;
    max-height: 112px;
    font-size: 14px;
    padding: 2px 2px 6px;
    &::placeholder { font-size: 13px; }
  }

  .chat-input__bar {
    flex-wrap: nowrap;
    gap: 4px;
    padding-top: 2px;
  }

  .chat-input__tools {
    flex: 1;
    min-width: 0;
    gap: 2px;
  }

  .chat-input__actions {
    gap: 4px;
    margin-left: 0;
    flex-shrink: 0;
  }

  .chat-input__status,
  .tool-btn__chev {
    display: none;
  }

  .tool-btn__text {
    display: none;
  }

  .tool-btn__text--model {
    display: inline;
    max-width: 7.5em;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .tool-btn__trigger {
    width: 32px;
    height: 32px;
    padding: 0;
    justify-content: center;
    gap: 0;
  }

  .tool-btn__trigger--pill {
    width: auto;
    max-width: 118px;
    padding: 0 8px;
    gap: 4px;
  }

  .tool-btn--plus,
  .tool-btn__icon-only {
    width: 32px;
    height: 32px;
  }

  .chat-input__send {
    width: 32px;
    height: 32px;
  }

  .slash-menu,
  .skill-pick__menu,
  .kb-pick__menu,
  .agent-pick__menu {
    position: fixed;
    left: 8px;
    right: 8px;
    width: auto;
    max-width: none;
    max-height: min(260px, 42vh);
    bottom: 70px;
  }

  .chat-att,
  .chat-skill-chips {
    flex-wrap: wrap;
  }
}
</style>
