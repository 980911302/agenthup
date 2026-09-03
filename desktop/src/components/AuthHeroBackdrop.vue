<template>
  <canvas ref="canvasRef" class="hero-backdrop" aria-hidden="true"></canvas>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useThemeStore } from '../stores/theme'

/**
 * 登录页左侧的三维粒子星云。
 *
 * 刻意用原生 Canvas 2D 手写三维投影，而不是引 three.js:登录页是首屏，
 * 项目里的 three 打进了 1.4MB 的 KnowledgeGraphViewer chunk，为一个背景动画
 * 让所有人先下载它不划算。这里整份代码压缩后只有几 KB。
 *
 * 画面分四层，由远及近:辉光 → 轨道环 → 粒子连线 → 粒子本体。
 * 每层都按投影后的 z 决定明暗与尺寸，近处更亮更大，才有纵深而不是一张贴纸。
 */

const theme = useThemeStore()
const canvasRef = ref(null)

/** 球面粒子数。180 个在 O(n²) 连线下每帧约 3 万次距离比较，仍然轻松 60fps。 */
const NODE_COUNT = 180
/** 连线阈值(单位球空间)。超过这个距离就不连，否则会糊成一团。 */
const LINK_DISTANCE = 0.46
/** 同时在飞的数据脉冲数量 —— 象征 Agent 正在调用链上传递。 */
const PULSE_COUNT = 7

let ctx = null
let raf = 0
let width = 0
let height = 0
let dpr = 1
let nodes = []
let orbits = []
let pulses = []
let running = false

/** 目标与当前的鼠标视差角度。用插值追随，指针猛甩时画面也不会跳。 */
const tilt = { x: 0, y: 0, targetX: 0, targetY: 0 }
let spin = 0
let reduceMotion = false

/** 主题配色。亮色下降低发光强度，否则在浅背景上糊成一片白。 */
function palette() {
  return theme.isDark
    ? {
        core: '#7dd3fc',
        accent: '#a78bfa',
        link: 'rgba(125, 211, 252, ',
        glowInner: 'rgba(56, 189, 248, 0.20)',
        glowOuter: 'rgba(124, 58, 237, 0.10)',
        orbit: 'rgba(148, 197, 255, ',
        nodeAlpha: 0.95,
        linkAlpha: 0.30,
        shadow: 14
      }
    : {
        // 亮色底的对比度全靠颜色本身，不能靠发光 —— 浅背景上加辉光只会糊成一片白，
        // 所以这里用更深的蓝紫、更高的不透明度，把清晰度做回来
        core: '#1d4ed8',
        accent: '#6d28d9',
        link: 'rgba(29, 78, 216, ',
        glowInner: 'rgba(59, 130, 246, 0.20)',
        glowOuter: 'rgba(124, 58, 237, 0.10)',
        orbit: 'rgba(29, 78, 216, ',
        nodeAlpha: 0.95,
        linkAlpha: 0.34,
        shadow: 5
      }
}

/**
 * 斐波那契球:用黄金角撒点，得到球面上近乎均匀的分布。
 * 换成随机经纬度会在两极堆积，一眼就看得出来。
 */
function buildNodes() {
  const golden = Math.PI * (3 - Math.sqrt(5))
  nodes = Array.from({ length: NODE_COUNT }, (_, i) => {
    const y = 1 - (i / (NODE_COUNT - 1)) * 2
    const radius = Math.sqrt(Math.max(0, 1 - y * y))
    const theta = golden * i
    return {
      x: Math.cos(theta) * radius,
      y,
      z: Math.sin(theta) * radius,
      // 少量粒子用强调色，作为“正在工作的节点”
      accent: i % 11 === 0,
      // 各自的呼吸相位，避免整体同步闪烁
      phase: Math.random() * Math.PI * 2,
      px: 0, py: 0, pz: 0, scale: 1
    }
  })
}

/** 三条倾角不同的轨道环，象征外挂的工具与技能在环绕调用。 */
function buildOrbits() {
  orbits = [
    { radius: 1.30, tiltX: 0.55, tiltZ: 0.20, speed: 0.00042, offset: 0 },
    { radius: 1.52, tiltX: -0.35, tiltZ: 0.62, speed: -0.00031, offset: 2.1 },
    { radius: 1.72, tiltX: 0.22, tiltZ: -0.48, speed: 0.00024, offset: 4.3 }
  ]
}

/** 数据脉冲:沿一条连线从 a 跑到 b，到头换一条边重来。 */
function buildPulses() {
  pulses = Array.from({ length: PULSE_COUNT }, () => spawnPulse())
}

function spawnPulse() {
  const a = (Math.random() * NODE_COUNT) | 0
  let b = (Math.random() * NODE_COUNT) | 0
  if (b === a) b = (b + 1) % NODE_COUNT
  return { a, b, t: Math.random(), speed: 0.0035 + Math.random() * 0.005 }
}

function resize() {
  const canvas = canvasRef.value
  if (!canvas) return
  const rect = canvas.getBoundingClientRect()
  dpr = Math.min(window.devicePixelRatio || 1, 2)
  width = rect.width
  height = rect.height
  canvas.width = Math.round(width * dpr)
  canvas.height = Math.round(height * dpr)
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  // 改 canvas.width 会清空画布。跑动画时下一帧自然补回来，但静态模式
  // (reduce-motion / 后台加载)没有下一帧 —— ResizeObserver 首次观察就会
  // 触发一次 resize，画好的那帧当场被抹掉，画面从此一片空白。
  if (!running) draw(performance.now())
}

/**
 * 绕 Y 轴自转 + 绕 X/Y 的鼠标视差，然后做透视投影。
 * fov 取 3.2:再小畸变过强，再大就退化成正交投影、失去纵深。
 */
function project(point, radius, cx, cy) {
  const cosY = Math.cos(spin + tilt.x)
  const sinY = Math.sin(spin + tilt.x)
  const cosX = Math.cos(tilt.y)
  const sinX = Math.sin(tilt.y)

  const x1 = point.x * cosY - point.z * sinY
  const z1 = point.x * sinY + point.z * cosY
  const y2 = point.y * cosX - z1 * sinX
  const z2 = point.y * sinX + z1 * cosX

  const fov = 3.2
  const scale = fov / (fov + z2)
  return { px: cx + x1 * radius * scale, py: cy + y2 * radius * scale, pz: z2, scale }
}

function draw(now) {
  if (!ctx) return
  const p = palette()
  const cx = width * 0.5
  const cy = height * 0.5
  // 左栏已无任何内容层，星云完全独占，半径再放开一档
  const radius = Math.min(width, height) * 0.40

  ctx.clearRect(0, 0, width, height)

  // ── 辉光:两层径向渐变，给球一个“发光体”的底
  const glow = ctx.createRadialGradient(cx, cy, 0, cx, cy, radius * 2.4)
  glow.addColorStop(0, p.glowInner)
  glow.addColorStop(0.45, p.glowOuter)
  glow.addColorStop(1, 'rgba(0,0,0,0)')
  ctx.fillStyle = glow
  ctx.fillRect(0, 0, width, height)

  // ── 投影所有粒子
  for (const node of nodes) {
    const q = project(node, radius, cx, cy)
    node.px = q.px
    node.py = q.py
    node.pz = q.pz
    node.scale = q.scale
  }

  // ── 轨道环:采样 96 个点连成曲线，与粒子共用同一套旋转，才不会“各转各的”
  ctx.lineWidth = 1
  for (const orbit of orbits) {
    const angle = now * orbit.speed + orbit.offset
    ctx.beginPath()
    for (let i = 0; i <= 96; i++) {
      const t = (i / 96) * Math.PI * 2
      const raw = {
        x: Math.cos(t) * orbit.radius,
        y: Math.sin(t) * orbit.radius * orbit.tiltZ,
        z: Math.sin(t) * orbit.radius * Math.cos(orbit.tiltX)
      }
      const q = project(raw, radius, cx, cy)
      if (i === 0) ctx.moveTo(q.px, q.py)
      else ctx.lineTo(q.px, q.py)
    }
    ctx.strokeStyle = p.orbit + (theme.isDark ? 0.16 : 0.16) + ')'
    ctx.stroke()

    // 环上的运行光点
    const raw = {
      x: Math.cos(angle) * orbit.radius,
      y: Math.sin(angle) * orbit.radius * orbit.tiltZ,
      z: Math.sin(angle) * orbit.radius * Math.cos(orbit.tiltX)
    }
    const q = project(raw, radius, cx, cy)
    const depth = (q.scale - 0.75) / 0.5
    ctx.beginPath()
    ctx.arc(q.px, q.py, 2.4 * q.scale, 0, Math.PI * 2)
    ctx.fillStyle = p.accent
    ctx.globalAlpha = Math.max(0.25, Math.min(1, depth))
    ctx.shadowColor = p.accent
    ctx.shadowBlur = p.shadow
    ctx.fill()
    ctx.shadowBlur = 0
    ctx.globalAlpha = 1
  }

  // ── 连线:只连彼此够近的一对，透明度按距离和深度双重衰减
  for (let i = 0; i < nodes.length; i++) {
    const a = nodes[i]
    for (let j = i + 1; j < nodes.length; j++) {
      const b = nodes[j]
      const dx = a.x - b.x
      const dy = a.y - b.y
      const dz = a.z - b.z
      const dist = Math.sqrt(dx * dx + dy * dy + dz * dz)
      if (dist > LINK_DISTANCE) continue
      const closeness = 1 - dist / LINK_DISTANCE
      const depth = Math.max(0, Math.min(1, ((a.scale + b.scale) / 2 - 0.72) / 0.55))
      ctx.beginPath()
      ctx.moveTo(a.px, a.py)
      ctx.lineTo(b.px, b.py)
      ctx.strokeStyle = p.link + (closeness * depth * p.linkAlpha).toFixed(3) + ')'
      ctx.stroke()
    }
  }

  // ── 数据脉冲:在连线上跑的小亮点
  for (const pulse of pulses) {
    const a = nodes[pulse.a]
    const b = nodes[pulse.b]
    pulse.t += reduceMotion ? 0 : pulse.speed
    if (pulse.t >= 1) Object.assign(pulse, spawnPulse(), { t: 0 })
    const x = a.px + (b.px - a.px) * pulse.t
    const y = a.py + (b.py - a.py) * pulse.t
    const fade = Math.sin(pulse.t * Math.PI)
    ctx.beginPath()
    ctx.arc(x, y, 1.9, 0, Math.PI * 2)
    ctx.fillStyle = p.core
    ctx.globalAlpha = fade * 0.9
    ctx.shadowColor = p.core
    ctx.shadowBlur = p.shadow
    ctx.fill()
    ctx.shadowBlur = 0
    ctx.globalAlpha = 1
  }

  // ── 粒子本体:按 z 从远到近画，远处的先被近处盖住
  const ordered = [...nodes].sort((m, n) => n.pz - m.pz)
  for (const node of ordered) {
    const depth = Math.max(0, Math.min(1, (node.scale - 0.72) / 0.55))
    const breath = reduceMotion ? 1 : 0.82 + Math.sin(now * 0.0016 + node.phase) * 0.18
    const size = (node.accent ? 2.6 : 1.6) * node.scale * breath
    ctx.beginPath()
    ctx.arc(node.px, node.py, Math.max(0.4, size), 0, Math.PI * 2)
    ctx.fillStyle = node.accent ? p.accent : p.core
    ctx.globalAlpha = (0.28 + depth * 0.72) * p.nodeAlpha
    if (node.accent) {
      ctx.shadowColor = p.accent
      ctx.shadowBlur = p.shadow
    }
    ctx.fill()
    ctx.shadowBlur = 0
    ctx.globalAlpha = 1
  }
}

function frame(now) {
  if (!running) return
  if (!reduceMotion) spin += 0.0013
  // 视差用插值追随，而不是直接赋值，指针快速移动时画面才不会硬切
  tilt.x += (tilt.targetX - tilt.x) * 0.045
  tilt.y += (tilt.targetY - tilt.y) * 0.045
  draw(now)
  raf = requestAnimationFrame(frame)
}

function start() {
  if (running) return
  running = true
  raf = requestAnimationFrame(frame)
}

function stop() {
  running = false
  if (raf) cancelAnimationFrame(raf)
  raf = 0
}

function onPointerMove(e) {
  const canvas = canvasRef.value
  if (!canvas) return
  const rect = canvas.getBoundingClientRect()
  // 归一化到 -1..1，再压到一个克制的角度范围 —— 幅度大了会晕
  tilt.targetX = ((e.clientX - rect.left) / rect.width - 0.5) * 0.9
  tilt.targetY = ((e.clientY - rect.top) / rect.height - 0.5) * -0.5
}

function onVisibilityChange() {
  // 标签页切走就停,不白烧 GPU
  if (document.hidden) stop()
  else if (!reduceMotion) start()
}

let resizeObserver = null
let motionQuery = null

onMounted(() => {
  const canvas = canvasRef.value
  if (!canvas) return
  ctx = canvas.getContext('2d')
  if (!ctx) return

  motionQuery = window.matchMedia?.('(prefers-reduced-motion: reduce)')
  reduceMotion = !!motionQuery?.matches
  const onMotionChange = e => { reduceMotion = e.matches }
  motionQuery?.addEventListener?.('change', onMotionChange)

  buildNodes()
  buildOrbits()
  buildPulses()
  resize()

  resizeObserver = new ResizeObserver(() => resize())
  resizeObserver.observe(canvas)

  window.addEventListener('pointermove', onPointerMove, { passive: true })
  document.addEventListener('visibilitychange', onVisibilityChange)

  if (reduceMotion || document.hidden) {
    // 两种情况都只画一帧静态构图:
    //   1. 系统要求减少动效 —— 本来就不该动;
    //   2. 页面在后台加载 —— 此时浏览器根本不执行 rAF 回调，若照样把 running 置为
    //      true，等页面转到前台时 start() 会被 `if (running) return` 挡掉，动画就
    //      再也起不来了。所以这里保持 running=false，交给 visibilitychange 启动。
    draw(performance.now())
  } else {
    start()
  }

  onBeforeUnmount(() => {
    stop()
    resizeObserver?.disconnect()
    window.removeEventListener('pointermove', onPointerMove)
    document.removeEventListener('visibilitychange', onVisibilityChange)
    motionQuery?.removeEventListener?.('change', onMotionChange)
  })
})

// 切换主题后立刻重绘，避免静态(reduce-motion)时配色停在旧主题上
watch(() => theme.isDark, () => {
  if (!running) draw(performance.now())
})
</script>

<style scoped>
.hero-backdrop {
  position: absolute;
  inset: 0;
  z-index: 1;
  width: 100%;
  height: 100%;
  display: block;
  pointer-events: none;
}
</style>
