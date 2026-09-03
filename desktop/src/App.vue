<script setup>
import { onMounted } from 'vue'
import AppToast from './components/AppToast.vue'
import AppConfirm from './components/AppConfirm.vue'
import AppPageLoader from './components/AppPageLoader.vue'
import { useThemeStore } from './stores/theme'

const theme = useThemeStore()

onMounted(() => {
  theme.initTheme()
})
</script>

<template>
  <router-view v-slot="{ Component, route }">
    <Transition name="route-view" mode="out-in">
      <div :key="route.name || route.path" class="app-route-shell">
        <Suspense>
          <component :is="Component" />
          <template #fallback>
            <AppPageLoader fullscreen label="正在进入页面…" />
          </template>
        </Suspense>
      </div>
    </Transition>
  </router-view>
  <AppToast />
  <AppConfirm />
</template>

<style scoped>
.app-route-shell { width: 100%; height: 100%; overflow: hidden; }
</style>
