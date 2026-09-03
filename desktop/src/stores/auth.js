import { defineStore } from 'pinia'
import { ref } from 'vue'
import {
  exchangeOauthTicket,
  getInfo,
  getDesktopOauthLoginConfig,
  login as loginApi,
  logout as logoutApi
} from '../api/auth'
import { getToken, removeToken, setToken } from '../utils/auth'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(getToken())
  const user = ref(null)
  const oauthEnabled = ref(false)
  const oauthAuthorizationUrl = ref('')

  async function login(payload) {
    const res = await loginApi(payload.username, payload.password, payload.code, payload.uuid)
    const next = res.token || res.data?.token
    if (!next) throw new Error(res.msg || '登录失败')
    setToken(next)
    token.value = next
    await fetchUser()
  }

  async function loginByOauth(ticket) {
    const res = await exchangeOauthTicket(ticket)
    const next = res.token || res.data?.token
    if (!next) throw new Error(res.msg || '统一登录失败')
    setToken(next)
    token.value = next
    await fetchUser()
  }

  async function fetchUser() {
    const res = await getInfo()
    user.value = res.user || res.data?.user || null
    return user.value
  }

  async function initOauth() {
    try {
      const res = await getDesktopOauthLoginConfig()
      oauthEnabled.value = Boolean(res.enabled)
      oauthAuthorizationUrl.value = res.authorizationUrl || ''
    } catch (_) {
      oauthEnabled.value = false
    }
  }

  async function logout() {
    try { await logoutApi() } catch (_) { /* 过期时仍清本地 */ }
    removeToken()
    token.value = ''
    user.value = null
  }

  return { token, user, oauthEnabled, oauthAuthorizationUrl, login, loginByOauth, fetchUser, initOauth, logout }
})
