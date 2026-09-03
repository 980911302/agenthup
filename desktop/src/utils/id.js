/** 密码学强度的 UUIDv4。sessionId 必须不可预测。 */
export function genId() {
  const c = typeof crypto !== 'undefined' ? crypto : null
  if (c && c.randomUUID) return c.randomUUID()
  if (c && c.getRandomValues) {
    const b = c.getRandomValues(new Uint8Array(16))
    b[6] = (b[6] & 0x0f) | 0x40
    b[8] = (b[8] & 0x3f) | 0x80
    const hex = Array.from(b, x => x.toString(16).padStart(2, '0')).join('')
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
  }
  throw new Error('当前环境缺少 Web Crypto，无法安全地创建会话')
}
