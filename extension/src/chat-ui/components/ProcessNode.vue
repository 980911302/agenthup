<template>
  <div class="process-node" v-if="processSteps.length > 0">
    <details
      class="process"
      :class="{ 'process--done': !isStreaming, 'process--streaming': isStreaming }"
      :open="open"
      @toggle="onToggle"
    >
      <summary class="process__toggle" :class="{ 'is-streaming': isStreaming }">
        <template v-if="isStreaming">
          <i class="process__pulse"></i>
          <span class="process__title">{{ streamingLabel }}</span>
        </template>
        <template v-else>
          <span class="process__icon">{{ hasReasoning ? '💭' : '🛠️' }}</span>
          <span class="process__title" :class="{ 'process__done-err': hasFailure }">{{ doneLabel }}</span>
        </template>
        <span class="process__chev">
          <svg width="9" height="9" viewBox="0 0 12 12" fill="none"><path d="M2.5 4.5L6 8l3.5-3.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </span>
      </summary>
      <div class="process__steps">
        <component
          v-for="(s, i) in processSteps"
          :key="i"
          :is="stepComponent(s.type)"
          :step="s"
        />
      </div>
    </details>
  </div>
</template>

<script setup>
import { ref, watch, computed } from 'vue'
import { STEP_TYPES } from '../../chat/types'
import ReasoningStep from './steps/ReasoningStep.vue'
import ToolStep from './steps/ToolStep.vue'
import AgentStep from './steps/AgentStep.vue'
import SummaryStep from './steps/SummaryStep.vue'

const props = defineProps({
  steps: { type: Array, default: () => [] },
  completed: { type: Boolean, default: false }
})

const processSteps = computed(() =>
  props.steps.filter(s =>
    s.type !== STEP_TYPES.CONTENT &&
    s.type !== STEP_TYPES.UI
  )
)

const hasReasoning = computed(() =>
  processSteps.value.some(s => s.type === STEP_TYPES.REASONING)
)

const isStreaming = computed(() =>
  !props.completed && processSteps.value.some(s => s.streaming)
)

const streamingLabel = computed(() => {
  const list = processSteps.value
  const running = list.find(s => s.streaming)
  const idx = running ? list.indexOf(running) + 1 : list.length
  const total = list.length
  const pos = total > 1 ? ` (${idx}/${total})` : ''
  if (!running) return '智能体处理中…'
  if (running.type === STEP_TYPES.REASONING) return `深度思考中…${pos}`
  if (running.type === STEP_TYPES.AGENT) return `${running.name || '子智能体'} 处理中…${pos}`
  if (running.type === STEP_TYPES.TOOL) return `执行 ${running.name || '工具'}…${pos}`
  return '处理中…'
})

const doneLabel = computed(() => {
  const list = processSteps.value
  const tools = list.filter(s => s.type === STEP_TYPES.TOOL).length
  const agents = list.filter(s => s.type === STEP_TYPES.AGENT).length
  const thinks = list.filter(s => s.type === STEP_TYPES.REASONING)
  const failed = list.filter(s => s.ok === false).length
  const parts = []
  if (thinks.length) {
    const totalChars = thinks.reduce((acc, cur) => acc + (cur.text || '').length, 0)
    parts.push(totalChars ? `深度思考 (${totalChars} 字)` : '深度思考')
  }
  if (tools) parts.push(`${tools} 次工具调用`)
  if (agents) parts.push(`${agents} 个子智能体`)
  const body = parts.length ? parts.join(' · ') : `${list.length} 个步骤`
  return failed ? `${body} · ${failed} 个失败` : body
})

const hasFailure = computed(() => processSteps.value.some(s => s.ok === false))

/** 展开状态：默认全部折叠，保持页面整洁，用户点击后才展开 */
const open = ref(false)
const userTouched = ref(false)

function onToggle(e) {
  const next = e.target.open
  if (next !== open.value) {
    userTouched.value = true
    open.value = next
  }
}

function stepComponent(type) {
  switch (type) {
    case STEP_TYPES.REASONING: return ReasoningStep
    case STEP_TYPES.TOOL: return ToolStep
    case STEP_TYPES.AGENT: return AgentStep
    case STEP_TYPES.SUMMARY: return SummaryStep
    default: return null
  }
}
</script>

<style scoped lang="scss">
@use '../ai-tokens.scss' as *;

.process-node {
  margin: 4px 0 6px;
}

.process {
  border-radius: 8px;
  background: transparent;
  transition: all 0.16s $ease;
}

.process__toggle {
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
  &::-webkit-details-marker { display: none; }

  &:hover {
    background: var(--accent-weak);
    color: var(--accent);
    border-color: var(--accent-border);
  }

  &.is-streaming {
    background: var(--accent-weak);
    color: var(--accent);
    border-color: var(--accent-border);
    font-weight: 600;
  }
}

.process__icon {
  font-size: 12px;
  line-height: 1;
  display: inline-flex;
}

.process__pulse {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent);
  box-shadow: 0 0 0 2.5px var(--accent-weak);
  animation: pulse 1.2s infinite ease-in-out;
  flex-shrink: 0;
}

@keyframes pulse {
  0%, 100% { opacity: 0.4; }
  50% { opacity: 1; }
}

.process__title {
  font-size: 11.5px;
  line-height: 1;
}

.process__chev {
  display: inline-flex;
  color: var(--text-tertiary);
  transition: transform 0.18s $ease;
  margin-left: 2px;
}

.process[open] .process__chev {
  transform: rotate(180deg);
}

.process__done-err {
  color: var(--danger-text);
}

.process__steps {
  margin-top: 6px;
  display: flex;
  flex-direction: column;
  gap: 5px;
  animation: fade-slide 0.16s cubic-bezier(0.16, 1, 0.3, 1) both;
}

@keyframes fade-slide {
  from { opacity: 0; transform: translateY(-4px); }
  to { opacity: 1; transform: translateY(0); }
}
</style>
