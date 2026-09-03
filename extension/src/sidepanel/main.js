import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import { createAppRouter, setAppRouter } from '../router'
import { hydrateToken } from '../utils/auth'
import { registerBrowserTools } from '../tools/browserTools'
import '../style.css'
import './sidepanel.css'

hydrateToken().then(() => {
  registerBrowserTools()
  const app = createApp(App)
  const router = createAppRouter()
  setAppRouter(router)
  app.use(createPinia())
  app.use(router)
  app.mount('#app')
})
