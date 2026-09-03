<script setup>
import { ref, toRef, watch } from 'vue'
import { useDialogLifecycle } from '../composables/useDialogLifecycle'

const props = defineProps({
  visible: { type: Boolean, default: false },
  // 编辑模式传入;新建传 null
  project: { type: Object, default: null }
})

const emit = defineEmits(['close', 'save'])

const name = ref('')
const description = ref('')
const dialogRoot = ref(null)

useDialogLifecycle(toRef(props, 'visible'), () => emit('close'), dialogRoot)

watch(
  () => props.visible,
  (v) => {
    if (v) {
      name.value = props.project?.projectName || ''
      description.value = props.project?.description || ''
    }
  },
  { immediate: true }
)

function submit() {
  if (!name.value.trim()) return
  emit('save', {
    projectId: props.project?.projectId,
    projectName: name.value.trim(),
    description: description.value.trim()
  })
}
</script>

<template>
  <Teleport to="body">
    <Transition name="app-modal">
      <div v-if="visible" ref="dialogRoot" class="project-dialog-mask" @click.self="emit('close')">
        <div class="project-dialog" data-dialog-surface role="dialog" aria-modal="true" :aria-label="project ? '编辑项目' : '新建项目'">
      <div class="project-dialog__head">
        <div>
          <strong>{{ project ? '编辑项目' : '新建项目' }}</strong>
          <p>{{ project ? '更新项目名称或说明。' : '项目用于归类后续创建的对话。' }}</p>
        </div>
        <button type="button" class="project-dialog__x" aria-label="关闭" @click="emit('close')">✕</button>
      </div>
      <label class="project-dialog__field">
        <span>项目名称 <em>*</em></span>
        <input v-model="name" placeholder="例如：产品官网改版" maxlength="100" autofocus @keydown.enter.prevent="submit" />
      </label>
      <label class="project-dialog__field">
        <span>项目描述</span>
        <textarea v-model="description" placeholder="可选，记录项目目标、背景或协作说明" maxlength="500" rows="4" />
      </label>
      <div class="project-dialog__foot">
        <button type="button" class="project-dialog__cancel" @click="emit('close')">取消</button>
        <button type="button" class="project-dialog__ok" :disabled="!name.trim()" @click="submit">保存项目</button>
      </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.project-dialog-mask {
  position: fixed;
  inset: 0;
  z-index: 100;
  padding: 20px;
  background: var(--ai-overlay, rgba(15, 23, 42, 0.42));
  backdrop-filter: blur(3px);
  display: flex;
  align-items: center;
  justify-content: center;
}
.project-dialog {
  width: min(100%, 420px);
  border-radius: 16px;
  background: var(--bg-raised);
  border: 1px solid var(--border-strong);
  box-shadow: var(--shadow);
  padding: 22px;
}
.project-dialog__head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 22px;
  color: var(--text);
}
.project-dialog__head strong { display: block; font-size: 17px; line-height: 1.35; letter-spacing: -0.015em; }
.project-dialog__head p { margin: 4px 0 0; color: var(--text-tertiary); font-size: 12.5px; line-height: 1.5; }
.project-dialog__x {
  display: grid; place-items: center; width: 28px; height: 28px; padding: 0;
  border: 1px solid transparent; border-radius: 7px; background: transparent;
  color: var(--text-tertiary); cursor: pointer; font-size: 14px;
}
.project-dialog__x:hover { color: var(--text); background: var(--bg-hover); border-color: var(--border); }
.project-dialog__field { display: flex; flex-direction: column; gap: 7px; margin-bottom: 15px; font-size: 12.5px; font-weight: 600; color: var(--text-secondary); }
.project-dialog__field em { color: var(--danger); font-style: normal; }
.project-dialog__field input, .project-dialog__field textarea {
  width: 100%; border: 1px solid var(--border); border-radius: 9px; padding: 10px 11px;
  background: var(--bg-input, var(--bg)); color: var(--text); font-family: inherit; font-size: 13px; line-height: 1.5; outline: none;
}
.project-dialog__field textarea { resize: vertical; min-height: 92px; }
.project-dialog__field input::placeholder, .project-dialog__field textarea::placeholder { color: var(--text-tertiary); }
.project-dialog__field input:focus, .project-dialog__field textarea:focus { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-weak); }
.project-dialog__foot { display: flex; justify-content: flex-end; gap: 9px; margin-top: 22px; }
.project-dialog__cancel {
  height: 34px; border: 1px solid var(--border); background: transparent; color: var(--text-secondary);
  border-radius: 8px; padding: 0 14px; cursor: pointer;
}
.project-dialog__cancel:hover { color: var(--text); background: var(--bg-hover); }
.project-dialog__ok {
  height: 34px; border: none; background: var(--accent); color: #fff; border-radius: 8px; padding: 0 16px; font-weight: 650; cursor: pointer;
  box-shadow: 0 2px 8px rgba(37, 99, 235, 0.24);
}
.project-dialog__ok:hover:not(:disabled) { background: var(--accent-hover); }
.project-dialog__ok:disabled { opacity: .48; cursor: not-allowed; box-shadow: none; }
</style>
