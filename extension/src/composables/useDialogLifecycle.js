import { nextTick, onBeforeUnmount, watch } from 'vue'

const FOCUSABLE = [
  '[autofocus]',
  '[data-autofocus]',
  'button:not([disabled])',
  'input:not([disabled]):not([type="hidden"])',
  'textarea:not([disabled])',
  'select:not([disabled])',
  'a[href]',
  '[tabindex]:not([tabindex="-1"])'
].join(',')

const dialogStack = []

export function useDialogLifecycle(openSource, close, rootRef) {
  const token = Symbol('dialog')
  let previousFocus = null
  let focusFrame = 0

  function focusables() {
    const root = rootRef?.value
    if (!root) return []
    return [...root.querySelectorAll(FOCUSABLE)].filter(el => !el.hidden && el.getAttribute('aria-hidden') !== 'true')
  }

  function onKeydown(event) {
    if (dialogStack[dialogStack.length - 1] !== token) return
    if (event.key === 'Escape' && !event.defaultPrevented) {
      event.preventDefault()
      close?.()
      return
    }
    if (event.key !== 'Tab') return
    const items = focusables()
    if (!items.length) return
    const first = items[0]
    const last = items[items.length - 1]
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first.focus()
    }
  }

  const stop = watch(openSource, async (open, wasOpen) => {
    if (open) {
      previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
      const existingIndex = dialogStack.indexOf(token)
      if (existingIndex >= 0) dialogStack.splice(existingIndex, 1)
      dialogStack.push(token)
      document.addEventListener('keydown', onKeydown)
      await nextTick()
      if (focusFrame) cancelAnimationFrame(focusFrame)
      focusFrame = requestAnimationFrame(() => {
        focusFrame = 0
        const preferred = rootRef?.value?.querySelector('[data-autofocus], [autofocus]')
        const target = preferred || focusables()[0]
        target?.focus({ preventScroll: true })
      })
      return
    }
    document.removeEventListener('keydown', onKeydown)
    const stackIndex = dialogStack.indexOf(token)
    if (stackIndex >= 0) dialogStack.splice(stackIndex, 1)
    if (wasOpen && previousFocus?.isConnected) {
      await nextTick()
      previousFocus.focus({ preventScroll: true })
    }
    previousFocus = null
  })

  onBeforeUnmount(() => {
    stop()
    document.removeEventListener('keydown', onKeydown)
    const stackIndex = dialogStack.indexOf(token)
    if (stackIndex >= 0) dialogStack.splice(stackIndex, 1)
    if (focusFrame) cancelAnimationFrame(focusFrame)
  })
}
