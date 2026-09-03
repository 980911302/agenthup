import { createRouter, createWebHashHistory } from 'vue-router'
import { getToken, hydrateToken } from '../utils/auth'

let currentRouter = null

export function setAppRouter(router) {
  currentRouter = router
}

export function getAppRouter() {
  return currentRouter
}

export function createAppRouter() {
  const routes = [
    { path: '/login', name: 'login', component: () => import('../views/LoginView.vue'), meta: { public: true } },
    { path: '/', name: 'chat', component: () => import('../views/ChatView.vue') }
  ]

  const router = createRouter({
    history: createWebHashHistory(),
    routes
  })

  router.beforeEach(async (to) => {
    await hydrateToken()
    if (to.meta.public) return true
    if (!getToken()) {
      return { path: '/login', query: to.path !== '/' ? { redirect: to.fullPath } : undefined }
    }
    return true
  })

  return router
}
