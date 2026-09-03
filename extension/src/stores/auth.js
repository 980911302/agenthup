import { defineStore } from 'pinia'
import { ref } from 'vue'
import {
  exchangeOauthTicket,
  getInfo,
  getExtensionOauthLoginConfig,
  login as loginApi,
  logout as logoutApi
} from '../api/auth'
import { getToken, hydrateToken, removeToken, setToken } from '../utils/auth'

export const useAuthStore = defineStore('auth', () => {
  const token = ref('')
  const user = ref(null)
  const oauthEnabled = ref(false)
  const oauthAuthorizationUrl = ref('')

  async function login(payload) {
    const res = await loginApi(payload.username, payload.password, payload.code, payload.uuid)
    const next = res.token || res.data?.token
    if (!next) throw new Error(res.msg || '登录失败')
    await setToken(next)
    token.value = next
    await fetchUser()
  }

  async function loginByOauth(ticket) {
    const res = await exchangeOauthTicket(ticket)
    const next = res.token || res.data?.token
    if (!next) throw new Error(res.msg || '统一登录失败')
    await setToken(next)
    token.value = next
    await fetchUser()
  }

  async function hydrate() {
    token.value = await hydrateToken()
    return token.value
  }

  async function fetchUser() {
    const res = await getInfo()
    user.value = res.user || res.data?.user || null
    return user.value
  }

  async function initOauth() {
    try {
      const res = await getExtensionOauthLoginConfig()
      oauthEnabled.value = Boolean(res.enabled)
      oauthAuthorizationUrl.value = res.authorizationUrl || ''
    } catch (_) {
      oauthEnabled.value = false
    }
  }

  async function logout() {
    // 登出接口不能挡住跳转：扩展里 /logout 一旦卡住，侧栏会一直停在聊天页。
    void logoutApi().catch(() => {})
    token.value = ''
    user.value = null
    await removeToken()
    try {
      if (typeof chrome !== 'undefined' && chrome.storage?.local) {
        await chrome.storage.local.remove(['oauthTicket', 'oauthError', 'oauthAt'])
      }
    } catch (_) { /* 票清不掉时仍已丢掉 JWT */ }
  }

  return { token, user, oauthEnabled, oauthAuthorizationUrl, login, loginByOauth, fetchUser, initOauth, logout, hydrate }
})
