<template>
  <section v-if="changes.length" class="turn-changes">
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
      <div v-for="change in changes" :key="change.path" class="turn-change" :class="`is-${String(change.operation || '').toLowerCase()}`" :title="change.path">
        <span class="turn-change__operation">{{ operationMark(change.operation) }}</span>
        <span class="turn-change__path">{{ change.path }}</span>
        <span v-if="change.size != null && change.operation !== 'DELETE'" class="turn-change__size">{{ formatSize(change.size) }}</span>
      </div>
      <p v-if="truncated" class="turn-changes__notice">文件过多，仅展示本轮已采集的变更。</p>
    </div>
  </section>
</template>

<script setup>
import { computed, ref } from 'vue'
import { workspaceChangeCounts } from '../composables/workspaceChanges'

const props = defineProps({
  changes: { type: Array, default: () => [] },
  truncated: { type: Boolean, default: false }
})

const expanded = ref(false)
const counts = computed(() => workspaceChangeCounts(props.changes))
const summary = computed(() => [
  counts.value.created ? `新增 ${counts.value.created}` : '',
  counts.value.modified ? `修改 ${counts.value.modified}` : '',
  counts.value.deleted ? `删除 ${counts.value.deleted}` : ''
].filter(Boolean).join(' · '))

function operationMark(operation) {
  if (operation === 'CREATE') return 'A'
  if (operation === 'DELETE') return 'D'
  return 'M'
}

function formatSize(bytes) {
  const value = Number(bytes) || 0
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1).replace(/\.0$/, '')} KB`
  return `${(value / 1024 / 1024).toFixed(1).replace(/\.0$/, '')} MB`
}
</script>

<style scoped lang="scss">
@use '../../../../assets/styles/ai-tokens.scss' as *;

.turn-changes {
  max-width: 680px; margin-top: 9px; border: 1px solid var(--ai-border-2); border-radius: 9px; background: var(--ai-fill-1); overflow: hidden;
  &__head { width: 100%; min-height: 34px; padding: 7px 9px; display: flex; align-items: center; gap: 6px; border: 0; background: transparent; color: $text2; cursor: pointer; font: 12px $font; text-align: left; }
  &__icon { display: inline-flex; color: $blue; }
  &__count, &__summary { color: $gray; font-size: 11px; }
  &__summary { margin-left: auto; }
  &__chevron { flex-shrink: 0; color: $gray2; transition: transform .16s $ease; &.is-open { transform: rotate(90deg); } }
  &__list { padding: 4px 7px 7px; border-top: 1px solid var(--ai-border); }
  &__notice { margin: 5px 3px 0; color: $gray2; font-size: 10.5px; }
}

.turn-change {
  min-height: 27px; padding: 4px 5px; display: flex; align-items: center; gap: 7px; color: $text;
  &__operation { width: 12px; flex-shrink: 0; color: #b77819; font: 700 11px ui-monospace, SFMono-Regular, Menlo, monospace; }
  &.is-create &__operation { color: #17834d; }
  &.is-delete &__operation { color: #c23b3b; }
  &__path { min-width: 0; overflow: hidden; flex: 1; font: 11.5px/1.35 ui-monospace, SFMono-Regular, Menlo, monospace; text-overflow: ellipsis; white-space: nowrap; }
  &__size { flex-shrink: 0; color: $gray2; font-size: 10.5px; font-variant-numeric: tabular-nums; }
}
</style>
