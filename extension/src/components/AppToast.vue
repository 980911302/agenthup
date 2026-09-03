<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'

const messages = ref([])
let id = 0

function onToast(e) {
  const msg = (e.detail && e.detail.message) || ''
  if (!msg) return
  const item = { id: ++id, text: msg }
  messages.value.push(item)
  setTimeout(() => dismiss(item.id), 3200)
}

function dismiss(id) {
  const index = messages.value.findIndex(m => m.id === id)
  if (index >= 0) messages.value.splice(index, 1)
}

onMounted(() => window.addEventListener('desk-toast', onToast))
onBeforeUnmount(() => window.removeEventListener('desk-toast', onToast))
</script>

<template>
  <div class="toasts">
    <TransitionGroup name="toast">
      <div v-for="m in messages" :key="m.id" class="toast" @click="dismiss(m.id)">
        {{ m.text }}
      </div>
    </TransitionGroup>
  </div>
</template>

<style scoped>
.toasts {
  position: fixed;
  top: 16px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 1000;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  pointer-events: none;
}
.toast {
  max-width: 480px;
  padding: 9px 16px;
  border-radius: 10px;
  background: var(--bg-elevated);
  border: 1px solid var(--border-strong);
  color: var(--text);
  font-size: 13.5px;
  line-height: 1.5;
  box-shadow: var(--shadow-soft);
  pointer-events: auto;
  cursor: pointer;
}
.toast-enter-active,
.toast-leave-active {
  transition: opacity 0.25s ease, transform 0.25s ease;
}
.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}
</style>
