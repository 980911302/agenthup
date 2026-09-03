/** 协议层：extension/src/utils/auth.js 用 chrome.storage，语义保持一致。 */
const TOKEN_KEY = 'AgentBook-Token'

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function setToken(token) {
  if (token) localStorage.setItem(TOKEN_KEY, token)
  else localStorage.removeItem(TOKEN_KEY)
}

export function removeToken() {
  localStorage.removeItem(TOKEN_KEY)
}
