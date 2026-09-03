<template>
  <button
    type="button"
    class="apple-btn"
    :class="[`apple-btn--${variant}`]"
    :disabled="disabled"
    v-bind="$attrs"
  >
    <slot />
  </button>
</template>

<script setup>
defineProps({
  /** primary | ghost | danger */
  variant: { type: String, default: 'ghost' },
  disabled: Boolean
})
</script>

<style scoped lang="scss">
@use '../../assets/styles/ai-tokens.scss' as *;

.apple-btn {
  display: inline-flex; align-items: center; gap: 6px; font-family: $font; font-size: 14px; font-weight: 500;
  border: none; border-radius: 980px; padding: 8px 16px; cursor: pointer; transition: all 0.2s $ease; outline: none;
  &:active { transform: scale(0.96); }
  &--primary {
    background: $blue; color: #fff; box-shadow: 0 2px 10px rgba(10,132,255,0.32);
    &:hover { background: #0071e3; }
    &:disabled { opacity: 0.5; cursor: not-allowed; }
  }
  &--ghost {
    background: transparent; color: $blue; padding: 8px 14px;
    &:hover { background: rgba(10,132,255,0.08); }
    &:disabled { opacity: 0.4; cursor: not-allowed; }
  }
  &--danger {
    background: $red; color: #fff; padding: 8px 16px; box-shadow: 0 2px 10px rgba(255,59,48,0.3);
    &:hover { background: #E22A20; }
    &:disabled { opacity: 0.5; cursor: not-allowed; }
  }
}
</style>
