<template>
  <div class="kb-chunks">
    <div class="kb-chunks__label">
      检索到 {{ total }} 个片段<span v-if="omitted">，仅展示前 {{ hits.length }} 个</span> · 点击查看原文
    </div>
    <div
      v-for="(h, i) in hits"
      :key="h.chunkId || i"
      class="kb-chunk"
      :class="{ 'is-open': isOpen(i) }"
      role="button"
      tabindex="0"
      @click="toggle(i)"
      @keydown.enter="toggle(i)"
      @keydown.space.prevent="toggle(i)"
    >
      <div class="kb-chunk__head">
        <span class="kb-chunk__idx">#{{ h.index }}</span>
        <span class="kb-chunk__doc">《{{ h.docName || '未知文档' }}》</span>
        <span v-if="h.headingPath" class="kb-chunk__path">{{ h.headingPath }}</span>
        <span v-if="h.channel" class="kb-chunk__channel">{{ h.channel }}</span>
        <span class="kb-chunk__chev" aria-hidden="true">
          <svg width="9" height="9" viewBox="0 0 12 12" fill="none"><path d="M4 2.5L8 6l-4 3.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </span>
      </div>
      <div v-if="isOpen(i)" class="kb-chunk__body">
        <pre class="kb-chunk__content">{{ h.content }}</pre>
        <button type="button" class="kb-chunk__close" @click.stop="toggle(i)">收起片段</button>
      </div>
    </div>
    <div v-if="omitted" class="kb-chunks__omitted">… 其余 {{ total - hits.length }} 个片段已省略</div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'

const props = defineProps({
  /** parseKbHits 的结果(可能已截断) */
  hits: { type: Array, default: () => [] },
  /** 实际片段总数(未截断),用于省略提示 */
  total: { type: Number, default: 0 }
})

/** 是否省略了部分片段 */
const omitted = computed(() => props.total > props.hits.length)

/** 展开集合:默认全部收起,点击片段卡片展开原文 */
const openSet = ref(new Set())
function isOpen(i) { return openSet.value.has(i) }
function toggle(i) {
  const next = new Set(openSet.value)
  if (next.has(i)) next.delete(i)
  else next.add(i)
  openSet.value = next
}
</script>

<style scoped lang="scss">
@use '../../ai-tokens.scss' as *;

.kb-chunks {
  display: flex; flex-direction: column; gap: 6px; margin: 2px 0 4px;
}
.kb-chunks__label {
  font-size: $ai-fs-5; color: $ai-text3; padding: 2px 0;
}
.kb-chunk {
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg-elevated);
  cursor: pointer;
  transition: border-color 0.15s;
  &:hover { border-color: var(--border-strong); }
  &.is-open { border-color: var(--border-strong); }
}
.kb-chunk__head {
  display: flex; align-items: center; gap: 6px;
  padding: 6px 10px; font-size: $ai-fs-5; color: $ai-text2;
  min-width: 0;
}
.kb-chunk__idx {
  font-family: ui-monospace, Menlo, monospace;
  color: $blue; font-weight: 600; flex-shrink: 0;
}
.kb-chunk__doc {
  color: $ai-text; font-weight: 500; white-space: nowrap; overflow: hidden;
  text-overflow: ellipsis; flex-shrink: 1; min-width: 0;
}
.kb-chunk__path {
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; flex-shrink: 1; min-width: 0;
}
.kb-chunk__channel {
  background: rgba(94, 92, 230, 0.08); color: $blue; border-radius: 6px;
  padding: 1px 6px; font-size: 11px; flex-shrink: 0;
}
.kb-chunk__chev {
  margin-left: auto; display: inline-flex; color: $ai-text3;
  transition: transform 0.2s;
  .kb-chunk.is-open & { transform: rotate(90deg); }
}
.kb-chunk__body { padding: 0 10px 8px; border-top: 1px dashed var(--border); }
.kb-chunk__content {
  margin: 8px 0 6px; white-space: pre-wrap; word-break: break-word;
  font-family: -apple-system, BlinkMacSystemFont, "PingFang SC", sans-serif;
  font-size: $ai-fs-5; line-height: 1.6; color: $ai-text-think;
  max-height: 320px; overflow: auto;
}
.kb-chunk__close {
  border: 0; background: transparent; color: $ai-text3; font-size: 12px;
  padding: 2px 0; cursor: pointer;
  &:hover { color: $blue; }
}
.kb-chunks__omitted {
  font-size: $ai-fs-5; color: $ai-text3; padding: 2px 0 2px 2px;
}
</style>
