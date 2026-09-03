<template>
  <div class="step step--summary">
    <div class="summary-divider">
      <span class="summary-line"></span>
      <span class="summary-text">{{ preview }}</span>
      <span class="summary-line"></span>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
const props = defineProps({ step: { type: Object, required: true } })
const preview = computed(() => {
  const t = props.step.text || ''
  return t.length > 40 ? t.slice(0, 40) + '…(摘要)' : (t || '已压缩为摘要')
})
</script>

<style scoped lang="scss">
@use '../../../../../assets/styles/ai-tokens.scss' as *;

.step--summary { margin: 10px 0; }
.summary-divider { display: flex; align-items: center; gap: 8px; }
.summary-line { flex: 1; height: 1px; background: var(--ai-fill-4); }
.summary-text { font-size: $ai-fs-6; color: $ai-text3; padding: 0 4px; }
</style>
