<template>
  <details class="step" :class="{ 'step--err': failed }" :open="open" @toggle="onToggle">
    <summary class="step__summary">
      <span class="step__icon">
        <svg width="14" height="14" viewBox="0 0 16 16" fill="none">
          <circle cx="5.3" cy="5.6" r="2.3" stroke="currentColor" stroke-width="1.25"/>
          <circle cx="11" cy="9.8" r="2.3" stroke="currentColor" stroke-width="1.25"/>
          <path d="M7.2 7L9.1 8.4" stroke="currentColor" stroke-width="1.25" stroke-linecap="round"/>
        </svg>
      </span>
      <span class="step__name">{{ step.name }}</span>
      <span class="step__brief">子智能体{{ childSteps.length ? ' · ' + childSteps.length + ' 个步骤' : '' }}</span>
      <span class="step__meta">
        <template v-if="step.streaming">
          <i class="step__dot step__dot--run"></i>思考中
        </template>
        <template v-else-if="failed">
          <i class="step__dot step__dot--err"></i>失败 · {{ formatMs(step.ms) }}
        </template>
        <template v-else>
          <i class="step__dot step__dot--ok"></i>{{ formatMs(step.ms) }}
        </template>
      </span>
    </summary>
    <div class="step__body step__body--agent">
      <!-- 子智能体内部的过程(思考/工具/更深层子智能体),嵌套展示 -->
      <div v-if="childSteps.length" class="step__children">
        <template v-for="(c, i) in childSteps" :key="i">
          <!-- AGENT 用组件名自引用递归(script setup 按 name 解析自身) -->
          <AgentStep v-if="c.type === STEP_TYPES.AGENT" :step="c" />
          <component v-else :is="childComponent(c.type)" :step="c" />
        </template>
      </div>
      <!-- 子智能体的回答(流式累积) -->
      <div v-if="step.result">
        <span v-if="childSteps.length" class="step__label">回复</span>
        <div class="step__think">{{ step.result }}</div>
      </div>
    </div>
  </details>
</template>

<script setup>
import { computed } from 'vue'
import { STEP_TYPES } from '../../../chat/types'
import { useStepToggle, formatMs } from '../../composables/useStepDisplay'
import ReasoningStep from './ReasoningStep.vue'
import ToolStep from './ToolStep.vue'
import SummaryStep from './SummaryStep.vue'

// 命名供模板按名自引用递归(子智能体里还可能嵌套更深层子智能体)
defineOptions({ name: 'AgentStep' })

const props = defineProps({ step: { type: Object, required: true } })

const childSteps = computed(() => props.step.steps || [])
const failed = computed(() => props.step.ok === false)

const { open, onToggle } = useStepToggle(() => props.step.streaming)

/** 叶子子步骤组件分发(AGENT 在模板里自引用,不走这里) */
function childComponent(type) {
  switch (type) {
    case STEP_TYPES.REASONING: return ReasoningStep
    case STEP_TYPES.TOOL: return ToolStep
    case STEP_TYPES.SUMMARY: return SummaryStep
    default: return null
  }
}
</script>

<style scoped lang="scss">
@use './step';
@use '../../ai-tokens.scss' as *;

// 子智能体的内部步骤缩进一层，用虚线标出从属关系
.step__children {
  display: flex; flex-direction: column; gap: 2px;
  padding-left: 8px; margin: 2px 0 6px;
  border-left: 1px dashed var(--ai-border-4);
}
.step__body--agent { padding-left: 32px; }
</style>
