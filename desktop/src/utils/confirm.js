import { reactive } from 'vue'

/** 危险工具/操作确认弹窗的共享状态(由 AppConfirm.vue 消费)。 */
const confirmState = reactive({
  visible: false,
  title: '',
  message: '',
  okLabel: '允许',
  cancelLabel: '拒绝',
  resolve: null
})

/** 弹出确认框,返回 Promise<boolean>。useChatRun 的危险工具确认走这里。 */
export function confirmDanger(title, message, options = {}) {
  return new Promise((resolve) => {
    confirmState.title = title || '需要确认'
    confirmState.message = message || ''
    confirmState.okLabel = options.okLabel || '允许'
    confirmState.cancelLabel = options.cancelLabel || '拒绝'
    confirmState.resolve = resolve
    confirmState.visible = true
  })
}

/** AppConfirm.vue 回调:用户点了允许/拒绝。 */
export function resolveConfirm(ok) {
  const resolve = confirmState.resolve
  confirmState.visible = false
  confirmState.resolve = null
  if (resolve) resolve(!!ok)
}

export { confirmState }

export function toast(message) {
  if (typeof window === 'undefined') return
  window.dispatchEvent(new CustomEvent('desk-toast', { detail: { message: String(message || '') } }))
}
