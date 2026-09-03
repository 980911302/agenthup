import { ref, watch, onBeforeUnmount } from 'vue'
import axios from 'axios'
import { getToken } from '../../utils/auth'

const baseURL = import.meta.env.VITE_APP_BASE_API

/**
 * 把工具步骤产出的视频附件加载成可播放的 objectURL。
 * 与 useToolImages 平行:走工作区下载接口带 token 拉 blob。
 */
export function useToolVideos(sessionIdRef, videoAttachments) {
  const videos = ref([])
  const objectUrls = []

  function revoke() {
    objectUrls.forEach((u) => { try { URL.revokeObjectURL(u) } catch (e) { /* noop */ } })
    objectUrls.length = 0
  }

  async function load() {
    revoke()
    videos.value = []
    const sid = sessionIdRef ? sessionIdRef.value : null
    const atts = videoAttachments.value
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
        const mime = att.mime || 'video/mp4'
        const blob = new Blob([res.data], { type: mime })
        const objUrl = URL.createObjectURL(blob)
        objectUrls.push(objUrl)
        loaded.push({ ...att, src: objUrl })
      } catch (e) {
        console.warn('加载视频附件失败:', att.path, e)
      }
    }
    videos.value = loaded
  }

  watch(
    [
      () => {
        const atts = videoAttachments && videoAttachments.value
        if (!atts || !atts.length) return ''
        return atts.map((a) => (a && a.path) || '').join('\0')
      },
      () => (sessionIdRef ? sessionIdRef.value : null)
    ],
    load,
    { immediate: true }
  )

  onBeforeUnmount(revoke)

  return { videos }
}
