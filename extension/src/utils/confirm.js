import { reactive } from 'vue'

/** 危险工具/操作确认弹窗的共享状态(由 AppConfirm.vue 消费)。 */
const confirmState = reactive({
  visible: false,
  title: '',
  message: '',
  okLabel: '允许',
  cancelLabel: '拒绝',
  pending: 0
})

// 排队而不是只留一个:服务端 parallel-tools 开着(最多 5 个并发),模型一轮里
// 可能同时调多个需要确认的工具。之前用单个 resolve 字段,第二次确认会把第一个
// 覆盖掉 —— 第一个 Promise 永远不 resolve,那个工具挂到 120 秒超时,
// 用户看到的现象是「弹窗自己关掉了」。
const queue = []
let current = null

function pump() {
  if (current || !queue.length) return
  current = queue.shift()
  confirmState.title = current.title
  confirmState.message = current.message
  confirmState.okLabel = current.okLabel
  confirmState.cancelLabel = current.cancelLabel
  confirmState.pending = queue.length
  confirmState.visible = true
}

/** 弹出确认框,返回 Promise<boolean>。多个并发确认按先来后到依次弹。 */
export function confirmDanger(title, message, options = {}) {
  return new Promise((resolve) => {
    queue.push({
      title: title || '需要确认',
      message: message || '',
      okLabel: options.okLabel || '允许',
      cancelLabel: options.cancelLabel || '拒绝',
      resolve
    })
    if (current) confirmState.pending = queue.length
    pump()
  })
}

/** AppConfirm.vue 回调:用户点了允许/拒绝。 */
export function resolveConfirm(ok) {
  const done = current
  current = null
  confirmState.visible = false
  if (done) done.resolve(!!ok)
  pump()
}

/** 面板卸载等场景:把所有未决确认按拒绝了结,不要留下永不 resolve 的 Promise。 */
export function rejectAllConfirms() {
  const pendingList = current ? [current, ...queue] : [...queue]
  current = null
  queue.length = 0
  confirmState.visible = false
  confirmState.pending = 0
  pendingList.forEach((c) => c.resolve(false))
}

export { confirmState }

export function toast(message) {
  if (typeof window === 'undefined') return
  window.dispatchEvent(new CustomEvent('desk-toast', { detail: { message: String(message || '') } }))
}
