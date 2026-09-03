import * as THREE from 'three'

export type RuntimeSceneApi = {
  canvas: HTMLCanvasElement
  setScroll: (t: number) => void
  setPointer: (nx: number, ny: number) => void
  resize: (w: number, h: number) => void
  start: () => void
  dispose: () => void
}

type NodeData = {
  mesh: THREE.Mesh
  glow: THREE.Sprite
  base: THREE.Vector3
  phase: number
  speed: number
  amp: number
  pulse: number
}

/**
 * Agent Java 运行时 3D 场景：
 * 墨黑雾境 + 朱红主灯 + 多智能体节点网络 + 地面网格 + 粒子尘埃。
 * 相机随滚动推进，鼠标做轻微视差。
 */
export function createRuntimeScene(
  container: HTMLElement,
  onReady?: () => void,
): RuntimeSceneApi {
  const canvas = document.createElement('canvas')
  canvas.id = 'gl'
  canvas.setAttribute('aria-hidden', 'true')
  container.appendChild(canvas)

  const renderer = new THREE.WebGLRenderer({
    canvas,
    antialias: true,
    alpha: false,
    powerPreference: 'high-performance',
  })
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2))
  renderer.setClearColor(0x05070a, 1)
  renderer.outputColorSpace = THREE.SRGBColorSpace
  renderer.toneMapping = THREE.ACESFilmicToneMapping
  renderer.toneMappingExposure = 1.12

  const scene = new THREE.Scene()
  scene.fog = new THREE.FogExp2(0x05070a, 0.022)

  // 场景整体右偏，给 Hero 左侧文案让出视觉中心（窄屏减弱偏移）
  const world = new THREE.Group()
  world.position.set(1.35, 0, 0)
  scene.add(world)

  const updateWorldOffset = (aspect: number) => {
    // 宽屏偏右让出文案；竖屏/窄屏回中，避免核心被裁切
    world.position.x = aspect < 0.85 ? 0.15 : aspect < 1.1 ? 0.55 : 1.35
  }

  const camera = new THREE.PerspectiveCamera(40, 1, 0.1, 120)
  camera.position.set(0, 2.35, 9.2)

  // —— 光 ——
  scene.add(new THREE.AmbientLight(0x9aa89c, 0.28))
  scene.add(new THREE.HemisphereLight(0xdfe7e0, 0x0a0e12, 0.35))

  const key = new THREE.PointLight(0xe0231c, 52, 48, 1.8)
  key.position.set(0.6, 5.2, 1.2)
  world.add(key)

  const fill = new THREE.PointLight(0xc9a24a, 10, 32, 2)
  fill.position.set(-5.5, 2.8, 4.5)
  world.add(fill)

  const rim = new THREE.DirectionalLight(0xdfe7e0, 0.42)
  rim.position.set(5, 9, -7)
  scene.add(rim)

  const soft = new THREE.PointLight(0x6a8f7a, 6, 26, 2)
  soft.position.set(4, 1.2, 5)
  world.add(soft)

  // —— 地面 ——
  const groundGeo = new THREE.CircleGeometry(38, 96)
  const groundMat = new THREE.MeshStandardMaterial({
    color: 0x070a0d,
    metalness: 0.72,
    roughness: 0.48,
  })
  const ground = new THREE.Mesh(groundGeo, groundMat)
  ground.rotation.x = -Math.PI / 2
  ground.position.y = -0.02
  world.add(ground)

  // 径向淡出网格
  const grid = new THREE.GridHelper(48, 48, 0x3a4a42, 0x161d1a)
  grid.position.y = 0.01
  const gridMats = Array.isArray(grid.material) ? grid.material : [grid.material]
  gridMats.forEach((m) => {
    m.transparent = true
    m.opacity = 0.28
    m.depthWrite = false
  })
  world.add(grid)

  // 地面高光环
  const floorRingGeo = new THREE.RingGeometry(1.6, 1.72, 96)
  const floorRingMat = new THREE.MeshBasicMaterial({
    color: 0xe0231c,
    transparent: true,
    opacity: 0.22,
    side: THREE.DoubleSide,
    depthWrite: false,
  })
  const floorRing = new THREE.Mesh(floorRingGeo, floorRingMat)
  floorRing.rotation.x = -Math.PI / 2
  floorRing.position.y = 0.03
  world.add(floorRing)

  const floorRing2Geo = new THREE.RingGeometry(3.1, 3.16, 96)
  const floorRing2 = new THREE.Mesh(
    floorRing2Geo,
    new THREE.MeshBasicMaterial({
      color: 0xdfe7e0,
      transparent: true,
      opacity: 0.1,
      side: THREE.DoubleSide,
      depthWrite: false,
    }),
  )
  floorRing2.rotation.x = -Math.PI / 2
  floorRing2.position.y = 0.03
  world.add(floorRing2)

  // —— 中央运行核 ——
  const coreGroup = new THREE.Group()
  coreGroup.position.y = 1.65
  world.add(coreGroup)

  const coreGeo = new THREE.IcosahedronGeometry(0.82, 2)
  const coreMat = new THREE.MeshPhysicalMaterial({
    color: 0x12181d,
    metalness: 0.92,
    roughness: 0.18,
    clearcoat: 0.85,
    clearcoatRoughness: 0.12,
    emissive: 0xe0231c,
    emissiveIntensity: 0.22,
  })
  const core = new THREE.Mesh(coreGeo, coreMat)
  coreGroup.add(core)

  // 线框外壳
  const shellGeo = new THREE.IcosahedronGeometry(1.05, 1)
  const shellMat = new THREE.MeshBasicMaterial({
    color: 0xdfe7e0,
    wireframe: true,
    transparent: true,
    opacity: 0.14,
  })
  const shell = new THREE.Mesh(shellGeo, shellMat)
  coreGroup.add(shell)

  // 内层朱红核
  const innerGeo = new THREE.IcosahedronGeometry(0.36, 1)
  const innerMat = new THREE.MeshPhysicalMaterial({
    color: 0xe0231c,
    emissive: 0xe0231c,
    emissiveIntensity: 1.1,
    metalness: 0.2,
    roughness: 0.35,
    transparent: true,
    opacity: 0.92,
  })
  const inner = new THREE.Mesh(innerGeo, innerMat)
  coreGroup.add(inner)

  // 轨道环
  const makeRing = (radius: number, tube: number, color: number, opacity: number) => {
    const geo = new THREE.TorusGeometry(radius, tube, 16, 128)
    const mat = new THREE.MeshPhysicalMaterial({
      color,
      metalness: 0.9,
      roughness: 0.18,
      emissive: color,
      emissiveIntensity: 0.15,
      transparent: true,
      opacity,
    })
    return new THREE.Mesh(geo, mat)
  }

  const ringA = makeRing(1.38, 0.018, 0xdfe7e0, 0.85)
  ringA.rotation.x = Math.PI / 2.35
  coreGroup.add(ringA)

  const ringB = makeRing(1.62, 0.012, 0xe0231c, 0.55)
  ringB.rotation.x = Math.PI / 2
  ringB.rotation.y = 0.55
  coreGroup.add(ringB)

  const ringC = makeRing(1.95, 0.008, 0xc9a24a, 0.35)
  ringC.rotation.x = Math.PI / 1.75
  ringC.rotation.z = 0.4
  coreGroup.add(ringC)

  // 核心光晕 sprite
  const glowTex = makeGlowTexture()
  const coreGlow = new THREE.Sprite(
    new THREE.SpriteMaterial({
      map: glowTex,
      color: 0xe0231c,
      transparent: true,
      opacity: 0.35,
      depthWrite: false,
      blending: THREE.AdditiveBlending,
    }),
  )
  coreGlow.scale.set(3.2, 3.2, 1)
  coreGroup.add(coreGlow)

  // 朱红「月」
  const moonGroup = new THREE.Group()
  moonGroup.position.set(3.4, 5.6, -5.2)
  world.add(moonGroup)

  const moonGeo = new THREE.SphereGeometry(0.52, 64, 64)
  const moonMat = new THREE.MeshPhysicalMaterial({
    color: 0xe0231c,
    emissive: 0xe0231c,
    emissiveIntensity: 1.35,
    metalness: 0.08,
    roughness: 0.42,
    clearcoat: 0.4,
  })
  const moon = new THREE.Mesh(moonGeo, moonMat)
  moonGroup.add(moon)

  const moonHalo = new THREE.Sprite(
    new THREE.SpriteMaterial({
      map: glowTex,
      color: 0xff4a32,
      transparent: true,
      opacity: 0.55,
      depthWrite: false,
      blending: THREE.AdditiveBlending,
    }),
  )
  moonHalo.scale.set(4.8, 4.8, 1)
  moonGroup.add(moonHalo)

  const moonHalo2 = new THREE.Sprite(
    new THREE.SpriteMaterial({
      map: glowTex,
      color: 0xe0231c,
      transparent: true,
      opacity: 0.22,
      depthWrite: false,
      blending: THREE.AdditiveBlending,
    }),
  )
  moonHalo2.scale.set(8.5, 8.5, 1)
  moonGroup.add(moonHalo2)

  // —— Agent 节点网络 ——
  const nodeCount = 16
  const nodes: NodeData[] = []
  const nodeGeo = new THREE.SphereGeometry(0.1, 24, 24)
  const accentGeo = new THREE.OctahedronGeometry(0.12, 0)

  for (let i = 0; i < nodeCount; i++) {
    const a = (i / nodeCount) * Math.PI * 2 + 0.2
    const ring = i % 3
    const r = 2.05 + ring * 0.72 + Math.sin(i * 1.9) * 0.18
    const y = 0.55 + (i % 5) * 0.42 + Math.cos(i * 1.1) * 0.18
    const x = Math.cos(a) * r
    const z = Math.sin(a) * r * 0.78
    const base = new THREE.Vector3(x, y, z)
    const isAccent = i % 4 === 0

    const mat = new THREE.MeshPhysicalMaterial({
      color: isAccent ? 0xe8ece8 : 0xdfe7e0,
      metalness: 0.78,
      roughness: 0.22,
      emissive: isAccent ? 0xe0231c : 0x4a5a52,
      emissiveIntensity: isAccent ? 0.65 : 0.28,
      clearcoat: 0.55,
      clearcoatRoughness: 0.2,
    })

    const mesh = new THREE.Mesh(isAccent ? accentGeo : nodeGeo, mat)
    mesh.position.copy(base)
    mesh.scale.setScalar(isAccent ? 1.15 : 0.75 + (i % 3) * 0.18)
    world.add(mesh)

    const glow = new THREE.Sprite(
      new THREE.SpriteMaterial({
        map: glowTex,
        color: isAccent ? 0xe0231c : 0xa8b8b0,
        transparent: true,
        opacity: isAccent ? 0.38 : 0.18,
        depthWrite: false,
        blending: THREE.AdditiveBlending,
      }),
    )
    glow.scale.set(isAccent ? 0.9 : 0.55, isAccent ? 0.9 : 0.55, 1)
    glow.position.copy(base)
    world.add(glow)

    nodes.push({
      mesh,
      glow,
      base: base.clone(),
      phase: Math.random() * Math.PI * 2,
      speed: 0.35 + Math.random() * 0.55,
      amp: 0.07 + Math.random() * 0.1,
      pulse: 0.8 + Math.random() * 0.4,
    })
  }

  // 连线
  const coreAnchor = new THREE.Vector3(0, 1.65, 0)
  type Edge = { a: number; b: number | 'core' }
  const edges: Edge[] = []
  for (let i = 0; i < nodeCount; i++) {
    edges.push({ a: i, b: 'core' })
    if (i % 2 === 0) edges.push({ a: i, b: (i + 1) % nodeCount })
    if (i % 3 === 0) edges.push({ a: i, b: (i + 5) % nodeCount })
  }
  const linePosAttr = new Float32Array(edges.length * 6)
  const lineGeo = new THREE.BufferGeometry()
  lineGeo.setAttribute('position', new THREE.BufferAttribute(linePosAttr, 3))
  const lineMat = new THREE.LineBasicMaterial({
    color: 0x7a8a82,
    transparent: true,
    opacity: 0.28,
  })
  const lines = new THREE.LineSegments(lineGeo, lineMat)
  world.add(lines)

  const writeEdges = () => {
    const attr = lineGeo.getAttribute('position') as THREE.BufferAttribute
    let o = 0
    for (let i = 0; i < edges.length; i++) {
      const e = edges[i]
      const pa = nodes[e.a].mesh.position
      const pb = e.b === 'core' ? coreAnchor : nodes[e.b].mesh.position
      attr.setXYZ(o++, pa.x, pa.y, pa.z)
      attr.setXYZ(o++, pb.x, pb.y, pb.z)
    }
    attr.needsUpdate = true
  }

  // 能量光束
  const beamCount = 7
  const beams: { line: THREE.Line; idx: number; t: number; speed: number }[] = []
  for (let i = 0; i < beamCount; i++) {
    const g = new THREE.BufferGeometry()
    g.setAttribute('position', new THREE.BufferAttribute(new Float32Array(6), 3))
    const m = new THREE.LineBasicMaterial({
      color: i % 2 === 0 ? 0xe0231c : 0xc9a24a,
      transparent: true,
      opacity: 0.6,
    })
    const line = new THREE.Line(g, m)
    world.add(line)
    beams.push({
      line,
      idx: i * 2,
      t: Math.random(),
      speed: 0.004 + Math.random() * 0.006,
    })
  }

  // —— 建筑感柱廊（圆柱 + 顶梁）——
  const pillarMat = new THREE.MeshPhysicalMaterial({
    color: 0x12181c,
    metalness: 0.55,
    roughness: 0.42,
    clearcoat: 0.25,
  })
  const pillarGeo = new THREE.CylinderGeometry(0.14, 0.16, 4.4, 20)
  const capGeo = new THREE.CylinderGeometry(0.22, 0.22, 0.1, 20)
  const pillarPositions: [number, number, number][] = [
    [-5.2, 2.2, -2.2],
    [5.2, 2.2, -2.2],
    [-4.0, 2.2, -6.2],
    [4.0, 2.2, -6.2],
  ]
  pillarPositions.forEach(([x, y, z]) => {
    const p = new THREE.Mesh(pillarGeo, pillarMat)
    p.position.set(x, y, z)
    world.add(p)
    const cap = new THREE.Mesh(capGeo, pillarMat)
    cap.position.set(x, y + 2.2, z)
    world.add(cap)
    const base = new THREE.Mesh(capGeo, pillarMat)
    base.position.set(x, 0.08, z)
    world.add(base)
  })

  const beamGeo = new THREE.BoxGeometry(10.8, 0.14, 0.28)
  const lintel = new THREE.Mesh(beamGeo, pillarMat)
  lintel.position.set(0, 4.35, -2.2)
  world.add(lintel)

  // —— 粒子尘埃（尺寸变化）——
  const dustCount = 1100
  const dustGeo = new THREE.BufferGeometry()
  const dustPos = new Float32Array(dustCount * 3)
  const dustSize = new Float32Array(dustCount)
  const dustSpeed: number[] = []
  for (let i = 0; i < dustCount; i++) {
    dustPos[i * 3] = (Math.random() - 0.5) * 30
    dustPos[i * 3 + 1] = Math.random() * 11
    dustPos[i * 3 + 2] = (Math.random() - 0.5) * 30
    dustSize[i] = 0.012 + Math.random() * 0.035
    dustSpeed.push(0.04 + Math.random() * 0.14)
  }
  dustGeo.setAttribute('position', new THREE.BufferAttribute(dustPos, 3))
  dustGeo.setAttribute('size', new THREE.BufferAttribute(dustSize, 1))
  const dustMat = new THREE.PointsMaterial({
    color: 0xdfe7e0,
    size: 0.028,
    transparent: true,
    opacity: 0.42,
    depthWrite: false,
    sizeAttenuation: true,
    map: glowTex,
    alphaTest: 0.01,
  })
  const dust = new THREE.Points(dustGeo, dustMat)
  world.add(dust)

  // —— 远景薄雾 ——
  const hazeGeo = new THREE.PlaneGeometry(48, 14)
  const hazeMat = new THREE.MeshBasicMaterial({
    color: 0x080c10,
    transparent: true,
    opacity: 0.5,
    depthWrite: false,
  })
  const haze = new THREE.Mesh(hazeGeo, hazeMat)
  haze.position.set(0, 3.2, -16)
  world.add(haze)

  // —— 状态 ——
  let scrollT = 0
  let targetScroll = 0
  let pointerX = 0
  let pointerY = 0
  let smoothPX = 0
  let smoothPY = 0
  let raf = 0
  let running = false
  let t0 = performance.now()

  const camFrom = new THREE.Vector3(0, 2.35, 9.2)
  const camTo = new THREE.Vector3(0.55, 3.6, 2.4)
  const lookFrom = new THREE.Vector3(1.1, 1.55, 0)
  const lookTo = new THREE.Vector3(1.0, 1.85, -2.2)
  const look = new THREE.Vector3()
  const camPos = new THREE.Vector3()

  const resize = (w: number, h: number) => {
    const width = Math.max(1, w)
    const height = Math.max(1, h)
    renderer.setSize(width, height, false)
    camera.aspect = width / height
    camera.updateProjectionMatrix()
    updateWorldOffset(width / height)
  }

  const setScroll = (t: number) => {
    targetScroll = THREE.MathUtils.clamp(t, 0, 1)
  }

  const setPointer = (nx: number, ny: number) => {
    pointerX = nx
    pointerY = ny
  }

  const updateBeams = () => {
    for (let i = 0; i < beams.length; i++) {
      const b = beams[i]
      b.t = (b.t + b.speed) % 1
      const node = nodes[(b.idx + i * 2) % nodes.length]
      const attr = b.line.geometry.getAttribute('position') as THREE.BufferAttribute
      const end = node.mesh.position
      const head = b.t
      const tail = Math.min(1, b.t + 0.1)
      const mid = new THREE.Vector3().lerpVectors(coreAnchor, end, head)
      const mid2 = new THREE.Vector3().lerpVectors(coreAnchor, end, tail)
      attr.setXYZ(0, mid.x, mid.y, mid.z)
      attr.setXYZ(1, mid2.x, mid2.y, mid2.z)
      attr.needsUpdate = true
      ;(b.line.material as THREE.LineBasicMaterial).opacity =
        0.2 + 0.55 * Math.sin(b.t * Math.PI)
    }
  }

  const tick = (now: number) => {
    if (!running) return
    raf = requestAnimationFrame(tick)
    const elapsed = (now - t0) / 1000

    scrollT += (targetScroll - scrollT) * 0.055
    smoothPX += (pointerX - smoothPX) * 0.045
    smoothPY += (pointerY - smoothPY) * 0.045

    const e = easeInOut(scrollT)
    camPos.lerpVectors(camFrom, camTo, e)
    camPos.x += smoothPX * 0.5
    camPos.y += smoothPY * 0.22
    camera.position.copy(camPos)

    look.lerpVectors(lookFrom, lookTo, e)
    look.x += smoothPX * 0.18
    camera.lookAt(look)

    // 核心
    core.rotation.y = elapsed * 0.22
    core.rotation.x = Math.sin(elapsed * 0.32) * 0.12
    shell.rotation.y = -elapsed * 0.12
    shell.rotation.z = elapsed * 0.08
    inner.rotation.y = -elapsed * 0.55
    inner.rotation.x = elapsed * 0.3
    ringA.rotation.z = elapsed * 0.35
    ringB.rotation.z = -elapsed * 0.22
    ringC.rotation.z = elapsed * 0.15
    coreGroup.position.y = 1.65 + Math.sin(elapsed * 0.65) * 0.055
    coreGlow.material.opacity = 0.28 + Math.sin(elapsed * 1.2) * 0.08
    coreMat.emissiveIntensity = 0.18 + Math.sin(elapsed * 1.1) * 0.08
    innerMat.emissiveIntensity = 0.95 + Math.sin(elapsed * 1.5) * 0.25

    // 节点漂浮
    for (let i = 0; i < nodes.length; i++) {
      const n = nodes[i]
      const yOff = Math.sin(elapsed * n.speed + n.phase) * n.amp
      const xOff = Math.cos(elapsed * n.speed * 0.7 + n.phase) * n.amp * 0.35
      n.mesh.position.set(n.base.x + xOff, n.base.y + yOff, n.base.z)
      n.mesh.rotation.y = elapsed * 0.45 + n.phase
      n.mesh.rotation.x = elapsed * 0.25
      n.glow.position.copy(n.mesh.position)
      const pulse = 0.85 + Math.sin(elapsed * n.pulse + n.phase) * 0.15
      n.glow.scale.setScalar((n.mesh.scale.x > 1 ? 0.95 : 0.55) * pulse)
    }
    coreAnchor.set(0, coreGroup.position.y, 0)
    writeEdges()

    // 粒子
    const dpos = dust.geometry.getAttribute('position') as THREE.BufferAttribute
    for (let i = 0; i < dustCount; i++) {
      let y = dpos.getY(i) + dustSpeed[i] * 0.016
      if (y > 11) y = 0
      dpos.setY(i, y)
    }
    dpos.needsUpdate = true
    dust.rotation.y = elapsed * 0.015

    // 灯与月
    key.intensity = 46 + Math.sin(elapsed * 1.35) * 8
    moonMat.emissiveIntensity = 1.15 + Math.sin(elapsed * 0.85) * 0.28
    moonGroup.position.y = 5.6 + Math.sin(elapsed * 0.32) * 0.14
    moonHalo.material.opacity = 0.48 + Math.sin(elapsed * 0.9) * 0.1
    floorRing.rotation.z = elapsed * 0.08
    floorRingMat.opacity = 0.16 + Math.sin(elapsed * 1.1) * 0.06

    updateBeams()

    if (scene.fog && scene.fog instanceof THREE.FogExp2) {
      scene.fog.density = 0.02 + scrollT * 0.012
    }

    renderer.render(scene, camera)
  }

  const start = () => {
    if (running) return
    running = true
    t0 = performance.now()
    raf = requestAnimationFrame(tick)
    onReady?.()
  }

  const dispose = () => {
    running = false
    cancelAnimationFrame(raf)
    renderer.dispose()
    groundGeo.dispose()
    groundMat.dispose()
    coreGeo.dispose()
    coreMat.dispose()
    shellGeo.dispose()
    shellMat.dispose()
    nodeGeo.dispose()
    accentGeo.dispose()
    dustGeo.dispose()
    dustMat.dispose()
    lineGeo.dispose()
    lineMat.dispose()
    moonGeo.dispose()
    moonMat.dispose()
    glowTex.dispose()
    if (canvas.parentElement) canvas.parentElement.removeChild(canvas)
  }

  const rect = container.getBoundingClientRect()
  resize(rect.width || window.innerWidth, rect.height || window.innerHeight)

  return { canvas, setScroll, setPointer, resize, start, dispose }
}

function easeInOut(t: number) {
  return t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2
}

/** 软光晕贴图（径向渐变，供 Sprite / Points 使用） */
function makeGlowTexture() {
  const size = 128
  const c = document.createElement('canvas')
  c.width = size
  c.height = size
  const ctx = c.getContext('2d')!
  const g = ctx.createRadialGradient(size / 2, size / 2, 0, size / 2, size / 2, size / 2)
  g.addColorStop(0, 'rgba(255,255,255,1)')
  g.addColorStop(0.25, 'rgba(255,255,255,0.55)')
  g.addColorStop(0.55, 'rgba(255,255,255,0.12)')
  g.addColorStop(1, 'rgba(255,255,255,0)')
  ctx.fillStyle = g
  ctx.fillRect(0, 0, size, size)
  const tex = new THREE.CanvasTexture(c)
  tex.colorSpace = THREE.SRGBColorSpace
  return tex
}
