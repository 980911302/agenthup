<script setup>
import { computed, ref } from 'vue'
import { confirmState, resolveConfirm } from '../utils/confirm'
import { useDialogLifecycle } from '../composables/useDialogLifecycle'

const visible = computed(() => confirmState.visible)
const confirmRoot = ref(null)
useDialogLifecycle(visible, onCancel, confirmRoot)

function onOk() {
  resolveConfirm(true)
}
function onCancel() {
  resolveConfirm(false)
}
</script>

<template>
  <Teleport to="body">
    <Transition name="app-modal">
      <div v-if="visible" ref="confirmRoot" class="confirm-mask" @click.self="onCancel">
        <div class="confirm-card" data-dialog-surface role="dialog" aria-modal="true">
          <h3 class="confirm-title">{{ confirmState.title }}</h3>
          <pre class="confirm-message">{{ confirmState.message }}</pre>
          <div class="confirm-actions">
            <button type="button" class="btn btn-ghost" @click="onCancel">{{ confirmState.cancelLabel }}</button>
            <button type="button" class="btn btn-primary" @click="onOk">{{ confirmState.okLabel }}</button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.confirm-mask {
  position: fixed;
  inset: 0;
  /* 全局确认必须高于设置、工作区和预览等所有业务弹层。 */
  z-index: 4000;
  background: rgba(0, 0, 0, 0.6);
  backdrop-filter: blur(2px);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}
.confirm-card {
  width: 100%;
  max-width: 440px;
  background: var(--bg-raised);
  border: 1px solid var(--border-strong);
  border-radius: 14px;
  padding: 22px 24px;
  box-shadow: var(--shadow);
}
.confirm-title {
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 12px;
}
.confirm-message {
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  font-size: 13.5px;
  line-height: 1.7;
  color: var(--text-secondary);
  max-height: 40vh;
  overflow: auto;
  margin-bottom: 20px;
}
.confirm-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}
.btn {
  border: 1px solid transparent;
  border-radius: 9px;
  padding: 8px 18px;
  font-size: 14px;
}
.btn-ghost {
  background: var(--bg-elevated);
  border-color: var(--border);
  color: var(--text-secondary);
}
.btn-ghost:hover {
  background: var(--bg-hover);
  color: var(--text);
}
.btn-primary {
  background: var(--accent);
  color: #fff;
}
.btn-primary:hover {
  background: var(--accent-hover);
}
.confirm-enter-active,
.confirm-leave-active {
  transition: opacity 0.18s ease;
}
.confirm-enter-active .confirm-card,
.confirm-leave-active .confirm-card {
  transition: transform 0.18s ease;
}
.confirm-enter-from,
.confirm-leave-to {
  opacity: 0;
}
.confirm-enter-from .confirm-card,
.confirm-leave-to .confirm-card {
  transform: translateY(8px) scale(0.98);
}
</style>
