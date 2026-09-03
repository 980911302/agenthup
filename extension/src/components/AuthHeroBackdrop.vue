<template>
  <canvas ref="canvasRef" class="hero-backdrop" aria-hidden="true"></canvas>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useThemeStore } from '../stores/theme'

const theme = useThemeStore()
const canvasRef = ref(null)

const NODE_COUNT = 100
const LINK_DISTANCE = 0.52
const PULSE_COUNT = 5

let ctx = null
let raf = 0
let width = 0
let height = 0
let dpr = 1
let nodes = []
let orbits = []
let pulses = []
let running = false

const tilt = { x: 0, y: 0, targetX: 0, targetY: 0 }
let spin = 0
let reduceMotion = false

function palette() {
  return theme.isDark
    ? {
        core: '#7dd3fc',
        accent: '#a78bfa',
        link: 'rgba(125, 211, 252, ',
        glowInner: 'rgba(56, 189, 248, 0.16)',
        glowOuter: 'rgba(124, 58, 237, 0.08)',
        orbit: 'rgba(148, 197, 255, ',
        nodeAlpha: 0.9,
        linkAlpha: 0.28,
        shadow: 12
      }
    : {
        core: '#2563eb',
        accent: '#7c3aed',
        link: 'rgba(37, 99, 235, ',
        glowInner: 'rgba(59, 130, 246, 0.14)',
        glowOuter: 'rgba(124, 58, 237, 0.06)',
        orbit: 'rgba(37, 99, 235, ',
        nodeAlpha: 0.9,
        linkAlpha: 0.28,
        shadow: 4
      }
}

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
      accent: i % 9 === 0,
      phase: Math.random() * Math.PI * 2,
      px: 0, py: 0, pz: 0, scale: 1
    }
  })
}

function buildOrbits() {
  orbits = [
    { radius: 1.28, tiltX: 0.55, tiltZ: 0.20, speed: 0.00042, offset: 0 },
    { radius: 1.50, tiltX: -0.35, tiltZ: 0.62, speed: -0.00031, offset: 2.1 }
  ]
}

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
  if (!running) draw(performance.now())
}

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
  const cy = height * 0.42
  const radius = Math.min(width, height) * 0.42

  ctx.clearRect(0, 0, width, height)

  const glow = ctx.createRadialGradient(cx, cy, 0, cx, cy, radius * 2.2)
  glow.addColorStop(0, p.glowInner)
  glow.addColorStop(0.5, p.glowOuter)
  glow.addColorStop(1, 'rgba(0,0,0,0)')
  ctx.fillStyle = glow
  ctx.fillRect(0, 0, width, height)

  for (const node of nodes) {
    const q = project(node, radius, cx, cy)
    node.px = q.px
    node.py = q.py
    node.pz = q.pz
    node.scale = q.scale
  }

  ctx.lineWidth = 1
  for (const orbit of orbits) {
    const angle = now * orbit.speed + orbit.offset
    ctx.beginPath()
    for (let i = 0; i <= 64; i++) {
      const t = (i / 64) * Math.PI * 2
      const raw = {
        x: Math.cos(t) * orbit.radius,
        y: Math.sin(t) * orbit.radius * orbit.tiltZ,
        z: Math.sin(t) * orbit.radius * Math.cos(orbit.tiltX)
      }
      const q = project(raw, radius, cx, cy)
      if (i === 0) ctx.moveTo(q.px, q.py)
      else ctx.lineTo(q.px, q.py)
    }
    ctx.strokeStyle = p.orbit + (theme.isDark ? 0.14 : 0.12) + ')'
    ctx.stroke()

    const raw = {
      x: Math.cos(angle) * orbit.radius,
      y: Math.sin(angle) * orbit.radius * orbit.tiltZ,
      z: Math.sin(angle) * orbit.radius * Math.cos(orbit.tiltX)
    }
    const q = project(raw, radius, cx, cy)
    const depth = (q.scale - 0.75) / 0.5
    ctx.beginPath()
    ctx.arc(q.px, q.py, 2.2 * q.scale, 0, Math.PI * 2)
    ctx.fillStyle = p.accent
    ctx.globalAlpha = Math.max(0.2, Math.min(1, depth))
    ctx.shadowColor = p.accent
    ctx.shadowBlur = p.shadow
    ctx.fill()
    ctx.shadowBlur = 0
    ctx.globalAlpha = 1
  }

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

  for (const pulse of pulses) {
    const a = nodes[pulse.a]
    const b = nodes[pulse.b]
    pulse.t += reduceMotion ? 0 : pulse.speed
    if (pulse.t >= 1) Object.assign(pulse, spawnPulse(), { t: 0 })
    const x = a.px + (b.px - a.px) * pulse.t
    const y = a.py + (b.py - a.py) * pulse.t
    const fade = Math.sin(pulse.t * Math.PI)
    ctx.beginPath()
    ctx.arc(x, y, 1.8, 0, Math.PI * 2)
    ctx.fillStyle = p.core
    ctx.globalAlpha = fade * 0.9
    ctx.shadowColor = p.core
    ctx.shadowBlur = p.shadow
    ctx.fill()
    ctx.shadowBlur = 0
    ctx.globalAlpha = 1
  }

  const ordered = [...nodes].sort((m, n) => n.pz - m.pz)
  for (const node of ordered) {
    const depth = Math.max(0, Math.min(1, (node.scale - 0.72) / 0.55))
    const breath = reduceMotion ? 1 : 0.82 + Math.sin(now * 0.0016 + node.phase) * 0.18
    const size = (node.accent ? 2.4 : 1.5) * node.scale * breath
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
  if (!reduceMotion) spin += 0.0011
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
  tilt.targetX = ((e.clientX - rect.left) / rect.width - 0.5) * 0.6
  tilt.targetY = ((e.clientY - rect.top) / rect.height - 0.5) * -0.4
}

function onVisibilityChange() {
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

watch(() => theme.isDark, () => {
  if (!running) draw(performance.now())
})
</script>

<style scoped>
.hero-backdrop {
  position: absolute;
  inset: 0;
  z-index: 0;
  width: 100%;
  height: 100%;
  display: block;
  pointer-events: none;
  opacity: 0.42;
}
</style>
