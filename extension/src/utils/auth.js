/** 协议层：desktop/src/utils/auth.js 用 localStorage。这里走 chrome.storage.local。 */
const TOKEN_KEY = 'AgentBook-Token'
let memoryToken = ''
let hydrated = false
let hydratePromise = null

export async function hydrateToken() {
  if (hydrated) return memoryToken
  if (hydratePromise) return hydratePromise
  hydratePromise = (async () => {
    try {
      if (typeof chrome !== 'undefined' && chrome.storage?.local) {
        const bag = await chrome.storage.local.get(TOKEN_KEY)
        memoryToken = bag[TOKEN_KEY] || ''
      } else {
        memoryToken = localStorage.getItem(TOKEN_KEY) || ''
      }
    } catch (_) {
      memoryToken = ''
    }
    hydrated = true
    return memoryToken
  })()
  return hydratePromise
}

export function getToken() {
  return memoryToken
}

export async function setToken(token) {
  memoryToken = token || ''
  hydrated = true
  try {
    if (typeof chrome !== 'undefined' && chrome.storage?.local) {
      if (token) await chrome.storage.local.set({ [TOKEN_KEY]: token })
      else await chrome.storage.local.remove(TOKEN_KEY)
    } else if (token) {
      localStorage.setItem(TOKEN_KEY, token)
    } else {
      localStorage.removeItem(TOKEN_KEY)
    }
  } catch (_) { /* storage 不可用时仍保留内存 token */ }
}

export async function removeToken() {
  await setToken('')
}

if (typeof chrome !== 'undefined' && chrome.storage?.onChanged) {
  chrome.storage.onChanged.addListener((changes, area) => {
    if (area !== 'local' || !changes[TOKEN_KEY]) return
    memoryToken = changes[TOKEN_KEY].newValue || ''
    hydrated = true
  })
}
