<template>
  <div class="gex" :class="{ 'is-embedded': embedded }">
    <div class="gex-toolbar">
      <div class="gex-search">
        <svg class="gex-search__icon" width="14" height="14" viewBox="0 0 15 15" fill="none" aria-hidden="true">
          <circle cx="6.5" cy="6.5" r="5" stroke="currentColor" stroke-width="1.4"/>
          <path d="M10.5 10.5L13.5 13.5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/>
        </svg>
        <input
          v-model="query"
          class="gex-search__input"
          :placeholder="scopeDocIds.length ? '在本文件实体中搜索…' : '搜索实体、主题关键词…'"
          @keyup.enter="runExplore"
        />
        <button
          v-if="query"
          type="button"
          class="gex-search__clear"
          @click="query = ''; runExplore()"
        >✕</button>
      </div>

      <div class="gex-depth" role="group" aria-label="探索范围">
        <button
          type="button"
          class="gex-depth__btn"
          :class="{ 'is-active': depth === 1 }"
          :disabled="loading"
          title="只显示与种子直接相连的实体"
          @click="setDepth(1)"
        >直接相关</button>
        <button
          type="button"
          class="gex-depth__btn"
          :class="{ 'is-active': depth === 2 }"
          :disabled="loading"
          title="再扩一层邻居（图更大，布局会自动拉开）"
          @click="setDepth(2)"
        >扩一层</button>
      </div>

      <button type="button" class="gex-btn gex-btn--primary" :disabled="loading" @click="runExplore">
        {{ loading ? '加载中…' : '探索' }}
      </button>
      <button
        type="button"
        class="gex-btn"
        :disabled="loading || !nodes.length"
        title="重新做力导向布局（不重新请求数据）"
        @click="relayout"
      >重排</button>
    </div>

    <div v-if="message" class="gex-status">
      <span class="gex-status__dot" :class="{ 'is-ok': nodes.length, 'is-warn': !nodes.length }" />
      {{ message }}
      <span class="gex-status__hint">拖拽旋转 · 滚轮缩放 · 点节点看详情</span>
    </div>

    <div class="gex-body" v-loading="loading">
      <div class="gex-canvas-wrap">
        <div ref="chartEl" class="gex-chart" />
        <div v-if="emptyHint && !loading" class="gex-empty">
          <div class="gex-empty__icon">◎</div>
          <p class="gex-empty__text">{{ emptyHint }}</p>
          <p class="gex-empty__hint">
            {{ scopeDocIds.length
              ? '请确认该文件已完成处理且开启了图谱抽取'
              : '可先上传文档并完成处理，或换个关键词再试' }}
          </p>
        </div>
        <div v-if="cats.length > 1 && nodes.length" class="gex-legend">
          <span v-for="c in cats" :key="c" class="gex-legend__item">
            <i :style="{ background: CATEGORY_COLOR[c] || CATEGORY_COLOR.other }" />
            {{ CAT_LABEL[c] || c }}
          </span>
        </div>
      </div>

      <aside class="gex-detail">
        <!-- 顶栏：空态 / 实体 / 关系 三种标题 -->
        <header class="gex-detail__head">
          <div class="gex-detail__head-text">
            <span class="gex-detail__eyebrow">{{ detailEyebrow }}</span>
            <h3 :title="detailOpen ? detailTitle : ''">{{ detailOpen ? detailTitle : '节点详情' }}</h3>
          </div>
          <button v-if="detailOpen" type="button" class="gex-detail__close" aria-label="关闭详情" @click="closeDetail">✕</button>
        </header>

        <div v-loading="detailLoading" class="gex-detail__body">
          <!-- 实体 -->
          <template v-if="detailKind === 'node' && entityDetail?.entity">
            <section class="gd-hero">
              <div class="gd-hero__row">
                <span class="gd-chip" :class="'is-' + entityCat">{{ entityTypeLabel }}</span>
                <span class="gd-stat" title="引用该实体的来源片段数">
                  <b>{{ entityDetail.entity.sourceCount || 0 }}</b> 来源
                </span>
                <span v-if="(entityDetail.relations || []).length" class="gd-stat">
                  <b>{{ entityDetail.relations.length }}</b> 关系
                </span>
              </div>
              <p class="gd-hero__desc">
                {{ entityDetail.entity.description || '暂无描述，可从下方来源证据了解上下文。' }}
              </p>
              <div v-if="(entityDetail.themes || []).length" class="gd-themes">
                <span
                  v-for="(t, i) in entityDetail.themes"
                  :key="i"
                  class="gd-theme"
                  :title="t.title || ('主题 ' + t.communityId)"
                >{{ t.title || ('主题 ' + t.communityId) }}</span>
              </div>
            </section>

            <section v-if="(entityDetail.relations || []).length" class="gd-sec">
              <div class="gd-sec__head">
                <h4>相关连接</h4>
                <span class="gd-sec__n">{{ entityDetail.relations.length }}</span>
              </div>
              <ul class="gd-rels">
                <li
                  v-for="(r, i) in entityDetail.relations"
                  :key="i"
                  class="gd-rel"
                  @click="loadRelation(r)"
                >
                  <span class="gd-rel__dir" :class="r.direction === 'out' ? 'is-out' : 'is-in'">
                    {{ r.direction === 'out' ? '出' : '入' }}
                  </span>
                  <div class="gd-rel__main">
                    <div class="gd-rel__name">{{ r.direction === 'out' ? r.target : r.source }}</div>
                    <div class="gd-rel__label">{{ r.label || '相关' }}</div>
                  </div>
                  <span class="gd-rel__go" aria-hidden="true">›</span>
                </li>
              </ul>
            </section>

            <section class="gd-sec">
              <div class="gd-sec__head">
                <h4>来源证据</h4>
                <span class="gd-sec__n">{{ (entityDetail.sources || []).length }}</span>
              </div>
              <div v-if="!(entityDetail.sources || []).length" class="gd-empty-inline">
                暂无引用片段
              </div>
              <ul v-else class="gd-srcs">
                <li v-for="(s, i) in entityDetail.sources" :key="i" class="gd-src">
                  <div class="gd-src__top">
                    <span class="gd-src__doc">{{ s.docName || ('文档 #' + s.docId) }}</span>
                    <span v-if="s.sourcePageFrom" class="gd-src__page">p.{{ s.sourcePageFrom }}</span>
                  </div>
                  <div v-if="s.headingPath || s.sourceLabel" class="gd-src__path">
                    {{ s.headingPath || s.sourceLabel }}
                  </div>
                  <p class="gd-src__snip">{{ s.snippet || '—' }}</p>
                </li>
              </ul>
            </section>

            <div class="gd-foot">
              <button
                type="button"
                class="gex-btn gex-btn--soft gd-foot__btn"
                @click="expandFrom(entityDetail.entity?.id || entityDetail.entity?.name)"
              >在图中展开关联</button>
            </div>
          </template>

          <!-- 关系 -->
          <template v-else-if="detailKind === 'edge' && relationDetail">
            <section class="gd-hero">
              <div class="gd-edge">
                <button type="button" class="gd-edge__node" @click="openNode(relationDetail.relation?.source)">
                  {{ relationDetail.relation?.source || '—' }}
                </button>
                <div class="gd-edge__mid">
                  <span class="gd-edge__line" />
                  <span class="gd-edge__lab">{{ relationDetail.relation?.label || '相关' }}</span>
                  <span class="gd-edge__line" />
                </div>
                <button type="button" class="gd-edge__node" @click="openNode(relationDetail.relation?.target)">
                  {{ relationDetail.relation?.target || '—' }}
                </button>
              </div>
              <p class="gd-hero__desc">
                {{ relationDetail.relation?.description || '暂无关系说明，可从来源证据查看上下文。' }}
              </p>
            </section>

            <section class="gd-sec">
              <div class="gd-sec__head">
                <h4>来源证据</h4>
                <span class="gd-sec__n">{{ (relationDetail.sources || []).length }}</span>
              </div>
              <div v-if="!(relationDetail.sources || []).length" class="gd-empty-inline">
                暂无引用片段
              </div>
              <ul v-else class="gd-srcs">
                <li v-for="(s, i) in relationDetail.sources" :key="i" class="gd-src">
                  <div class="gd-src__top">
                    <span class="gd-src__doc">{{ s.docName || ('文档 #' + s.docId) }}</span>
                    <span v-if="s.sourcePageFrom" class="gd-src__page">p.{{ s.sourcePageFrom }}</span>
                  </div>
                  <div v-if="s.headingPath" class="gd-src__path">{{ s.headingPath }}</div>
                  <p class="gd-src__snip">{{ s.snippet || '—' }}</p>
                </li>
              </ul>
            </section>
          </template>

          <!-- 空态 -->
          <div v-else-if="!detailLoading" class="gex-detail-empty">
            <div class="gex-detail-empty__icon">
              <svg width="22" height="22" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <circle cx="4" cy="4" r="1.6" stroke="currentColor" stroke-width="1.3"/>
                <circle cx="12" cy="5" r="1.6" stroke="currentColor" stroke-width="1.3"/>
                <circle cx="8" cy="12" r="1.6" stroke="currentColor" stroke-width="1.3"/>
                <path d="M5.4 4.8l5.2.6M5 5.4l2.4 5.2M11.2 6.2L9 10.6" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/>
              </svg>
            </div>
            <p>点选图中的节点</p>
            <span>查看简介、连接与文档出处</span>
          </div>
        </div>
      </aside>
    </div>
  </div>
</template>

<script setup>
/**
 * 3D 知识图谱：3d-force-graph (Three.js + WebGL)
 * 第二大脑/知识图谱演示常用的立体力导向效果：可旋转、缩放、粒子连线。
 * 实体名称：three-spritetext 始终贴在节点旁（默认可见，非悬停才出）。
 */
import ForceGraph3D from '3d-force-graph'
import SpriteText from 'three-spritetext'
import { graphExplore, graphEntityDetail, graphRelationDetail } from '@/api/ai/kb'

const props = defineProps({
  kbId: { type: [Number, String], required: true },
  initial: { type: Object, default: null },
  docId: { type: [Number, String], default: null },
  docIds: { type: Array, default: null },
  embedded: { type: Boolean, default: false }
})

const query = ref('')
/** 探索深度：1=直接相关，2=再扩一层 */
const depth = ref(1)
const message = ref('')
const emptyHint = ref('')
const loading = ref(false)
const chartEl = ref(null)

const nodes = ref([])
const edges = ref([])
const cats = ref([])

const detailOpen = ref(false)
const detailLoading = ref(false)
const detailKind = ref('node')
const detailTitle = ref('')
const entityDetail = ref(null)
const relationDetail = ref(null)
const selectedNodeId = ref(null)

let fg = null
let resizeObs = null
/** 防止快速切换范围时旧请求覆盖新结果 */
let exploreSeq = 0
let pendingRenderTimer = null
/** 仅首次布局结束后 zoomToFit；用户点节点/旋转后不再抢视角 */
let initialFitDone = false
let cameraUserControlled = false
let fitTimer = null

/** 3D 相机控制灵敏度（Trackball / Orbit 通用；默认约 1.0~1.2） */
const CAMERA_ZOOM_SPEED = 7.2   // 滚轮放大/缩小，再加快
const CAMERA_ROTATE_SPEED = 1.5
const CAMERA_PAN_SPEED = 0.7

function applyCameraSensitivity(graph) {
  if (!graph || typeof graph.controls !== 'function') return
  try {
    const c = graph.controls()
    if (!c) return
    if (c.zoomSpeed != null) c.zoomSpeed = CAMERA_ZOOM_SPEED
    if (c.rotateSpeed != null) c.rotateSpeed = CAMERA_ROTATE_SPEED
    if (c.panSpeed != null) c.panSpeed = CAMERA_PAN_SPEED
    // OrbitControls 另有字段
    if (c.enableZoom != null) c.enableZoom = true
  } catch { /* ignore */ }
}

// 高饱和配色，深色 3D 场景下仍醒目
const CATEGORY_COLOR = {
  person: '#FF5C7A',
  org: '#00D4AA',
  loc: '#3DBBFF',
  event: '#FFC53D',
  doc: '#B56BFF',
  concept: '#4C9AFF',
  other: '#8E9AAF'
}
const CAT_LABEL = {
  person: '人物', org: '组织', loc: '地点', event: '事件',
  doc: '文档', concept: '概念', other: '其他'
}

const detailEyebrow = computed(() => {
  if (!detailOpen.value) return '知识图谱'
  if (detailKind.value === 'edge') return '关系'
  return '实体'
})

function categoryOfType(type) {
  const t = String(type || '').toUpperCase()
  if (t.includes('PERSON') || t.includes('人')) return 'person'
  if (t.includes('ORG') || t.includes('组织') || t.includes('公司')) return 'org'
  if (t.includes('LOC') || t.includes('地')) return 'loc'
  if (t.includes('EVENT') || t.includes('事件')) return 'event'
  if (t.includes('DOC') || t.includes('文档')) return 'doc'
  if (t.includes('CONCEPT') || t.includes('概念')) return 'concept'
  return 'other'
}

const entityCat = computed(() => {
  const e = entityDetail.value?.entity
  if (!e) return 'other'
  const cat = String(e.category || '').toLowerCase()
  if (cat && CATEGORY_COLOR[cat]) return cat
  return categoryOfType(e.type)
})

const entityTypeLabel = computed(() => {
  const e = entityDetail.value?.entity
  if (!e) return '实体'
  return CAT_LABEL[entityCat.value] || e.type || '实体'
})

/** 稳定哈希色：无 category 时也保证每个实体有独立颜色 */
function hashColor(str) {
  const s = String(str || 'x')
  let h = 0
  for (let i = 0; i < s.length; i++) h = ((h << 5) - h) + s.charCodeAt(i) | 0
  const hue = Math.abs(h) % 360
  // HSL → 饱和亮色
  return `hsl(${hue} 78% 62%)`
}

function resolveNodeColor(n) {
  const cat = String(n.category || n.raw?.category || '').toLowerCase()
  if (cat && CATEGORY_COLOR[cat]) return CATEGORY_COLOR[cat]
  // type 里也可能是 PERSON / 中文
  const type = String(n.raw?.type || n.type || '').toUpperCase()
  if (type.includes('PERSON') || type.includes('人')) return CATEGORY_COLOR.person
  if (type.includes('ORG') || type.includes('组织') || type.includes('公司')) return CATEGORY_COLOR.org
  if (type.includes('LOC') || type.includes('地')) return CATEGORY_COLOR.loc
  if (type.includes('EVENT') || type.includes('事件')) return CATEGORY_COLOR.event
  if (type.includes('DOC') || type.includes('文档')) return CATEGORY_COLOR.doc
  if (type.includes('CONCEPT') || type.includes('概念')) return CATEGORY_COLOR.concept
  return hashColor(n.name || n.id)
}

function makeNodeLabelSprite(node) {
  const text = String(node.name || node.id || '')
  const sprite = new SpriteText(text.length > 16 ? text.slice(0, 15) + '…' : text)
  sprite.color = '#F5F5F7'
  sprite.textHeight = 3.2
  sprite.fontFace = '-apple-system, BlinkMacSystemFont, "SF Pro Text", "PingFang SC", sans-serif'
  sprite.fontWeight = '600'
  sprite.strokeWidth = 0.6
  sprite.strokeColor = 'rgba(0,0,0,0.75)'
  sprite.padding = 1.2
  // 略偏节点下方，始终面向相机
  sprite.center.y = -0.9
  if (sprite.material) {
    sprite.material.depthWrite = false
    sprite.material.depthTest = false
  }
  return sprite
}

const scopeDocIds = computed(() => {
  if (props.docIds?.length) return props.docIds.map(Number).filter(n => !Number.isNaN(n))
  if (props.docId != null && props.docId !== '') {
    const n = Number(props.docId)
    return Number.isNaN(n) ? [] : [n]
  }
  return []
})

function isDark() {
  return document.documentElement.classList.contains('dark')
}

function setDepth(d) {
  const next = d === 2 ? 2 : 1
  if (depth.value === next && !loading.value) {
    // 同档再点一次也刷新
    runExplore()
    return
  }
  depth.value = next
  runExplore()
}

/**
 * 按节点规模 + 探索深度调力参数，避免「扩一层」后连线缩成一团。
 * depth=2 / 节点多时：斥力更强、边更长、冷却更久，让图充分展开。
 */
function forceParams(nodeCount, linkCount, hop) {
  const n = Math.max(1, nodeCount || 1)
  const e = Math.max(0, linkCount || 0)
  const dense = e / Math.max(n, 1)
  const hopBoost = hop >= 2 ? 1.4 : 1
  // 斥力适中：过强会把点弹出视野，看起来像「空图」
  const charge = -Math.min(180, (36 + Math.sqrt(n) * 14 + dense * 10) * hopBoost)
  const linkDist = Math.min(72, (26 + Math.sqrt(n) * 5 + dense * 3) * (hop >= 2 ? 1.25 : 1))
  const cooldown = hop >= 2 ? Math.min(220, 120 + n) : Math.min(150, 90 + n * 0.8)
  const alphaDecay = hop >= 2 ? 0.022 : 0.028
  const velocityDecay = hop >= 2 ? 0.32 : 0.36
  const spawnR = Math.min(140, 40 + Math.sqrt(n) * 12 * hopBoost)
  const camDist = Math.max(260, 70 + Math.sqrt(n) * (hop >= 2 ? 48 : 38))
  return { charge, linkDist, cooldown, alphaDecay, velocityDecay, spawnR, camDist }
}

/**
 * 只改已有 force 的参数，绝不 remove/replace force。
 * 必须在 graphData 之后调用；过早 d3Reheat 会让 layout 未就绪就 tick → 白屏报错。
 */
function applyForces(graph, nodeCount, linkCount, hop = depth.value) {
  if (!graph) return forceParams(nodeCount, linkCount, hop)
  const p = forceParams(nodeCount, linkCount, hop)
  try {
    const charge = graph.d3Force('charge')
    if (charge && typeof charge.strength === 'function') {
      charge.strength(p.charge)
    }
    const linkF = graph.d3Force('link')
    if (linkF && typeof linkF.distance === 'function') {
      linkF.distance(p.linkDist)
    }
  } catch { /* ignore */ }
  try {
    graph.cooldownTicks(p.cooldown)
    graph.d3AlphaDecay(p.alphaDecay)
    graph.d3VelocityDecay(p.velocityDecay)
  } catch { /* ignore */ }
  return p
}

function runExplore(extra = {}) {
  if (!props.kbId) return
  loading.value = true
  const seq = ++exploreSeq
  // 外部若显式传 depth 则同步到 UI
  if (extra.depth != null) {
    depth.value = extra.depth === 2 ? 2 : 1
  }
  const hop = depth.value
  const body = {
    query: query.value || undefined,
    depth: hop,
    // 二跳节点/边更多，略抬 limit（后端会 clamp）
    limit: scopeDocIds.value.length
      ? (hop >= 2 ? 120 : 80)
      : (hop >= 2 ? 100 : 60),
    edgeLimit: scopeDocIds.value.length
      ? (hop >= 2 ? 300 : 200)
      : (hop >= 2 ? 260 : 150),
    ...extra
  }
  // extra 可能带 depth，最终以 UI 为准
  body.depth = extra.depth != null ? (extra.depth === 2 ? 2 : 1) : hop
  if (scopeDocIds.value.length) body.docIds = scopeDocIds.value
  if (props.initial?.communityId && !query.value && !extra.seedNames) {
    body.communityId = props.initial.communityId
  }
  if (props.initial?.seedNames && !query.value) {
    body.seedNames = props.initial.seedNames
  }
  graphExplore(props.kbId, body).then(res => {
    if (seq !== exploreSeq) return
    const data = res.data || {}
    message.value = data.userMessage || ''
    nodes.value = Array.isArray(data.nodes) ? data.nodes : []
    edges.value = Array.isArray(data.edges) ? data.edges : []
    cats.value = [...new Set(nodes.value.map(n => n.category || 'other'))]
    // available 缺省时仍尝试渲染（避免旧接口/包装导致误判空）
    if (data.available === false) emptyHint.value = data.userMessage || '图谱不可用'
    else if (!nodes.value.length) emptyHint.value = data.userMessage || '无结果'
    else emptyHint.value = ''
    loading.value = false
    nextTick(() => scheduleRenderGraph())
  }).catch(() => {
    if (seq !== exploreSeq) return
    loading.value = false
    emptyHint.value = '探索失败'
  })
}

/** 等容器有尺寸再画，避免 sheet 打开瞬间 clientHeight=0 画出空画布 */
function scheduleRenderGraph(attempt = 0) {
  if (pendingRenderTimer) {
    clearTimeout(pendingRenderTimer)
    pendingRenderTimer = null
  }
  const el = chartEl.value
  if (!nodes.value.length) {
    destroyGraph()
    return
  }
  if (el && (el.clientWidth > 40 && el.clientHeight > 40)) {
    renderGraph()
    return
  }
  if (attempt >= 12) {
    // 兜底：强制给尺寸再画
    renderGraph()
    return
  }
  pendingRenderTimer = setTimeout(() => scheduleRenderGraph(attempt + 1), 50 + attempt * 20)
}

function destroyGraph() {
  const inst = fg
  fg = null
  initialFitDone = false
  cameraUserControlled = false
  if (fitTimer) {
    clearTimeout(fitTimer)
    fitTimer = null
  }
  if (!inst) {
    if (chartEl.value) chartEl.value.innerHTML = ''
    return
  }
  try {
    // 先停动画，避免 rAF 在 layout 清空后继续 tick
    inst.pauseAnimation?.()
  } catch { /* ignore */ }
  try {
    // 不要在销毁前 graphData([])：会 engineRunning=true 且 layout 时序不稳
    if (typeof inst._destructor === 'function') inst._destructor()
  } catch { /* ignore */ }
  if (chartEl.value) chartEl.value.innerHTML = ''
}

/** 仅在用户未手动操作相机时，做一次总览适配 */
function fitOnce(graph, ms = 400, padding = 50) {
  if (!graph || cameraUserControlled || initialFitDone) return
  try {
    if (typeof graph.zoomToFit === 'function' && nodes.value.length) {
      graph.zoomToFit(ms, padding)
      initialFitDone = true
    }
  } catch { /* ignore */ }
}

/** 重排：打散节点坐标，重新跑力导向（不重新请求接口） */
function relayout() {
  if (!fg || !nodes.value.length) return
  try {
    const data = fg.graphData()
    const gNodes = data?.nodes || []
    const gLinks = data?.links || []
    const p = applyForces(fg, gNodes.length, gLinks.length, depth.value)
    const r = p?.spawnR || 80
    gNodes.forEach(n => {
      n.fx = n.fy = n.fz = undefined
      n.x = (Math.random() - 0.5) * r * 2
      n.y = (Math.random() - 0.5) * r * 2
      n.z = (Math.random() - 0.5) * r * 2
      n.vx = n.vy = n.vz = 0
    })
    // 用 graphData 重喂以重建 layout，比单独 reheat 更稳
    fg.graphData({ nodes: gNodes, links: gLinks })
    applyForces(fg, gNodes.length, gLinks.length, depth.value)
    const dist = p?.camDist || Math.max(280, 80 + Math.sqrt(gNodes.length) * 45)
    fg.cameraPosition({ x: dist * 0.6, y: dist * 0.35, z: dist }, { x: 0, y: 0, z: 0 }, 600)
    // 重排是主动操作，允许再 fit 一次
    initialFitDone = false
    cameraUserControlled = false
    fitTimer = setTimeout(() => {
      fitTimer = null
      fitOnce(fg, 500, 48)
    }, 400)
  } catch (e) {
    console.warn('relayout failed, redraw', e)
    destroyGraph()
    nextTick(() => scheduleRenderGraph())
  }
}

function buildGraphPayload() {
  const hop = depth.value
  const params = forceParams(nodes.value.length, edges.value.length, hop)
  const spawnR = params.spawnR
  const linkCol = 'rgba(148,163,184,0.55)'

  const gNodes = nodes.value.map(n => {
    const cat = String(n.category || 'other').toLowerCase()
    const color = resolveNodeColor({ ...n, category: cat, raw: n })
    const id = String(n.id != null && n.id !== '' ? n.id : n.name)
    return {
      id,
      name: n.name || id,
      val: 2.2 + Math.min(10, (n.sourceCount || 0) * 0.85),
      color,
      category: cat,
      raw: n,
      x: (Math.random() - 0.5) * spawnR * 2,
      y: (Math.random() - 0.5) * spawnR * 2,
      z: (Math.random() - 0.5) * spawnR * 2
    }
  })
  const idSet = new Set(gNodes.map(n => n.id))
  const idLower = new Map([...idSet].map(id => [id.toLowerCase(), id]))
  const gLinks = []
  edges.value.forEach((e, i) => {
    let s = String(e.source ?? '')
    let t = String(e.target ?? '')
    if (!idSet.has(s) && idLower.has(s.toLowerCase())) s = idLower.get(s.toLowerCase())
    if (!idSet.has(t) && idLower.has(t.toLowerCase())) t = idLower.get(t.toLowerCase())
    if (!idSet.has(s) || !idSet.has(t) || s === t) return
    gLinks.push({
      id: `e${i}`,
      source: s,
      target: t,
      label: e.label || '',
      color: linkCol,
      raw: e
    })
  })
  return { gNodes, gLinks, params, hop, linkCol }
}

function renderGraph() {
  if (!chartEl.value) return

  if (!nodes.value.length) {
    destroyGraph()
    return
  }

  try {
    const bg = '#0b0d12'
    const particleCol = '#7DD3FC'
    const { gNodes, gLinks, params, hop, linkCol } = buildGraphPayload()
    const w = Math.max(chartEl.value.clientWidth || 0, 480)
    const h = Math.max(chartEl.value.clientHeight || 0, 360)

    // 每次全量重建更稳：避免旧实例 rAF 与新数据交错导致 layout 为 undefined
    destroyGraph()
    if (!chartEl.value) return

    const graph = ForceGraph3D()(chartEl.value)
    fg = graph

    graph
      .backgroundColor(bg)
      .showNavInfo(false)
      .enableNodeDrag(true)
      .enableNavigationControls(true)
      .nodeId('id')
      .nodeLabel(n => `${n.name}${n.raw?.type ? ' · ' + n.raw.type : ''}`)
      .nodeVal('val')
      .nodeColor(n => n.color || resolveNodeColor(n))
      .nodeOpacity(1)
      .nodeResolution(16)
      .nodeThreeObject(node => makeNodeLabelSprite(node))
      .nodeThreeObjectExtend(true)
      .linkColor(l => l.color || linkCol)
      .linkOpacity(0.75)
      .linkWidth(1.1)
      .linkDirectionalArrowLength(3.2)
      .linkDirectionalArrowRelPos(1)
      .linkDirectionalParticles(2)
      .linkDirectionalParticleWidth(1.4)
      .linkDirectionalParticleSpeed(0.005)
      .linkDirectionalParticleColor(() => particleCol)
      .linkLabel(l => l.label || '')
      .cooldownTicks(params.cooldown)
      .d3AlphaDecay(params.alphaDecay)
      .d3VelocityDecay(params.velocityDecay)
      .warmupTicks(20)
      .width(w)
      .height(h)
      .onNodeClick(node => {
        if (!fg) return
        // 用户已主动看某个节点：禁止后续 zoomToFit 把视角拽回全图
        cameraUserControlled = true
        selectedNodeId.value = node.id
        openNode(node.id || node.name)
        const dist = 140
        const hyp = Math.hypot(node.x || 1, node.y || 1, node.z || 1) || 1
        const distRatio = 1 + dist / hyp
        fg.cameraPosition(
          {
            x: (node.x || 0) * distRatio,
            y: (node.y || 0) * distRatio,
            z: (node.z || 0) * distRatio
          },
          node,
          700
        )
      })
      .onNodeDrag(() => {
        // 拖拽过程中也视为用户接管
        cameraUserControlled = true
      })
      .onNodeDragEnd(node => {
        // 松手后固定坐标，避免力导向把节点弹回原位（双击/轻拖尤其明显）
        if (!node) return
        node.fx = node.x
        node.fy = node.y
        node.fz = node.z
      })
      .onLinkClick(link => {
        cameraUserControlled = true
        const src = typeof link.source === 'object' ? link.source.id : link.source
        const tgt = typeof link.target === 'object' ? link.target.id : link.target
        openEdge({
          ...(link.raw || {}),
          source: src,
          target: tgt,
          label: link.label || link.raw?.label
        })
      })
      .onBackgroundClick(() => {
        selectedNodeId.value = null
      })
      .onEngineStop(() => {
        // 只在首次自动总览；用户点过节点后绝不再 fit
        if (fg === graph) fitOnce(graph, 400, 50)
      })

    // 关键：先喂数据建立 layout，再调力参数（禁止在 graphData 前 reheat）
    graph.graphData({ nodes: gNodes, links: gLinks })
    applyForces(graph, gNodes.length, gLinks.length, hop)
    applyCameraSensitivity(graph)

    try {
      const dist = params.camDist
      graph.cameraPosition({ x: dist * 0.55, y: dist * 0.3, z: dist }, { x: 0, y: 0, z: 0 }, 0)
    } catch { /* ignore */ }

    // sheet 动画结束后仅首次 fit
    fitTimer = setTimeout(() => {
      fitTimer = null
      if (fg !== graph) return
      onResize()
      fitOnce(graph, 500, 48)
    }, 320)
  } catch (err) {
    console.error('renderGraph failed', err)
    destroyGraph()
    emptyHint.value = '图谱渲染失败，请刷新重试'
  }
}

function openNode(name) {
  if (!name) return
  detailOpen.value = true
  detailKind.value = 'node'
  detailTitle.value = name
  detailLoading.value = true
  entityDetail.value = null
  relationDetail.value = null
  graphEntityDetail(props.kbId, name).then(res => {
    entityDetail.value = res.data || {}
    // 展示名（canonical）优先
    const display = entityDetail.value?.entity?.name
    if (display) detailTitle.value = display
    detailLoading.value = false
  }).catch(() => { detailLoading.value = false })
}

function openEdge(e) {
  if (!e) return
  detailOpen.value = true
  detailKind.value = 'edge'
  detailTitle.value = e.label || '关系'
  detailLoading.value = true
  relationDetail.value = null
  entityDetail.value = null
  graphRelationDetail(props.kbId, e.source, e.target, e.label).then(res => {
    relationDetail.value = res.data || {}
    detailLoading.value = false
  }).catch(() => { detailLoading.value = false })
}

function loadRelation(r) {
  openEdge(r)
}

function expandFrom(name) {
  if (!name) return
  runExplore({ seedNames: [name], depth: depth.value })
}

function closeDetail() {
  detailOpen.value = false
  detailKind.value = 'node'
  entityDetail.value = null
  relationDetail.value = null
  detailTitle.value = ''
  selectedNodeId.value = null
}

function onResize() {
  if (!chartEl.value) return
  const w = chartEl.value.clientWidth
  const h = chartEl.value.clientHeight
  if (w <= 0 || h <= 0) return
  if (fg) {
    fg.width(w).height(h)
  } else if (nodes.value.length) {
    scheduleRenderGraph()
  }
}

watch(() => props.kbId, () => {
  query.value = ''
  closeDetail()
  runExplore()
}, { immediate: true })

watch(() => [props.docId, props.docIds], () => {
  query.value = ''
  closeDetail()
  runExplore()
}, { deep: true })

watch(() => props.initial, (v) => {
  if (v?.communityId || v?.seedNames) runExplore()
}, { deep: true })

onMounted(() => {
  window.addEventListener('resize', onResize)
  if (chartEl.value && typeof ResizeObserver !== 'undefined') {
    resizeObs = new ResizeObserver(() => onResize())
    resizeObs.observe(chartEl.value)
  }
})

onUnmounted(() => {
  window.removeEventListener('resize', onResize)
  if (resizeObs) {
    resizeObs.disconnect()
    resizeObs = null
  }
  if (pendingRenderTimer) {
    clearTimeout(pendingRenderTimer)
    pendingRenderTimer = null
  }
  if (fitTimer) {
    clearTimeout(fitTimer)
    fitTimer = null
  }
  exploreSeq++
  destroyGraph()
})

function exploreTheme(communityId) {
  runExplore({ communityId })
}

function resize() {
  onResize()
}
defineExpose({ runExplore, exploreTheme, resize })
</script>

<style scoped lang="scss">
@use '../../../../assets/styles/ai-tokens.scss' as *;

.gex {
  display: flex;
  flex-direction: column;
  gap: 10px;
  font-family: $font;
  min-height: 480px;
  color: $text;
  &.is-embedded {
    flex: 1;
    min-height: 0;
    height: 100%;
    /* 保证 body 拿到剩余高度，3D 画布不会塌成 0 */
    overflow: hidden;
  }
}

.gex-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  flex-shrink: 0;
}

.gex-search {
  position: relative;
  flex: 1;
  min-width: 180px;
  max-width: 360px;
  &__icon {
    position: absolute;
    left: 12px;
    top: 50%;
    transform: translateY(-50%);
    color: $gray2;
    pointer-events: none;
  }
  &__input {
    width: 100%;
    height: 34px;
    padding: 0 30px 0 34px;
    border: none;
    border-radius: 980px;
    background: var(--ai-search-bg);
    font-size: 13px;
    font-family: $font;
    color: $text;
    outline: none;
    box-shadow: 0 1px 3px var(--ai-border);
    box-sizing: border-box;
    transition: all 0.2s $ease;
    &::placeholder { color: $gray2; }
    &:focus {
      background: var(--ai-card-bg);
      box-shadow: 0 0 0 3px rgba(10, 132, 255, 0.12), 0 1px 3px var(--ai-border);
    }
  }
  &__clear {
    position: absolute;
    right: 8px;
    top: 50%;
    transform: translateY(-50%);
    width: 18px;
    height: 18px;
    border: none;
    border-radius: 50%;
    background: $gray3;
    color: #fff;
    font-size: 9px;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    &:hover { background: $gray; }
  }
}

.gex-depth {
  display: inline-flex;
  align-items: center;
  height: 34px;
  padding: 2px;
  border-radius: 980px;
  background: var(--ai-fill-1);
  box-shadow: 0 0 0 1px var(--ai-border-2);
  flex-shrink: 0;
  &__btn {
    height: 30px;
    border: none;
    border-radius: 980px;
    padding: 0 12px;
    font-size: 12.5px;
    font-weight: 500;
    font-family: $font;
    color: $text2;
    background: transparent;
    cursor: pointer;
    white-space: nowrap;
    transition: all 0.18s $ease;
    &:hover:not(:disabled):not(.is-active) {
      color: $text;
      background: var(--ai-fill-2);
    }
    &.is-active {
      background: var(--ai-card-bg);
      color: $text;
      box-shadow: 0 1px 3px var(--ai-border), 0 0 0 1px var(--ai-border-2);
      font-weight: 600;
    }
    &:disabled { opacity: 0.45; cursor: not-allowed; }
  }
}

.gex-btn {
  height: 34px;
  border: none;
  background: var(--ai-card-bg);
  border-radius: 980px;
  padding: 0 14px;
  font-size: 13px;
  font-weight: 500;
  font-family: $font;
  color: $text;
  cursor: pointer;
  box-shadow: 0 0 0 1px var(--ai-border-2);
  transition: all 0.18s $ease;
  white-space: nowrap;
  &:hover:not(:disabled) { background: var(--ai-fill-1); }
  &--primary {
    background: $blue;
    color: #fff;
    box-shadow: 0 2px 10px rgba(10, 132, 255, 0.28);
    &:hover:not(:disabled) { background: #0071e3; }
  }
  &--soft {
    height: 32px;
    margin-top: 4px;
    background: rgba(10, 132, 255, 0.08);
    color: $blue;
    box-shadow: none;
    &:hover:not(:disabled) { background: rgba(10, 132, 255, 0.14); }
  }
  &:disabled { opacity: 0.45; cursor: not-allowed; }
}

.gex-status {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  color: $text2;
  flex-shrink: 0;
  flex-wrap: wrap;
  &__dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: $gray3;
    flex-shrink: 0;
    &.is-ok { background: $green; box-shadow: 0 0 0 2.5px rgba(52, 199, 89, 0.18); }
    &.is-warn { background: $orange; box-shadow: 0 0 0 2.5px rgba(255, 159, 10, 0.18); }
  }
  &__hint {
    margin-left: auto;
    font-size: 11.5px;
    color: $gray;
  }
}

.gex-body {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 1fr minmax(280px, 320px);
  grid-template-rows: minmax(0, 1fr);
  gap: 10px;
  .is-embedded & {
    min-height: 320px;
  }
  @media (max-width: 900px) {
    grid-template-columns: 1fr;
    grid-template-rows: minmax(360px, 1fr) minmax(200px, 40%);
  }
}

.gex-canvas-wrap {
  position: relative;
  min-height: 360px;
  height: 100%;
  border-radius: 16px;
  /* 固定深色场景，保证彩色球体与白字标签可读 */
  background:
    radial-gradient(700px 420px at 20% 15%, rgba(76, 154, 255, 0.14), transparent 55%),
    radial-gradient(600px 400px at 85% 80%, rgba(181, 107, 255, 0.1), transparent 50%),
    #0b0d12;
  border: 1px solid var(--ai-border);
  box-shadow: 0 1px 2px var(--ai-fill-2);
  overflow: hidden;
  .is-embedded & {
    min-height: 0;
  }
}

.gex-chart {
  width: 100%;
  height: 100%;
  min-height: 360px;
  .is-embedded & {
    min-height: 280px;
  }
  :deep(canvas) {
    outline: none;
    display: block;
  }
}

.gex-legend {
  position: absolute;
  left: 12px;
  bottom: 12px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px 12px;
  padding: 8px 12px;
  border-radius: 12px;
  background: rgba(22, 22, 24, 0.78);
  backdrop-filter: blur(10px);
  border: 1px solid rgba(255, 255, 255, 0.08);
  max-width: calc(100% - 24px);
  z-index: 2;
  &__item {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    font-size: 11px;
    color: rgba(245, 245, 247, 0.78);
    white-space: nowrap;
    i {
      width: 7px;
      height: 7px;
      border-radius: 50%;
      display: inline-block;
      box-shadow: 0 0 6px currentColor;
    }
  }
}

.gex-empty {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 24px;
  text-align: center;
  background: var(--ai-card-bg);
  pointer-events: none;
  z-index: 1;
  &__icon {
    width: 44px;
    height: 44px;
    border-radius: 12px;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 20px;
    color: $blue;
    background: rgba(10, 132, 255, 0.08);
    margin-bottom: 12px;
  }
  &__text {
    margin: 0 0 6px;
    font-size: 14px;
    font-weight: 600;
    color: $text;
  }
  &__hint {
    margin: 0;
    font-size: 12.5px;
    color: $gray;
    line-height: 1.5;
    max-width: 280px;
  }
}

.gex-detail {
  display: flex;
  flex-direction: column;
  min-height: 0;
  border-radius: 16px;
  background: var(--ai-card-bg);
  border: 1px solid var(--ai-border);
  box-shadow: 0 1px 2px var(--ai-fill-2);
  overflow: hidden;
  &__head {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    gap: 8px;
    padding: 12px 14px 11px;
    border-bottom: 1px solid var(--ai-border);
    flex-shrink: 0;
    background: var(--ai-fill-1);
  }
  &__head-text {
    min-width: 0;
    flex: 1;
  }
  &__eyebrow {
    display: block;
    font-size: 10.5px;
    font-weight: 600;
    letter-spacing: 0.04em;
    text-transform: uppercase;
    color: $gray;
    margin-bottom: 3px;
  }
  h3 {
    margin: 0;
    font-size: 15px;
    font-weight: 700;
    letter-spacing: -0.2px;
    color: $text;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    line-height: 1.3;
  }
  &__close {
    width: 26px;
    height: 26px;
    border: none;
    border-radius: 50%;
    background: var(--ai-fill-2);
    color: $gray;
    font-size: 11px;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
    margin-top: 2px;
    &:hover { background: var(--ai-hover-strong); color: $text; }
  }
  &__body {
    flex: 1;
    min-height: 0;
    overflow: auto;
    padding: 0;
    display: flex;
    flex-direction: column;
  }
}

.gex-detail-empty {
  flex: 1;
  min-height: 180px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  color: $gray;
  padding: 28px 18px;
  &__icon {
    width: 44px;
    height: 44px;
    border-radius: 12px;
    display: flex;
    align-items: center;
    justify-content: center;
    color: $blue;
    background: rgba(10, 132, 255, 0.08);
    margin-bottom: 12px;
  }
  p {
    margin: 0 0 5px;
    font-size: 13.5px;
    font-weight: 650;
    color: $text2;
  }
  span { font-size: 12px; line-height: 1.5; max-width: 180px; }
}

/* —— 详情内容分区 —— */
.gd-hero {
  padding: 14px 14px 12px;
  border-bottom: 1px solid var(--ai-border);
  &__row {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 6px 10px;
    margin-bottom: 10px;
  }
  &__desc {
    margin: 0;
    font-size: 13px;
    line-height: 1.55;
    color: $text2;
  }
}

.gd-chip {
  font-size: 11px;
  font-weight: 650;
  padding: 2px 8px;
  border-radius: 980px;
  background: rgba(10, 132, 255, 0.1);
  color: $blue;
  &.is-person { background: rgba(255, 92, 122, 0.12); color: #c73455; }
  &.is-org { background: rgba(0, 212, 170, 0.12); color: #0a8f73; }
  &.is-loc { background: rgba(61, 187, 255, 0.14); color: #0b7bb8; }
  &.is-event { background: rgba(255, 197, 61, 0.18); color: #9a6b00; }
  &.is-doc { background: rgba(142, 154, 175, 0.16); color: $text2; }
  &.is-concept { background: rgba(10, 132, 255, 0.1); color: $blue; }
  &.is-other { background: var(--ai-fill-2); color: $text2; }
}

.gd-stat {
  font-size: 11.5px;
  color: $gray;
  b {
    font-weight: 700;
    color: $text;
    font-variant-numeric: tabular-nums;
    margin-right: 2px;
  }
}

.gd-themes {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}
.gd-theme {
  max-width: 100%;
  font-size: 11px;
  font-weight: 500;
  padding: 3px 8px;
  border-radius: 8px;
  background: var(--ai-fill-1);
  color: $text2;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.gd-sec {
  padding: 12px 14px 4px;
  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 8px;
    h4 {
      margin: 0;
      font-size: 12px;
      font-weight: 700;
      color: $text;
      letter-spacing: -0.1px;
    }
  }
  &__n {
    font-size: 11px;
    font-weight: 650;
    font-variant-numeric: tabular-nums;
    color: $gray;
    background: var(--ai-fill-2);
    padding: 1px 7px;
    border-radius: 980px;
  }
}

.gd-empty-inline {
  font-size: 12.5px;
  color: $gray;
  padding: 10px 0 14px;
}

.gd-rels {
  list-style: none;
  margin: 0 0 8px;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.gd-rel {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 8px 8px 6px;
  border-radius: 10px;
  background: var(--ai-fill-1);
  cursor: pointer;
  transition: background 0.15s $ease;
  border: 1px solid transparent;
  &:hover {
    background: rgba(10, 132, 255, 0.06);
    border-color: rgba(10, 132, 255, 0.12);
    .gd-rel__go { color: $blue; }
  }
  &__dir {
    flex-shrink: 0;
    width: 22px;
    height: 22px;
    border-radius: 6px;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 10px;
    font-weight: 700;
    &.is-out {
      background: rgba(10, 132, 255, 0.12);
      color: $blue;
    }
    &.is-in {
      background: var(--ai-fill-2);
      color: $text2;
    }
  }
  &__main {
    flex: 1;
    min-width: 0;
  }
  &__name {
    font-size: 12.5px;
    font-weight: 600;
    color: $text;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    line-height: 1.3;
  }
  &__label {
    margin-top: 2px;
    font-size: 11px;
    color: $gray;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  &__go {
    flex-shrink: 0;
    font-size: 16px;
    color: $gray3;
    line-height: 1;
    transition: color 0.15s $ease;
  }
}

.gd-srcs {
  list-style: none;
  margin: 0 0 8px;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.gd-src {
  padding: 10px 11px;
  border-radius: 12px;
  background: var(--ai-fill-1);
  border: 1px solid var(--ai-border);
  &__top {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 8px;
    margin-bottom: 4px;
  }
  &__doc {
    font-size: 12.5px;
    font-weight: 650;
    color: $text;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    min-width: 0;
  }
  &__page {
    flex-shrink: 0;
    font-size: 11px;
    font-weight: 600;
    font-variant-numeric: tabular-nums;
    color: $blue;
    background: rgba(10, 132, 255, 0.08);
    padding: 1px 6px;
    border-radius: 4px;
  }
  &__path {
    font-size: 11px;
    color: $gray;
    margin-bottom: 6px;
    line-height: 1.35;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  &__snip {
    margin: 0;
    font-size: 12px;
    line-height: 1.5;
    color: $text2;
    display: -webkit-box;
    -webkit-line-clamp: 4;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }
}

.gd-edge {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 6px;
  margin-bottom: 12px;
  &__node {
    border: none;
    text-align: left;
    background: var(--ai-fill-1);
    border-radius: 10px;
    padding: 9px 11px;
    font-size: 13px;
    font-weight: 650;
    font-family: $font;
    color: $text;
    cursor: pointer;
    transition: background 0.15s $ease;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    &:hover { background: rgba(10, 132, 255, 0.08); color: $blue; }
  }
  &__mid {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 0 4px;
  }
  &__line {
    flex: 1;
    height: 1px;
    background: var(--ai-border-2);
  }
  &__lab {
    flex-shrink: 0;
    font-size: 11px;
    font-weight: 600;
    color: $blue;
    background: rgba(10, 132, 255, 0.08);
    padding: 2px 8px;
    border-radius: 980px;
    max-width: 60%;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.gd-foot {
  margin-top: auto;
  padding: 10px 14px 14px;
  border-top: 1px solid var(--ai-border);
  background: var(--ai-card-bg);
  position: sticky;
  bottom: 0;
  &__btn {
    width: 100%;
    margin-top: 0 !important;
    justify-content: center;
  }
}

.gex-btn--soft {
  display: inline-flex;
  align-items: center;
}
</style>
