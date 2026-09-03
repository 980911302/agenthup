/** 协议层副本：desktop/src/api/request.js。VITE_APP_BASE_API 必须是绝对地址。 */
import axios from 'axios'
import { getToken, hydrateToken, removeToken } from '../utils/auth'
import { toast } from '../utils/confirm'

async function goLogin(redirect) {
  const { getAppRouter } = await import('../router')
  const router = getAppRouter()
  if (!router) return
  if (router.currentRoute.value?.path === '/login') return
  router.replace({ path: '/login', query: redirect && redirect !== '/' ? { redirect } : undefined })
}

const service = axios.create({
  baseURL: import.meta.env.VITE_APP_BASE_API || 'http://localhost:8080',
  timeout: 20000
})

function unsetContentType(headers) {
  if (!headers) return
  if (typeof headers.delete === 'function') {
    headers.delete('Content-Type')
    headers.delete('content-type')
  } else {
    delete headers['Content-Type']
    delete headers['content-type']
  }
}

service.interceptors.request.use(async config => {
  await hydrateToken()
  const isToken = (config.headers || {}).isToken === false
  if (getToken() && !isToken) {
    config.headers.Authorization = 'Bearer ' + getToken()
  }
  if (typeof FormData !== 'undefined' && config.data instanceof FormData) {
    unsetContentType(config.headers)
  }
  return config
})

service.interceptors.response.use(
  res => {
    if (res.request?.responseType === 'blob' || res.request?.responseType === 'arraybuffer') {
      return res.data
    }
    const data = res.data || {}
    const code = data.code || 200
    if (code === 401) {
      removeToken()
      goLogin()
      return Promise.reject(new Error(data.msg || '登录已过期，请重新登录'))
    }
    if (code !== 200 && code !== 0) {
      const msg = data.msg || '请求失败'
      toast(msg)
      const error = new Error(msg)
      error.code = code
      error.data = data.data
      return Promise.reject(error)
    }
    return data
  },
  error => {
    const silent = error.config?.silent === true
    const raw = error.response?.data?.msg || error.message || '网络异常'
    const msg = /status code|Network Error|ECONNREFUSED/i.test(raw)
      ? '无法连接服务器，请确认后台已启动'
      : raw
    if (error.response?.status === 401) {
      removeToken()
      goLogin()
    } else if (!silent) {
      toast(msg)
    }
    return Promise.reject(error)
  }
)

export default service
