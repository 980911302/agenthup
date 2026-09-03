<template>
  <div class="dashboard">
    <!-- 顶部条:标题 + 时间窗口 + 系统健康 chip -->
    <header class="topbar">
      <div class="topbar__title">
        <div class="topbar__h1">AI 中心 · 运营总览</div>
        <div class="topbar__sub">{{ greeting }},{{ userStore.nickName }} · 实时反映平台运行健康与资源消耗</div>
      </div>
      <div class="topbar__right">
        <div class="health-chips">
          <span class="chip" :class="chipCls(channelsHealth.healthyRate)">
            <span class="chip__dot" :style="{ background: chipColor(channelsHealth.healthyRate) }" />
            渠道 {{ channelsHealth.healthyRate }}%
          </span>
          <span class="chip" :class="chipCls(mcpHealth.healthyRate)">
            <span class="chip__dot" :style="{ background: chipColor(mcpHealth.healthyRate) }" />
            MCP {{ mcpHealth.healthyRate }}%
          </span>
          <span class="chip" :class="chipCls(runs.successRate)">
            <span class="chip__dot" :style="{ background: chipColor(runs.successRate) }" />
            任务 {{ runs.successRate }}%
          </span>
        </div>
        <el-radio-group v-model="windowDays" size="default" class="window-switch">
          <el-radio-button :value="1">24h</el-radio-button>
          <el-radio-button :value="7">7d</el-radio-button>
          <el-radio-button :value="30">30d</el-radio-button>
          <el-radio-button :value="90">90d</el-radio-button>
        </el-radio-group>
      </div>
    </header>

    <!-- 6 卡核心 KPI -->
    <section class="kpi-grid">
      <div class="kpi-card kpi-card--blue">
        <div class="kpi-card__label">Token 消耗</div>
        <div class="kpi-card__value">{{ formatTokens(overview.totalTokens) }}</div>
        <div class="kpi-card__sub">prompt {{ formatTokens(overview.promptTokens) }} · completion {{ formatTokens(overview.completionTokens) }}</div>
        <div class="pc-split">
          <div class="pc-split__prompt" :style="{ width: pcWidth('p') }" />
          <div class="pc-split__completion" :style="{ width: pcWidth('c') }" />
        </div>
      </div>

      <div class="kpi-card kpi-card--cyan">
        <div class="kpi-card__label">LLM 调用</div>
        <div class="kpi-card__value">{{ formatNum(overview.callCount) }}</div>
        <div class="kpi-card__sub">含工具续轮 / 多轮对话</div>
      </div>

      <div class="kpi-card kpi-card--violet">
        <div class="kpi-card__label">活跃会话</div>
        <div class="kpi-card__value">{{ formatNum(overview.sessionCount) }}</div>
        <div class="kpi-card__sub">N 天内至少一次调用</div>
      </div>

      <div class="kpi-card kpi-card--green">
        <div class="kpi-card__label">真实率</div>
        <div class="kpi-card__value">{{ overview.realRate }}%</div>
        <div class="kpi-card__sub">上游返回 usage 的占比</div>
      </div>

      <div class="kpi-card kpi-card--amber">
        <div class="kpi-card__label">缓存命中率</div>
        <div class="kpi-card__value">{{ cache.hitRate }}%</div>
        <div class="kpi-card__sub">命中 {{ formatTokens(cache.hitTokens) }} / 未命中 {{ formatTokens(cache.missTokens) }}</div>
      </div>

      <div class="kpi-card kpi-card--rose">
        <div class="kpi-card__label">任务成功率</div>
        <div class="kpi-card__value">{{ runs.successRate }}%</div>
        <div class="kpi-card__sub">avg {{ formatDuration(runs.avgDurationMs) }} · {{ runs.succeeded }} 成功</div>
      </div>
    </section>

    <!-- 趋势 + 排行 -->
    <section class="row">
      <!-- 趋势图 -->
      <div class="panel panel--chart">
        <div class="panel__head">
          <div class="panel__title">消耗与调用趋势</div>
          <div class="panel__legend">
            <span class="legend-dot" style="background: #0A84FF" /> Token
            <span class="legend-dot" style="background: #5E5CE6" /> 调用
          </div>
        </div>
        <div class="panel__body">
          <div v-if="!trend.length" class="empty">所选时间窗口暂无数据</div>
          <svg v-else class="trend-svg" :viewBox="`0 0 ${trendVB.w} ${trendVB.h}`" preserveAspectRatio="none">
            <!-- grid -->
            <g class="grid">
              <line v-for="i in 4" :key="'g' + i" :x1="0" :x2="trendVB.w" :y1="trendVB.h - padY - (trendVB.h - padY * 2) * i / 4" :y2="trendVB.h - padY - (trendVB.h - padY * 2) * i / 4" />
            </g>
            <!-- 柱状:每天两根(Token / 调用) -->
            <g v-for="(pt, i) in trendPoints" :key="'b' + i" class="bars">
              <rect :x="pt.x - barW" :y="pt.yT" :width="barW" :height="pt.hT" class="bar bar--t" />
              <rect :x="pt.x" :y="pt.yC" :width="barW" :height="pt.hC" class="bar bar--c" />
            </g>
            <!-- X 轴标签(只显示首尾+中间稀疏) -->
            <g class="axis">
              <text v-for="(t, i) in trendXTicks" :key="'x' + i" :x="t.x" :y="trendVB.h - 4" text-anchor="middle">{{ t.label }}</text>
            </g>
          </svg>
        </div>
      </div>

      <!-- 任务状态分布(简化环形) -->
      <div class="panel panel--ring">
        <div class="panel__head">
          <div class="panel__title">任务状态分布</div>
          <div class="panel__hint">总 {{ runs.total }}</div>
        </div>
        <div class="panel__body panel__body--ring">
          <div class="ring-wrap">
            <svg viewBox="0 0 120 120" class="ring-svg">
              <circle cx="60" cy="60" r="48" class="ring-bg" />
              <circle v-for="seg in runSegments" :key="seg.key"
                cx="60" cy="60" r="48"
                class="ring-seg"
                :class="`ring-seg--${seg.key}`"
                :stroke-dasharray="`${seg.len} ${seg.rest}`"
                :stroke-dashoffset="-seg.offset"
                :stroke="seg.color"
                stroke-width="14"
                fill="none"
                transform="rotate(-90 60 60)" />
              <text x="60" y="58" text-anchor="middle" class="ring-num">{{ runs.succeeded }}</text>
              <text x="60" y="74" text-anchor="middle" class="ring-cap">成功</text>
            </svg>
          </div>
          <ul class="ring-legend">
            <li v-for="seg in runSegments" :key="'l' + seg.key">
              <span class="legend-dot" :style="{ background: seg.color }" />
              <span class="ring-legend__name">{{ STATUS_LABEL[seg.key] || seg.key }}</span>
              <span class="ring-legend__val">{{ seg.count }}</span>
            </li>
          </ul>
        </div>
      </div>
    </section>

    <!-- 模型/智能体 TOP -->
    <section class="row">
      <div class="panel">
        <div class="panel__head">
          <div class="panel__title">模型 TOP{{ modelRank.length }}</div>
          <div class="panel__hint">按 Token 消耗</div>
        </div>
        <div class="panel__body">
          <div v-if="!modelRank.length" class="empty">暂无数据</div>
          <div v-else class="rank-list">
            <div v-for="(r, i) in modelRank" :key="'m' + i" class="rank-row">
              <div class="rank-row__name" :title="r.modelName || ('#' + r.modelId)">
                <span class="rank-num">{{ i + 1 }}</span>
                {{ r.modelName || ('#' + r.modelId) }}
              </div>
              <div class="rank-row__bar-wrap">
                <div class="rank-row__bar" :style="{ width: barWidth(r.totalTokens, modelMax), background: barColor(i) }" />
              </div>
              <div class="rank-row__val">{{ formatTokens(r.totalTokens) }}</div>
            </div>
          </div>
        </div>
      </div>

      <div class="panel">
        <div class="panel__head">
          <div class="panel__title">智能体 TOP{{ agentRank.length }}</div>
          <div class="panel__hint">按 Token 消耗</div>
        </div>
        <div class="panel__body">
          <div v-if="!agentRank.length" class="empty">暂无数据</div>
          <div v-else class="rank-list">
            <div v-for="(r, i) in agentRank" :key="'a' + i" class="rank-row">
              <div class="rank-row__name" :title="r.agentName || ('#' + r.agentId)">
                <span class="rank-num">{{ i + 1 }}</span>
                {{ r.agentName || ('#' + r.agentId) }}
              </div>
              <div class="rank-row__bar-wrap">
                <div class="rank-row__bar" :style="{ width: barWidth(r.totalTokens, agentMax), background: barColor(i) }" />
              </div>
              <div class="rank-row__val">{{ formatTokens(r.totalTokens) }}</div>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- 系统健康四象限 -->
    <section class="panel">
      <div class="panel__head">
        <div class="panel__title">系统健康</div>
        <div class="panel__hint">绿/黄/红按阈值 90/70 划分,点击模块可跳转管理</div>
      </div>
      <div class="panel__body">
        <div class="health-grid">
          <div class="health-cell" :class="cellCls(channelsHealth.healthyRate)" @click="router.push('/ai/model/channel')">
            <div class="health-cell__label">渠道</div>
            <div class="health-cell__num">{{ channelsHealth.healthyRate }}<span class="unit">%</span></div>
            <div class="health-cell__sub">健康 {{ channelsHealth.healthy }} / 启用 {{ channelsHealth.enabled }} / 总 {{ channelsHealth.total }}</div>
            <div class="health-cell__bar">
              <div class="health-cell__bar-fill" :style="{ width: channelsHealth.healthyRate + '%', background: chipColor(channelsHealth.healthyRate) }" />
            </div>
          </div>
          <div class="health-cell" :class="cellCls(mcpHealth.healthyRate)" @click="router.push('/ai/cap/mcpServer')">
            <div class="health-cell__label">MCP 服务</div>
            <div class="health-cell__num">{{ mcpHealth.healthyRate }}<span class="unit">%</span></div>
            <div class="health-cell__sub">健康 {{ mcpHealth.healthy }} / 启用 {{ mcpHealth.enabled }} / 总 {{ mcpHealth.total }}</div>
            <div class="health-cell__bar">
              <div class="health-cell__bar-fill" :style="{ width: mcpHealth.healthyRate + '%', background: chipColor(mcpHealth.healthyRate) }" />
            </div>
          </div>
          <div class="health-cell" :class="cellCls(runs.successRate)" @click="router.push('/ai/app/chat')">
            <div class="health-cell__label">任务运行</div>
            <div class="health-cell__num">{{ runs.successRate }}<span class="unit">%</span></div>
            <div class="health-cell__sub">成功 {{ runs.succeeded }} / 失败 {{ runs.failed }} / 中断 {{ runs.interrupted }} / 取消 {{ runs.cancelled }}</div>
            <div class="health-cell__bar">
              <div class="health-cell__bar-fill" :style="{ width: runs.successRate + '%', background: chipColor(runs.successRate) }" />
            </div>
          </div>
          <div class="health-cell" :class="cellCls(cache.hitRate)" @click="router.push('/index')">
            <div class="health-cell__label">缓存命中</div>
            <div class="health-cell__num">{{ cache.hitRate }}<span class="unit">%</span></div>
            <div class="health-cell__sub">命中 {{ formatTokens(cache.hitTokens) }} / 未命中 {{ formatTokens(cache.missTokens) }}</div>
            <div class="health-cell__bar">
              <div class="health-cell__bar-fill" :style="{ width: cache.hitRate + '%', background: chipColor(cache.hitRate) }" />
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- 模块入口 -->
    <section class="modules">
      <div v-for="m in modules" :key="m.path" class="module-tile" @click="m.path && router.push(m.path)">
        <div class="module-tile__icon" :style="{ background: m.bg }">
          <el-icon :size="18"><component :is="m.icon" /></el-icon>
        </div>
        <div class="module-tile__name">{{ m.name }}</div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import useUserStore from '@/store/modules/user'
import { ChatDotRound, Cpu, MagicStick, Tools, Connection, Collection, Box, Link } from '@element-plus/icons-vue'
import {
  getStatOverview, getStatByModel, getStatByAgent, getStatTrend,
  getStatCache, getStatRuns, getStatChannelsHealth, getStatMcpHealth
} from '@/api/ai/stat'

const userStore = useUserStore()
const router = useRouter()

// ============ 时间窗口 ============
const windowDays = ref(7)

const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 6) return '夜深了'
  if (h < 9) return '早上好'
  if (h < 12) return '上午好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})

// ============ 状态 ============
const empty = () => ({ totalTokens: 0, promptTokens: 0, completionTokens: 0, callCount: 0, sessionCount: 0, realRate: 100 })
const overview = ref(empty())
const modelRank = ref([])
const agentRank = ref([])
const trend = ref([])
const cache = ref({ hitRate: 0, hitTokens: 0, missTokens: 0, promptTokens: 0 })
const runs = ref({ succeeded: 0, failed: 0, interrupted: 0, cancelled: 0, queued: 0, running: 0, finalizing: 0, total: 0, successRate: 0, avgDurationMs: 0, durationSamples: 0 })
const channelsHealth = ref({ total: 0, enabled: 0, healthy: 0, unhealthy: 0, unknown: 0, healthyRate: 0 })
const mcpHealth = ref({ total: 0, enabled: 0, healthy: 0, unhealthy: 0, unknown: 0, healthyRate: 0 })

// ============ 加载 ============
function load() {
  const days = windowDays.value
  // 用 Promise.allSettled 避免一个失败全挂
  Promise.allSettled([
    getStatOverview(days),
    getStatByModel(days, 5),
    getStatByAgent(days, 5),
    getStatTrend(Math.max(days, 14)),
    getStatCache(days),
    getStatRuns(days),
    getStatChannelsHealth(),
    getStatMcpHealth()
  ]).then(([ov, mm, ag, tr, ca, ru, ch, mc]) => {
    if (ov.status === 'fulfilled') overview.value = { ...empty(), ...(ov.value.data || {}) }
    if (mm.status === 'fulfilled') modelRank.value = mm.value.data || []
    if (ag.status === 'fulfilled') agentRank.value = ag.value.data || []
    if (tr.status === 'fulfilled') trend.value = tr.value.data || []
    if (ca.status === 'fulfilled') cache.value = { hitRate: 0, hitTokens: 0, missTokens: 0, ...(ca.value.data || {}) }
    if (ru.status === 'fulfilled') runs.value = { ...runs.value, ...(ru.value.data || {}) }
    if (ch.status === 'fulfilled') channelsHealth.value = { ...channelsHealth.value, ...(ch.value.data || {}) }
    if (mc.status === 'fulfilled') mcpHealth.value = { ...mcpHealth.value, ...(mc.value.data || {}) }
  })
}

onMounted(load)
watch(windowDays, load)

// ============ 格式化 ============
function formatTokens(n) {
  const v = Number(n) || 0
  if (v >= 1000000000) return (v / 1000000000).toFixed(1).replace(/\.0$/, '') + 'B'
  if (v >= 1000000) return (v / 1000000).toFixed(1).replace(/\.0$/, '') + 'M'
  if (v >= 1000) return (v / 1000).toFixed(1).replace(/\.0$/, '') + 'K'
  return String(Math.round(v))
}
function formatNum(n) {
  return (Number(n) || 0).toLocaleString()
}
function formatDuration(ms) {
  if (!ms) return '0ms'
  const v = Number(ms) || 0
  if (v >= 60000) return (v / 60000).toFixed(1) + 'm'
  if (v >= 1000) return (v / 1000).toFixed(1) + 's'
  return Math.round(v) + 'ms'
}

// ============ 健康度色 ============
function chipColor(rate) {
  if (rate >= 90) return '#34C759'   // 绿
  if (rate >= 70) return '#FF9F0A'   // 橙
  return '#FF3B30'                    // 红
}
function chipCls(rate) {
  if (rate >= 90) return 'chip--ok'
  if (rate >= 70) return 'chip--warn'
  return 'chip--bad'
}
function cellCls(rate) {
  if (rate >= 90) return 'cell--ok'
  if (rate >= 70) return 'cell--warn'
  return 'cell--bad'
}

// ============ KPI 卡:prompt / completion 拆分宽度 ============
function pcWidth(which) {
  const p = Number(overview.value.promptTokens) || 0
  const c = Number(overview.value.completionTokens) || 0
  const total = p + c
  if (total === 0) return '0%'
  const pct = which === 'p' ? (p / total) * 100 : (c / total) * 100
  return Math.max(2, pct).toFixed(1) + '%'
}

// ============ TOP 排行 ============
const PALETTE = ['#0A84FF', '#FF9F0A', '#30D158', '#BF5AF2', '#64D2FF', '#FF375F', '#FFD60A', '#2CB5C6']
function barColor(i) { return PALETTE[i % PALETTE.length] }
const modelMax = computed(() => Math.max(...modelRank.value.map(r => Number(r.totalTokens) || 0), 1))
const agentMax = computed(() => Math.max(...agentRank.value.map(r => Number(r.totalTokens) || 0), 1))
function barWidth(val, max) {
  const p = max > 0 ? (Number(val) || 0) / max : 0
  return Math.max(4, Math.round(p * 100)) + '%'
}

// ============ 趋势 SVG ============
const trendVB = { w: 600, h: 220 }
// 绘图区上下留白(柱状图 y 起点与网格共用)
const padY = 20
// 单根柱宽;每天两根柱并排,总占 step 的 60%
const barW = 10
const trendPoints = computed(() => {
  const data = trend.value
  if (!data.length) return []
  const maxT = Math.max(...data.map(d => Number(d.totalTokens) || 0), 1)
  const maxC = Math.max(...data.map(d => Number(d.callCount) || 0), 1)
  const w = trendVB.w
  const h = trendVB.h
  const padX = 20
  const plot = h - padY * 2   // 可用绘图高度
  const base = h - padY       // 柱底 y
  // 每天两根柱并排,中心占满绘图区;单根柱宽固定,第一天居中显示
  const step = (w - padX * 2) / data.length
  return data.map((d, i) => {
    const x = padX + step * i + step / 2
    const t = Number(d.totalTokens) || 0
    const c = Number(d.callCount) || 0
    const hT = (t / maxT) * plot
    const hC = (c / maxC) * plot
    return {
      x,
      yT: base - hT, hT,
      yC: base - hC, hC,
      day: d.day
    }
  })
})
const trendXTicks = computed(() => {
  const pts = trendPoints.value
  if (pts.length === 0) return []
  if (pts.length <= 4) return pts.map(p => ({ x: p.x, label: (p.day || '').slice(5) }))
  return [pts[0], pts[Math.floor(pts.length / 2)], pts[pts.length - 1]]
    .map(p => ({ x: p.x, label: (p.day || '').slice(5) }))
})

// ============ 任务状态环形 ============
const STATUS_LABEL = { succeeded: '成功', failed: '失败', interrupted: '中断', cancelled: '取消', running: '运行中', queued: '排队中', finalizing: '收尾中' }
const STATUS_COLOR = { succeeded: '#34C759', failed: '#FF3B30', interrupted: '#FF9F0A', cancelled: '#8E8E93', running: '#0A84FF', queued: '#AEAEB2', finalizing: '#64D2FF' }
const STATUS_RENDER = ['succeeded', 'failed', 'interrupted', 'cancelled', 'running', 'queued', 'finalizing']
const runSegments = computed(() => {
  const r = 48
  const C = 2 * Math.PI * r
  const data = STATUS_RENDER
    .map(k => ({ key: k, count: Number(runs.value[k]) || 0, color: STATUS_COLOR[k] }))
    .filter(s => s.count > 0)
  const total = data.reduce((a, b) => a + b.count, 0)
  if (total === 0) return []
  let off = 0
  return data.map(s => {
    const frac = s.count / total
    const len = frac * C
    const seg = { key: s.key, count: s.count, color: s.color, len, rest: C - len, offset: off }
    off += len
    return seg
  })
})

// ============ 模块入口 ============
// 路径依赖 sys_menu 层级(应用=app, 能力=cap, 模型=model):
// 改菜单分组就要同步改这里;长期方案是改成从 usePermissionStore 动态拼 full path。
const modules = [
  { name: '对话',     icon: ChatDotRound, bg: 'linear-gradient(135deg,#0EA5E9,#22D3EE)', path: '/ai/app/chat' },
  { name: '智能体',   icon: Cpu,         bg: 'linear-gradient(135deg,#3B82F6,#60A5FA)', path: '/ai/app/agent' },
  { name: '技能',     icon: MagicStick,  bg: 'linear-gradient(135deg,#F59E0B,#FBBF24)', path: '/ai/cap/skill' },
  { name: '工具',     icon: Tools,       bg: 'linear-gradient(135deg,#10B981,#34D399)', path: '/ai/cap/tool' },
  { name: 'MCP',      icon: Connection,  bg: 'linear-gradient(135deg,#F43F5E,#FB7185)', path: '/ai/cap/mcpServer' },
  { name: '知识库',   icon: Collection,  bg: 'linear-gradient(135deg,#14B8A6,#2DD4BF)', path: '/ai/cap/kb' },
  { name: '模型',     icon: Box,         bg: 'linear-gradient(135deg,#F97316,#FB923C)', path: '/ai/model/model' },
  { name: '渠道',     icon: Link,        bg: 'linear-gradient(135deg,#0891B2,#06B6D4)', path: '/ai/model/channel' }
]
</script>

<style lang="scss" scoped>
// 复用 AI 设计 token：颜色全部走 --ai-* CSS 变量，html.dark 下自动切暗色
@use '../assets/styles/ai-tokens.scss' as *;

.dashboard {
  padding: 20px 24px 32px;
  min-height: calc(100vh - 50px);
  background: var(--ai-page-bg, #F4F4F7);
  font-family: -apple-system, BlinkMacSystemFont, 'SF Pro Display', 'SF Pro Text', 'Helvetica Neue', sans-serif;
  color: $text;
}

/* ===== 顶部条 ===== */
.topbar {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 18px;

  &__title { display: flex; flex-direction: column; }
  &__h1    { font-size: 22px; font-weight: 700; letter-spacing: -0.3px; }
  &__sub   { margin-top: 4px; font-size: 12px; color: $text2; }

  &__right { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
}

.health-chips { display: flex; gap: 6px; }
.chip {
  display: inline-flex; align-items: center; gap: 6px;
  font-size: 12px; font-weight: 500;
  padding: 4px 10px; border-radius: 999px;
  background: var(--ai-card-bg, #fff); border: 1px solid var(--ai-border, rgba(0,0,0,0.06));
  color: $text2;

  &__dot { width: 7px; height: 7px; border-radius: 50%; }
  &--ok   .chip__dot { box-shadow: 0 0 0 3px rgba(52,199,89,0.18); }
  &--warn .chip__dot { box-shadow: 0 0 0 3px rgba(255,159,10,0.20); }
  &--bad  .chip__dot { box-shadow: 0 0 0 3px rgba(255,59,48,0.20); }
}

.window-switch :deep(.el-radio-button__inner) {
  padding: 6px 14px;
  font-size: 12px;
}

/* ===== KPI ===== */
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}
.kpi-card {
  background: var(--ai-card-bg, #fff);
  border: 1px solid var(--ai-border, rgba(0,0,0,0.05));
  border-radius: 14px;
  padding: 16px 18px 14px;
  position: relative;
  overflow: hidden;

  &::before {
    content: ''; position: absolute; left: 0; top: 0; bottom: 0; width: 3px;
    background: var(--accent, #0A84FF);
  }

  &--blue   { --accent: #0A84FF; }
  &--cyan   { --accent: #64D2FF; }
  &--violet { --accent: #BF5AF2; }
  &--green  { --accent: #30D158; }
  &--amber  { --accent: #FF9F0A; }
  &--rose   { --accent: #FF375F; }

  &__label { font-size: 12px; color: $text2; }
  &__value {
    margin-top: 6px;
    font-size: 26px; font-weight: 700;
    font-variant-numeric: tabular-nums;
    letter-spacing: -0.5px;
  }
  &__sub {
    margin-top: 4px;
    font-size: 11px;
    color: $text2;
    font-variant-numeric: tabular-nums;
  }
}
.pc-split {
  display: flex; height: 6px; border-radius: 999px;
  background: var(--ai-fill-2, rgba(0,0,0,0.04));
  margin-top: 10px; overflow: hidden;
  &__prompt     { background: linear-gradient(90deg, #0A84FF, #5E5CE6); }
  &__completion { background: linear-gradient(90deg, #FF9F0A, #FF375F); }
}

/* ===== Row 容器 ===== */
.row {
  display: grid;
  grid-template-columns: 2fr 1fr;
  gap: 12px;
  margin-bottom: 16px;
  &:has(> .panel:only-child) { grid-template-columns: 1fr; }
}
@media (max-width: 1100px) {
  .row { grid-template-columns: 1fr; }
}

/* ===== Panel ===== */
.panel {
  background: var(--ai-card-bg, #fff);
  border: 1px solid var(--ai-border, rgba(0,0,0,0.05));
  border-radius: 14px;
  padding: 16px 18px 18px;
  display: flex; flex-direction: column;

  &__head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 12px; }
  &__title { font-size: 14px; font-weight: 600; }
  &__hint  { font-size: 11px; color: $text2; }
  &__legend { display: flex; gap: 12px; font-size: 11px; color: $text2; align-items: center; }
  &__body  { flex: 1; min-height: 0; }
  &__body--ring { display: flex; gap: 18px; align-items: center; }
}
.legend-dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 4px; vertical-align: middle; }
.empty { padding: 28px 0; text-align: center; font-size: 12px; color: $text2; }

/* ===== 趋势柱状图 ===== */
.trend-svg {
  width: 100%; height: 220px;
  .grid line { stroke: var(--ai-fill-3, rgba(0,0,0,0.05)); stroke-width: 1; }
  .bar { stroke: none; }
  .bar--t { fill: #0A84FF; opacity: 0.85; }
  .bar--c { fill: #5E5CE6; opacity: 0.65; }
  .axis text { font-size: 10px; fill: $text2; font-family: inherit; }
}

/* ===== 任务状态环形 ===== */
.ring-wrap { width: 120px; flex-shrink: 0; }
.ring-svg  { width: 120px; height: 120px; }
.ring-bg   { stroke: var(--ai-fill-3, rgba(0,0,0,0.04)); stroke-width: 14; fill: none; }
.ring-num  { font-size: 20px; font-weight: 700; fill: $text; font-family: inherit; }
.ring-cap  { font-size: 9px;  fill: $text2; font-family: inherit; letter-spacing: 0.5px; }
.ring-legend { list-style: none; margin: 0; padding: 0; flex: 1; font-size: 12px; }
.ring-legend li {
  display: flex; align-items: center; gap: 6px; padding: 4px 0;
  & + li { border-top: 1px dashed var(--ai-border, rgba(0,0,0,0.05)); }
  .ring-legend__name { flex: 1; color: $text2; }
  .ring-legend__val  { font-variant-numeric: tabular-nums; font-weight: 600; }
}

/* ===== 排行 ===== */
.rank-list { display: flex; flex-direction: column; gap: 10px; }
.rank-row {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(0, 2fr) 60px;
  align-items: center; gap: 10px;
  font-size: 12px;

  &__name {
    display: flex; align-items: center; gap: 8px;
    overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    color: $text;
  }
  &__bar-wrap { height: 8px; background: var(--ai-fill-2, rgba(0,0,0,0.04)); border-radius: 999px; overflow: hidden; }
  &__bar      { height: 100%; border-radius: 999px; min-width: 4px; }
  &__val      { text-align: right; font-variant-numeric: tabular-nums; color: $text2; }
}
.rank-num {
  display: inline-flex; align-items: center; justify-content: center;
  width: 18px; height: 18px; border-radius: 6px;
  font-size: 10px; font-weight: 600; color: $text2;
  background: var(--ai-fill-2, rgba(0,0,0,0.04));
}

/* ===== 系统健康四象限 ===== */
.health-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 12px;
}
.health-cell {
  background: var(--ai-card-bg, #fff);
  border: 1px solid var(--ai-border, rgba(0,0,0,0.05));
  border-radius: 12px;
  padding: 14px 16px;
  cursor: pointer;
  transition: all 0.2s ease;
  position: relative;

  &:hover { transform: translateY(-2px); box-shadow: var(--ai-shadow-card, 0 8px 20px rgba(0,0,0,0.06)); }

  &.cell--ok   { border-left: 3px solid #30D158; }
  &.cell--warn { border-left: 3px solid #FF9F0A; }
  &.cell--bad  { border-left: 3px solid #FF3B30; }

  &__label { font-size: 12px; color: $text2; }
  &__num   { margin-top: 4px; font-size: 24px; font-weight: 700; font-variant-numeric: tabular-nums; .unit { font-size: 14px; color: $text2; margin-left: 1px; } }
  &__sub   { margin-top: 2px; font-size: 11px; color: $text2; font-variant-numeric: tabular-nums; }
  &__bar   { margin-top: 10px; height: 4px; background: var(--ai-fill-2, rgba(0,0,0,0.04)); border-radius: 999px; overflow: hidden; }
  &__bar-fill { height: 100%; border-radius: 999px; transition: width 0.4s ease; }
}

/* ===== 模块入口 ===== */
.modules {
  display: grid;
  grid-template-columns: repeat(8, minmax(0, 1fr));
  gap: 8px;
  margin-top: 16px;
  @media (max-width: 1100px) { grid-template-columns: repeat(4, minmax(0, 1fr)); }
  @media (max-width: 600px)  { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
.module-tile {
  background: var(--ai-card-bg, #fff);
  border: 1px solid var(--ai-border, rgba(0,0,0,0.05));
  border-radius: 12px;
  padding: 14px 12px;
  display: flex; flex-direction: column; align-items: center; gap: 8px;
  cursor: pointer;
  transition: all 0.2s ease;
  &:hover { transform: translateY(-2px); box-shadow: var(--ai-shadow-card, 0 6px 16px rgba(0,0,0,0.06)); }

  &__icon {
    width: 36px; height: 36px; border-radius: 10px;
    display: flex; align-items: center; justify-content: center;
    color: #fff;
    box-shadow: 0 3px 8px rgba(0,0,0,0.12);
  }
  &__name { font-size: 12px; color: $text; }
}
</style>
