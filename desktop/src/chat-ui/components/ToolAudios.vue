<template>
  <div v-if="audios.length" class="tool-audios">
    <div v-for="(aud, i) in audios" :key="i" class="tool-audios__item">
      <audio :src="aud.src" class="tool-audios__player" controls preload="metadata" :title="aud.name" />
      <button type="button" class="tool-audios__dl" title="下载语音" @click="download(aud)">下载</button>
    </div>
  </div>
</template>

<script setup>
import { computed, inject } from 'vue'
import { useToolAudios } from '../composables/useToolAudios'

const props = defineProps({
  attachments: { type: Array, default: () => [] }
})

const sessionId = inject('sessionId', null)
const audioAtts = computed(() =>
  (props.attachments || []).filter((a) => a && a.type === 'audio' && a.path)
)
const { audios } = useToolAudios(sessionId, audioAtts)

function download(aud) {
  const a = document.createElement('a')
  a.href = aud.src
  a.download = aud.name || 'audio.mp3'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
}
</script>

<style scoped lang="scss">
.tool-audios { display: flex; flex-direction: column; gap: 8px; padding-top: 2px; }
.tool-audios__item {
  display: flex; align-items: center; gap: 8px;
  max-width: 420px; padding: 8px 10px;
  border-radius: 10px; border: 1px solid var(--ai-fill-4);
  background: var(--ai-fill-1);
}
.tool-audios__player { flex: 1; min-width: 0; height: 36px; }
.tool-audios__dl {
  border: none; background: transparent; cursor: pointer;
  font-size: 12px; color: var(--ai-text3, #8e8e93);
  &:hover { color: #0A84FF; }
}
</style>
