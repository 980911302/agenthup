/**
 * AI 模块配色工具
 * 用途：让智能体 / 技能分类 / 工具来源有稳定且互相区分的颜色，
 * 避免整个模块只有一种蓝色导致的视觉单调。
 * 同一个字符串永远得到同一组颜色（纯函数，不依赖顺序）。
 */

// 8 组渐变，色相均匀铺开，饱和度/明度对齐，保证放在一起不打架
const PALETTE = [
  { from: '#0A84FF', to: '#5E5CE6', solid: '#0A84FF', soft: 'rgba(10,132,255,0.10)' },
  { from: '#FF9F0A', to: '#FF375F', solid: '#FF9F0A', soft: 'rgba(255,159,10,0.12)' },
  { from: '#30D158', to: '#2CB5C6', solid: '#26A85A', soft: 'rgba(48,209,88,0.12)' },
  { from: '#14B8A6', to: '#38BDF8', solid: '#0EA5A5', soft: 'rgba(20,184,166,0.12)' },
  { from: '#64D2FF', to: '#0A84FF', solid: '#0E9CD6', soft: 'rgba(100,210,255,0.14)' },
  { from: '#F59E0B', to: '#FF6B6B', solid: '#D97706', soft: 'rgba(245,158,11,0.14)' },
  { from: '#FFD60A', to: '#FF9F0A', solid: '#D9A400', soft: 'rgba(255,214,10,0.16)' },
  { from: '#2CB5C6', to: '#30D158', solid: '#1F9AA8', soft: 'rgba(44,181,198,0.12)' }
]

function hash(str) {
  const s = String(str == null ? '' : str)
  let h = 0
  for (let i = 0; i < s.length; i++) {
    h = (h * 31 + s.charCodeAt(i)) >>> 0
  }
  return h
}

/** 可选主题总数，供取色器渲染色板 */
export const THEME_COUNT = PALETTE.length

/**
 * 取一组配色。
 * theme 传了 0..7 就用指定色（用户手选），否则按 key 稳定散列。
 */
export function paletteOf(key, theme) {
  if (theme !== undefined && theme !== null && theme !== '') {
    const i = Number(theme)
    if (Number.isInteger(i) && i >= 0 && i < PALETTE.length) return PALETTE[i]
  }
  return PALETTE[hash(key) % PALETTE.length]
}

/** 渐变背景，用于头像 */
export function gradientOf(key, theme) {
  const p = paletteOf(key, theme)
  return `linear-gradient(135deg, ${p.from}, ${p.to})`
}

/** 实色，用于分类圆点、边框 */
export function colorOf(key, theme) {
  return paletteOf(key, theme).solid
}

/** 浅色底，用于 chip 背景 */
export function softOf(key, theme) {
  return paletteOf(key, theme).soft
}

/** 头像阴影，跟随主色 */
export function glowOf(key, theme) {
  const p = paletteOf(key, theme)
  return `0 6px 18px ${p.soft.replace(/[\d.]+\)$/, '0.45)')}`
}
