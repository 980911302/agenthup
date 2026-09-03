<template>
  <div class="thinking-node" v-if="step">
    <details class="thinking-details" :class="{ 'is-streaming': isStreaming }" :open="open" @toggle="onToggle">
      <summary class="thinking-summary" :class="{ 'is-streaming': isStreaming }">
        <template v-if="isStreaming">
          <i class="thinking-summary__pulse"></i>
          <span class="thinking-summary__title">{{ streamingLabel }}</span>
        </template>
        <template v-else>
          <span class="thinking-summary__icon">💭</span>
          <span class="thinking-summary__title">{{ doneLabel }}</span>
        </template>
        <svg class="thinking-summary__chev" width="9" height="9" viewBox="0 0 12 12" fill="none"><path d="M2.5 4.5L6 8l3.5-3.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
      </summary>

      <div class="thinking-body">
        <div class="thinking-content">{{ view.shown }}</div>
        <div class="thinking-footer" v-if="view.collapsible || view.expanded">
          <button class="thinking-more" type="button" @click="view.toggle">
            <template v-if="view.expanded && view.hasMore">
              … 已省略 {{ view.hiddenExpandedLines }} 行（点击收起）
            </template>
            <template v-else-if="view.expanded">收起思考</template>
            <template v-else>展开全部 · 共 {{ view.totalLines }} 行</template>
          </button>
        </div>
      </div>
    </details>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { useCollapsibleText } from '../composables/useStepDisplay'

const props = defineProps({
  step: { type: Object, required: true },
  completed: { type: Boolean, default: false }
})

const charCount = computed(() => (props.step?.text || '').length)
const isStreaming = computed(() => !props.completed && !!props.step?.streaming)

const streamingLabel = computed(() => {
  return charCount.value ? `正在深度思考 (${charCount.value} 字)…` : '正在深度思考…'
})

const doneLabel = computed(() => {
  return charCount.value ? `已深度思考 (${charCount.value} 字)` : '已深度思考'
})

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

watch(isStreaming, (streaming) => {
  if (!userTouched.value) {
    open.value = streaming
  }
})

const view = reactive(useCollapsibleText(() => props.step?.text || '', {
  maxLines: 12, maxChars: 800, maxExpandedLines: 150, maxExpandedChars: 12000
}))
</script>

<style scoped lang="scss">
@use '../ai-tokens.scss' as *;

.thinking-node {
  margin: 4px 0 6px;
}

.thinking-details {
  border-radius: 8px;
  background: transparent;
  transition: all 0.16s $ease;
}

.thinking-summary {
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

.thinking-summary__icon {
  font-size: 12px;
  line-height: 1;
  display: inline-flex;
}

.thinking-summary__pulse {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent);
  box-shadow: 0 0 0 2.5px var(--accent-weak);
  animation: pulse 1.2s infinite ease-in-out;
  flex-shrink: 0;
}

.thinking-summary__title {
  font-size: 11.5px;
  line-height: 1;
}

.thinking-summary__chev {
  display: inline-flex;
  color: var(--text-tertiary);
  transition: transform 0.18s $ease;
  margin-left: 2px;
}

.thinking-details[open] .thinking-summary__chev {
  transform: rotate(180deg);
}

.thinking-body {
  margin-top: 6px;
  padding: 10px 14px;
  background: var(--bg-code, rgba(0, 0, 0, 0.025));
  border-left: 2.5px solid var(--border-strong, #cbd5e1);
  border-radius: 0 8px 8px 0;
  animation: fade-slide 0.16s cubic-bezier(0.16, 1, 0.3, 1) both;
}

.thinking-content {
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--text-secondary);
  white-space: pre-wrap;
  word-break: break-word;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
}

.thinking-footer {
  margin-top: 6px;
  display: flex;
  align-items: center;
}

.thinking-more {
  padding: 2px 7px;
  font-size: 10.5px;
  color: var(--text-tertiary);
  background: transparent;
  border: 1px solid var(--border);
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.12s $ease;

  &:hover {
    color: var(--text);
    background: var(--bg-hover);
    border-color: var(--border-strong);
  }
}

@keyframes pulse {
  0%, 100% { opacity: 0.4; }
  50% { opacity: 1; }
}

@keyframes fade-slide {
  from { opacity: 0; transform: translateY(-4px); }
  to { opacity: 1; transform: translateY(0); }
}
</style>
