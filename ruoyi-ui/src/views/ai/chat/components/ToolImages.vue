<template>
  <div v-if="images.length" class="tool-images">
    <!-- 方形缩略图:点击开全屏预览,悬停出现下载按钮 -->
    <div v-for="(img, i) in images" :key="i" class="tool-images__item">
      <img :src="img.src" :alt="img.name" class="tool-images__thumb" @click="openPreview(i)" />
      <button type="button" class="tool-images__dl" title="下载图片" @click.stop="download(img)">
        <svg width="14" height="14" viewBox="0 0 16 16" fill="none">
          <path d="M8 2v8m0 0l3-3m-3 3L5 7" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
          <path d="M2.5 11.5v1a1.5 1.5 0 0 0 1.5 1.5h8a1.5 1.5 0 0 0 1.5-1.5v-1" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
        </svg>
      </button>
    </div>

    <!-- 全屏预览:Teleport 到 body,摆脱聊天区祖先的 transform/overflow 影响,
         否则 position:fixed 会相对聊天区定位,变成"弹在聊天区里要往上滚" -->
    <Teleport to="body">
      <div v-if="previewIndex >= 0 && images[previewIndex]" class="img-preview" @click.self="closePreview">
        <img :src="images[previewIndex].src" :alt="images[previewIndex].name" class="img-preview__img" />

        <!-- 顶部工具栏:下载 / 关闭 -->
        <div class="img-preview__bar">
          <span class="img-preview__name">{{ images[previewIndex].name }}</span>
          <button type="button" class="img-preview__btn" title="下载图片" @click="download(images[previewIndex])">
            <svg width="15" height="15" viewBox="0 0 16 16" fill="none">
              <path d="M8 2v8m0 0l3-3m-3 3L5 7" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
              <path d="M2.5 11.5v1a1.5 1.5 0 0 0 1.5 1.5h8a1.5 1.5 0 0 0 1.5-1.5v-1" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
            </svg>
            下载
          </button>
          <button type="button" class="img-preview__btn" title="关闭 (Esc)" @click="closePreview">
            <svg width="14" height="14" viewBox="0 0 16 16" fill="none">
              <path d="M4 4l8 8M12 4l-8 8" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
            </svg>
          </button>
        </div>

        <!-- 多张时左右切换 -->
        <template v-if="images.length > 1">
          <button v-if="previewIndex > 0" type="button" class="img-preview__nav img-preview__nav--prev" title="上一张 (←)" @click="prev">
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><path d="M10 3L5 8l5 5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </button>
          <button v-if="previewIndex < images.length - 1" type="button" class="img-preview__nav img-preview__nav--next" title="下一张 (→)" @click="next">
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><path d="M6 3l5 5-5 5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </button>
        </template>
      </div>
    </Teleport>
  </div>
</template>

<script setup>
import { ref, computed, inject, watch, onMounted, onBeforeUnmount } from 'vue'
import { useToolImages } from '../composables/useToolImages'

const props = defineProps({
  /** 工具产出的附件数组(来自 step.attachments),组件内部自行筛出图片 */
  attachments: { type: Array, default: () => [] }
})

// 当前会话 ID(聊天页 provide),下载附件要拼工作区接口
const sessionId = inject('sessionId', null)

const imageAtts = computed(() =>
  (props.attachments || []).filter((a) => a && a.type === 'image' && a.path)
)
const { images } = useToolImages(sessionId, imageAtts)

// ============ 全屏预览 ============
const previewIndex = ref(-1)
function openPreview(i) { previewIndex.value = i }
function closePreview() { previewIndex.value = -1 }
function prev() { if (previewIndex.value > 0) previewIndex.value-- }
function next() { if (previewIndex.value < images.value.length - 1) previewIndex.value++ }

function onKey(e) {
  if (previewIndex.value < 0) return
  if (e.key === 'Escape') closePreview()
  else if (e.key === 'ArrowLeft') prev()
  else if (e.key === 'ArrowRight') next()
}
onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKey)
  document.body.style.overflow = ''
})

// 预览打开时锁住背景滚动,别让背后的聊天区还能滚
watch(previewIndex, (v) => {
  document.body.style.overflow = v >= 0 ? 'hidden' : ''
})

// ============ 下载 ============
// images[].src 已是本地 objectURL,直接触发浏览器下载即可,无需再走一次鉴权请求
function download(img) {
  const a = document.createElement('a')
  a.href = img.src
  a.download = img.name || 'image.png'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
}
</script>

<style scoped lang="scss">
.tool-images {
  display: flex; flex-wrap: wrap; gap: 10px; padding-top: 2px;
}
.tool-images__item {
  position: relative; width: 160px; height: 160px;
  border-radius: 10px; overflow: hidden;
  border: 1px solid var(--ai-fill-4);
  background: var(--ai-fill-1);
}
.tool-images__thumb {
  width: 100%; height: 100%; object-fit: cover;
  display: block; cursor: zoom-in;
}
.tool-images__dl {
  position: absolute; right: 8px; bottom: 8px;
  width: 28px; height: 28px;
  display: flex; align-items: center; justify-content: center;
  border: none; border-radius: 8px; cursor: pointer;
  background: rgba(0, 0, 0, 0.55); color: #fff;
  opacity: 0; transition: opacity 0.15s ease;
  &:hover { background: rgba(0, 0, 0, 0.75); }
}
.tool-images__item:hover .tool-images__dl { opacity: 1; }
</style>

<!-- 预览层样式:Teleport 到 body 后脱离了本页作用域,scoped 选择器够不着,
     必须用非 scoped 块;类名带 img-preview 前缀防污染 -->
<style lang="scss">
.img-preview {
  position: fixed; inset: 0; z-index: 3000;
  background: rgba(0, 0, 0, 0.78);
  display: flex; align-items: center; justify-content: center;
}
.img-preview__img {
  max-width: 90vw; max-height: 86vh;
  object-fit: contain; border-radius: 6px;
  box-shadow: 0 8px 40px rgba(0, 0, 0, 0.5);
}
.img-preview__bar {
  position: absolute; top: 16px; right: 20px;
  display: flex; align-items: center; gap: 10px;
}
.img-preview__name {
  max-width: 320px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  font-size: 12px; color: var(--ai-search-bg);
}
.img-preview__btn {
  display: inline-flex; align-items: center; gap: 5px;
  padding: 7px 12px; border: none; border-radius: 8px; cursor: pointer;
  background: rgba(255, 255, 255, 0.14); color: #fff; font-size: 13px;
  transition: background 0.15s ease;
  &:hover { background: rgba(255, 255, 255, 0.26); }
}
.img-preview__nav {
  position: absolute; top: 50%; transform: translateY(-50%);
  width: 40px; height: 40px;
  display: flex; align-items: center; justify-content: center;
  border: none; border-radius: 50%; cursor: pointer;
  background: rgba(255, 255, 255, 0.14); color: #fff;
  transition: background 0.15s ease;
  &:hover { background: rgba(255, 255, 255, 0.28); }
  &--prev { left: 20px; }
  &--next { right: 20px; }
}
</style>
