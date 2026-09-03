<template>
  <div v-if="videos.length" class="tool-videos">
    <div v-for="(vid, i) in videos" :key="i" class="tool-videos__item">
      <video :src="vid.src" class="tool-videos__player" controls preload="metadata" :title="vid.name" />
      <button type="button" class="tool-videos__dl" title="下载视频" @click="download(vid)">
        <svg width="14" height="14" viewBox="0 0 16 16" fill="none">
          <path d="M8 2v8m0 0l3-3m-3 3L5 7" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
          <path d="M2.5 11.5v1a1.5 1.5 0 0 0 1.5 1.5h8a1.5 1.5 0 0 0 1.5-1.5v-1" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
        </svg>
      </button>
    </div>
  </div>
</template>

<script setup>
import { computed, inject } from 'vue'
import { useToolVideos } from '../composables/useToolVideos'

const props = defineProps({
  attachments: { type: Array, default: () => [] }
})

const sessionId = inject('sessionId', null)
const videoAtts = computed(() =>
  (props.attachments || []).filter((a) => a && a.type === 'video' && a.path)
)
const { videos } = useToolVideos(sessionId, videoAtts)

function download(vid) {
  const a = document.createElement('a')
  a.href = vid.src
  a.download = vid.name || 'video.mp4'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
}
</script>

<style scoped lang="scss">
.tool-videos {
  display: flex; flex-wrap: wrap; gap: 10px; padding-top: 2px;
}
.tool-videos__item {
  position: relative; width: 280px; max-width: 100%;
  border-radius: 10px; overflow: hidden;
  border: 1px solid var(--ai-fill-4);
  background: #111;
}
.tool-videos__player {
  width: 100%; display: block; max-height: 200px; background: #000;
}
.tool-videos__dl {
  position: absolute; right: 8px; bottom: 40px;
  width: 28px; height: 28px;
  display: flex; align-items: center; justify-content: center;
  border: none; border-radius: 8px; cursor: pointer;
  background: rgba(0, 0, 0, 0.55); color: #fff;
  opacity: 0; transition: opacity 0.15s ease;
  &:hover { background: rgba(0, 0, 0, 0.75); }
}
.tool-videos__item:hover .tool-videos__dl { opacity: 1; }
</style>
