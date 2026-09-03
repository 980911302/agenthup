import { ref, nextTick, watch } from 'vue'

/**
 * 消息区自动跟随滚动 composable。
 *
 * <p>流式输出时内容持续变长，必须跟着往上推，否则用户要一直手动滑 —— 这是聊天最基本的行为。
 * 但不能无脑强制拉到底：用户上滑翻历史时被拽回来更难受。
 * 所以只在「视口已经贴近底部」时跟随；一旦用户上滑就暂停，
 * 并露出「回到最新」按钮，点了(force=true)才恢复跟随。
 */
export function useScroll(options = {}) {
  const { getTurns } = options

  const bodyRef = ref(null)
  const autoFollow = ref(true)
  const showJumpLatest = ref(false)
  /** 距底部多少像素以内算「还在看最新」 */
  const FOLLOW_THRESHOLD = 90

  function onBodyScroll() {
    const el = bodyRef.value
    if (!el) return
    const distance = el.scrollHeight - el.scrollTop - el.clientHeight
    autoFollow.value = distance <= FOLLOW_THRESHOLD
    // 只有内容确实超出一屏时才提示，空对话不弹按钮
    showJumpLatest.value = !autoFollow.value && el.scrollHeight > el.clientHeight + 40
  }

  function scrollToBottom(force = false) {
    if (force) autoFollow.value = true
    nextTick(() => {
      const el = bodyRef.value
      if (!el) return
      if (!force && !autoFollow.value) return
      el.scrollTop = el.scrollHeight
      showJumpLatest.value = false
      // 还原历史会话时，markdown / 代码块渲染完高度还会再变一次，
      // nextTick 这一下往往滚不到真正的底部，下一帧再兜一次。
      if (force) {
        requestAnimationFrame(() => {
          const node = bodyRef.value
          if (node) node.scrollTop = node.scrollHeight
        })
      }
    })
  }

  // 流式期间 turns 内容在不断追加(文本 chunk、工具步骤)，深层监听驱动跟随。
  // scrollTop 赋值本身很便宜，且 nextTick 会把同一 tick 内的多次变更合并成一次。
  watch(getTurns, () => scrollToBottom(), { deep: true })

  return {
    bodyRef,
    autoFollow,
    showJumpLatest,
    onBodyScroll,
    scrollToBottom,
    FOLLOW_THRESHOLD
  }
}
