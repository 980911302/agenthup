import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

const THEME_STORAGE_KEY = 'agenthub_desktop_theme'

export const useThemeStore = defineStore('theme', () => {
  // 'dark' | 'light' | 'system'
  const mode = ref(localStorage.getItem(THEME_STORAGE_KEY) || 'light')
  const systemIsDark = ref(
    typeof window !== 'undefined' && window.matchMedia
      ? window.matchMedia('(prefers-color-scheme: dark)').matches
      : false
  )

  const isDark = computed(() => {
    if (mode.value === 'system') return systemIsDark.value
    return mode.value === 'dark'
  })

  function applyThemeClass() {
    if (typeof document === 'undefined') return
    const root = document.documentElement
    if (isDark.value) {
      root.classList.add('dark')
      root.setAttribute('data-theme', 'dark')
    } else {
      root.classList.remove('dark')
      root.setAttribute('data-theme', 'light')
    }
  }

  function setMode(newMode) {
    if (!['dark', 'light', 'system'].includes(newMode)) return
    mode.value = newMode
    localStorage.setItem(THEME_STORAGE_KEY, newMode)
    applyThemeClass()
  }

  function toggleTheme() {
    if (isDark.value) {
      setMode('light')
    } else {
      setMode('dark')
    }
  }

  function initTheme() {
    if (typeof window === 'undefined') return

    // 监听系统主题变化
    if (window.matchMedia) {
      const mediaQuery = window.matchMedia('(prefers-color-scheme: dark)')
      const onChange = (e) => {
        systemIsDark.value = e.matches
        if (mode.value === 'system') {
          applyThemeClass()
        }
      }
      if (mediaQuery.addEventListener) {
        mediaQuery.addEventListener('change', onChange)
      } else if (mediaQuery.addListener) {
        mediaQuery.addListener(onChange)
      }
    }

    applyThemeClass()
  }

  return {
    mode,
    isDark,
    setMode,
    toggleTheme,
    initTheme
  }
})
