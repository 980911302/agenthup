<template>
  <div class="tool-card" :class="{ 'tool-card--open': open, 'tool-card--running': step.streaming }">
    <div class="tool-card__header" @click="open = !open">
      <div class="tool-card__lead">
        <span class="tool-card__icon tool-card__icon--think">
          <svg width="13" height="13" viewBox="0 0 16 16" fill="none">
            <path d="M8 1.6a4.4 4.4 0 0 0-2.6 7.95V11a.9.9 0 0 0 .9.9h3.4a.9.9 0 0 0 .9-.9V9.55A4.4 4.4 0 0 0 8 1.6z" stroke="currentColor" stroke-width="1.25"/>
            <path d="M6.4 13.6h3.2" stroke="currentColor" stroke-width="1.25" stroke-linecap="round"/>
          </svg>
        </span>
        <span class="tool-card__title">深度思考</span>
        <code v-if="brief" class="tool-card__arg-tag" :title="brief">{{ brief }}</code>
      </div>
      <div class="tool-card__meta">
        <span v-if="step.streaming" class="tool-card__badge tool-card__badge--run">
          <i class="tool-card__dot tool-card__dot--run"></i>思考中
        </span>
        <span v-else class="tool-card__badge tool-card__badge--ok">
          <i class="tool-card__dot tool-card__dot--ok"></i>{{ charCount }} 字
        </span>
        <svg class="tool-card__chev" :class="{ 'is-open': open }" width="9" height="9" viewBox="0 0 12 12" fill="none"><path d="M2.5 4.5L6 8l3.5-3.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
      </div>
    </div>

    <!-- 展开思考内容框 -->
    <div v-if="open" class="tool-card__body">
      <div class="tool-card__nav">
        <span class="tool-card__subtitle">思考过程</span>
        <button type="button" class="tool-card__copy-btn" :title="copied ? '已复制' : '复制思考'" @click.stop="doCopy">
          <span v-if="copied" class="tool-card__copied-text">✓ 已复制</span>
          <template v-else>
            <svg width="11" height="11" viewBox="0 0 16 16" fill="none"><path d="M5.5 2.5h6A1.5 1.5 0 0 1 13 4v7a1.5 1.5 0 0 1-1.5 1.5h-6A1.5 1.5 0 0 1 4 11V4a1.5 1.5 0 0 1 1.5-1.5z" stroke="currentColor" stroke-width="1.3"/><path d="M3 5.5H2.5A1.5 1.5 0 0 0 1 7v6A1.5 1.5 0 0 0 2.5 14.5h6A1.5 1.5 0 0 0 10 13v-.5" stroke="currentColor" stroke-width="1.3"/></svg>
            <span>复制</span>
          </template>
        </button>
      </div>

      <div class="tool-card__pane">
        <div class="tool-card__think-text">{{ view.shown }}</div>
        <button
          v-if="view.collapsible"
          class="tool-card__more"
          type="button"
          @click.stop="view.toggle"
        >
          <template v-if="view.expanded && view.hasMore">
            … 已省略 {{ view.hiddenExpandedLines }} 行（点击收起）
          </template>
          <template v-else-if="view.expanded">收起思考</template>
          <template v-else>展开全部 · 共 {{ view.totalLines }} 行</template>
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { useCollapsibleText } from '../../composables/useStepDisplay'

const props = defineProps({ step: { type: Object, required: true } })

const charCount = computed(() => (props.step.text || '').length)
const brief = computed(() => {
  const t = (props.step.text || '').replace(/\s+/g, ' ').trim()
  return t.length > 50 ? t.slice(0, 50) + '…' : t
})

const open = ref(false)
const copied = ref(false)

// 默认保持折叠，由用户按需点击展开

const view = reactive(useCollapsibleText(() => props.step.text || '', {
  maxLines: 12, maxChars: 800, maxExpandedLines: 150, maxExpandedChars: 12000
}))

async function doCopy() {
  const content = props.step.text || ''
  if (!content) return
  try {
    await navigator.clipboard.writeText(content)
    copied.value = true
    setTimeout(() => { copied.value = false }, 1800)
  } catch (_) {}
}
</script>

<style scoped lang="scss">
@use '../../ai-tokens.scss' as *;

.tool-card {
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--bg-card, var(--bg-elevated));
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.02);
  transition: all 0.14s $ease;
  overflow: hidden;

  &:hover {
    border-color: var(--border-strong);
  }

  &--running {
    border-color: var(--accent-border);
  }

  &__header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 6px 9px;
    cursor: pointer;
    user-select: none;
    transition: background 0.12s $ease;

    &:hover {
      background: var(--bg-hover);
    }
  }

  &__lead {
    display: flex;
    align-items: center;
    gap: 6.5px;
    min-width: 0;
    flex: 1;
  }

  &__icon {
    width: 20px;
    height: 20px;
    border-radius: 4px;
    background: var(--ai-fill-2);
    border: 1px solid var(--border);
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--text-secondary);
    flex-shrink: 0;

    &--think {
      color: var(--accent, #0A84FF);
      background: var(--accent-weak, rgba(10, 132, 255, 0.08));
      border-color: var(--accent-border, rgba(10, 132, 255, 0.15));
    }
  }

  &__title {
    font-size: 11.5px;
    font-weight: 600;
    color: var(--text);
    flex-shrink: 0;
  }

  &__arg-tag {
    font-family: $mono;
    font-size: 11px;
    color: var(--text-tertiary);
    background: var(--bg-code, #f1f5f9);
    padding: 1px 5px;
    border-radius: 3px;
    border: 1px solid var(--border);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    max-width: 280px;
  }

  &__meta {
    display: flex;
    align-items: center;
    gap: 5px;
    flex-shrink: 0;
  }

  &__badge {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    font-size: 10.5px;
    color: var(--text-tertiary);
    font-variant-numeric: tabular-nums;

    &--ok { color: var(--text-tertiary); }
    &--run { color: var(--accent); font-weight: 500; }
  }

  &__dot {
    width: 5px;
    height: 5px;
    border-radius: 50%;

    &--ok { background: var(--ok, #10b981); }
    &--run { background: var(--accent, #0A84FF); animation: pulse 1.2s infinite ease-in-out; }
  }

  &__chev {
    color: var(--text-tertiary);
    transition: transform 0.16s $ease;

    &.is-open {
      transform: rotate(180deg);
    }
  }

  &__body {
    border-top: 1px solid var(--border);
    background: var(--bg-subtle, var(--ai-fill-1));
    padding: 7px 9px 9px;
  }

  &__nav {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 5px;
  }

  &__subtitle {
    font-size: 11px;
    font-weight: 600;
    color: var(--text-secondary);
  }

  &__copy-btn {
    height: 20px;
    padding: 0 5px;
    font-size: 10.5px;
    color: var(--text-tertiary);
    background: transparent;
    border: 1px solid transparent;
    border-radius: 3px;
    cursor: pointer;
    display: inline-flex;
    align-items: center;
    gap: 3.5px;
    transition: all 0.12s $ease;

    &:hover {
      background: var(--bg-hover);
      color: var(--text);
      border-color: var(--border);
    }
  }

  &__copied-text {
    color: var(--ok, #10b981);
    font-weight: 600;
  }

  &__pane {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }

  &__think-text {
    font-size: 12px;
    line-height: 1.6;
    color: var(--text-secondary);
    background: var(--bg-code, #f1f5f9);
    border: 1px solid var(--border);
    border-radius: 5px;
    padding: 8px 10px;
    white-space: pre-wrap;
    word-break: break-word;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
  }

  &__more {
    align-self: flex-start;
    padding: 2px 6px;
    font-size: 10.5px;
    color: var(--text-tertiary);
    background: transparent;
    border: 1px solid var(--border);
    border-radius: 3px;
    cursor: pointer;
    transition: all 0.12s $ease;

    &:hover {
      color: var(--text);
      background: var(--bg-hover);
      border-color: var(--border-strong);
    }
  }
}

@keyframes pulse {
  0%, 100% { opacity: 0.4; }
  50% { opacity: 1; }
}
</style>
