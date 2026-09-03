<template>
  <section v-if="changes && changes.length" class="turn-changes">
    <button type="button" class="turn-changes__head" :aria-expanded="expanded" @click="expanded = !expanded">
      <span class="turn-changes__icon">
        <svg width="14" height="14" viewBox="0 0 16 16" fill="none"><path d="M2.2 3.5h11.6M2.2 8h8.2M2.2 12.5h5" stroke="currentColor" stroke-width="1.45" stroke-linecap="round"/><path d="m11.3 9.8 2.5 2.5-2.5 2.5" stroke="currentColor" stroke-width="1.35" stroke-linecap="round" stroke-linejoin="round"/></svg>
      </span>
      <span>本轮文件变更</span>
      <span class="turn-changes__count">{{ counts.total }} 项</span>
      <span class="turn-changes__summary">{{ summary }}</span>
      <svg class="turn-changes__chevron" :class="{ 'is-open': expanded }" width="11" height="11" viewBox="0 0 12 12" fill="none"><path d="M4 2.5 8 6l-4 3.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
    </button>
    <div v-if="expanded" class="turn-changes__list">
      <div
        v-for="change in changes"
        :key="change.path"
        class="turn-change"
        :class="[`is-${normalizedOp(change.operation)}`, { 'is-clickable': change.operation !== 'DELETE' }]"
        :title="change.operation === 'DELETE' ? `${change.path} (已删除)` : `点击预览 ${change.path}`"
        @click="onClickChange(change)"
      >
        <span class="turn-change__operation">{{ operationMark(change.operation) }}</span>
        <span class="turn-change__path">{{ change.path }}</span>
        <span v-if="change.size != null && change.operation !== 'DELETE'" class="turn-change__size">{{ formatSize(change.size) }}</span>
      </div>
      <p v-if="truncated" class="turn-changes__notice">文件过多，仅展示本轮已采集的变更。</p>
    </div>

    <!-- 工作区文件内容预览弹窗 -->
    <WorkspacePreviewModal
      v-if="previewVisible"
      :visible="previewVisible"
      :session-id="sessionId"
      :path="previewPath"
      @close="previewVisible = false"
    />
  </section>
</template>

<script setup>
import { computed, inject, ref } from 'vue'
import { workspaceChangeCounts } from '../composables/workspaceChanges'
import WorkspacePreviewModal from '../../components/WorkspacePreviewModal.vue'

const props = defineProps({
  changes: { type: Array, default: () => [] },
  truncated: { type: Boolean, default: false },
  sessionId: { type: [String, Number], default: null }
})

const openWorkspaceFile = inject('openWorkspaceFile', null)

const expanded = ref(false)
const previewVisible = ref(false)
const previewPath = ref('')

const counts = computed(() => workspaceChangeCounts(props.changes))
const summary = computed(() => [
  counts.value.created ? `新增 ${counts.value.created}` : '',
  counts.value.modified ? `修改 ${counts.value.modified}` : '',
  counts.value.deleted ? `删除 ${counts.value.deleted}` : ''
].filter(Boolean).join(' · '))

function normalizedOp(operation) {
  const op = String(operation || '').toUpperCase()
  if (op === 'CREATE' || op === 'ADD' || op === 'NEW') return 'create'
  if (op === 'DELETE' || op === 'REMOVE') return 'delete'
  return 'modify'
}

function operationMark(operation) {
  const op = String(operation || '').toUpperCase()
  if (op === 'CREATE' || op === 'ADD' || op === 'NEW') return 'A'
  if (op === 'DELETE' || op === 'REMOVE') return 'D'
  return 'M'
}

function formatSize(bytes) {
  const value = Number(bytes) || 0
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1).replace(/\.0$/, '')} KB`
  return `${(value / 1024 / 1024).toFixed(1).replace(/\.0$/, '')} MB`
}

function onClickChange(change) {
  if (!change?.path || change.operation === 'DELETE') return
  if (openWorkspaceFile) {
    openWorkspaceFile(change.path)
  } else {
    previewPath.value = change.path
    previewVisible.value = true
  }
}
</script>

<style scoped lang="scss">
@use '../ai-tokens.scss' as *;

.turn-changes {
  max-width: 680px;
  margin-top: 9px;
  border: 1px solid var(--ai-border-2, rgba(148, 163, 196, 0.15));
  border-radius: 9px;
  background: var(--ai-fill-1, rgba(255, 255, 255, 0.04));
  overflow: hidden;

  &__head {
    width: 100%;
    min-height: 34px;
    padding: 7px 11px;
    display: flex;
    align-items: center;
    gap: 6px;
    border: 0;
    background: transparent;
    color: var(--ai-text2, #9aa4b8);
    cursor: pointer;
    font-size: 12px;
    text-align: left;
    user-select: none;
    transition: background 0.14s ease;

    &:hover {
      background: var(--ai-fill-2, rgba(255, 255, 255, 0.06));
    }
  }

  &__icon {
    display: inline-flex;
    color: var(--accent, #38bdf8);
    flex-shrink: 0;
  }

  &__count, &__summary {
    color: var(--ai-text3, #64708a);
    font-size: 11px;
  }

  &__summary {
    margin-left: auto;
  }

  &__chevron {
    flex-shrink: 0;
    color: var(--ai-text3, #64708a);
    transition: transform 0.16s ease;

    &.is-open {
      transform: rotate(90deg);
    }
  }

  &__list {
    padding: 4px 7px 7px;
    border-top: 1px solid var(--ai-border, rgba(148, 163, 196, 0.1));
    background: var(--ai-card-bg, #141824);
  }

  &__notice {
    margin: 5px 3px 0;
    color: var(--ai-text3, #64708a);
    font-size: 10.5px;
  }
}

.turn-change {
  min-height: 27px;
  padding: 4px 6px;
  display: flex;
  align-items: center;
  gap: 7px;
  color: var(--ai-text, #e8ecf4);
  border-radius: 4px;
  transition: all 0.14s ease;

  &.is-clickable {
    cursor: pointer;

    &:hover {
      background: var(--accent-weak, rgba(56, 189, 248, 0.12));

      .turn-change__path {
        color: var(--accent, #38bdf8);
        text-decoration: underline;
        text-underline-offset: 2px;
      }
    }
  }

  &__operation {
    width: 12px;
    flex-shrink: 0;
    color: #fbbf24;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 11px;
    font-weight: 700;
  }

  &.is-create &__operation {
    color: #34d399;
  }

  &.is-delete &__operation {
    color: #f87171;
  }

  &__path {
    min-width: 0;
    overflow: hidden;
    flex: 1;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 11.5px;
    line-height: 1.35;
    text-overflow: ellipsis;
    white-space: nowrap;
    transition: color 0.12s ease;
  }

  &__size {
    flex-shrink: 0;
    color: var(--ai-text3, #64708a);
    font-size: 10.5px;
    font-variant-numeric: tabular-nums;
  }
}
</style>
