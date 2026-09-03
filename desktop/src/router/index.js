import { createRouter, createWebHashHistory } from 'vue-router'
import { getToken } from '../utils/auth'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('../views/LoginView.vue'), meta: { public: true } },
    { path: '/', name: 'chat', component: () => import('../views/ChatView.vue') }
  ]
})

router.beforeEach((to) => {
  if (to.meta.public) return true
  if (!getToken()) {
    return { path: '/login', query: to.path !== '/' ? { redirect: to.fullPath } : undefined }
  }
  return true
})

export default router
