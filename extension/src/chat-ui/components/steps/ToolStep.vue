<template>
  <div class="tool-card" :class="{ 'tool-card--err': failed, 'tool-card--open': open, 'tool-card--running': step.streaming }">
    <div class="tool-card__header" @click="open = !open">
      <div class="tool-card__lead">
        <span class="tool-card__icon">
          <ToolIcon :kind="iconKind" />
        </span>
        <span class="tool-card__title">{{ humanName }}</span>
        <code v-if="step.name" class="tool-card__raw-name" :title="`真实工具名: ${step.name}`">{{ step.name }}</code>
        <code v-if="brief" class="tool-card__arg-tag" :title="brief">{{ brief }}</code>
      </div>
      <div class="tool-card__meta">
        <span v-if="step.pendingConfirm" class="tool-card__badge tool-card__badge--warn">
          <i class="tool-card__dot tool-card__dot--run"></i>等待确认
        </span>
        <span v-else-if="step.streaming" class="tool-card__badge tool-card__badge--run">
          <i class="tool-card__dot tool-card__dot--run"></i>执行中
        </span>
        <span v-else-if="failed" class="tool-card__badge tool-card__badge--err">
          <i class="tool-card__dot tool-card__dot--err"></i>失败 · {{ formatMs(step.ms) }}
        </span>
        <span v-else class="tool-card__badge tool-card__badge--ok">
          <i class="tool-card__dot tool-card__dot--ok"></i>{{ formatMs(step.ms) }}
        </span>
        <svg class="tool-card__chev" :class="{ 'is-open': open }" width="9" height="9" viewBox="0 0 12 12" fill="none"><path d="M2.5 4.5L6 8l3.5-3.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
      </div>
    </div>

    <!-- 展开详情 (参数与返回值) -->
    <div v-if="open" class="tool-card__body">
      <div class="tool-card__nav">
        <div class="tool-card__tabs">
          <button
            type="button"
            class="tool-card__tab"
            :class="{ 'is-active': activeTab === 'args' }"
            @click.stop="activeTab = 'args'"
          >
            入参
          </button>
          <button
            v-if="step.result"
            type="button"
            class="tool-card__tab"
            :class="{ 'is-active': activeTab === 'result' }"
            @click.stop="activeTab = 'result'"
          >
            {{ failed ? '错误输出' : '执行返回' }}
          </button>
        </div>
        <button type="button" class="tool-card__copy-btn" :title="copied ? '已复制' : '复制内容'" @click.stop="doCopy">
          <span v-if="copied" class="tool-card__copied-text">✓ 已复制</span>
          <template v-else>
            <svg width="11" height="11" viewBox="0 0 16 16" fill="none"><path d="M5.5 2.5h6A1.5 1.5 0 0 1 13 4v7a1.5 1.5 0 0 1-1.5 1.5h-6A1.5 1.5 0 0 1 4 11V4a1.5 1.5 0 0 1 1.5-1.5z" stroke="currentColor" stroke-width="1.3"/><path d="M3 5.5H2.5A1.5 1.5 0 0 0 1 7v6A1.5 1.5 0 0 0 2.5 14.5h6A1.5 1.5 0 0 0 10 13v-.5" stroke="currentColor" stroke-width="1.3"/></svg>
            <span>复制</span>
          </template>
        </button>
      </div>

      <div v-if="activeTab === 'args'" class="tool-card__pane">
        <pre class="tool-card__code"><code>{{ argsView.shown }}</code></pre>
      </div>

      <div v-else-if="activeTab === 'result'" class="tool-card__pane">
        <pre class="tool-card__code" :class="{ 'tool-card__code--err': failed }"><code>{{ resultView.shown }}</code></pre>
        <button
          v-if="resultView.collapsible || canLoadFull"
          class="tool-card__more"
          type="button"
          :disabled="loadingFull"
          @click.stop="onToggleResult"
        >
          <template v-if="loadingFull">加载中…</template>
          <template v-else-if="resultView.expanded">收起结果</template>
          <template v-else>展开全部 · 共 {{ resultLengthLabel }}</template>
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import ToolIcon from './ToolIcon.vue'
import { getToolResult } from '../../../api/session'
import {
  formatMs, briefOfArgs, prettyJson,
  toolIconKind, useCollapsibleText
} from '../../composables/useStepDisplay'

const props = defineProps({ step: { type: Object, required: true } })

const failed = computed(() => props.step.ok === false)
const brief = computed(() => {
  const b = briefOfArgs(props.step.args)
  if (!b || b === '{}' || b === 'null' || b === '""') return ''
  return b
})
const iconKind = computed(() => toolIconKind(props.step))

// 语义化的人类可读工具名称
const humanName = computed(() => {
  const raw = props.step.name || ''
  const n = raw.toLowerCase()
  if (n === 'read' || n === 'readfile' || n === 'view_file') return '读取文件'
  if (n === 'write' || n === 'write_to_file') return '写入文件'
  if (n === 'edit' || n === 'replace_file_content') return '编辑文件'
  if (n === 'bash' || n === 'runshell' || n === 'run_command') return '终端命令'
  if (n === 'grep' || n === 'grep_search' || n === 'find_by_name') return '检索代码'
  if (n === 'kb' || n === 'retrieve_kb') return '检索知识库'
  if (n === 'listscheduledjobs' || n === 'list_scheduled_jobs') return '定时任务列表'
  if (n === 'getcurrenttime' || n === 'get_current_time') return '获取当前时间'
  if (n === 'ls' || n === 'list_dir') return '文件目录'
  if (n.startsWith('mcp_')) return `MCP · ${raw.slice(4)}`
  return raw || '工具调用'
})

const open = ref(false)
const activeTab = ref(props.step.result ? 'result' : 'args')
const copied = ref(false)

const argsView = reactive(useCollapsibleText(() => prettyJson(props.step.args), { maxLines: 10, maxChars: 600 }))
const cachedFull = ref('')
const loadError = ref('')
const loadingFull = ref(false)
const resultView = reactive(useCollapsibleText(() => cachedFull.value || props.step.result, {
  maxLines: 12, maxChars: 800, maxExpandedLines: 100, maxExpandedChars: 8000
}))

const PREVIEW_LIMIT = 200
const canLoadFull = computed(() => {
  if (props.step.streaming) return false
  if (!props.step.sessionId || !props.step.messageId) return false
  const len = Number(props.step.toolResultLength)
  if (Number.isFinite(len) && len > PREVIEW_LIMIT) return true
  return !!props.step.hasFullToolResult
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

async function doCopy() {
  const content = activeTab.value === 'args' ? (props.step.args || '') : (cachedFull.value || props.step.result || '')
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

  &--err {
    border-color: rgba(239, 68, 68, 0.25);
    background: rgba(239, 68, 68, 0.015);
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
  }

  &__title {
    font-size: 11.5px;
    font-weight: 600;
    color: var(--text);
    flex-shrink: 0;
  }

  &__raw-name {
    font-family: $mono;
    font-size: 11px;
    font-weight: 600;
    color: var(--text-secondary);
    background: var(--bg-code, #f1f5f9);
    padding: 1px 6px;
    border-radius: 4px;
    border: 1px solid var(--border);
    flex-shrink: 0;
    letter-spacing: -0.2px;
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
    &--err { color: var(--danger-text, #ef4444); font-weight: 500; }
    &--run { color: var(--accent); font-weight: 500; }
    &--warn { color: var(--warn-text, #f59e0b); font-weight: 500; }
  }

  &__dot {
    width: 5px;
    height: 5px;
    border-radius: 50%;

    &--ok { background: var(--ok, #10b981); }
    &--err { background: var(--danger, #ef4444); }
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

  &__tabs {
    display: inline-flex;
    align-items: center;
    background: var(--bg-hover, rgba(0, 0, 0, 0.04));
    border-radius: 5px;
    padding: 1.5px;
    gap: 1.5px;
  }

  &__tab {
    height: 20px;
    padding: 0 7px;
    font-size: 11px;
    font-weight: 500;
    border-radius: 4px;
    border: none;
    background: transparent;
    color: var(--text-tertiary);
    cursor: pointer;
    transition: all 0.12s $ease;

    &:hover {
      color: var(--text-secondary);
    }

    &.is-active {
      background: var(--bg-card, var(--bg-elevated));
      color: var(--text);
      font-weight: 600;
      box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
    }
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

  &__code {
    margin: 0;
    padding: 7px 9px;
    font-family: $mono;
    font-size: 11px;
    line-height: 1.5;
    background: var(--bg-code, #f1f5f9);
    border: 1px solid var(--border);
    border-radius: 5px;
    color: var(--text-code, #0f172a);
    max-height: 260px;
    overflow: auto;
    white-space: pre-wrap;
    word-break: break-word;

    &--err {
      color: var(--danger-text, #ef4444);
      background: rgba(239, 68, 68, 0.04);
      border-color: rgba(239, 68, 68, 0.2);
    }
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
