<template>
  <Teleport to="body">
    <Transition name="graph-sheet">
      <div v-if="open" class="graph-overlay" :class="{ 'graph-overlay--dark': theme.isDark }" @click.self="close">
        <section class="graph-sheet" role="dialog" aria-modal="true" :aria-label="title">
          <header class="graph-sheet__header">
            <div class="graph-sheet__identity">
              <span class="graph-sheet__icon" aria-hidden="true">
                <svg width="18" height="18" viewBox="0 0 16 16" fill="none"><circle cx="4" cy="4" r="1.7"/><circle cx="12" cy="5" r="1.7"/><circle cx="8" cy="12" r="1.7"/><path d="m5.5 4.9 5 .5M5.2 5.5l2.2 5m3.7-4.2L9 10.5"/></svg>
              </span>
              <div><div class="graph-sheet__title"><h2>{{ title }}</h2><span>{{ docId ? '单文件' : '全库' }}</span></div><p>{{ docName || '3D 力导向图谱 · 拖拽旋转 · 滚轮缩放 · 点选节点' }}</p></div>
            </div>
            <button type="button" class="graph-sheet__close" aria-label="关闭" @click="close">×</button>
          </header>

          <div class="graph-sheet__body">
            <div class="graph-toolbar">
              <label class="graph-search">
                <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true"><circle cx="7" cy="7" r="4.5"/><path d="m10.5 10.5 3 3"/></svg>
                <input v-model.trim="query" :placeholder="docId ? '在本文件实体中搜索…' : '搜索实体、主题关键词…'" @keyup.enter="explore">
                <button v-if="query" type="button" @click="query = ''; explore()">×</button>
              </label>
              <div class="graph-depth"><button type="button" :class="{ active: depth === 1 }" @click="setDepth(1)">直接相关</button><button type="button" :class="{ active: depth === 2 }" @click="setDepth(2)">扩一层</button></div>
              <button type="button" class="graph-button graph-button--primary" :disabled="loading" @click="explore">{{ loading ? '加载中…' : '探索' }}</button>
              <button type="button" class="graph-button" :disabled="loading || !nodes.length" @click="relayout">重排</button>
            </div>

            <div v-if="message" class="graph-message"><i :class="{ ok: nodes.length }"></i><span>{{ message }}</span><small>拖拽旋转 · 滚轮缩放 · 点节点看详情</small></div>

            <div class="graph-workspace">
              <div class="graph-stage">
                <div ref="chartEl" class="graph-canvas"></div>
                <div v-if="loading" class="graph-loading"><span></span><p>正在加载知识图谱…</p></div>
                <div v-else-if="emptyHint" class="graph-empty"><b>◎</b><p>{{ emptyHint }}</p><small>{{ docId ? '请确认该文件已完成处理并开启图谱抽取' : '请先完成文档处理，或换个关键词重试' }}</small></div>
                <div v-if="categories.length > 1 && nodes.length" class="graph-legend"><span v-for="category in categories" :key="category"><i :style="{ background: categoryColor(category) }"></i>{{ categoryLabel(category) }}</span></div>
              </div>

              <aside class="graph-detail">
                <header><div><small>{{ detailKind === 'edge' ? '关系' : '知识图谱' }}</small><h3>{{ detailTitle || '节点详情' }}</h3></div><button v-if="detailTitle" type="button" @click="clearDetail">×</button></header>
                <div class="graph-detail__body">
                  <div v-if="detailLoading" class="graph-detail__loading">正在加载详情…</div>
                  <template v-else-if="detailKind === 'node' && entityDetail?.entity">
                    <section class="detail-hero">
                      <div class="detail-hero__meta"><span>{{ categoryLabel(entityDetail.entity.category) }}</span><small><b>{{ entityDetail.entity.sourceCount || 0 }}</b> 来源</small><small><b>{{ entityDetail.relations?.length || 0 }}</b> 关系</small></div>
                      <p>{{ entityDetail.entity.description || '暂无描述，可从来源证据查看上下文。' }}</p>
                      <div v-if="entityDetail.themes?.length" class="detail-themes"><span v-for="theme in entityDetail.themes" :key="theme.communityId">{{ theme.title || `主题 ${theme.communityId}` }}</span></div>
                    </section>
                    <section v-if="entityDetail.relations?.length" class="detail-section"><h4>相关连接 <span>{{ entityDetail.relations.length }}</span></h4><button v-for="relation in entityDetail.relations" :key="relation.id" type="button" class="relation-row" @click="openRelation(relation)"><span>{{ relation.direction === 'out' ? relation.target : relation.source }}</span><small>{{ relation.label || '相关' }}</small><b>›</b></button></section>
                    <section class="detail-section"><h4>来源证据 <span>{{ entityDetail.sources?.length || 0 }}</span></h4><p v-if="!entityDetail.sources?.length" class="detail-none">暂无引用片段</p><article v-for="(source, index) in entityDetail.sources" :key="index" class="source-card"><strong>{{ source.docName || `文档 #${source.docId}` }}</strong><small v-if="source.headingPath || source.sourceLabel">{{ source.headingPath || source.sourceLabel }}</small><p>{{ source.snippet || '—' }}</p></article></section>
                  </template>
                  <template v-else-if="detailKind === 'edge' && relationDetail">
                    <section class="detail-hero"><div class="edge-title"><button type="button" @click="openEntity(relationDetail.relation?.source)">{{ relationDetail.relation?.source }}</button><span>{{ relationDetail.relation?.label || '相关' }}</span><button type="button" @click="openEntity(relationDetail.relation?.target)">{{ relationDetail.relation?.target }}</button></div><p>{{ relationDetail.relation?.description || '暂无关系说明，可从来源证据查看上下文。' }}</p></section>
                    <section class="detail-section"><h4>来源证据 <span>{{ relationDetail.sources?.length || 0 }}</span></h4><p v-if="!relationDetail.sources?.length" class="detail-none">暂无引用片段</p><article v-for="(source, index) in relationDetail.sources" :key="index" class="source-card"><strong>{{ source.docName || `文档 #${source.docId}` }}</strong><small v-if="source.headingPath">{{ source.headingPath }}</small><p>{{ source.snippet || '—' }}</p></article></section>
                  </template>
                  <div v-else class="graph-detail__empty"><span aria-hidden="true">⌘</span><p>点选图中的节点</p><small>查看简介、连接与文档出处</small></div>
                </div>
              </aside>
            </div>
          </div>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import ForceGraph3D from '3d-force-graph'
import SpriteText from 'three-spritetext'
import { graphEntityDetail, graphExplore, graphRelationDetail } from '../api/kb'
import { useThemeStore } from '../stores/theme'

const props = defineProps({
  open: { type: Boolean, default: false },
  kbId: { type: [Number, String], required: true },
  docId: { type: [Number, String], default: null },
  docName: { type: String, default: '' }
})
const emit = defineEmits(['update:open'])
const theme = useThemeStore()
const title = computed(() => props.docId ? '文件知识图谱' : '知识库图谱')
const query = ref('')
const depth = ref(1)
const loading = ref(false)
const message = ref('')
const emptyHint = ref('')
const nodes = ref([])
const edges = ref([])
const chartEl = ref(null)
const detailKind = ref('node')
const detailTitle = ref('')
const detailLoading = ref(false)
const entityDetail = ref(null)
const relationDetail = ref(null)
const categories = computed(() => [...new Set(nodes.value.map(node => node.category || 'other'))])
let graph = null
let observer = null
let requestSeq = 0

const DARK_COLORS = { person: '#ff6680', org: '#12c7a2', loc: '#40a9ff', event: '#f4b740', doc: '#8a7cff', concept: '#3887ff', other: '#8d9aac' }
const LIGHT_COLORS = { person: '#dd4f69', org: '#07947a', loc: '#2e75d4', event: '#cc8611', doc: '#6d5fd0', concept: '#2563eb', other: '#718096' }
const graphTheme = computed(() => theme.isDark ? {
  background: '#060c13', label: '#dce2e9', labelBackground: 'rgba(6,12,19,.80)',
  link: 'rgba(126,138,151,.42)', particle: '#62a0df'
} : {
  background: '#f7faff', label: '#26364f', labelBackground: 'rgba(255,255,255,.82)',
  link: 'rgba(72,108,158,.38)', particle: '#2563eb'
})
const LABELS = { person: '人物', org: '组织', loc: '地点', event: '事件', doc: '文档', concept: '概念', other: '其他' }
function categoryColor(category) {
  const colors = theme.isDark ? DARK_COLORS : LIGHT_COLORS
  return colors[String(category || 'other').toLowerCase()] || colors.other
}
function categoryLabel(category) { return LABELS[String(category || 'other').toLowerCase()] || category || '其他' }
function close() { emit('update:open', false) }
function setDepth(value) { depth.value = value; explore() }

async function explore() {
  if (!props.kbId) return
  const seq = ++requestSeq
  loading.value = true
  clearDetail()
  try {
    const payload = { query: query.value || undefined, depth: depth.value, limit: depth.value === 2 ? 100 : 60, edgeLimit: depth.value === 2 ? 260 : 150 }
    if (props.docId) payload.docIds = [props.docId]
    const res = await graphExplore(props.kbId, payload)
    if (seq !== requestSeq) return
    const data = res.data || {}
    nodes.value = Array.isArray(data.nodes) ? data.nodes : []
    edges.value = Array.isArray(data.edges) ? data.edges : []
    message.value = data.userMessage || ''
    emptyHint.value = nodes.value.length ? '' : (data.userMessage || '暂无可展示的图谱数据')
    await nextTick()
    renderGraph()
  } catch (error) {
    if (seq !== requestSeq) return
    nodes.value = []
    edges.value = []
    message.value = ''
    emptyHint.value = error.message || '图谱加载失败，请稍后重试'
    destroyGraph()
  } finally { if (seq === requestSeq) loading.value = false }
}

function graphPayload() {
  const graphNodes = nodes.value.map(node => ({ ...node, id: String(node.id || node.name), val: 3 + Math.min(8, Number(node.sourceCount || 0)), color: categoryColor(node.category), raw: node }))
  const ids = new Set(graphNodes.map(node => node.id))
  const links = edges.value.filter(edge => ids.has(String(edge.source)) && ids.has(String(edge.target))).map(edge => ({ ...edge, source: String(edge.source), target: String(edge.target), raw: edge }))
  return { nodes: graphNodes, links }
}

function destroyGraph() {
  const current = graph
  graph = null
  try { current?.pauseAnimation?.(); current?._destructor?.() } catch (_) { /* ignore */ }
  if (chartEl.value) chartEl.value.innerHTML = ''
}

function renderGraph() {
  if (!chartEl.value || !nodes.value.length) { destroyGraph(); return }
  destroyGraph()
  const data = graphPayload()
  const width = Math.max(chartEl.value.clientWidth, 480)
  const height = Math.max(chartEl.value.clientHeight, 360)
  const palette = graphTheme.value
  graph = ForceGraph3D()(chartEl.value)
    .backgroundColor(palette.background)
    .showNavInfo(false)
    .width(width).height(height)
    .nodeId('id').nodeVal('val').nodeColor('color').nodeOpacity(1)
    .nodeThreeObject(node => { const label = new SpriteText(node.name || node.id); label.color = palette.label; label.textHeight = 4.5; label.backgroundColor = palette.labelBackground; label.padding = 1.5; label.borderRadius = 2; return label })
    .nodeThreeObjectExtend(true)
    .linkColor(() => palette.link).linkOpacity(.75).linkWidth(1)
    .linkDirectionalArrowLength(3).linkDirectionalArrowRelPos(1)
    .linkDirectionalParticles(2).linkDirectionalParticleWidth(1.2).linkDirectionalParticleColor(() => palette.particle)
    .onNodeClick(node => { openEntity(node.id); const distance = 130; const length = Math.hypot(node.x || 1, node.y || 1, node.z || 1); graph?.cameraPosition({ x: (node.x || 0) * (1 + distance / length), y: (node.y || 0) * (1 + distance / length), z: (node.z || 0) * (1 + distance / length) }, node, 650) })
    .onLinkClick(link => openRelation({ ...link.raw, source: typeof link.source === 'object' ? link.source.id : link.source, target: typeof link.target === 'object' ? link.target.id : link.target }))
    .onEngineStop(() => { try { graph?.zoomToFit(450, 45) } catch (_) { /* ignore */ } })
  graph.graphData(data)
  try { graph.d3Force('charge')?.strength(-170); graph.d3Force('link')?.distance(74); graph.cameraPosition({ x: 260, y: 140, z: 460 }) } catch (_) { /* ignore */ }
}

function relayout() {
  if (!graph) return
  const data = graph.graphData()
  data.nodes.forEach(node => { node.fx = node.fy = node.fz = undefined; node.x = (Math.random() - .5) * 180; node.y = (Math.random() - .5) * 180; node.z = (Math.random() - .5) * 180 })
  graph.graphData(data)
  graph.d3ReheatSimulation?.()
  window.setTimeout(() => { try { graph?.zoomToFit(450, 45) } catch (_) { /* ignore */ } }, 450)
}

async function openEntity(name) {
  if (!name) return
  detailKind.value = 'node'; detailTitle.value = String(name); detailLoading.value = true; entityDetail.value = null; relationDetail.value = null
  try { const res = await graphEntityDetail(props.kbId, name); entityDetail.value = res.data || {}; detailTitle.value = entityDetail.value?.entity?.name || String(name) }
  finally { detailLoading.value = false }
}
async function openRelation(edge) {
  if (!edge?.source || !edge?.target) return
  detailKind.value = 'edge'; detailTitle.value = edge.label || '关系'; detailLoading.value = true; entityDetail.value = null; relationDetail.value = null
  try { const res = await graphRelationDetail(props.kbId, edge.source, edge.target, edge.label); relationDetail.value = res.data || {} }
  finally { detailLoading.value = false }
}
function clearDetail() { detailKind.value = 'node'; detailTitle.value = ''; detailLoading.value = false; entityDetail.value = null; relationDetail.value = null }
function resize() { if (!graph || !chartEl.value) return; graph.width(chartEl.value.clientWidth).height(chartEl.value.clientHeight) }

watch(() => props.open, async value => {
  if (!value) { requestSeq++; destroyGraph(); return }
  query.value = ''; depth.value = 1; await nextTick(); explore()
  if (chartEl.value && typeof ResizeObserver !== 'undefined') { observer?.disconnect(); observer = new ResizeObserver(resize); observer.observe(chartEl.value) }
})
watch(() => [props.kbId, props.docId], () => { if (props.open) explore() })
watch(() => theme.isDark, async () => {
  if (!props.open || !nodes.value.length) return
  await nextTick()
  renderGraph()
})
onBeforeUnmount(() => { requestSeq++; observer?.disconnect(); destroyGraph() })
</script>

<style scoped lang="scss">
.graph-overlay {
  --graph-overlay-bg: rgba(15, 23, 42, .32);
  --graph-stage-bg: #f7faff;
  --graph-stage-glow-a: rgba(37, 99, 235, .10);
  --graph-stage-glow-b: rgba(96, 165, 250, .09);
  --graph-mask-bg: rgba(247, 250, 255, .88);
  --graph-stage-text: #516078;
  --graph-stage-subtle: #8491a6;
  --graph-legend-bg: rgba(255, 255, 255, .86);
  --graph-legend-border: rgba(91, 121, 165, .18);
  --graph-legend-text: #53627a;
  position: fixed; z-index: 1200; inset: 0; display: grid; place-items: center; padding: 20px;
  background: var(--graph-overlay-bg); backdrop-filter: blur(4px);
}
.graph-overlay--dark {
  --graph-overlay-bg: rgba(0, 3, 7, .64);
  --graph-stage-bg: #060c13;
  --graph-stage-glow-a: rgba(79, 141, 204, .08);
  --graph-stage-glow-b: rgba(49, 140, 244, .045);
  --graph-mask-bg: rgba(6, 12, 19, .88);
  --graph-stage-text: #929ba6;
  --graph-stage-subtle: #66717d;
  --graph-legend-bg: rgba(15, 23, 32, .82);
  --graph-legend-border: #25313d;
  --graph-legend-text: #929ba6;
}
.graph-sheet { width: min(1216px, 96vw); height: min(820px, 92vh); display: flex; flex-direction: column; overflow: hidden; border: 1px solid var(--border); border-radius: 14px; background: var(--bg); color: var(--text); box-shadow: 0 24px 72px rgba(15, 23, 42, .20); }
.graph-sheet__header { min-height: 58px; display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 0 16px; border-bottom: 1px solid var(--divider); background: var(--bg-elevated); }.graph-sheet__identity { min-width: 0; display: flex; align-items: center; gap: 10px; }.graph-sheet__icon { width: 34px; height: 34px; display: grid; place-items: center; flex: none; border-radius: 9px; color: var(--accent); background: var(--accent-weak); box-shadow: none; }.graph-sheet__icon svg { stroke: currentColor; stroke-width: 1.4; }.graph-sheet__title { display: flex; align-items: center; gap: 8px; }.graph-sheet__title h2 { margin: 0; font-size: 16px; font-weight: 650; }.graph-sheet__title span { padding: 2px 7px; border: 1px solid var(--accent-border); border-radius: 99px; color: var(--accent); background: var(--accent-weak); font-size: 10px; }.graph-sheet__identity p { margin: 2px 0 0; overflow: hidden; color: var(--text-tertiary); font-size: 11.5px; text-overflow: ellipsis; white-space: nowrap; }.graph-sheet__close { width: 30px; height: 30px; border: 0; border-radius: 8px; color: var(--text-tertiary); background: transparent; font-size: 18px; cursor: pointer; }.graph-sheet__close:hover { color: var(--text); background: var(--bg-hover); }.graph-sheet__body { min-height: 0; flex: 1; display: flex; flex-direction: column; gap: 10px; padding: 10px 12px 12px; }
.graph-toolbar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }.graph-search { position: relative; min-width: 220px; max-width: 360px; flex: 1; }.graph-search > svg { position: absolute; top: 50%; left: 11px; transform: translateY(-50%); stroke: currentColor; stroke-width: 1.4; color: var(--text-tertiary); }.graph-search input { width: 100%; height: 36px; box-sizing: border-box; padding: 0 30px 0 33px; border: 1px solid var(--border); border-radius: 9px; outline: 0; color: var(--text); background: var(--bg-input); font: inherit; font-size: 12.5px; }.graph-search input:focus { border-color: var(--accent-border); box-shadow: var(--ring); }.graph-search > button { position: absolute; top: 8px; right: 8px; width: 20px; height: 20px; border: 0; border-radius: 6px; color: var(--text-tertiary); background: var(--bg-hover); cursor: pointer; }.graph-depth { height: 36px; display: flex; align-items: center; padding: 2px; border: 1px solid var(--border); border-radius: 9px; background: var(--bg-hover); }.graph-depth button { height: 30px; padding: 0 11px; border: 0; border-radius: 7px; color: var(--text-secondary); background: transparent; font: inherit; font-size: 12px; cursor: pointer; }.graph-depth button.active { color: var(--text); background: var(--bg-elevated); box-shadow: 0 1px 3px rgba(20, 50, 90, .10); }.graph-button { height: 36px; padding: 0 13px; border: 1px solid var(--border); border-radius: 8px; color: var(--text); background: var(--bg-elevated); font: inherit; font-size: 12.5px; cursor: pointer; }.graph-button:hover:not(:disabled) { border-color: var(--accent-border); color: var(--accent); background: var(--accent-weak); }.graph-button--primary { border-color: var(--accent); color: #fff; background: var(--accent); }.graph-button--primary:hover:not(:disabled) { color: #fff; filter: brightness(.96); }.graph-button:disabled { cursor: default; opacity: .5; }
.graph-message { display: flex; align-items: center; gap: 8px; color: var(--text-secondary); font-size: 12px; }.graph-message > i { width: 6px; height: 6px; border-radius: 50%; background: #f5a623; }.graph-message > i.ok { background: #34a853; box-shadow: 0 0 0 3px rgba(52, 168, 83, .13); }.graph-message small { margin-left: auto; color: var(--text-tertiary); font-size: 11px; }
.graph-workspace { min-height: 0; flex: 1; display: grid; grid-template-columns: minmax(0, 1fr) 310px; gap: 10px; }.graph-stage { position: relative; min-height: 360px; overflow: hidden; border: 1px solid var(--border); border-radius: 12px; background: radial-gradient(600px 380px at 18% 16%, var(--graph-stage-glow-a), transparent 55%), radial-gradient(520px 360px at 86% 82%, var(--graph-stage-glow-b), transparent 52%), var(--graph-stage-bg); }.graph-canvas { width: 100%; height: 100%; min-height: 360px; }.graph-canvas :deep(canvas) { display: block; outline: none; }.graph-loading, .graph-empty { position: absolute; z-index: 2; inset: 0; display: grid; place-content: center; justify-items: center; gap: 8px; padding: 24px; color: var(--graph-stage-text); text-align: center; background: var(--graph-mask-bg); }.graph-loading span { width: 24px; height: 24px; border: 2px solid var(--accent-border); border-right-color: var(--accent); border-radius: 50%; animation: graph-spin .8s linear infinite; }.graph-loading p, .graph-empty p { margin: 0; font-size: 13px; }.graph-empty b { color: var(--accent); font-size: 32px; font-weight: 400; }.graph-empty small { color: var(--graph-stage-subtle); font-size: 11.5px; }.graph-legend { position: absolute; z-index: 3; left: 12px; bottom: 12px; display: flex; gap: 10px; flex-wrap: wrap; max-width: calc(100% - 24px); padding: 7px 10px; border: 1px solid var(--graph-legend-border); border-radius: 9px; color: var(--graph-legend-text); background: var(--graph-legend-bg); backdrop-filter: blur(8px); box-shadow: 0 3px 12px rgba(15, 23, 42, .06); }.graph-legend span { display: inline-flex; align-items: center; gap: 5px; font-size: 10.5px; }.graph-legend i { width: 7px; height: 7px; border-radius: 50%; }
.graph-detail { min-height: 0; display: flex; flex-direction: column; overflow: hidden; border: 1px solid var(--border); border-radius: 12px; background: var(--bg-elevated); }.graph-detail > header { min-height: 54px; display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 0 14px; border-bottom: 1px solid var(--divider); }.graph-detail > header small { display: block; color: var(--accent); font-size: 9px; font-weight: 700; letter-spacing: .08em; }.graph-detail > header h3 { max-width: 230px; margin: 3px 0 0; overflow: hidden; font-size: 13.5px; text-overflow: ellipsis; white-space: nowrap; }.graph-detail > header button { border: 0; color: var(--text-tertiary); background: transparent; cursor: pointer; }.graph-detail__body { min-height: 0; flex: 1; overflow: auto; }.graph-detail__loading, .graph-detail__empty { height: 100%; min-height: 240px; display: grid; place-content: center; justify-items: center; gap: 7px; padding: 20px; color: var(--text-tertiary); font-size: 12px; text-align: center; }.graph-detail__empty span { width: 42px; height: 42px; display: grid; place-items: center; border-radius: 10px; color: var(--accent); background: var(--accent-weak); font-size: 20px; }.graph-detail__empty p { margin: 2px 0 0; color: var(--text); font-size: 13px; font-weight: 600; }.graph-detail__empty small { font-size: 11px; }.detail-hero, .detail-section { padding: 14px; border-bottom: 1px solid var(--divider); }.detail-hero__meta { display: flex; align-items: center; gap: 7px; }.detail-hero__meta > span, .detail-themes span { padding: 2px 7px; border-radius: 99px; color: var(--accent); background: var(--accent-weak); font-size: 10px; }.detail-hero__meta small { color: var(--text-tertiary); font-size: 10.5px; }.detail-hero__meta b { color: var(--text); }.detail-hero > p { margin: 10px 0 0; color: var(--text-secondary); font-size: 11.5px; line-height: 1.65; }.detail-themes { display: flex; gap: 5px; flex-wrap: wrap; margin-top: 10px; }.detail-section h4 { display: flex; align-items: center; gap: 6px; margin: 0 0 9px; font-size: 11.5px; }.detail-section h4 span { color: var(--text-tertiary); font-size: 10px; font-weight: 500; }.relation-row { width: 100%; display: grid; grid-template-columns: minmax(0, 1fr) auto 10px; align-items: center; gap: 6px; padding: 8px; border: 0; border-radius: 7px; color: var(--text); background: transparent; text-align: left; cursor: pointer; }.relation-row:hover { background: var(--bg-hover); }.relation-row span { overflow: hidden; font-size: 11.5px; text-overflow: ellipsis; white-space: nowrap; }.relation-row small { color: var(--text-tertiary); font-size: 10px; }.relation-row b { color: var(--text-tertiary); }.source-card { padding: 9px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg-raised); }.source-card + .source-card { margin-top: 7px; }.source-card strong, .source-card small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.source-card strong { font-size: 10.5px; }.source-card small { margin-top: 3px; color: var(--accent); font-size: 9.5px; }.source-card p { display: -webkit-box; margin: 6px 0 0; overflow: hidden; color: var(--text-secondary); font-size: 10.5px; line-height: 1.5; -webkit-box-orient: vertical; -webkit-line-clamp: 4; }.detail-none { color: var(--text-tertiary); font-size: 11px; }.edge-title { display: flex; align-items: center; gap: 6px; }.edge-title button { max-width: 100px; overflow: hidden; border: 0; color: var(--accent); background: transparent; font: inherit; font-size: 10.5px; text-overflow: ellipsis; white-space: nowrap; cursor: pointer; }.edge-title span { color: var(--text-tertiary); font-size: 9.5px; }
.graph-sheet-enter-active { transition: opacity .22s ease; }.graph-sheet-enter-active .graph-sheet { transition: transform .28s cubic-bezier(.2,.8,.2,1); }.graph-sheet-leave-active { transition: opacity .16s ease; }.graph-sheet-enter-from, .graph-sheet-leave-to { opacity: 0; }.graph-sheet-enter-from .graph-sheet { transform: translateY(12px) scale(.97); } @keyframes graph-spin { to { transform: rotate(360deg); } }
@media (max-width: 900px) { .graph-workspace { grid-template-columns: 1fr; overflow: auto; }.graph-stage { min-height: 420px; }.graph-detail { min-height: 280px; }.graph-message small { display: none; } }
</style>
