import { ref, watch, onBeforeUnmount } from 'vue'
import axios from 'axios'
import { getToken } from '@/utils/auth'

const baseURL = import.meta.env.VITE_APP_BASE_API

/**
 * 把工具步骤产出的图片附件加载成可直接渲染的 objectURL。
 *
 * <p>生图工具产出的图片落在会话沙箱,附件只存了相对 path。{@code <img src>}
 * 无法携带 token,所以这里用 axios + token 走工作区下载接口拉 blob,
 * 再 {@code URL.createObjectURL} 转成本地地址供内联显示。
 *
 * @param sessionIdRef    当前会话 ID 的 ref(由聊天页 provide 的 sessionId)
 * @param imageAttachments 图片附件数组的 computed({type:'image',path,name,mime,...})
 * @returns { images } 加载完成的 {src,name,...} 列表
 */
export function useToolImages(sessionIdRef, imageAttachments) {
  const images = ref([])
  // 记录创建的 objectURL,组件卸载时释放,避免内存泄漏
  const objectUrls = []

  function revoke() {
    objectUrls.forEach((u) => { try { URL.revokeObjectURL(u) } catch (e) { /* noop */ } })
    objectUrls.length = 0
  }

  async function load() {
    revoke()
    images.value = []
    const sid = sessionIdRef ? sessionIdRef.value : null
    const atts = imageAttachments.value
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
        const mime = att.mime || 'image/png'
        const blob = new Blob([res.data], { type: mime })
        const objUrl = URL.createObjectURL(blob)
        objectUrls.push(objUrl)
        loaded.push({ ...att, src: objUrl })
      } catch (e) {
        // 单张图片加载失败不阻断其余附件展示
        console.warn('加载生图附件失败:', att.path, e)
      }
    }
    images.value = loaded
  }

  // 附件变化或会话切换时重新加载。
  // 必须指纹到 path 列表，不能只 watch length：
  // 流式 tool_end 写入 attachments 后 length 从 0→N 会触发；若仅 length，
  // 同数量替换 path 时不会重载；deep 数组内容变化也要覆盖。
  watch(
    [
      () => {
        const atts = imageAttachments && imageAttachments.value
        if (!atts || !atts.length) return ''
        return atts.map((a) => (a && a.path) || '').join('\0')
      },
      () => (sessionIdRef ? sessionIdRef.value : null)
    ],
    load,
    { immediate: true }
  )

  onBeforeUnmount(revoke)

  return { images }
}
