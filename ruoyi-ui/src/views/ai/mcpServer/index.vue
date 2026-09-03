<template>
  <div class="mcp-page">
    <!-- 页面标题 -->
    <header class="mcp-header">
      <div class="mcp-header__left">
        <h1 class="mcp-header__title">MCP 服务</h1>
        <span class="mcp-header__count">{{ total }} 个</span>
      </div>
      <div class="mcp-header__actions">
        <button type="button" class="apple-btn apple-btn--add" @click="handleAdd" v-hasPermi="['ai:mcpServer:add']">
          <svg width="14" height="14" viewBox="0 0 14 14" fill="none"><path d="M7 1v12M1 7h12" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>
          新增服务
        </button>
      </div>
    </header>

    <!-- 搜索栏 -->
    <div class="mcp-search">
      <div class="mcp-search__field">
        <svg class="mcp-search__icon" width="15" height="15" viewBox="0 0 15 15" fill="none">
          <circle cx="6.5" cy="6.5" r="5.5" stroke="currentColor" stroke-width="1.4"/>
          <path d="M10.5 10.5L14 14" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/>
        </svg>
        <input v-model="queryParams.serverName" class="mcp-search__input" placeholder="搜索服务名称…" @keyup.enter="handleQuery" />
        <button type="button" v-if="queryParams.serverName" class="mcp-search__clear" @click="queryParams.serverName = ''; handleQuery()">✕</button>
      </div>
      <input v-model="queryParams.serverCode" class="mcp-search__input mcp-search__input--mid" placeholder="按服务编码" @keyup.enter="handleQuery" />
      <select v-model="queryParams.transport" class="mcp-select" @change="handleQuery">
        <option value="">全部传输</option>
        <option v-for="t in TRANSPORTS" :key="t.value" :value="t.value">{{ t.label }}</option>
      </select>
      <select v-model="queryParams.status" class="mcp-select" @change="handleQuery">
        <option value="">全部状态</option>
        <option v-for="dict in sys_normal_disable" :key="dict.value" :value="dict.value">{{ dict.label }}</option>
      </select>
      <button type="button" class="apple-btn apple-btn--ghost" @click="resetQuery">重置</button>
    </div>

    <!-- 卡片列表 -->
    <div v-loading="loading" class="mcp-grid">
      <article
        v-for="item in serverList"
        :key="item.mcpServerId"
        class="mcp-card"
        :class="{ 'is-off': item.status !== '0' }"
        :style="{ '--accent': colorOf(item.transport || item.serverName) }"
        @click="handleDetail(item)"
      >
        <span class="mcp-card__rail"></span>
        <div class="mcp-card__head">
          <div class="mcp-card__ident">
            <h3 class="mcp-card__name" :title="item.serverName">{{ item.serverName }}</h3>
            <div class="mcp-card__sub">
              <span class="mcp-card__code">{{ item.serverCode }}</span>
              <span class="mcp-card__type" :class="'is-' + (item.transport || '').toLowerCase()">
                <i class="mcp-card__type-dot" :style="{ background: colorOf(item.transport) }"></i>
                {{ item.transport || '—' }}
              </span>
              <span class="mcp-card__status" :class="item.status === '0' ? 'is-on' : 'is-off'">
                <i></i>{{ item.status === '0' ? '已启用' : '已停用' }}
              </span>
              <!-- 运行时连接状态：跟「已启用」是两回事，配置启用但连接断掉时这里会亮红 -->
              <span
                v-if="item.status === '0'"
                class="mcp-card__link"
                :class="linkClassOf(item)"
                :title="linkTitleOf(item)"
              >
                <i></i>{{ linkTextOf(item) }}
              </span>
            </div>
          </div>
          <div class="mcp-card__actions" @click.stop>
            <button type="button" class="mcp-card__action" title="编辑" @click.stop="handleUpdate(item)" v-hasPermi="['ai:mcpServer:edit']">
              <svg width="14" height="14" viewBox="0 0 16 16" fill="none"><path d="M11.5 2.5l2 2L5 13H3v-2l8.5-8.5z" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/></svg>
            </button>
            <button type="button" class="mcp-card__action mcp-card__action--warn" title="重连" @click.stop="handleReconnect(item)" v-hasPermi="['ai:mcpServer:edit']">
              <svg width="14" height="14" viewBox="0 0 16 16" fill="none"><path d="M2 8a6 6 0 0110-4.5M12 2v3.5h-3.5M12 8a6 6 0 01-10 4.5M2 14v-3.5h3.5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/></svg>
            </button>
            <button type="button" class="mcp-card__action mcp-card__action--danger" title="删除" @click.stop="handleDelete(item)" v-hasPermi="['ai:mcpServer:remove']">
              <svg width="14" height="14" viewBox="0 0 16 16" fill="none"><path d="M3 4.5h10M6.5 2.5h3M4.5 4.5l.5 9h6l.5-9M6.5 7v4M9.5 7v4" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round"/></svg>
            </button>
          </div>
        </div>

        <div class="mcp-card__props">
          <span class="mcp-card__prop" :class="'is-health-' + (item.healthStatus || '0')" :title="healthHint(item)">
            <span class="mcp-card__prop-k">健康</span>
            <b>{{ healthLabel(item.healthStatus) }}</b>
          </span>
          <span v-if="item.transport === 'STDIO'" class="mcp-card__prop"><span class="mcp-card__prop-k">命令</span><b :title="item.command">{{ item.command || '—' }}</b></span>
          <span v-else class="mcp-card__prop"><span class="mcp-card__prop-k">端点</span><b :title="item.endpoint">{{ item.endpoint || '—' }}</b></span>
        </div>

        <div class="mcp-card__meta">
          <span class="meta-row">
            <span class="meta-row__k">上次</span>
            <span class="meta-row__v">{{ formatTime(item.healthCheckTime) }}</span>
          </span>
          <span class="meta-row" :title="item.env ? '已配置环境变量' : '未配置'">
            <span class="meta-row__k">env</span>
            <span class="meta-row__v meta-row__v--mono">{{ item.env ? '已配置（加密）' : '未配置' }}</span>
          </span>
        </div>
      </article>

      <!-- 空态 -->
      <div v-if="!loading && serverList.length === 0" class="mcp-empty">
        <div class="mcp-empty__icon">🔌</div>
        <p class="mcp-empty__text">还没有 MCP 服务</p>
        <p class="mcp-empty__sub">MCP 服务用于接入远程工具源，配置后可在工具管理同步</p>
        <button type="button" class="apple-btn apple-btn--add" @click="handleAdd" v-hasPermi="['ai:mcpServer:add']">添加第一个服务</button>
      </div>
    </div>

    <!-- 分页 -->
    <div v-show="total > 0" class="mcp-pagination">
      <pagination :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" @pagination="getList" />
    </div>

    <!-- ==================== 详情面板（只读） ==================== -->
    <Teleport to="body">
      <Transition name="sheet">
        <div v-if="detailOpen" class="sheet-overlay" @click.self="closeDetail">
          <div class="sheet sheet--detail" role="dialog" aria-modal="true" aria-label="MCP 服务详情">
            <div class="hero" :style="{ background: detail.status === '0' ? gradientOf(detail.transport || detail.serverName) : offGradient }">
              <button type="button" class="hero__close" aria-label="关闭" @click="closeDetail">✕</button>
              <div class="hero__body">
                <div class="hero__avatar">{{ transportEmoji(detail.transport) }}</div>
                <div class="hero__text">
                  <h2 class="hero__name">{{ detail.serverName }}</h2>
                  <div class="hero__sub">
                    <span class="hero__chip" :style="{ background: 'rgba(255,255,255,0.22)', color: '#fff' }">{{ detail.transport }}</span>
                    <span class="hero__code">{{ detail.serverCode }}</span>
                    <span class="hero__status" :class="detail.status === '0' ? 'is-on' : 'is-off'">
                      <i :class="detail.status === '0' ? 'is-on' : 'is-off'"></i>{{ detail.status === '0' ? '已启用' : '已停用' }}
                    </span>
                  </div>
                </div>
              </div>
              <div class="hero__stats">
                <div class="hero__stat"><b>{{ healthLabel(detail.healthStatus) }}</b><span>健康状态</span></div>
                <div class="hero__stat"><b>{{ formatTime(detail.healthCheckTime) }}</b><span>上次检查</span></div>
                <div class="hero__stat"><b>{{ detail.env ? '✓' : '—' }}</b><span>环境变量</span></div>
              </div>
            </div>

            <div class="sheet__body">
              <div class="detail-cols">
                <div class="detail-main">
                  <div class="detail-block">
                    <div class="detail-block__title">连接配置</div>
                    <div v-if="detail.transport === 'STDIO'" class="kv-stack">
                      <div class="kv-stack__row"><span class="kv-stack__k">启动命令</span><span class="kv-stack__v kv-stack__v--mono">{{ detail.command || '—' }}</span></div>
                      <div v-if="detail.args" class="kv-stack__block">
                        <div class="kv-stack__block-k">命令参数</div>
                        <pre class="json-block json-block--lite">{{ formatJson(detail.args) }}</pre>
                      </div>
                    </div>
                    <div v-else class="kv-stack">
                      <div class="kv-stack__row"><span class="kv-stack__k">连接端点</span><span class="kv-stack__v kv-stack__v--mono">{{ detail.endpoint || '—' }}</span></div>
                    </div>
                  </div>

                  <div class="detail-block" v-if="detail.env">
                    <div class="detail-block__title detail-block__title--with-meta">
                      <span>环境变量</span>
                      <span class="detail-block__meta">
                        <span class="detail-block__len">{{ envKeyCount(detail.env) }} 项</span>
                      </span>
                    </div>
                    <pre class="json-block">{{ formatJson(detail.env) }}</pre>
                  </div>

                  <div class="detail-block" v-if="detail.remark">
                    <div class="detail-block__title">备注</div>
                    <div class="detail-prompt detail-prompt--static">{{ detail.remark }}</div>
                  </div>
                </div>

                <aside class="detail-side">
                  <div class="detail-block">
                    <div class="detail-block__title">健康状态</div>
                    <div class="health-card" :class="'is-' + (detail.healthStatus || '0')">
                      <div class="health-card__icon">{{ healthEmoji(detail.healthStatus) }}</div>
                      <div class="health-card__body">
                        <div class="health-card__label">{{ healthLabel(detail.healthStatus) }}</div>
                        <div class="health-card__time">上次检查 · {{ formatTime(detail.healthCheckTime) }}</div>
                      </div>
                    </div>
                    <button type="button" class="apple-btn apple-btn--outline detail-side__btn" @click="handleReconnect(detail)" v-hasPermi="['ai:mcpServer:edit']">立即重连</button>
                  </div>

                  <div class="detail-block">
                    <div class="detail-block__title">基础信息</div>
                    <dl class="detail-kv">
                      <div class="detail-kv__row"><dt>编码</dt><dd :title="detail.serverCode" class="detail-kv__mono">{{ detail.serverCode }}</dd></div>
                      <div class="detail-kv__row"><dt>传输</dt><dd>{{ detail.transport }}</dd></div>
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
              <button type="button" class="apple-btn apple-btn--primary" @click="editFromDetail" v-hasPermi="['ai:mcpServer:edit']">编辑</button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>

    <!-- ==================== 编辑面板 ==================== -->
    <Teleport to="body">
      <Transition name="sheet">
        <div v-if="open" class="sheet-overlay" @click.self="cancel">
          <div class="sheet" role="dialog" aria-modal="true" aria-label="编辑 MCP 服务">
            <div class="sheet__header">
              <h2 class="sheet__title">{{ title }}</h2>
              <button type="button" class="sheet__close" aria-label="关闭" @click="cancel">✕</button>
            </div>

            <div class="sheet__body">
              <el-form ref="serverRef" :model="form" :rules="rules" label-position="top" class="aform" @submit.prevent>
                <!-- 基本信息 -->
                <div class="aform__group">
                  <div class="aform__row aform__row--2">
                    <el-form-item label="服务名称" prop="serverName" class="aform__item">
                      <el-input v-model="form.serverName" placeholder="如：filesystem-mcp" />
                    </el-form-item>
                    <el-form-item v-if="form.mcpServerId" label="服务编码" prop="serverCode" class="aform__item">
                      <el-input v-model="form.serverCode" placeholder="提交后自动生成" disabled />
                    </el-form-item>
                  </div>
                </div>

                <!-- 传输方式 -->
                <div class="aform__group">
                  <div class="aform__group-label">传输方式</div>
                  <el-radio-group v-model="form.transport" class="transport-radio" @change="onTransportChange">
                    <el-radio-button v-for="t in TRANSPORTS" :key="t.value" :value="t.value">
                      <span class="transport-radio__label">{{ t.label }}</span>
                      <span class="transport-radio__hint">{{ t.hint }}</span>
                    </el-radio-button>
                  </el-radio-group>
                </div>

                <!-- 连接参数：按 transport 切换 -->
                <div class="aform__group">
                  <template v-if="form.transport === 'STDIO'">
                    <el-form-item label="启动命令" prop="command">
                      <el-input v-model="form.command" placeholder="如 npx / uvx / python / node" />
                    </el-form-item>
                    <el-form-item label="命令参数" prop="args">
                      <el-input v-model="form.args" type="textarea" :rows="3" placeholder='JSON 数组，如 ["-y","@modelcontextprotocol/server-filesystem"]' class="mono-textarea" />
                    </el-form-item>
                  </template>
                  <template v-else>
                    <el-form-item label="连接端点" prop="endpoint">
                      <el-input v-model="form.endpoint" placeholder="完整 URL，如 https://example.com/mcp" />
                    </el-form-item>
                  </template>
                </div>

                <!-- 环境变量 -->
                <div class="aform__group">
                  <el-form-item label="环境变量" prop="env">
                    <el-input v-model="form.env" type="textarea" :rows="4" placeholder='JSON 对象（加密存储），如 {"API_KEY":"xxx","TOKEN":"yyy"}' class="mono-textarea" />
                    <span class="aform__hint">密钥/token 放这里，保存时加密入库。留空表示不修改。</span>
                  </el-form-item>
                </div>

                <!-- 状态 + 备注 -->
                <div class="aform__group aform__group--toggles">
                  <div class="toggle-row">
                    <div class="toggle-row__info">
                      <span class="toggle-row__label">启用状态</span>
                      <span class="toggle-row__hint">停用后此服务不参与任何连接</span>
                    </div>
                    <el-switch v-model="form.status" active-value="0" inactive-value="1" />
                  </div>
                </div>

                <div class="aform__group">
                  <el-form-item label="备注">
                    <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="可选备注" />
                  </el-form-item>
                </div>
              </el-form>
            </div>

            <div class="sheet__footer">
              <button type="button" class="apple-btn apple-btn--ghost" @click="cancel">取消</button>
              <button type="button" class="apple-btn apple-btn--primary" @click="submitForm">保存</button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<script setup name="AiMcpServer">
import { listMcpServer, getMcpServer, addMcpServer, updateMcpServer, delMcpServer, reconnectMcpServer, getMcpRuntimeStatus } from '@/api/ai/mcpServer'
import { gradientOf, colorOf } from '@/utils/ai-palette'

const { proxy } = getCurrentInstance()
const { sys_normal_disable } = proxy.useDict('sys_normal_disable')

const offGradient = 'linear-gradient(135deg, #A1A1A6, #C7C7CC)'

const TRANSPORTS = [
  { value: 'STDIO', label: 'STDIO', hint: '本地进程' },
  { value: 'SSE', label: 'SSE', hint: '服务端推送' },
  { value: 'HTTP', label: 'HTTP', hint: '请求响应' }
]
const TRANSPORT_EMOJI = { STDIO: '⚙️', SSE: '📡', HTTP: '🌐' }
const HEALTH_EMOJI = { '0': '⚪', '1': '🟢', '2': '🔴' }
const HEALTH_LABEL = { '0': '未知', '1': '正常', '2': '异常' }

const serverList = ref([])
const loading = ref(true)
const total = ref(0)

const data = reactive({
  queryParams: {
    pageNum: 1, pageSize: 12,
    serverName: undefined, serverCode: undefined,
    transport: undefined, status: undefined
  },
  form: {},
  rules: {
    serverName: [{ required: true, message: '服务名称不能为空', trigger: 'blur' }],
    // 服务编码:disabled,新增由后端自动生成 / 编辑回显已有值,无需前端校验
    transport: [{ required: true, message: '传输方式不能为空', trigger: 'change' }],
    command: [{
      validator: (rule, value, cb) => {
        if (data.form.transport === 'STDIO' && !value) cb(new Error('STDIO 模式必填启动命令'))
        else cb()
      },
      trigger: 'blur'
    }],
    endpoint: [{
      validator: (rule, value, cb) => {
        if (data.form.transport !== 'STDIO' && !value) cb(new Error('SSE/HTTP 模式必填连接端点'))
        else cb()
      },
      trigger: 'blur'
    }]
  }
})
const { queryParams, form, rules } = toRefs(data)

function transportEmoji(t) { return TRANSPORT_EMOJI[t] || '🔌' }
function healthLabel(s) { return HEALTH_LABEL[s] || '未知' }
function healthEmoji(s) { return HEALTH_EMOJI[s] || '⚪' }
function healthHint(item) { return HEALTH_LABEL[item.healthStatus] || '未知' }
function formatTime(s) {
  if (!s) return '—'
  return String(s).replace('T', ' ').slice(0, 16)
}
function formatJson(text) {
  if (!text) return ''
  try { return JSON.stringify(JSON.parse(text), null, 2) } catch (e) { return text }
}
function envKeyCount(s) {
  if (!s) return 0
  try {
    const obj = JSON.parse(s)
    if (obj && typeof obj === 'object') return Object.keys(obj).length
  } catch (e) { /* ignore */ }
  return 0
}

/* ==================== 列表 ==================== */

function getList() {
  loading.value = true
  listMcpServer(queryParams.value).then((res) => {
    serverList.value = res.rows
    total.value = res.total
    loading.value = false
    loadRuntimeStatus()
  }).catch(() => { loading.value = false })
}

/**
 * 运行时连接状态。列表里的 status 只是「启用/停用」的配置意图，
 * 连接是否真的活着得单独问后端 —— 两者不一致(配置启用、连接已断)正是
 * 「第一次调用 MCP 工具必超时」的根源，界面上必须能看出来。
 */
const runtimeStatus = ref({})

function loadRuntimeStatus() {
  getMcpRuntimeStatus().then((res) => {
    const map = {}
    for (const row of res.data || []) map[row.serverCode] = row
    runtimeStatus.value = map
  }).catch(() => { /* 状态拿不到不影响列表展示 */ })
}

function linkClassOf(item) {
  const st = runtimeStatus.value[item.serverCode]
  if (!st) return 'is-unknown'
  return st.connected ? 'is-live' : 'is-down'
}

function linkTextOf(item) {
  const st = runtimeStatus.value[item.serverCode]
  if (!st) return '未连接'
  return st.connected ? '连接正常' : '连接断开'
}

function linkTitleOf(item) {
  const st = runtimeStatus.value[item.serverCode]
  if (!st) return '当前没有活跃连接：服务未启动连接，或连接已被清理'
  const lines = []
  lines.push(st.connected ? '连接正常' : '连接断开')
  if (st.lastError) lines.push('最近错误：' + st.lastError)
  if (st.lastOkAt) lines.push('最近成功：' + new Date(st.lastOkAt).toLocaleString())
  if (st.reconnectCount) lines.push('自动重连次数：' + st.reconnectCount)
  return lines.join('\n')
}

function handleQuery() {
  queryParams.value.pageNum = 1
  getList()
}

function resetQuery() {
  queryParams.value = { pageNum: 1, pageSize: queryParams.value.pageSize, serverName: undefined, serverCode: undefined, transport: undefined, status: undefined }
  getList()
}

/* ==================== 详情 ==================== */

const detailOpen = ref(false)
const detail = ref({})

function handleDetail(row) {
  detail.value = { ...row }
  detailOpen.value = true
  getMcpServer(row.mcpServerId).then((res) => {
    detail.value = res.data
  })
}

function closeDetail() {
  detailOpen.value = false
}

function editFromDetail() {
  const copy = JSON.parse(JSON.stringify(detail.value))
  detailOpen.value = false
  reset()
  openEditor(copy, '编辑 MCP 服务')
}

/* ==================== 编辑 ==================== */

const open = ref(false)
const title = ref('')
let formSnapshot = ''

function takeSnapshot() { formSnapshot = JSON.stringify(form.value) }
function isDirty() { return JSON.stringify(form.value) !== formSnapshot }

function reset() {
  form.value = {
    mcpServerId: undefined,
    serverName: undefined,
    serverCode: undefined,
    transport: 'STDIO',
    command: undefined,
    args: undefined,
    endpoint: undefined,
    env: undefined,
    healthStatus: '0',
    status: '0',
    remark: undefined
  }
  proxy.resetForm('serverRef')
}

function openEditor(payload, sheetTitle) {
  form.value = payload
  title.value = sheetTitle
  open.value = true
  nextTick(() => takeSnapshot())
}

function handleAdd() {
  reset()
  openEditor(form.value, '新增 MCP 服务')
}

function handleUpdate(row) {
  reset()
  getMcpServer(row.mcpServerId).then((res) => {
    openEditor(res.data, '编辑 MCP 服务')
  })
}

function onTransportChange() {
  // 切换传输方式时清空另一组的字段，避免误保存
  if (form.value.transport === 'STDIO') {
    form.value.endpoint = undefined
  } else {
    form.value.command = undefined
    form.value.args = undefined
  }
}

function cancel() {
  if (!isDirty()) {
    closeEditor()
    return
  }
  proxy.$modal.confirm('有未保存的修改，关闭后将丢失。确定关闭吗？')
    .then(closeEditor)
    .catch(() => {})
}

function closeEditor() {
  open.value = false
  formSnapshot = ''
}

function submitForm() {
  proxy.$refs['serverRef'].validate((valid) => {
    if (!valid) return
    const payload = { ...form.value }
    // env 留空不修改（避免清空数据库）
    if (payload.mcpServerId && (!payload.env || !payload.env.trim())) {
      delete payload.env
    }
    const req = payload.mcpServerId
      ? updateMcpServer(payload)
      : addMcpServer(payload)
    req.then(() => {
      proxy.$modal.msgSuccess(payload.mcpServerId ? '修改成功' : '新增成功')
      closeEditor()
      getList()
    })
  })
}

/* ==================== 删除 ==================== */

function handleDelete(row) {
  proxy.$modal.confirm(`确认删除 MCP 服务「${row.serverName}」？关联的工具引用会受影响。`).then(() => {
    return delMcpServer(row.mcpServerId)
  }).then(() => {
    proxy.$modal.msgSuccess('删除成功')
    if (detail.value.mcpServerId === row.mcpServerId) detailOpen.value = false
    getList()
  }).catch(() => {})
}

/* ==================== 重连 ==================== */

function handleReconnect(row) {
  proxy.$modal.confirm(`重连 MCP 服务「${row.serverName}」？将断开现有连接并重新初始化。`).then(() => {
    return reconnectMcpServer(row.mcpServerId)
  }).then((res) => {
    proxy.$modal.msgSuccess(res.msg || '重连成功')
    getList()
    // 同步详情
    if (detail.value.mcpServerId === row.mcpServerId) {
      const fresh = serverList.value.find(s => s.mcpServerId === row.mcpServerId)
      if (fresh) detail.value = fresh
    }
  }).catch(() => {})
}

/* ==================== 键盘 ==================== */

function onKeydown(e) {
  if (e.key === 'Escape') {
    if (detailOpen.value) closeDetail()
    else if (open.value) cancel()
  }
}

onMounted(() => document.addEventListener('keydown', onKeydown))
onUnmounted(() => document.removeEventListener('keydown', onKeydown))

getList()
</script>

<style scoped lang="scss">
@use '../../../assets/styles/ai-tokens.scss' as *;

// 设计令牌见 @/assets/styles/ai-tokens.scss + ai-theme.scss（支持暗色）
$spring: cubic-bezier(0.34, 1.56, 0.64, 1);

.mcp-page {
  font-family: $font; padding: 40px 48px; min-height: calc(100vh - 84px); -webkit-font-smoothing: antialiased;
  background:
    var(--ai-page-bg);
  @media (max-width: 768px) { padding: 24px 16px; }
}

/* Header */
.mcp-header {
  display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 28px;
  &__left { display: flex; align-items: baseline; gap: 12px; }
  &__title { font-size: 34px; font-weight: 700; color: $text; letter-spacing: -0.4px; margin: 0; }
  &__count { font-size: 15px; color: $gray; }
}
.apple-btn {
  display: inline-flex; align-items: center; gap: 6px; font-family: $font; font-size: 14px; font-weight: 500;
  border: none; border-radius: 980px; padding: 8px 18px; cursor: pointer; transition: all 0.2s $ease; outline: none;
  &:active { transform: scale(0.96); }
  &--add, &--primary { background: $blue; color: #fff; padding: 10px 24px; box-shadow: 0 2px 10px rgba(10,132,255,0.32); &:hover { background: #0071e3; } }
  &--ghost { background: transparent; color: $blue; padding: 10px 16px; &:hover { background: rgba(10,132,255,0.08); } }
  &--outline { background: transparent; color: $blue; border: 1.5px solid rgba(10,132,255,0.35); padding: 7px 16px; &:hover { background: rgba(10,132,255,0.06); border-color: $blue; } }
}

/* Search */
.mcp-search { display: flex; align-items: center; gap: 10px; margin-bottom: 24px; flex-wrap: wrap;
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
.mcp-select {
  height: 36px; padding: 0 28px 0 12px; border: none; border-radius: 8px; background: var(--ai-search-bg);
  box-shadow: 0 1px 3px var(--ai-border);
  font-size: 13px; font-family: $font; color: $text; appearance: none; cursor: pointer; outline: none;
  background-image: url("data:image/svg+xml,%3Csvg width='10' height='6' viewBox='0 0 10 6' fill='none' xmlns='http://www.w3.org/2000/svg'%3E%3Cpath d='M1 1l4 4 4-4' stroke='%238E8E93' stroke-width='1.5' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E");
  background-repeat: no-repeat; background-position: right 10px center;
}

/* 卡片网格 */
.mcp-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(330px, 1fr)); gap: 16px; min-height: 180px; @media (max-width: 768px) { grid-template-columns: 1fr; } }
.mcp-card {
  position: relative; display: flex; flex-direction: column; gap: 10px; padding: 16px 18px 14px;
  background: var(--ai-card-bg); border: 1px solid var(--ai-border); border-radius: 16px; cursor: pointer;
  box-shadow: 0 1px 2px var(--ai-fill-2); transition: all 0.28s $ease; overflow: hidden;
  &:hover { box-shadow: var(--ai-shadow-card); transform: translateY(-3px); border-color: var(--ai-input-bg);
    .mcp-card__actions { opacity: 1; transform: translateY(0); } .mcp-card__rail { opacity: 1; } }
  &:active { transform: translateY(-1px) scale(0.995); }
  &.is-off { background: var(--ai-card-off); .mcp-card__name { color: $text2; } }
  &__rail { position: absolute; left: 0; top: 0; bottom: 0; width: 3px; background: var(--accent); opacity: 0; transition: opacity 0.28s $ease; }
  &__head { display: flex; align-items: center; gap: 8px; }
  &__ident { flex: 1; min-width: 0; }
  &__name { font-size: 16px; font-weight: 600; color: $text; margin: 0 0 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; letter-spacing: -0.2px; }
  &__sub { display: flex; align-items: center; gap: 7px; min-width: 0; flex-wrap: wrap; }
  &__code { font-family: $mono; font-size: 10.5px; color: $gray; background: var(--ai-fill-2); padding: 1.5px 6px; border-radius: 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  &__type { display: inline-flex; align-items: center; gap: 4px; font-size: 11.5px; font-weight: 600; padding: 2px 8px; border-radius: 980px;
    &-dot { width: 5px; height: 5px; border-radius: 50%; }
    &.is-stdio { background: rgba(255,159,10,0.14); color: #B35C00; }
    &.is-sse { background: rgba(10,132,255,0.14); color: #0071e3; }
    &.is-http { background: rgba(191,90,242,0.14); color: #8E3FBE; }
  }
  &__status { display: inline-flex; align-items: center; gap: 4px; font-size: 11px; flex-shrink: 0;
    i { width: 6px; height: 6px; border-radius: 50%; display: inline-block; }
    &.is-on { color: #248A3D; i { background: $green; box-shadow: 0 0 0 2.5px rgba(52,199,89,0.18); } }
    &.is-off { color: $gray; i { background: $gray2; } }
  }
  // 运行时连接状态：与 __status(启用/停用配置)并排但语义不同，断开时必须显眼
  &__link { display: inline-flex; align-items: center; gap: 4px; font-size: 11px; flex-shrink: 0; cursor: help;
    i { width: 6px; height: 6px; border-radius: 50%; display: inline-block; }
    &.is-live { color: #248A3D; i { background: $green; } }
    &.is-down { color: #C4362D; i { background: $red; box-shadow: 0 0 0 2.5px rgba(255,59,48,0.18); } }
    &.is-unknown { color: $gray; i { background: $gray2; } }
  }
  &__actions { display: flex; gap: 4px; opacity: 0; transform: translateY(-3px); transition: all 0.22s $ease; flex-shrink: 0; }
  &__action { width: 27px; height: 27px; border: none; border-radius: 8px; background: var(--ai-border); color: $text2; cursor: pointer; display: flex; align-items: center; justify-content: center; transition: all 0.18s;
    &:hover { background: rgba(10,132,255,0.12); color: $blue; }
    &--warn:hover { background: rgba(255,159,10,0.14); color: #B35C00; }
    &--danger:hover { background: rgba(255,59,48,0.12); color: $red; }
  }
  &__props { display: flex; flex-wrap: wrap; gap: 5px 12px; align-items: center; padding: 2px 0; }
  &__prop { display: inline-flex; align-items: baseline; gap: 4px; font-size: 12px;
    &-k { color: $gray; } b { font-weight: 600; color: $text; font-variant-numeric: tabular-nums; max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    &.is-health-1 b { color: #1E7A3C; }
    &.is-health-2 b { color: $red; }
  }
  &__meta { display: flex; flex-direction: column; gap: 4px; padding: 8px 10px; background: var(--ai-fill-1); border-radius: $radius-sm; }
}
.meta-row { display: flex; align-items: baseline; gap: 6px; font-size: 12px; min-width: 0;
  &__k { color: $gray; flex-shrink: 0; width: 32px; }
  &__v { color: $text; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 500;
    &--mono { font-family: $mono; font-size: 11.5px; } }
}
.mcp-empty { grid-column: 1 / -1; text-align: center; padding: 72px 0; &__icon { font-size: 44px; margin-bottom: 14px; } &__text { font-size: 16px; color: $gray; margin: 0 0 6px; } &__sub { font-size: 13px; color: $gray3; margin: 0 0 18px; } }
.mcp-pagination { margin-top: 28px; display: flex; justify-content: center; }

/* ==================== Sheet ==================== */
.sheet-overlay { position: fixed; inset: 0; z-index: 2000; background: var(--ai-overlay); backdrop-filter: blur(5px); display: flex; align-items: center; justify-content: center; padding: 32px; }
.sheet {
  width: 100%; max-width: 820px; height: min(740px, 88vh); background: var(--ai-card-bg);
  border-radius: 20px; box-shadow: var(--ai-shadow-sheet);
  display: flex; flex-direction: column; overflow: hidden;
  transition: max-width 0.3s $ease, height 0.3s $ease;
  &--detail { max-width: 940px; height: auto; max-height: 88vh; }
  &__header { display: flex; align-items: center; justify-content: space-between; padding: 22px 28px 0; flex-shrink: 0; }
  &__title { font-size: 21px; font-weight: 700; color: $text; margin: 0; }
  &__close { width: 28px; height: 28px; border: none; border-radius: 50%; background: var(--ai-fill-3); color: $gray; font-size: 12px; cursor: pointer; display: flex; align-items: center; justify-content: center; flex-shrink: 0; &:hover { background: var(--ai-hover-strong); color: $text; } }
  &__body { flex: 1; min-height: 0; overflow-y: auto; padding: 18px 28px 24px;
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
  &__len { font-size: 11px; color: $gray; font-variant-numeric: tabular-nums; }
}
.kv-stack { display: flex; flex-direction: column; gap: 8px;
  &__row { display: flex; align-items: baseline; gap: 10px; min-width: 0; }
  &__k { font-size: 12px; color: $gray; flex-shrink: 0; width: 80px; }
  &__v { font-size: 12.5px; font-weight: 500; color: $text; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    &--mono { font-family: $mono; font-size: 12px; } }
  &__block { display: flex; flex-direction: column; gap: 6px; }
  &__block-k { font-size: 11.5px; color: $gray; font-weight: 500; }
}
.json-block {
  font-family: $mono; font-size: 12.5px; line-height: 1.75; color: #f5f5f7;
  background: #1d1d1f; padding: 16px 18px; border-radius: $radius-sm; margin: 0; max-height: 460px; overflow: auto;
  white-space: pre; word-break: normal;
  &::-webkit-scrollbar { width: 5px; height: 5px; } &::-webkit-scrollbar-thumb { background: rgba(255,255,255,0.18); border-radius: 3px; }
  &--lite { background: var(--ai-block-bg); color: $text; max-height: 200px; border: 1px solid var(--ai-border-2); }
}
.detail-prompt { padding: 14px 16px; background: var(--ai-block-bg); border: 1px solid var(--ai-border-2); border-radius: $radius-sm; font-size: 13.5px; line-height: 1.7; color: $text;
  &--static { white-space: pre-wrap; }
}
.detail-kv { margin: 0; display: flex; flex-direction: column; gap: 7px;
  &__row { display: flex; align-items: baseline; gap: 10px; min-width: 0; }
  dt { font-size: 12px; color: $gray; flex-shrink: 0; width: 50px; }
  dd { margin: 0; font-size: 12.5px; font-weight: 500; color: $text; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  &__mono { font-family: $mono; font-size: 12px; }
}
.detail-side__btn { margin-top: 12px; width: 100%; justify-content: center; }

.health-card {
  display: flex; align-items: center; gap: 12px; padding: 14px;
  background: var(--ai-card-bg); border: 1px solid var(--ai-fill-3); border-radius: $radius-sm;
  &.is-1 { border-color: rgba(48,209,88,0.3); background: rgba(48,209,88,0.05); }
  &.is-2 { border-color: rgba(255,59,48,0.3); background: rgba(255,59,48,0.05); }
  &__icon { font-size: 28px; line-height: 1; }
  &__body { min-width: 0; flex: 1; }
  &__label { font-size: 14px; font-weight: 600; color: $text; }
  &__time { font-size: 11.5px; color: $text2; margin-top: 2px; }
}

/* ==================== 表单 ==================== */
.aform {
  :deep(.el-form-item) { margin-bottom: 14px; }
  :deep(.el-form-item__label) { font-size: 13px; font-weight: 500; color: $text2; padding-bottom: 4px; }
  :deep(.el-input__wrapper), :deep(.el-textarea__inner) { border-radius: $radius-sm; background: var(--ai-input-bg); box-shadow: 0 0 0 1px var(--ai-border-3) inset; transition: all 0.2s $ease;
    &:hover { box-shadow: 0 0 0 1px var(--ai-border-4) inset; }
    &.is-focus, &:focus { background: var(--ai-card-bg); box-shadow: 0 0 0 4px rgba(10,132,255,0.12), 0 0 0 1px $blue inset; } }
  :deep(.el-switch.is-checked .el-switch__core) { background-color: $green; border-color: $green; }
  &__group { background: var(--ai-fill-1); border-radius: $radius; padding: 16px 20px; margin-bottom: 12px; &--toggles { padding: 6px 20px; } }
  &__row { display: flex; gap: 14px; @media (max-width: 600px) { flex-direction: column; gap: 0; } }
  &__item { flex: 1; }
  &__row--2 { display: grid; grid-template-columns: 1fr 1fr; gap: 0 14px; @media (max-width: 600px) { grid-template-columns: 1fr; } }
  &__group-label { font-size: 13px; font-weight: 600; color: $text; margin-bottom: 10px; }
  &__hint { display: block; margin-top: 6px; font-size: 11.5px; color: $gray; }
}
.toggle-row { display: flex; align-items: center; justify-content: space-between; padding: 12px 0; gap: 16px;
  & + & { border-top: 1px solid var(--ai-border); }
  &__info { display: flex; flex-direction: column; gap: 2px; } &__label { font-size: 14px; font-weight: 500; color: $text; } &__hint { font-size: 12px; color: $gray; }
}
.mono-textarea :deep(.el-textarea__inner) { font-family: $mono; font-size: 12.5px; line-height: 1.7; }

/* 传输方式 radio：按钮内显示标签 + 副标签 */
.transport-radio { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; width: 100%; }
.transport-radio :deep(.el-radio-button) { margin: 0; flex: 1; }
.transport-radio :deep(.el-radio-button__inner) { width: 100%; padding: 10px 14px; border-radius: 10px !important; border-color: var(--ai-hover-strong) !important; background: var(--ai-card-bg); box-shadow: none !important; }
.transport-radio :deep(.el-radio-button__inner) { display: flex; flex-direction: column; align-items: center; gap: 2px; line-height: 1.3; }
.transport-radio :deep(.el-radio-button.is-active .el-radio-button__inner) { background: rgba(10,132,255,0.08) !important; border-color: $blue !important; color: $blue !important; box-shadow: 0 0 0 3px rgba(10,132,255,0.12) !important; }
.transport-radio__label { font-size: 14px; font-weight: 600; }
.transport-radio__hint { font-size: 11px; color: $gray; font-weight: 400; }
.transport-radio :deep(.el-radio-button.is-active) .transport-radio__hint { color: $blue; opacity: 0.8; }
</style>
