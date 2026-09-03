import { ref, watch, onBeforeUnmount } from 'vue'
import axios from 'axios'
import { getToken } from '@/utils/auth'

const baseURL = import.meta.env.VITE_APP_BASE_API

export function useToolAudios(sessionIdRef, audioAttachments) {
  const audios = ref([])
  const objectUrls = []

  function revoke() {
    objectUrls.forEach((u) => { try { URL.revokeObjectURL(u) } catch (e) { /* noop */ } })
    objectUrls.length = 0
  }

  async function load() {
    revoke()
    audios.value = []
    const sid = sessionIdRef ? sessionIdRef.value : null
    const atts = audioAttachments.value
    if (!sid || !atts || !atts.length) return

    const loaded = []
    for (const att of atts) {
      try {
        const url = baseURL + '/ai/chat/workspace/' + sid + '/download?path=' + encodeURIComponent(att.path)
        const res = await axios({
          method: 'get',
          url,
          responseType: 'blob',
          headers: { 'Authorization': 'Bearer ' + getToken() }
        })
        const mime = att.mime || 'audio/mpeg'
        const blob = new Blob([res.data], { type: mime })
        const objUrl = URL.createObjectURL(blob)
        objectUrls.push(objUrl)
        loaded.push({ ...att, src: objUrl })
      } catch (e) {
        console.warn('加载语音附件失败:', att.path, e)
      }
    }
    audios.value = loaded
  }

  watch(
    [
      () => {
        const atts = audioAttachments && audioAttachments.value
        if (!atts || !atts.length) return ''
        return atts.map((a) => (a && a.path) || '').join('\0')
      },
      () => (sessionIdRef ? sessionIdRef.value : null)
    ],
    load,
    { immediate: true }
  )

  onBeforeUnmount(revoke)

  return { audios }
}
