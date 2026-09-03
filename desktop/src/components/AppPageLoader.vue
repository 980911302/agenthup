<script setup>
defineProps({
  label: { type: String, default: '正在加载…' },
  fullscreen: { type: Boolean, default: false },
  compact: { type: Boolean, default: false }
})
</script>

<template>
  <div
    class="app-page-loader"
    :class="{ 'app-page-loader--fullscreen': fullscreen, 'app-page-loader--compact': compact }"
    role="status"
    aria-live="polite"
    aria-busy="true"
  >
    <span class="app-page-loader__spinner" aria-hidden="true"></span>
    <span class="app-page-loader__label">{{ label }}</span>
    <span class="app-page-loader__line" aria-hidden="true"></span>
  </div>
</template>
