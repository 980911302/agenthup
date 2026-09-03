<script setup>
import { onMounted } from 'vue'
import { useAuthStore } from '../stores/auth'
import { useThemeStore } from '../stores/theme'
import AppToast from '../components/AppToast.vue'
import AppConfirm from '../components/AppConfirm.vue'

const auth = useAuthStore()
const theme = useThemeStore()

onMounted(async () => {
  theme.initTheme()
  await auth.hydrate()
  if (auth.token) {
    try { await auth.fetchUser() } catch (_) { /* 过期交给路由 */ }
  }
})
</script>

<template>
  <router-view />
  <AppToast />
  <AppConfirm />
</template>
