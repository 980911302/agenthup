<template>
  <Teleport to="body">
    <Transition name="gvs">
      <div v-if="open" class="gvs-overlay" @click.self="close">
        <div
          class="gvs-sheet"
          role="dialog"
          aria-modal="true"
          :aria-label="title"
        >
          <header class="gvs-sheet__header">
            <div class="gvs-sheet__ident">
              <div class="gvs-sheet__icon" aria-hidden="true">
                <svg width="18" height="18" viewBox="0 0 16 16" fill="none">
                  <circle cx="4" cy="4" r="1.7" stroke="currentColor" stroke-width="1.3"/>
                  <circle cx="12" cy="5" r="1.7" stroke="currentColor" stroke-width="1.3"/>
                  <circle cx="8" cy="12" r="1.7" stroke="currentColor" stroke-width="1.3"/>
                  <path d="M5.5 4.9l5 .5M5.2 5.5l2.2 5M11.1 6.3L9 10.5" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/>
                </svg>
              </div>
              <div class="gvs-sheet__titles">
                <div class="gvs-sheet__title-row">
                  <h2 class="gvs-sheet__title">{{ title }}</h2>
                  <span class="gvs-sheet__scope" :class="isDocScope ? 'is-doc' : 'is-kb'">
                    {{ isDocScope ? '单文件' : '全库' }}
                  </span>
                </div>
                <p v-if="subtitle" class="gvs-sheet__sub" :title="subtitle">{{ subtitle }}</p>
              </div>
            </div>
            <button type="button" class="gvs-sheet__close" aria-label="关闭" @click="close">
              <svg width="12" height="12" viewBox="0 0 12 12" fill="none" aria-hidden="true">
                <path d="M2 2l8 8M10 2L2 10" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/>
              </svg>
            </button>
          </header>

          <div class="gvs-sheet__body">
            <GraphExplore
              v-if="open && kbId"
              :kb-id="kbId"
              :doc-id="docId"
              :doc-ids="docIds"
              embedded
            />
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
import GraphExplore from './GraphExplore.vue'

const props = defineProps({
  open: { type: Boolean, default: false },
  kbId: { type: [Number, String], required: true },
  docId: { type: [Number, String], default: null },
  docIds: { type: Array, default: null },
  docName: { type: String, default: '' }
})

const emit = defineEmits(['update:open', 'close'])

const isDocScope = computed(() =>
  !!(props.docId || (props.docIds && props.docIds.length))
)

const title = computed(() => (isDocScope.value ? '文件知识图谱' : '知识库图谱'))

const subtitle = computed(() => {
  if (props.docName) return props.docName
  if (props.docId) return `文档 #${props.docId}`
  return '3D 力导向图谱 · 拖拽旋转 · 滚轮缩放 · 点选节点'
})

function close() {
  emit('update:open', false)
  emit('close')
}
</script>

<style scoped lang="scss">
@use '../../../../assets/styles/ai-tokens.scss' as *;

.gvs-overlay {
  position: fixed;
  inset: 0;
  z-index: 2200;
  background: var(--ai-overlay);
  backdrop-filter: blur(6px);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 28px 18px;
  box-sizing: border-box;
}

.gvs-sheet {
  width: min(1120px, 96vw);
  height: min(880px, 94vh);
  background: var(--ai-page-base, var(--ai-sheet-bg));
  border-radius: 20px;
  box-shadow: var(--ai-shadow-sheet);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  font-family: $font;
  border: 1px solid var(--ai-border);

  &__header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 14px;
    padding: 16px 20px;
    flex-shrink: 0;
    background: var(--ai-card-bg);
    border-bottom: 1px solid var(--ai-border);
  }
  &__ident {
    display: flex;
    align-items: center;
    gap: 12px;
    min-width: 0;
    flex: 1;
  }
  &__icon {
    width: 40px;
    height: 40px;
    border-radius: 12px;
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
    color: #fff;
    background: linear-gradient(135deg, #0A84FF, #0071e3);
    box-shadow: 0 3px 10px rgba(10, 132, 255, 0.28);
  }
  &__titles { min-width: 0; flex: 1; }
  &__title-row {
    display: flex;
    align-items: center;
    gap: 8px;
    flex-wrap: wrap;
  }
  &__title {
    margin: 0;
    font-size: 17px;
    font-weight: 700;
    color: $text;
    letter-spacing: -0.25px;
  }
  &__scope {
    font-size: 11px;
    font-weight: 600;
    padding: 2px 8px;
    border-radius: 980px;
    &.is-kb {
      color: $blue;
      background: rgba(10, 132, 255, 0.1);
    }
    &.is-doc {
      color: $blue;
      background: rgba(10, 132, 255, 0.1);
    }
  }
  &__sub {
    margin: 3px 0 0;
    font-size: 12.5px;
    color: $text2;
    line-height: 1.4;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  &__close {
    width: 30px;
    height: 30px;
    border: none;
    border-radius: 50%;
    background: var(--ai-fill-2);
    color: $gray;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
    transition: all 0.15s $ease;
    &:hover { background: var(--ai-hover-strong); color: $text; }
  }
  &__body {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
    padding: 12px 14px 14px;
    overflow: hidden;
  }
}

.gvs-enter-active { transition: all 0.32s cubic-bezier(0.34, 1.56, 0.64, 1); }
.gvs-leave-active { transition: all 0.2s ease-in; }
.gvs-enter-from {
  opacity: 0;
  .gvs-sheet { transform: scale(0.94) translateY(14px); opacity: 0; }
}
.gvs-leave-to {
  opacity: 0;
  .gvs-sheet { transform: scale(0.97); opacity: 0; }
}
</style>
