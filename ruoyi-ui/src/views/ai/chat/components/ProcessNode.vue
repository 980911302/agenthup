<template>
  <div class="process-node">
    <!-- 有过程步骤(思考/工具/子agent)时显示折叠容器 -->
    <template v-if="processSteps.length > 0">
      <!-- 单一 details：展开状态由 ref 控制，用户手动操作后不再自动接管，避免流式结束 DOM 重建跳动 -->
      <details
        class="process"
        :class="{ 'process--done': !isStreaming }"
        :open="open"
        @toggle="onToggle"
      >
        <summary :class="isStreaming ? 'process__head' : 'process__toggle'">
          <template v-if="isStreaming">
            <i class="process__pulse"></i>
            <span class="process__title">{{ streamingLabel }}</span>
          </template>
          <template v-else>
            <span class="process__chev">
              <svg width="10" height="10" viewBox="0 0 12 12" fill="none"><path d="M4 2.5L8 6l-4 3.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
            </span>
            <span :class="{ 'process__done-err': hasFailure }">{{ doneLabel }}</span>
          </template>
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
    </template>
  </div>
</template>

<script setup>
import { ref, watch, computed } from 'vue'
import { STEP_TYPES } from '../types/chat'
import ReasoningStep from './steps/ReasoningStep.vue'
import ToolStep from './steps/ToolStep.vue'
import AgentStep from './steps/AgentStep.vue'
import SummaryStep from './steps/SummaryStep.vue'

const props = defineProps({
  steps: { type: Array, default: () => [] },
  completed: { type: Boolean, default: false }
})

/** 过程步骤:排除 content(正文单独渲染)和 ui 产物(挂引用,不进时间线) */
const processSteps = computed(() =>
  props.steps.filter(s => s.type !== STEP_TYPES.CONTENT && s.type !== STEP_TYPES.UI)
)

/** 是否还在流式(有 streaming step 或未完成) */
const isStreaming = computed(() =>
  !props.completed || processSteps.value.some(s => s.streaming)
)

/**
 * 流式中的标签。原先只说类别(「执行工具中…」),不说在执行哪个、第几步 ——
 * 过程区的价值在于扫一眼就知道进展,名字和序号都在事件里,没道理不显示。
 */
const streamingLabel = computed(() => {
  const list = processSteps.value
  const running = list.find(s => s.streaming)
  const idx = running ? list.indexOf(running) + 1 : list.length
  const total = list.length
  const pos = total > 1 ? ` (${idx}/${total})` : ''
  if (!running) return '处理中…'
  if (running.type === STEP_TYPES.AGENT) return `${running.name || '子智能体'} 处理中…${pos}`
  if (running.type === STEP_TYPES.TOOL) return `执行 ${running.name || '工具'}…${pos}`
  if (running.type === STEP_TYPES.REASONING) return `思考中…${pos}`
  return `处理中…${pos}`
})

/** 完成后的折叠入口:概括这一轮做了什么,而不是干巴巴一句「查看过程」 */
const doneLabel = computed(() => {
  const list = processSteps.value
  const tools = list.filter(s => s.type === STEP_TYPES.TOOL).length
  const agents = list.filter(s => s.type === STEP_TYPES.AGENT).length
  const thinks = list.filter(s => s.type === STEP_TYPES.REASONING).length
  const failed = list.filter(s => s.ok === false).length
  const parts = []
  if (thinks) parts.push('思考')
  if (tools) parts.push(`${tools} 次工具调用`)
  if (agents) parts.push(`${agents} 个子智能体`)
  const body = parts.length ? parts.join(' · ') : `${list.length} 个步骤`
  return failed ? `${body} · ${failed} 个失败` : body
})

/** 有失败步骤时折叠入口标红,不展开也能看出这一轮出过问题 */
const hasFailure = computed(() => processSteps.value.some(s => s.ok === false))

/** 展开状态：流式中默认展开实时反馈；已完成历史默认折叠保持整洁；用户手动操作后尊重用户意图 */
const open = ref(isStreaming.value)
const userTouched = ref(false)

function onToggle(e) {
  const next = e.target.open
  if (next !== open.value) {
    userTouched.value = true
    open.value = next
  }
}

// 流式结束时自动收起 —— 但用户手动动过就尊重用户的选择
watch(isStreaming, (streaming) => {
  if (!userTouched.value) {
    open.value = streaming
  }
})

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
@use '../../../../assets/styles/ai-tokens.scss' as *;

.process {
  margin: 8px 0;
  border-radius: 12px;
  background: var(--ai-card-bg);
  border: 1px solid var(--ai-border);
  box-shadow: 0 1px 2px var(--ai-fill-1);
  transition: border-color 0.18s $ease;
  &:hover { border-color: var(--ai-border-3); }
  &--done { background: transparent; border: none; box-shadow: none; }
}
.process__head {
  display: flex; align-items: center; gap: 8px; padding: 8px 12px;
  cursor: default; list-style: none;
  &::-webkit-details-marker { display: none; }
}
.process__pulse {
  width: 8px; height: 8px; border-radius: 50%; background: $blue;
  box-shadow: 0 0 0 3px rgba(10, 132, 255, 0.12);
  animation: pulse 1.2s infinite ease-in-out;
}
@keyframes pulse { 0%,100%{opacity:0.4} 50%{opacity:1} }
.process__title { font-size: $ai-fs-5; color: $blue; font-weight: 550; }
.process__steps { padding: 2px 8px 8px; display: flex; flex-direction: column; gap: 5px; }
.process__chev { display: inline-flex; transition: transform 0.2s $ease; }
.process[open] .process__chev { transform: rotate(90deg); }
.process__done-err { color: #C4362D; }
/* 完成后的折叠入口:小胶囊,扫一眼知道这轮做了什么,点击展开详情 */
.process__toggle {
  display: inline-flex; align-items: center; gap: 6px;
  font-size: $ai-fs-5; color: $ai-text3; cursor: pointer; padding: 4px 12px;
  border-radius: 980px; background: var(--ai-fill-1);
  border: 1px solid var(--ai-border);
  list-style: none; user-select: none;
  transition: background 0.16s $ease, color 0.16s $ease, border-color 0.16s $ease;
  &::-webkit-details-marker { display: none; }
  &:hover { background: rgba(10, 132, 255, 0.08); color: $blue; border-color: rgba(10, 132, 255, 0.25); }
}
</style>
