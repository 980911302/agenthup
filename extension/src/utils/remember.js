const KEY = 'AgentBook-Remember'
const TTL_MS = 30 * 24 * 60 * 60 * 1000
const MASK = 'agenthub-book'

function xorBytes(bytes) {
  const out = new Uint8Array(bytes.length)
  for (let i = 0; i < bytes.length; i++) {
    out[i] = bytes[i] ^ MASK.charCodeAt(i % MASK.length)
  }
  return out
}

export function encodeSecret(text) {
  const bytes = xorBytes(new TextEncoder().encode(String(text ?? '')))
  let bin = ''
  for (const b of bytes) bin += String.fromCharCode(b)
  return btoa(bin)
}

export function decodeSecret(text) {
  try {
    const raw = atob(String(text ?? ''))
    const bytes = xorBytes(Uint8Array.from(raw, (c) => c.charCodeAt(0)))
    return new TextDecoder().decode(bytes)
  } catch {
    return ''
  }
}

export function loadRemember() {
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return null
    const data = JSON.parse(raw)
    if (!data?.exp || Date.now() > data.exp) {
      localStorage.removeItem(KEY)
      return null
    }
    return {
      username: data.username || '',
      password: data.password ? decodeSecret(data.password) : ''
    }
  } catch {
    return null
  }
}

export function saveRemember(username, password) {
  localStorage.setItem(KEY, JSON.stringify({
    username: String(username || '').trim(),
    password: encodeSecret(password || ''),
    exp: Date.now() + TTL_MS
  }))
}

export function clearRemember() {
  localStorage.removeItem(KEY)
}
