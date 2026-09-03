<template>
  <details class="step" :class="{ 'step--err': failed }" :open="open" @toggle="onToggle">
    <summary class="step__summary">
      <span class="step__icon">
        <ToolIcon :kind="iconKind" />
      </span>
      <span class="step__name">{{ step.name }}</span>
      <span class="step__brief">{{ brief }}</span>
      <span class="step__meta">
        <template v-if="step.pendingConfirm">
          <i class="step__dot step__dot--run"></i>等待确认
        </template>
        <template v-else-if="step.streaming">
          <i class="step__dot step__dot--run"></i>执行中
        </template>
        <template v-else-if="failed">
          <i class="step__dot step__dot--err"></i>失败 · {{ formatMs(step.ms) }}
        </template>
        <template v-else>
          <i class="step__dot step__dot--ok"></i>{{ formatMs(step.ms) }}
        </template>
      </span>
    </summary>
    <div class="step__body">
      <div v-if="step.args" class="step__field">
        <span class="step__label">入参</span>
        <pre class="step__code">{{ argsView.shown }}</pre>
        <button v-if="argsView.collapsible" class="step__more" type="button" @click="argsView.toggle">
          {{ argsView.expanded ? '收起' : `展开全部 · ${argsView.totalLines} 行` }}
        </button>
      </div>
      <!-- 工具返回文本(知识库片段引用统一收在最终回答下方,见 CitationsView) -->
      <div v-if="step.result" class="step__field">
        <span class="step__label">{{ failed ? '错误' : '返回' }}</span>
        <pre class="step__code" :class="{ 'step__code--err': failed }">{{ resultView.shown }}</pre>
        <button
          v-if="resultView.collapsible || canLoadFull"
          class="step__more"
          type="button"
          :disabled="loadingFull"
          @click="onToggleResult"
        >
          <template v-if="loadingFull">加载中…</template>
          <template v-else-if="resultView.expanded && resultView.hasMore">
            … 已省略 {{ resultView.hiddenExpandedLines }} 行，超出部分不展示（点击收起）
          </template>
          <template v-else-if="resultView.expanded">收起</template>
          <template v-else>展开 · 共 {{ resultLengthLabel }}</template>
        </button>
        <div v-if="loadError" class="step__omitted">{{ loadError }}</div>
        <div v-if="resultView.expanded && resultView.hasMore" class="step__omitted">…… 结果过长，仅展示前 100 行</div>
      </div>
    </div>

  </details>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import ToolIcon from './ToolIcon.vue'
import { getToolResult } from '@/api/ai/session'
import {
  useStepToggle, formatMs, briefOfArgs, prettyJson,
  toolIconKind, useCollapsibleText
} from '../../composables/useStepDisplay'

const props = defineProps({ step: { type: Object, required: true } })

// step.ok 一直有值,但改造前模板从没读过 —— 工具失败时界面照样显示 ✓,
// 用户以为全都成功了,实际模型可能正在重试。
const failed = computed(() => props.step.ok === false)
const brief = computed(() => briefOfArgs(props.step.args))
const iconKind = computed(() => toolIconKind(props.step))

const { open, onToggle } = useStepToggle(() => props.step.streaming)

// 入参通常不长,给它更紧的阈值;返回可能上万字符,折叠态只渲染片段(见 useCollapsibleText)
const argsView = reactive(useCollapsibleText(() => prettyJson(props.step.args), { maxLines: 8, maxChars: 400 }))
const cachedFull = ref('')
const loadError = ref('')
const loadingFull = ref(false)
const resultView = reactive(useCollapsibleText(() => cachedFull.value || props.step.result, {
  maxLines: 12, maxChars: 600, maxExpandedLines: 100, maxExpandedChars: 8000
}))

const PREVIEW_LIMIT = 200
const canLoadFull = computed(() => {
  if (props.step.streaming) return false
  if (!props.step.sessionId || !props.step.messageId) return false
  const len = Number(props.step.toolResultLength)
  if (Number.isFinite(len) && len > PREVIEW_LIMIT) return true
  return !!props.step.toolResultPath
})
const resultLengthLabel = computed(() => {
  const len = Number(props.step.toolResultLength)
  if (Number.isFinite(len) && len > 0) return `${len} 字`
  return `${resultView.totalLines} 行`
})

async function onToggleResult() {
  if (!resultView.expanded && canLoadFull.value && !cachedFull.value) {
    await fetchFull()
  }
  resultView.toggle()
}

async function fetchFull() {
  if (loadingFull.value) return
  loadingFull.value = true
  loadError.value = ''
  try {
    const res = await getToolResult(props.step.sessionId, props.step.messageId)
    cachedFull.value = (res && res.data && res.data.toolResult) || props.step.result || ''
  } catch (e) {
    loadError.value = '完整结果加载失败，仍显示预览'
  } finally {
    loadingFull.value = false
  }
}
</script>

<style scoped lang="scss">
@use './step';

/* 结果过长被截断时的省略提示 */
.step__omitted {
  font-size: 12px; color: var(--ai-text3, #5A5C64);
  padding: 4px 0 0; font-style: italic;
}
</style>
