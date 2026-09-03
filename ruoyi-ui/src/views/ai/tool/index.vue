<template>
  <div class="tool-page">
    <!-- 页面标题 -->
    <header class="tool-header">
      <div class="tool-header__left">
        <h1 class="tool-header__title">工具管理</h1>
        <span class="tool-header__count">{{ total }} 个</span>
      </div>
      <div class="tool-header__actions">
        <button type="button" class="apple-btn apple-btn--add" @click="handleSyncMcp" v-hasPermi="['ai:tool:sync']">
          <svg width="13" height="13" viewBox="0 0 14 14" fill="none"><path d="M2 8a6 6 0 0110-4.5M12 2v3.5h-3.5M12 8a6 6 0 01-10 4.5M2 14v-3.5h3.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
          同步 MCP
        </button>
      </div>
    </header>

    <!-- 提示 + 统计 -->
    <div class="tool-banner">
      <p class="tool-banner__hint">内置工具启动时自动同步；MCP 工具在连接服务后同步，远端工具有变时点「同步 MCP」。</p>
      <div v-if="stats" class="tool-banner__stats">
        <span class="stat-chip"><b>{{ stats.builtinCallbacks || 0 }}</b><span>内置回调</span></span>
        <span class="stat-chip"><b>{{ stats.mcpTools || 0 }}</b><span>MCP 工具</span></span>
        <span class="stat-chip stat-chip--total"><b>{{ stats.total || 0 }}</b><span>总数</span></span>
      </div>
    </div>

    <!-- 搜索栏 -->
    <div class="tool-search">
      <div class="tool-search__field">
        <svg class="tool-search__icon" width="15" height="15" viewBox="0 0 15 15" fill="none">
          <circle cx="6.5" cy="6.5" r="5.5" stroke="currentColor" stroke-width="1.4"/>
          <path d="M10.5 10.5L14 14" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/>
        </svg>
        <input v-model="queryParams.toolName" class="tool-search__input" placeholder="搜索工具名称…" @keyup.enter="handleQuery" />
        <button type="button" v-if="queryParams.toolName" class="tool-search__clear" @click="queryParams.toolName = ''; handleQuery()">✕</button>
      </div>
      <input v-model="queryParams.toolCode" class="tool-search__input tool-search__input--mid" placeholder="按工具编码" @keyup.enter="handleQuery" />
      <input v-model="queryParams.category" class="tool-search__input tool-search__input--mid" placeholder="按分类" @keyup.enter="handleQuery" />
      <select v-model="queryParams.toolType" class="tool-select" @change="handleQuery">
        <option value="">全部类型</option>
        <option value="1">内置</option>
        <option value="2">MCP</option>
      </select>
      <select v-model="queryParams.status" class="tool-select" @change="handleQuery">
        <option value="">全部状态</option>
        <option v-for="dict in sys_normal_disable" :key="dict.value" :value="dict.value">{{ dict.label }}</option>
      </select>
      <button type="button" class="apple-btn apple-btn--ghost" @click="resetQuery">重置</button>
    </div>

    <!-- 卡片列表 -->
    <div v-loading="loading" class="tool-grid">
      <article
        v-for="item in toolList"
        :key="item.toolId"
        class="tool-card"
        :class="{ 'is-off': item.status !== '0' }"
        :style="{ '--accent': colorOf(item.toolType) }"
        @click="handleDetail(item)"
      >
        <span class="tool-card__rail"></span>
        <div class="tool-card__head">
          <div class="tool-card__ident">
            <h3 class="tool-card__name" :title="item.toolName">{{ item.toolName }}</h3>
            <div class="tool-card__sub">
              <span class="tool-card__code">{{ item.toolCode }}</span>
              <span class="tool-card__type" :class="'is-' + item.toolType">
                <i class="tool-card__type-dot" :style="{ background: colorOf(item.toolType) }"></i>
                {{ typeLabel(item.toolType) }}
              </span>
              <span class="tool-card__status" :class="item.status === '0' ? 'is-on' : 'is-off'">
                <i></i>{{ item.status === '0' ? '已启用' : '已停用' }}
              </span>
            </div>
          </div>
          <div class="tool-card__actions" @click.stop>
            <button v-if="item.toolType === '2'" type="button" class="tool-card__action" title="同步" @click.stop="handleSyncByRow(item)" v-hasPermi="['ai:tool:sync']">
              <svg width="14" height="14" viewBox="0 0 16 16" fill="none"><path d="M2 8a6 6 0 0110-4.5M12 2v3.5h-3.5M12 8a6 6 0 01-10 4.5M2 14v-3.5h3.5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/></svg>
            </button>
            <div class="tool-card__switch" @click.stop>
              <el-switch v-model="item.status" active-value="0" inactive-value="1" @change="handleStatusChange(item)" v-hasPermi="['ai:tool:edit']" />
            </div>
          </div>
        </div>

        <div class="tool-card__props">
          <span v-if="item.category" class="tool-card__chip">
            <i class="tool-card__chip-dot" :style="{ background: colorOf(item.category) }"></i>
            {{ item.category }}
          </span>
          <span v-else class="tool-card__chip tool-card__chip--empty">未分类</span>
          <span class="tool-card__prop" :title="sourceText(item)">
            <span class="tool-card__prop-k">来源</span>
            <b>{{ sourceText(item) }}</b>
          </span>
        </div>

        <p v-if="item.description" class="tool-card__desc" :title="item.description">{{ item.description }}</p>
      </article>

      <!-- 空态 -->
      <div v-if="!loading && toolList.length === 0" class="tool-empty">
        <div class="tool-empty__icon">🛠️</div>
        <p class="tool-empty__text">还没有任何工具</p>
        <p class="tool-empty__sub">内置工具重启后会出现；MCP 请先配置服务再点「同步 MCP」</p>
        <button type="button" class="apple-btn apple-btn--add" @click="handleSyncMcp" v-hasPermi="['ai:tool:sync']">同步 MCP</button>
      </div>
    </div>

    <!-- 分页 -->
    <div v-show="total > 0" class="tool-pagination">
      <pagination :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" @pagination="getList" />
    </div>

    <!-- ==================== 详情面板（只读） ==================== -->
    <Teleport to="body">
      <Transition name="sheet">
        <div v-if="detailOpen" class="sheet-overlay" @click.self="closeDetail">
          <div class="sheet sheet--wide" role="dialog" aria-modal="true" aria-label="工具详情">
            <div class="hero" :style="{ background: detail.status === '0' ? gradientOf(detail.toolType) : offGradient }">
              <button type="button" class="hero__close" aria-label="关闭" @click="closeDetail">✕</button>
              <div class="hero__body">
                <div class="hero__avatar">{{ typeEmoji(detail.toolType) }}</div>
                <div class="hero__text">
                  <h2 class="hero__name">{{ detail.toolName }}</h2>
                  <div class="hero__sub">
                    <span class="hero__chip" :style="{ background: 'rgba(255,255,255,0.22)', color: '#fff' }">
                      {{ typeLabel(detail.toolType) }}
                    </span>
                    <span class="hero__code">{{ detail.toolCode }}</span>
                    <span class="hero__status" :class="detail.status === '0' ? 'is-on' : 'is-off'">
                      <i :class="detail.status === '0' ? 'is-on' : 'is-off'"></i>{{ detail.status === '0' ? '已启用' : '已停用' }}
                    </span>
                  </div>
                </div>
              </div>
              <div class="hero__stats">
                <div class="hero__stat"><b>{{ formatLen(detail.description) }}</b><span>描述字数</span></div>
                <div class="hero__stat"><b>{{ paramCount(detail.inputSchema) }}</b><span>入参字段</span></div>
                <div class="hero__stat"><b>{{ formatLen(detail.returnDesc) }}</b><span>返回说明</span></div>
              </div>
            </div>

            <div class="sheet__body">
              <div class="detail-cols">
                <div class="detail-main">
                  <div class="detail-block" v-if="detail.description">
                    <div class="detail-block__title">工具描述</div>
                    <div class="detail-prompt detail-prompt--static">{{ detail.description }}</div>
                  </div>

                  <div class="detail-block">
                    <div class="detail-block__title detail-block__title--with-meta">
                      <span>入参 Schema</span>
                      <span class="detail-block__meta">
                        <span v-if="paramCount(detail.inputSchema)" class="detail-block__ph">{{ paramCount(detail.inputSchema) }} 个字段</span>
                        <span class="detail-block__len">{{ formatLen(detail.inputSchema) }} 字</span>
                      </span>
                    </div>
                    <pre v-if="detail.inputSchema" class="json-block">{{ formatJson(detail.inputSchema) }}</pre>
                    <div v-else class="detail-hollow">
                      <span>该工具无入参</span>
                    </div>
                  </div>

                  <div class="detail-block" v-if="detail.returnDesc">
                    <div class="detail-block__title">返回说明</div>
                    <div class="detail-prompt detail-prompt--static">{{ detail.returnDesc }}</div>
                  </div>
                </div>

                <aside class="detail-side">
                  <div class="detail-block">
                    <div class="detail-block__title">基本信息</div>
                    <dl class="detail-kv">
                      <div class="detail-kv__row"><dt>编码</dt><dd :title="detail.toolCode" class="detail-kv__mono">{{ detail.toolCode }}</dd></div>
                      <div class="detail-kv__row"><dt>类型</dt><dd>{{ typeLabel(detail.toolType) }}</dd></div>
                      <div class="detail-kv__row"><dt>分类</dt><dd :class="{ 'is-missing': !detail.category }">{{ detail.category || '未分类' }}</dd></div>
                      <div class="detail-kv__row"><dt>排序</dt><dd>{{ detail.sort ?? 0 }}</dd></div>
                    </dl>
                  </div>

                  <div class="detail-block">
                    <div class="detail-block__title">来源信息</div>
                    <dl class="detail-kv">
                      <template v-if="detail.toolType === '1'">
                        <div class="detail-kv__row"><dt>Bean</dt><dd :title="detail.beanName" class="detail-kv__mono">{{ detail.beanName || '—' }}</dd></div>
                        <div class="detail-kv__row"><dt>方法</dt><dd :title="detail.methodName" class="detail-kv__mono">{{ detail.methodName || '—' }}</dd></div>
                      </template>
                      <template v-else>
                        <div class="detail-kv__row"><dt>MCP 服务</dt><dd>{{ detail.mcpServerName || '—' }}</dd></div>
                        <div class="detail-kv__row"><dt>远端工具</dt><dd :title="detail.remoteToolName" class="detail-kv__mono">{{ detail.remoteToolName || '—' }}</dd></div>
                      </template>
                    </dl>
                  </div>

                  <div class="detail-block">
                    <div class="detail-block__title">操作记录</div>
                    <dl class="detail-kv">
                      <div class="detail-kv__row"><dt>创建</dt><dd :title="detail.createTime">{{ formatTime(detail.createTime) }}</dd></div>
                      <div class="detail-kv__row"><dt>更新</dt><dd :title="detail.updateTime">{{ formatTime(detail.updateTime) }}</dd></div>
                      <div class="detail-kv__row" v-if="detail.createBy"><dt>创建人</dt><dd>{{ detail.createBy }}</dd></div>
                    </dl>
                  </div>
                </aside>
              </div>
            </div>

            <div class="sheet__footer">
              <button type="button" class="apple-btn apple-btn--ghost" @click="closeDetail">关闭</button>
              <button v-if="detail.toolType === '2'" type="button" class="apple-btn apple-btn--outline" @click="handleSyncByRow(detail)" v-hasPermi="['ai:tool:sync']">同步此 MCP</button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<script setup name="AiTool">
import { listTool, getTool, changeToolStatus, syncMcpAllTools, syncMcpTools } from '@/api/ai/tool'
import { gradientOf, colorOf } from '@/utils/ai-palette'

const { proxy } = getCurrentInstance()
const { sys_normal_disable } = proxy.useDict('sys_normal_disable')

const offGradient = 'linear-gradient(135deg, #A1A1A6, #C7C7CC)'

const TYPE_LABEL = { '1': '内置', '2': 'MCP' }
const TYPE_EMOJI = { '1': '⚙️', '2': '🔌' }

const toolList = ref([])
const loading = ref(true)
const total = ref(0)
const stats = ref(null)

const data = reactive({
  queryParams: {
    pageNum: 1, pageSize: 12,
    toolName: undefined, toolCode: undefined, category: undefined,
    toolType: undefined, status: undefined
  }
})
const { queryParams } = toRefs(data)

function typeLabel(t) { return TYPE_LABEL[t] || (t || '—') }
function typeEmoji(t) { return TYPE_EMOJI[t] || '🔧' }
function sourceText(item) {
  if (item.toolType === '2') return item.mcpServerName || 'MCP'
  return '@Tool 注解'
}
function formatLen(s) {
  if (!s) return 0
  return String(s).length
}
function formatTime(s) {
  if (!s) return '—'
  return String(s).replace('T', ' ').slice(0, 16)
}
function formatJson(text) {
  if (!text) return ''
  try {
    return JSON.stringify(JSON.parse(text), null, 2)
  } catch (e) {
    return text
  }
}
function paramCount(schema) {
  if (!schema) return 0
  try {
    const obj = JSON.parse(schema)
    if (obj && obj.properties && typeof obj.properties === 'object') {
      return Object.keys(obj.properties).length
    }
  } catch (e) { /* ignore */ }
  return 0
}

/* ==================== 列表 ==================== */

function getList() {
  loading.value = true
  listTool(queryParams.value).then((res) => {
    toolList.value = res.rows
    total.value = res.total
    loading.value = false
  }).catch(() => { loading.value = false })
}

function handleQuery() {
  queryParams.value.pageNum = 1
  getList()
}

function resetQuery() {
  queryParams.value = { pageNum: 1, pageSize: queryParams.value.pageSize, toolName: undefined, toolCode: undefined, category: undefined, toolType: undefined, status: undefined }
  getList()
}

/* ==================== 详情 ==================== */

const detailOpen = ref(false)
const detail = ref({})

function handleDetail(row) {
  // 详情用列表里的简略字段先撑住，getTool 拿到完整（含 inputSchema）再覆盖
  detail.value = { ...row }
  detailOpen.value = true
  getTool(row.toolId).then((res) => {
    detail.value = res.data
  })
}

function closeDetail() {
  detailOpen.value = false
}

/* ==================== 启停 ==================== */

function handleStatusChange(row) {
  changeToolStatus({ toolId: row.toolId, status: row.status }).then(() => {
    proxy.$modal.msgSuccess(row.status === '0' ? '已启用' : '已停用')
    // 同步详情里的状态
    if (detail.value.toolId === row.toolId) detail.value = { ...detail.value, status: row.status }
  }).catch(() => {
    row.status = row.status === '0' ? '1' : '0'
  })
}

/* ==================== 同步 ==================== */

function handleSyncMcp() {
  proxy.$modal.confirm('从已连接的 MCP 服务重新拉取工具列表？（内置工具启动时已同步，此处不同步）').then(() => {
    return syncMcpAllTools()
  }).then((res) => {
    if (res.data && res.data.stats) stats.value = res.data.stats
    proxy.$modal.msgSuccess(res.msg || 'MCP 同步完成')
    getList()
  }).catch(() => {})
}

function handleSyncByRow(row) {
  if (!row.mcpServerId) {
    proxy.$modal.msgWarning('该工具缺少 MCP 服务关联')
    return
  }
  proxy.$modal.confirm(`同步 MCP 服务 [${row.mcpServerName}] 的工具？`).then(() => {
    return syncMcpTools(row.mcpServerId)
  }).then((res) => {
    proxy.$modal.msgSuccess(res.msg || 'MCP 工具同步完成')
    getList()
  }).catch(() => {})
}

/* ==================== 键盘 ==================== */

function onKeydown(e) {
  if (e.key === 'Escape' && detailOpen.value) closeDetail()
}

onMounted(() => document.addEventListener('keydown', onKeydown))
onUnmounted(() => document.removeEventListener('keydown', onKeydown))

getList()
</script>

<style scoped lang="scss">
@use '../../../assets/styles/ai-tokens.scss' as *;

// 设计令牌见 @/assets/styles/ai-tokens.scss + ai-theme.scss（支持暗色）
$spring: cubic-bezier(0.34, 1.56, 0.64, 1);

.tool-page {
  font-family: $font; padding: 40px 48px; min-height: calc(100vh - 84px); -webkit-font-smoothing: antialiased;
  background:
    var(--ai-page-bg);
  @media (max-width: 768px) { padding: 24px 16px; }
}

/* Header */
.tool-header {
  display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 18px;
  &__left { display: flex; align-items: baseline; gap: 12px; }
  &__title { font-size: 34px; font-weight: 700; color: $text; letter-spacing: -0.4px; margin: 0; }
  &__count { font-size: 15px; color: $gray; }
  &__actions { display: flex; gap: 8px; }
}
.apple-btn {
  display: inline-flex; align-items: center; gap: 6px; font-family: $font; font-size: 14px; font-weight: 500;
  border: none; border-radius: 980px; padding: 8px 18px; cursor: pointer; transition: all 0.2s $ease; outline: none;
  &:active { transform: scale(0.96); }
  &--add, &--primary { background: $blue; color: #fff; padding: 10px 24px; box-shadow: 0 2px 10px rgba(10,132,255,0.32); &:hover { background: #0071e3; } }
  &--ghost { background: transparent; color: $blue; padding: 10px 16px; &:hover { background: rgba(10,132,255,0.08); } }
  &--outline { background: transparent; color: $blue; border: 1.5px solid rgba(10,132,255,0.35); padding: 7px 16px; &:hover { background: rgba(10,132,255,0.06); border-color: $blue; } }
}

/* 顶部 banner：提示 + 统计 */
.tool-banner { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 12px 18px; background: rgba(10,132,255,0.05); border: 1px solid rgba(10,132,255,0.12); border-radius: $radius; margin-bottom: 18px; flex-wrap: wrap;
  &__hint { font-size: 12.5px; color: $text2; margin: 0; line-height: 1.5; code { font-family: $mono; font-size: 12px; background: rgba(10,132,255,0.1); color: $blue; padding: 1px 5px; border-radius: 4px; } }
  &__stats { display: flex; align-items: center; gap: 8px; }
}
.stat-chip { display: inline-flex; align-items: baseline; gap: 4px; font-size: 12px; color: $text2; background: var(--ai-chip-bg); padding: 4px 9px; border-radius: 8px;
  b { font-size: 14px; font-weight: 700; color: $text; font-variant-numeric: tabular-nums; }
  &--total { background: rgba(10,132,255,0.12); b { color: $blue; } }
}

/* Search */
.tool-search { display: flex; align-items: center; gap: 10px; margin-bottom: 24px; flex-wrap: wrap;
  &__field { position: relative; flex: 1; min-width: 220px; max-width: 320px; }
  &__icon { position: absolute; left: 13px; top: 50%; transform: translateY(-50%); color: $gray2; pointer-events: none; }
  &__input {
    width: 100%; height: 38px; padding: 0 32px 0 36px; border: none; border-radius: 980px;
    background: var(--ai-search-bg); font-size: 14px; font-family: $font; color: $text; outline: none;
    transition: all 0.25s $ease; box-shadow: 0 1px 3px var(--ai-border);
    &::placeholder { color: $gray2; }
    &:focus { background: var(--ai-card-bg); box-shadow: 0 0 0 4px rgba(10,132,255,0.12), 0 2px 12px var(--ai-border-2); }
    &--mid { padding-left: 14px; max-width: 200px; }
  }
  &__clear {
    position: absolute; right: 10px; top: 50%; transform: translateY(-50%); width: 18px; height: 18px;
    border: none; border-radius: 50%; background: $gray3; color: #fff; font-size: 9px; cursor: pointer;
    display: flex; align-items: center; justify-content: center; &:hover { background: $gray; }
  }
}
.tool-select {
  height: 36px; padding: 0 28px 0 12px; border: none; border-radius: 8px; background: var(--ai-search-bg);
  box-shadow: 0 1px 3px var(--ai-border);
  font-size: 13px; font-family: $font; color: $text; appearance: none; cursor: pointer; outline: none;
  background-image: url("data:image/svg+xml,%3Csvg width='10' height='6' viewBox='0 0 10 6' fill='none' xmlns='http://www.w3.org/2000/svg'%3E%3Cpath d='M1 1l4 4 4-4' stroke='%238E8E93' stroke-width='1.5' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E");
  background-repeat: no-repeat; background-position: right 10px center;
}

/* 卡片网格 */
.tool-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(330px, 1fr)); gap: 16px; min-height: 180px; @media (max-width: 768px) { grid-template-columns: 1fr; } }
.tool-card {
  position: relative; display: flex; flex-direction: column; gap: 10px; padding: 16px 18px 14px;
  background: var(--ai-card-bg); border: 1px solid var(--ai-border); border-radius: 16px; cursor: pointer;
  box-shadow: 0 1px 2px var(--ai-fill-2); transition: all 0.28s $ease; overflow: hidden;
  &:hover { box-shadow: var(--ai-shadow-card); transform: translateY(-3px); border-color: var(--ai-input-bg);
    .tool-card__actions { opacity: 1; transform: translateY(0); } .tool-card__rail { opacity: 1; } }
  &:active { transform: translateY(-1px) scale(0.995); }
  &.is-off { background: var(--ai-card-off); .tool-card__name { color: $text2; } }
  &__rail { position: absolute; left: 0; top: 0; bottom: 0; width: 3px; background: var(--accent); opacity: 0; transition: opacity 0.28s $ease; }
  &__head { display: flex; align-items: center; gap: 8px; }
  &__ident { flex: 1; min-width: 0; }
  &__name { font-size: 16px; font-weight: 600; color: $text; margin: 0 0 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; letter-spacing: -0.2px; }
  &__sub { display: flex; align-items: center; gap: 7px; min-width: 0; flex-wrap: wrap; }
  &__code { font-family: $mono; font-size: 10.5px; color: $gray; background: var(--ai-fill-2); padding: 1.5px 6px; border-radius: 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  &__type { display: inline-flex; align-items: center; gap: 4px; font-size: 11.5px; font-weight: 600; padding: 2px 8px; border-radius: 980px;
    &-dot { width: 5px; height: 5px; border-radius: 50%; }
    &.is-1 { background: rgba(255,159,10,0.14); color: #B35C00; }
    &.is-2 { background: rgba(191,90,242,0.14); color: #8E3FBE; }
  }
  &__status { display: inline-flex; align-items: center; gap: 4px; font-size: 11px; flex-shrink: 0;
    i { width: 6px; height: 6px; border-radius: 50%; display: inline-block; }
    &.is-on { color: #248A3D; i { background: $green; box-shadow: 0 0 0 2.5px rgba(52,199,89,0.18); } }
    &.is-off { color: $gray; i { background: $gray2; } }
  }
  &__actions { display: flex; align-items: center; gap: 6px; opacity: 0; transform: translateY(-3px); transition: all 0.22s $ease; flex-shrink: 0; }
  &__action { width: 27px; height: 27px; border: none; border-radius: 8px; background: var(--ai-border); color: $text2; cursor: pointer; display: flex; align-items: center; justify-content: center; transition: all 0.18s;
    &:hover { background: rgba(10,132,255,0.12); color: $blue; }
  }
  &__switch { display: flex; align-items: center; }
  &__props { display: flex; flex-wrap: wrap; gap: 5px 12px; align-items: center; padding: 2px 0; }
  &__chip { display: inline-flex; align-items: center; gap: 5px; font-size: 11.5px; font-weight: 600; padding: 2.5px 8px; border-radius: 980px; background: var(--ai-fill-2); color: $text2;
    &-dot { width: 6px; height: 6px; border-radius: 50%; }
    &--empty { background: var(--ai-fill-2); color: $gray3; }
  }
  &__prop { display: inline-flex; align-items: baseline; gap: 4px; font-size: 12px;
    &-k { color: $gray; } b { font-weight: 600; color: $text; font-variant-numeric: tabular-nums; max-width: 160px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  }
  &__desc { font-size: 12.5px; color: $text2; margin: 0; line-height: 1.5; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
}
.tool-empty { grid-column: 1 / -1; text-align: center; padding: 64px 0; &__icon { font-size: 44px; margin-bottom: 14px; } &__text { font-size: 16px; color: $gray; margin: 0 0 6px; } &__sub { font-size: 13px; color: $gray3; margin: 0 0 18px; } }
.tool-pagination { margin-top: 28px; display: flex; justify-content: center; }

/* ==================== Sheet ==================== */
.sheet-overlay { position: fixed; inset: 0; z-index: 2000; background: var(--ai-overlay); backdrop-filter: blur(5px); display: flex; align-items: center; justify-content: center; padding: 32px; }
.sheet {
  width: 100%; max-width: 820px; height: min(780px, 88vh); background: var(--ai-card-bg);
  border-radius: 20px; box-shadow: var(--ai-shadow-sheet);
  display: flex; flex-direction: column; overflow: hidden;
  transition: max-width 0.3s $ease, height 0.3s $ease;
  &--wide { max-width: 1120px; height: min(880px, 94vh); }
  &__header { display: flex; align-items: center; justify-content: space-between; padding: 22px 28px 0; flex-shrink: 0; }
  &__title { font-size: 21px; font-weight: 700; color: $text; margin: 0; }
  &__close { width: 28px; height: 28px; border: none; border-radius: 50%; background: var(--ai-fill-3); color: $gray; font-size: 12px; cursor: pointer; display: flex; align-items: center; justify-content: center; flex-shrink: 0; &:hover { background: var(--ai-hover-strong); color: $text; } }
  &__body { flex: 1; min-height: 0; overflow-y: auto; padding: 20px 28px 24px;
    &::-webkit-scrollbar { width: 5px; } &::-webkit-scrollbar-thumb { background: var(--ai-border-4); border-radius: 3px; } }
  &__footer { display: flex; justify-content: flex-end; gap: 10px; padding: 14px 28px 22px; border-top: 1px solid var(--ai-fill-3); flex-shrink: 0; }
}
.sheet-enter-active { transition: all 0.35s $spring; }
.sheet-leave-active { transition: all 0.2s ease-in; }
.sheet-enter-from { opacity: 0; .sheet { transform: scale(0.92) translateY(20px); opacity: 0; } }
.sheet-leave-to { opacity: 0; .sheet { transform: scale(0.96); opacity: 0; } }

.hero {
  position: relative; flex-shrink: 0; padding: 24px 28px 0; color: #fff;
  &::after { content: ''; position: absolute; inset: 0; background: linear-gradient(180deg, var(--ai-fill-3), var(--ai-border-4)); pointer-events: none; }
  &__close { position: absolute; top: 16px; right: 16px; z-index: 2; width: 28px; height: 28px; border: none; border-radius: 50%; background: rgba(255,255,255,0.22); color: #fff; font-size: 12px; cursor: pointer; display: flex; align-items: center; justify-content: center; backdrop-filter: blur(6px); &:hover { background: rgba(255,255,255,0.34); } }
  &__body { position: relative; z-index: 1; display: flex; align-items: center; gap: 14px; }
  &__avatar { width: 54px; height: 54px; border-radius: 15px; flex-shrink: 0; display: flex; align-items: center; justify-content: center; font-size: 26px; line-height: 1; background: rgba(255,255,255,0.24); backdrop-filter: blur(10px); border: 1px solid rgba(255,255,255,0.3); }
  &__text { min-width: 0; }
  &__name { font-size: 22px; font-weight: 700; margin: 0 0 6px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; text-shadow: 0 1px 3px var(--ai-border-4); }
  &__sub { display: flex; align-items: center; gap: 9px; flex-wrap: wrap; }
  &__chip { font-size: 12px; font-weight: 600; padding: 2px 9px; border-radius: 980px; }
  &__code { font-family: $mono; font-size: 11px; background: rgba(255,255,255,0.2); padding: 2px 7px; border-radius: 5px; }
  &__status { display: inline-flex; align-items: center; gap: 5px; font-size: 12px; i { width: 6px; height: 6px; border-radius: 50%; background: var(--ai-card-bg); display: inline-block; &.is-off { opacity: 0.55; } } }
  &__stats { position: relative; z-index: 1; display: flex; gap: 26px; margin-top: 18px; padding: 12px 2px; border-top: 1px solid rgba(255,255,255,0.22); }
  &__stat { display: flex; align-items: baseline; gap: 5px; b { font-size: 18px; font-weight: 700; font-variant-numeric: tabular-nums; } span { font-size: 12px; opacity: 0.82; } }
}

.detail-cols {
  display: grid; grid-template-columns: minmax(0, 1fr) 280px; gap: 24px; align-items: start;
  @media (max-width: 760px) { grid-template-columns: 1fr; gap: 20px; }
}
.detail-main { min-width: 0; display: flex; flex-direction: column; gap: 18px; }
.detail-side { min-width: 0; display: flex; flex-direction: column; gap: 14px; background: var(--ai-fill-1); border-radius: $radius; padding: 16px; }
.detail-block {
  min-width: 0;
  & + & { padding-top: 14px; border-top: 1px solid var(--ai-fill-3); }
  &__title { display: flex; align-items: center; gap: 6px; font-size: 12px; font-weight: 600; color: $text; margin-bottom: 9px;
    &--with-meta { justify-content: space-between; }
  }
  &__meta { display: inline-flex; align-items: center; gap: 8px; }
  &__ph { font-size: 11px; color: $blue; font-weight: 500; }
  &__len { font-size: 11px; color: $gray; font-variant-numeric: tabular-nums; }
}
.detail-kv { margin: 0; display: flex; flex-direction: column; gap: 7px;
  &__row { display: flex; align-items: baseline; gap: 10px; min-width: 0; }
  dt { font-size: 12px; color: $gray; flex-shrink: 0; width: 50px; }
  dd { margin: 0; font-size: 12.5px; font-weight: 500; color: $text; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    &.is-missing { color: $gray3; } }
  &__mono { font-family: $mono; font-size: 12px; }
}
.detail-prompt { padding: 14px 16px; background: var(--ai-block-bg); border: 1px solid var(--ai-border-2); border-radius: $radius-sm; font-size: 13.5px; line-height: 1.7; color: $text;
  &--static { white-space: pre-wrap; }
}
.detail-hollow { display: flex; align-items: center; justify-content: center; min-height: 100px; border: 1px dashed var(--ai-border-4); border-radius: $radius-sm; background: var(--ai-fill-1); font-size: 13px; color: $gray3; }
.json-block {
  font-family: $mono; font-size: 12.5px; line-height: 1.75; color: #f5f5f7;
  background: #1d1d1f; padding: 16px 18px; border-radius: $radius-sm; margin: 0; max-height: 460px; overflow: auto;
  white-space: pre; word-break: normal;
  &::-webkit-scrollbar { width: 5px; height: 5px; } &::-webkit-scrollbar-thumb { background: rgba(255,255,255,0.18); border-radius: 3px; }
}
</style>
