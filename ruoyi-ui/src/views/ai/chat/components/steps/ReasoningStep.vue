<template>
  <details class="step" :open="open" @toggle="onToggle">
    <summary class="step__summary">
      <span class="step__icon">
        <svg width="14" height="14" viewBox="0 0 16 16" fill="none">
          <path d="M8 1.6a4.4 4.4 0 0 0-2.6 7.95V11a.9.9 0 0 0 .9.9h3.4a.9.9 0 0 0 .9-.9V9.55A4.4 4.4 0 0 0 8 1.6z" stroke="currentColor" stroke-width="1.25"/>
          <path d="M6.4 13.6h3.2" stroke="currentColor" stroke-width="1.25" stroke-linecap="round"/>
        </svg>
      </span>
      <span class="step__name">思考</span>
      <span class="step__brief">{{ brief }}</span>
      <span class="step__meta">
        <i class="step__dot" :class="step.streaming ? 'step__dot--run' : 'step__dot--ok'"></i>
        {{ charCount }} 字
      </span>
    </summary>
    <div class="step__body">
      <div class="step__think">{{ view.shown }}</div>
      <button v-if="view.collapsible" class="step__more" type="button" @click="view.toggle">
        <template v-if="view.expanded && view.hasMore">
          … 已省略 {{ view.hiddenExpandedLines }} 行，超出部分不展示（点击收起）
        </template>
        <template v-else-if="view.expanded">收起</template>
        <template v-else>展开全部 · 共 {{ view.totalLines }} 行</template>
      </button>
    </div>
  </details>
</template>

<script setup>
import { computed, reactive } from 'vue'
import { useStepToggle, useCollapsibleText } from '../../composables/useStepDisplay'

const props = defineProps({ step: { type: Object, required: true } })

const charCount = computed(() => (props.step.text || '').length)
// 折叠时给一句思考开头,比只显示「思考」两个字有信息量
const brief = computed(() => {
  const t = (props.step.text || '').replace(/\s+/g, ' ').trim()
  return t.length > 90 ? t.slice(0, 90) + '…' : t
})

const { open, onToggle } = useStepToggle(() => props.step.streaming)

// 思考是模型的草稿,不是正文——几千上万字符的思考不该整段糊进 DOM。
// 与工具结果同一套折叠机制(见 useCollapsibleText),阈值更紧凑:
// 折叠态给一小段就够(展开状态的 summary 已经有 90 字符摘要了),
// 展开态仍受上限约束,避免极端情况下单条思考塞满页面。
const view = reactive(useCollapsibleText(() => props.step.text, {
  maxLines: 8, maxChars: 500, maxExpandedLines: 100, maxExpandedChars: 8000
}))
</script>

<style scoped lang="scss">
@use './step';
</style>
