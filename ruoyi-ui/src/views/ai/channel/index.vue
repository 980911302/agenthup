<template>
  <div class="channel-page">
    <!-- 页面标题 -->
    <header class="channel-header">
      <div class="channel-header__left">
        <h1 class="channel-header__title">上游渠道</h1>
        <span class="channel-header__count">{{ total }} 个</span>
      </div>
      <div class="channel-header__actions">
        <button type="button" class="apple-btn apple-btn--add" @click="handleAdd" v-hasPermi="['ai:channel:add']">
          <svg width="14" height="14" viewBox="0 0 14 14" fill="none"><path d="M7 1v12M1 7h12" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>
          新增渠道
        </button>
      </div>
    </header>

    <!-- 搜索栏 -->
    <div class="channel-search">
      <div class="channel-search__field">
        <svg class="channel-search__icon" width="15" height="15" viewBox="0 0 15 15" fill="none">
          <circle cx="6.5" cy="6.5" r="5.5" stroke="currentColor" stroke-width="1.4"/>
          <path d="M10.5 10.5L14 14" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/>
        </svg>
        <input v-model="queryParams.channelName" class="channel-search__input" placeholder="搜索渠道名称…" @keyup.enter="handleQuery" />
        <button type="button" v-if="queryParams.channelName" class="channel-search__clear" @click="queryParams.channelName = ''; handleQuery()">✕</button>
      </div>
      <input v-model="queryParams.channelCode" class="channel-search__input channel-search__input--mid" placeholder="按渠道编码" @keyup.enter="handleQuery" />
      <select v-model="queryParams.channelType" class="channel-select" @change="handleQuery">
        <option value="">全部类型</option>
        <option v-for="t in CHANNEL_TYPES" :key="t.value" :value="t.value">{{ t.label }}</option>
      </select>
      <select v-model="queryParams.status" class="channel-select" @change="handleQuery">
        <option value="">全部状态</option>
        <option v-for="dict in sys_normal_disable" :key="dict.value" :value="dict.value">{{ dict.label }}</option>
      </select>
      <button type="button" class="apple-btn apple-btn--ghost" @click="resetQuery">重置</button>
    </div>

    <!-- 卡片列表 -->
    <div v-loading="loading" class="channel-grid">
      <article
        v-for="item in channelList"
        :key="item.channelId"
        class="channel-card"
        :class="{ 'is-off': item.status !== '0' }"
        :style="{ '--accent': colorOf(item.channelType || item.channelName) }"
        @click="handleDetail(item)"
      >
        <span class="channel-card__rail"></span>
        <div class="channel-card__head">
          <div class="channel-card__ident">
            <h3 class="channel-card__name" :title="item.channelName">{{ item.channelName }}</h3>
            <div class="channel-card__sub">
              <span class="channel-card__code">{{ item.channelCode }}</span>
              <span class="channel-card__status" :class="item.status === '0' ? 'is-on' : 'is-off'">
                <i></i>{{ item.status === '0' ? '已启用' : '已停用' }}
              </span>
            </div>
          </div>
          <div class="channel-card__actions">
            <button type="button" class="channel-card__action" title="模型清单" @click.stop="handleUpstreamModels(item)" v-hasPermi="['ai:channel:list']">
              <svg width="15" height="15" viewBox="0 0 16 16" fill="none"><path d="M3 3.5h10M3 8h10M3 12.5h10" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/></svg>
            </button>
            <button v-if="item.isCustom !== '1'" type="button" class="channel-card__action" title="同步模型" :disabled="syncingId === item.channelId" @click.stop="handleSync(item)" v-hasPermi="['ai:channel:edit']">
              <svg width="15" height="15" viewBox="0 0 16 16" fill="none"><path d="M13 5.5A5.5 5.5 0 0 0 3.2 4L2 5.5M3 10.5A5.5 5.5 0 0 0 12.8 12l1.2-1.5M2 2.8v2.7h2.7M14 13.2v-2.7h-2.7" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/></svg>
            </button>
            <button type="button" class="channel-card__action" title="探活" @click.stop="handleCheck(item)" v-hasPermi="['ai:channel:check']">
              <svg width="15" height="15" viewBox="0 0 16 16" fill="none"><path d="M2 8h3l2-4 3 8 2-4h2" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/></svg>
            </button>
            <button type="button" class="channel-card__action" title="编辑" @click.stop="handleUpdate(item)" v-hasPermi="['ai:channel:edit']">
              <svg width="15" height="15" viewBox="0 0 16 16" fill="none"><path d="M11.5 2.5l2 2L5 13H3v-2l8.5-8.5z" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/></svg>
            </button>
            <button type="button" class="channel-card__action channel-card__action--danger" title="删除" @click.stop="handleDelete(item)" v-hasPermi="['ai:channel:remove']">
              <svg width="15" height="15" viewBox="0 0 16 16" fill="none"><path d="M3 4.5h10M6.5 2.5h3M4.5 4.5l.5 9h6l.5-9M6.5 7v4M9.5 7v4" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round"/></svg>
            </button>
          </div>
        </div>

        <div class="channel-card__props">
          <span class="channel-card__chip" :style="{ background: softOf(item.channelType || ''), color: colorOf(item.channelType || '') }">
            <i class="channel-card__chip-dot" :style="{ background: colorOf(item.channelType || '') }"></i>
            {{ typeLabel(item.channelType) }}
          </span>
          <span class="channel-card__prop" :class="'is-health-' + item.healthStatus" :title="healthHint(item)">
            <span class="channel-card__prop-k">健康</span>
            <b>{{ healthLabel(item.healthStatus) }}</b>
          </span>
        </div>

        <div class="channel-card__meta">
          <span class="meta-row" :title="item.baseUrl">
            <span class="meta-row__k">API</span>
            <span class="meta-row__v">{{ item.baseUrl || '—' }}</span>
          </span>
          <span class="meta-row" :title="item.apiKey">
            <span class="meta-row__k">Key</span>
            <span class="meta-row__v meta-row__v--mono">{{ item.apiKey || '未配置' }}</span>
          </span>
        </div>

        <p v-if="item.remark" class="channel-card__remark" :title="item.remark">备注：{{ item.remark }}</p>
      </article>

      <!-- 空态 -->
      <div v-if="!loading && channelList.length === 0" class="channel-empty">
        <div class="channel-empty__icon">🌐</div>
        <p class="channel-empty__text">还没有上游渠道</p>
        <button type="button" class="apple-btn apple-btn--add" @click="handleAdd" v-hasPermi="['ai:channel:add']">添加第一个渠道</button>
      </div>
    </div>

    <!-- 分页 -->
    <div v-show="total > 0" class="channel-pagination">
      <pagination :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" @pagination="getList" />
    </div>

    <!-- ==================== 详情面板（只读） ==================== -->
    <Teleport to="body">
      <Transition name="sheet">
        <div v-if="detailOpen" class="sheet-overlay" @click.self="closeDetail">
          <div class="sheet sheet--detail" role="dialog" aria-modal="true" aria-label="渠道详情">
            <div class="hero" :style="{ background: detail.status === '0' ? gradientOf(detail.channelType || detail.channelName) : offGradient }">
              <button type="button" class="hero__close" aria-label="关闭" @click="closeDetail">✕</button>
              <div class="hero__body">
                <div class="hero__avatar">{{ typeEmoji(detail.channelType) }}</div>
                <div class="hero__text">
                  <h2 class="hero__name">{{ detail.channelName }}</h2>
                  <div class="hero__sub">
                    <span class="hero__code">{{ detail.channelCode }}</span>
                    <span class="hero__status" :class="detail.status === '0' ? 'is-on' : 'is-off'">
                      <i :class="detail.status === '0' ? 'is-on' : 'is-off'"></i>{{ detail.status === '0' ? '已启用' : '已停用' }}
                    </span>
                  </div>
                </div>
              </div>
              <div class="hero__stats">
                <div class="hero__stat"><b>{{ healthLabel(detail.healthStatus) }}</b><span>健康状态</span></div>
                <div class="hero__stat"><b>{{ formatTime(detail.healthCheckTime) }}</b><span>上次检查</span></div>
              </div>
            </div>

            <div class="sheet__body">
              <div class="detail-cols">
                <div class="detail-main">
                  <div class="detail-block">
                    <div class="detail-block__title">连接配置</div>
                    <dl class="detail-kv detail-kv--two">
                      <div class="detail-kv__row"><dt>渠道类型</dt><dd>{{ typeLabel(detail.channelType) }}</dd></div>
                      <div class="detail-kv__row"><dt>API 地址</dt><dd :title="detail.baseUrl" class="detail-kv__url">{{ detail.baseUrl || '—' }}</dd></div>
                      <div class="detail-kv__row"><dt>API Key</dt><dd class="detail-kv__mono">{{ detail.apiKey || '未配置' }}</dd></div>
                      <div class="detail-kv__row"><dt>健康路径</dt><dd class="detail-kv__mono">{{ detail.healthCheckUri || '/models' }}</dd></div>
                    </dl>
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
                        <div v-if="detail.healthFailCount" class="health-card__fail">连续失败 {{ detail.healthFailCount }} 次</div>
                      </div>
                    </div>
                    <button type="button" class="apple-btn apple-btn--outline detail-side__btn" @click="handleCheck(detail)" v-hasPermi="['ai:channel:check']">立即探活</button>
                  </div>

                  <div class="detail-block">
                    <div class="detail-block__title">基础信息</div>
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
              <button type="button" class="apple-btn apple-btn--primary" @click="editFromDetail" v-hasPermi="['ai:channel:edit']">编辑</button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>

    <!-- ==================== 编辑面板 ==================== -->
    <Teleport to="body">
      <Transition name="sheet">
        <div v-if="open" class="sheet-overlay" @click.self="cancel">
          <div class="sheet" role="dialog" aria-modal="true" aria-label="编辑渠道">
            <div class="sheet__header">
              <h2 class="sheet__title">{{ title }}</h2>
              <button type="button" class="sheet__close" aria-label="关闭" @click="cancel">✕</button>
            </div>

            <div class="sheet__body">
              <el-form ref="channelRef" :model="form" :rules="rules" label-position="top" class="aform" @submit.prevent>
                <!-- 基本信息 -->
                <div class="aform__group">
                  <div class="aform__row aform__row--2">
                    <el-form-item label="渠道名称" prop="channelName" class="aform__item">
                      <el-input v-model="form.channelName" placeholder="如：DeepSeek-生产" />
                    </el-form-item>
                    <!-- 渠道编码:仅编辑时只读回显,新增由后端兜底生成 -->
                    <el-form-item v-if="form.channelId" label="渠道编码" prop="channelCode" class="aform__item">
                      <el-input v-model="form.channelCode" disabled />
                    </el-form-item>
                  </div>
                  <el-form-item label="渠道类型" prop="channelType">
                    <el-select v-model="form.channelType" placeholder="请选择" style="width: 100%">
                      <el-option v-for="t in CHANNEL_TYPES" :key="t.value" :label="t.label" :value="t.value" />
                    </el-select>
                  </el-form-item>
                  <el-form-item label="自定义渠道" prop="isCustom">
                    <el-switch v-model="form.isCustom" active-value="1" inactive-value="0" />
                    <span class="aform__hint aform__hint--plain">开启后手动维护模型清单；关闭则通过「同步模型」从上游全量同步。</span>
                  </el-form-item>
                </div>

                <!-- 连接配置 -->
                <div class="aform__group">
                  <el-form-item label="API 基础地址" prop="baseUrl">
                    <el-input v-model="form.baseUrl" placeholder="如：https://api.deepseek.com/v1" />
                  </el-form-item>
                  <el-form-item label="API Key" :prop="form.channelId ? '' : 'apiKey'">
                    <el-input v-model="form.apiKey" type="password" show-password :placeholder="form.channelId ? '留空不修改（原 Key 已加密存储）' : '请输入密钥，保存后加密存储'" />
                    <span class="aform__hint" v-if="form.channelId && detail.apiKey">当前：{{ detail.apiKey }}</span>
                  </el-form-item>
                  <el-form-item label="健康检查路径">
                    <el-input v-model="form.healthCheckUri" placeholder="默认 /models，可留空" />
                  </el-form-item>
                </div>

                <!-- 状态 + 备注 -->
                <div class="aform__group aform__group--toggles">
                  <div class="toggle-row">
                    <div class="toggle-row__info">
                      <span class="toggle-row__label">启用状态</span>
                      <span class="toggle-row__hint">停用后该渠道不参与任何调用</span>
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
    <UpstreamModel ref="upstreamModelRef" />
  </div>
</template>

<script setup name="AiChannel">
import { listChannel, getChannel, addChannel, updateChannel, delChannel, checkChannel } from '@/api/ai/channel'
import { syncUpstreamModel } from '@/api/ai/upstreamModel'
import { gradientOf, colorOf, softOf } from '@/utils/ai-palette'
import UpstreamModel from './upstreamModel.vue'

const { proxy } = getCurrentInstance()
const { sys_normal_disable } = proxy.useDict('sys_normal_disable')

const offGradient = 'linear-gradient(135deg, #A1A1A6, #C7C7CC)'

// 渠道类型选项
const CHANNEL_TYPES = [
  { value: 'OPENAI', label: 'OpenAI 兼容' },
  { value: 'ANTHROPIC', label: 'Anthropic' },
  { value: 'GEMINI', label: 'Gemini' },
  { value: 'OLLAMA', label: 'Ollama' }
]
const TYPE_EMOJI = {
  OPENAI: '🤖', ANTHROPIC: '🧠', GEMINI: '✨', OLLAMA: '🦙'
}
const HEALTH_EMOJI = { '0': '⚪', '1': '🟢', '2': '🔴' }
const HEALTH_LABEL = { '0': '未知', '1': '正常', '2': '异常' }

function typeLabel(t) {
  const m = CHANNEL_TYPES.find(x => x.value === t)
  return m ? m.label : (t || '—')
}
function typeEmoji(t) { return TYPE_EMOJI[t] || '🔌' }
function healthLabel(s) { return HEALTH_LABEL[s] || '未知' }
function healthEmoji(s) { return HEALTH_EMOJI[s] || '⚪' }
function healthHint(item) {
  if (item.healthFailCount) return `连续失败 ${item.healthFailCount} 次`
  return HEALTH_LABEL[item.healthStatus] || '未知'
}
function formatTime(s) {
  if (!s) return '—'
  return String(s).replace('T', ' ').slice(0, 16)
}

/* ==================== 列表 ==================== */

const channelList = ref([])
const loading = ref(true)
const total = ref(0)

const data = reactive({
  queryParams: {
    pageNum: 1, pageSize: 12,
    channelName: undefined, channelCode: undefined,
    channelType: undefined, status: undefined
  },
  form: {},
  rules: {
    channelName: [{ required: true, message: '渠道名称不能为空', trigger: 'blur' }],
    // channelCode:新增时由后端兜底生成,UI 不展示、不校验;编辑时只读回显
    channelType: [{ required: true, message: '渠道类型不能为空', trigger: 'change' }],
    baseUrl: [{ required: true, message: 'API 地址不能为空', trigger: 'blur' }],
    apiKey: [{ required: true, message: 'API Key 不能为空', trigger: 'blur' }]
  }
})
const { queryParams, form, rules } = toRefs(data)

function getList() {
  loading.value = true
  listChannel(queryParams.value).then((res) => {
    channelList.value = res.rows
    total.value = res.total
    loading.value = false
  }).catch(() => { loading.value = false })
}

function handleQuery() {
  queryParams.value.pageNum = 1
  getList()
}

function resetQuery() {
  queryParams.value = { pageNum: 1, pageSize: queryParams.value.pageSize, channelName: undefined, channelCode: undefined, channelType: undefined, status: undefined }
  getList()
}

/* ==================== 详情（只读） ==================== */

const detailOpen = ref(false)
const detail = ref({})

function handleDetail(row) {
  detail.value = { ...row }
  detailOpen.value = true
}

function closeDetail() {
  detailOpen.value = false
}

function editFromDetail() {
  const copy = JSON.parse(JSON.stringify(detail.value))
  detailOpen.value = false
  reset()
  openEditor(copy, '编辑渠道')
}

/* ==================== 编辑 ==================== */

const open = ref(false)
const title = ref('')
const originalIsCustom = ref('0')
const upstreamModelRef = ref()
const syncingId = ref(null)
let formSnapshot = ''

function takeSnapshot() { formSnapshot = JSON.stringify(form.value) }
function isDirty() { return JSON.stringify(form.value) !== formSnapshot }

function reset() {
  form.value = {
    channelId: undefined,
    channelName: undefined,
    channelCode: undefined,
    channelType: 'OPENAI',
    baseUrl: undefined,
    apiKey: undefined,
    healthCheckUri: '/models',
    healthStatus: undefined,
    isCustom: '0',
    status: '0',
    remark: undefined
  }
  proxy.resetForm('channelRef')
}

function openEditor(payload, sheetTitle) {
  // 只挑表单需要的字段,避免把后端只读字段(createTime/updateTime/healthCheckTime 等 ISO 8601 字符串)回填,
  // 后端 AiChannel 上的 @JsonFormat 严格按 yyyy-MM-dd HH:mm:ss 反序列化,直接放过去会 400。
  form.value = {
    channelId: payload.channelId,
    channelName: payload.channelName,
    channelCode: payload.channelCode,
    channelType: payload.channelType,
    baseUrl: payload.baseUrl,
    apiKey: '',                                  // 编辑时 apiKey 字段不预填(脱敏值不应回填到 password 框)
    healthCheckUri: payload.healthCheckUri,
    isCustom: payload.isCustom || '0',
    status: payload.status,
    remark: payload.remark
    // 故意不取: createBy / createTime / updateBy / updateTime / params
    //          healthStatus / healthCheckTime / healthFailCount —— 这些由后端管理
  }
  originalIsCustom.value = payload.isCustom || '0'
  title.value = sheetTitle
  open.value = true
  nextTick(() => takeSnapshot())
}

function handleAdd() {
  reset()
  // 新增时清空 detail，避免显示陈旧脱敏值
  detail.value = {}
  // channelCode 由后端兜底生成,前端不预览也不传,省一次请求 + 避免异步时序问题
  openEditor(form.value, '新增渠道')
}

function handleUpdate(row) {
  reset()
  getChannel(row.channelId).then((res) => {
    // 同步 detail，让编辑面板能展示"当前 Key"脱敏提示
    detail.value = { ...res.data }
    openEditor(res.data, '编辑渠道')
  })
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
  proxy.$refs['channelRef'].validate(async (valid) => {
    if (!valid) return
    const payload = { ...form.value }
    if (payload.channelId && originalIsCustom.value === '1' && payload.isCustom === '0') {
      try {
        await proxy.$modal.confirm('改为非自定义后，现有手动模型会保留，但首次同步时会被全量覆盖。是否继续？')
      } catch {
        return
      }
    }
    // 编辑且 apiKey 留空 → 不传该字段，避免清空数据库
    if (payload.channelId && (!payload.apiKey || !payload.apiKey.trim())) {
      delete payload.apiKey
    }
    // 新增时让后端兜底生成 channelCode,前端不传(避免前端预览值与入库值不一致)
    if (!payload.channelId) {
      delete payload.channelCode
    }
    const req = payload.channelId
      ? updateChannel(payload)
      : addChannel(payload)
    req.then(() => {
      proxy.$modal.msgSuccess(payload.channelId ? '修改成功' : '新增成功')
      closeEditor()
      getList()
    })
  })
}

/* ==================== 模型清单 ==================== */

function handleUpstreamModels(row) {
  upstreamModelRef.value?.open(row)
}

async function handleSync(row) {
  try {
    await proxy.$modal.confirm(`同步会用上游返回的清单全量覆盖渠道「${row.channelName}」的现有模型，是否继续？`)
  } catch {
    return
  }
  syncingId.value = row.channelId
  try {
    const res = await syncUpstreamModel(row.channelId)
    proxy.$modal.msgSuccess(res.msg || '同步完成')
  } finally {
    syncingId.value = null
  }
}

/* ==================== 删除 ==================== */

function handleDelete(row) {
  proxy.$modal.confirm(`确认删除渠道「${row.channelName}」？删除后相关模型供应绑定也会受影响。`).then(() => {
    return delChannel(row.channelId)
  }).then(() => {
    proxy.$modal.msgSuccess('删除成功')
    if (detail.value.channelId === row.channelId) detailOpen.value = false
    getList()
  }).catch(() => {})
}

/* ==================== 探活 ==================== */

function handleCheck(row) {
  checkChannel(row.channelId).then((res) => {
    // 后端 data: 0=不存在 1=正常 2=异常
    const code = res.data
    if (code === 1) {
      proxy.$modal.msgSuccess('渠道正常')
    } else if (code === 2) {
      proxy.$modal.msgError('渠道异常')
    } else {
      proxy.$modal.msgWarning(res.msg || '渠道不存在')
    }
    // 刷新该行 / 详情
    getList()
    if (detail.value.channelId === row.channelId) {
      // 详情也要更新：从列表里取最新一条
      const fresh = channelList.value.find(c => c.channelId === row.channelId)
      if (fresh) detail.value = fresh
    }
  }).catch(() => {})
}

/* ==================== 键盘交互 ==================== */

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

.channel-page {
  font-family: $font; padding: 40px 48px; min-height: calc(100vh - 84px); -webkit-font-smoothing: antialiased;
  background:
    var(--ai-page-bg);
  @media (max-width: 768px) { padding: 24px 16px; }
}

/* Header */
.channel-header {
  display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 28px;
  &__left { display: flex; align-items: baseline; gap: 12px; }
  &__title { font-size: 34px; font-weight: 700; color: $text; letter-spacing: -0.4px; margin: 0; }
  &__count { font-size: 15px; color: $gray; }
}

/* Buttons */
.apple-btn {
  display: inline-flex; align-items: center; gap: 6px; font-family: $font; font-size: 14px; font-weight: 500;
  border: none; border-radius: 980px; padding: 8px 18px; cursor: pointer; transition: all 0.2s $ease; outline: none;
  &:active { transform: scale(0.96); }
  &--add, &--primary { background: $blue; color: #fff; padding: 10px 24px; box-shadow: 0 2px 10px rgba(10,132,255,0.32); &:hover { background: #0071e3; } }
  &--ghost { background: transparent; color: $blue; padding: 10px 16px; &:hover { background: rgba(10,132,255,0.08); } }
  &--outline { background: transparent; color: $blue; border: 1.5px solid rgba(10,132,255,0.35); padding: 7px 16px; &:hover { background: rgba(10,132,255,0.06); border-color: $blue; } }
}

/* Search */
.channel-search { display: flex; align-items: center; gap: 10px; margin-bottom: 24px; flex-wrap: wrap;
  &__field { position: relative; flex: 1; min-width: 220px; max-width: 320px; }
  &__icon { position: absolute; left: 13px; top: 50%; transform: translateY(-50%); color: $gray2; pointer-events: none; }
  &__input {
    width: 100%; height: 38px; padding: 0 32px 0 36px; border: none; border-radius: 980px;
    background: var(--ai-search-bg); font-size: 14px; font-family: $font; color: $text; outline: none;
    transition: all 0.25s $ease; box-shadow: 0 1px 3px var(--ai-border);
    &::placeholder { color: $gray2; }
    &:focus { background: var(--ai-card-bg); box-shadow: 0 0 0 4px rgba(10,132,255,0.12), 0 2px 12px var(--ai-border-2); }
    &--mid { padding-left: 14px; max-width: 220px; }
  }
  &__clear {
    position: absolute; right: 10px; top: 50%; transform: translateY(-50%); width: 18px; height: 18px;
    border: none; border-radius: 50%; background: $gray3; color: #fff; font-size: 9px; cursor: pointer;
    display: flex; align-items: center; justify-content: center; &:hover { background: $gray; }
  }
}
.channel-select {
  height: 36px; padding: 0 28px 0 12px; border: none; border-radius: 8px; background: var(--ai-search-bg);
  box-shadow: 0 1px 3px var(--ai-border);
  font-size: 13px; font-family: $font; color: $text; appearance: none; cursor: pointer; outline: none;
  background-image: url("data:image/svg+xml,%3Csvg width='10' height='6' viewBox='0 0 10 6' fill='none' xmlns='http://www.w3.org/2000/svg'%3E%3Cpath d='M1 1l4 4 4-4' stroke='%238E8E93' stroke-width='1.5' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E");
  background-repeat: no-repeat; background-position: right 10px center;
}

/* 卡片网格 */
.channel-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(330px, 1fr)); gap: 16px; min-height: 180px; @media (max-width: 768px) { grid-template-columns: 1fr; } }
.channel-card {
  position: relative; display: flex; flex-direction: column; gap: 10px; padding: 16px 18px 14px;
  background: var(--ai-card-bg); border: 1px solid var(--ai-border); border-radius: 16px; cursor: pointer;
  box-shadow: 0 1px 2px var(--ai-fill-2); transition: all 0.28s $ease; overflow: hidden;
  &:hover { box-shadow: var(--ai-shadow-card); transform: translateY(-3px); border-color: var(--ai-input-bg);
    .channel-card__actions { opacity: 1; transform: translateY(0); } .channel-card__rail { opacity: 1; } }
  &:active { transform: translateY(-1px) scale(0.995); }
  &.is-off { background: var(--ai-card-off); .channel-card__name { color: $text2; } }
  &__rail { position: absolute; left: 0; top: 0; bottom: 0; width: 3px; background: var(--accent); opacity: 0; transition: opacity 0.28s $ease; }
  &__head { display: flex; align-items: center; gap: 8px; }
  &__ident { flex: 1; min-width: 0; }
  &__name { font-size: 16px; font-weight: 600; color: $text; margin: 0 0 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; letter-spacing: -0.2px; }
  &__sub { display: flex; align-items: center; gap: 7px; min-width: 0; }
  &__code { font-family: $mono; font-size: 10.5px; color: $gray; background: var(--ai-fill-2); padding: 1.5px 6px; border-radius: 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  &__status { display: inline-flex; align-items: center; gap: 4px; font-size: 11px; flex-shrink: 0;
    i { width: 6px; height: 6px; border-radius: 50%; display: inline-block; }
    &.is-on { color: #248A3D; i { background: $green; box-shadow: 0 0 0 2.5px rgba(52,199,89,0.18); } }
    &.is-off { color: $gray; i { background: $gray2; } }
  }
  &__actions { display: flex; gap: 4px; opacity: 0; transform: translateY(-3px); transition: all 0.22s $ease; flex-shrink: 0; }
  &__action { width: 27px; height: 27px; border: none; border-radius: 8px; background: var(--ai-border); color: $text2; cursor: pointer; display: flex; align-items: center; justify-content: center; transition: all 0.18s;
    &:hover { background: rgba(10,132,255,0.12); color: $blue; }
    &--danger:hover { background: rgba(255,59,48,0.12); color: $red; }
  }
  &__props { display: flex; flex-wrap: wrap; gap: 5px 12px; align-items: center; padding: 2px 0; }
  &__chip { display: inline-flex; align-items: center; gap: 5px; font-size: 11.5px; font-weight: 600; padding: 2.5px 8px; border-radius: 980px;
    &-dot { width: 6px; height: 6px; border-radius: 50%; }
  }
  &__prop { display: inline-flex; align-items: baseline; gap: 4px; font-size: 12px;
    &-k { color: $gray; } b { font-weight: 600; color: $text; font-variant-numeric: tabular-nums; }
    &.is-health-1 b { color: #1E7A3C; }
    &.is-health-2 b { color: $red; }
  }
  &__meta { display: flex; flex-direction: column; gap: 4px; padding: 8px 10px; background: var(--ai-fill-1); border-radius: $radius-sm; }
  &__remark { font-size: 12px; color: $text2; margin: 0; padding-top: 6px; border-top: 1px dashed var(--ai-border-2); display: -webkit-box; -webkit-line-clamp: 1; -webkit-box-orient: vertical; overflow: hidden; }
}
.meta-row { display: flex; align-items: baseline; gap: 6px; font-size: 12px; min-width: 0;
  &__k { color: $gray; flex-shrink: 0; width: 32px; }
  &__v { color: $text; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 500;
    &--mono { font-family: $mono; font-size: 11.5px; } }
}
.channel-empty { grid-column: 1 / -1; text-align: center; padding: 72px 0; &__icon { font-size: 44px; margin-bottom: 14px; } &__text { font-size: 16px; color: $gray; margin: 0 0 18px; } }
.channel-pagination { margin-top: 28px; display: flex; justify-content: center; }

/* ==================== Sheet ==================== */
.sheet-overlay { position: fixed; inset: 0; z-index: 2000; background: var(--ai-overlay); backdrop-filter: blur(5px); display: flex; align-items: center; justify-content: center; padding: 32px; }
.sheet {
  width: 100%; max-width: 820px; height: min(720px, 88vh); background: var(--ai-card-bg);
  border-radius: 20px; box-shadow: var(--ai-shadow-sheet);
  display: flex; flex-direction: column; overflow: hidden;
  transition: max-width 0.3s $ease, height 0.3s $ease;
  &--detail { max-width: 940px; height: auto; max-height: 88vh; }
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

/* ==================== Hero ==================== */
.hero {
  position: relative; flex-shrink: 0; padding: 24px 28px 0; color: #fff;
  &::after { content: ''; position: absolute; inset: 0; background: linear-gradient(180deg, var(--ai-fill-3), var(--ai-border-4)); pointer-events: none; }
  &__close {
    position: absolute; top: 16px; right: 16px; z-index: 2; width: 28px; height: 28px; border: none; border-radius: 50%;
    background: rgba(255,255,255,0.22); color: #fff; font-size: 12px; cursor: pointer;
    display: flex; align-items: center; justify-content: center; backdrop-filter: blur(6px);
    &:hover { background: rgba(255,255,255,0.34); }
  }
  &__body { position: relative; z-index: 1; display: flex; align-items: center; gap: 14px; }
  &__avatar {
    width: 54px; height: 54px; border-radius: 15px; flex-shrink: 0; display: flex; align-items: center; justify-content: center;
    font-size: 26px; line-height: 1; background: rgba(255,255,255,0.24); backdrop-filter: blur(10px);
    border: 1px solid rgba(255,255,255,0.3);
  }
  &__text { min-width: 0; }
  &__name { font-size: 22px; font-weight: 700; margin: 0 0 6px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; text-shadow: 0 1px 3px var(--ai-border-4); }
  &__sub { display: flex; align-items: center; gap: 9px; flex-wrap: wrap; }
  &__code { font-family: $mono; font-size: 11px; background: rgba(255,255,255,0.2); padding: 2px 7px; border-radius: 5px; }
  &__status { display: inline-flex; align-items: center; gap: 5px; font-size: 12px;
    i { width: 6px; height: 6px; border-radius: 50%; background: var(--ai-card-bg); display: inline-block; &.is-off { opacity: 0.55; } }
  }
  &__stats {
    position: relative; z-index: 1; display: flex; gap: 26px; margin-top: 18px;
    padding: 12px 2px; border-top: 1px solid rgba(255,255,255,0.22);
  }
  &__stat { display: flex; align-items: baseline; gap: 5px; b { font-size: 18px; font-weight: 700; font-variant-numeric: tabular-nums; } span { font-size: 12px; opacity: 0.82; } }
}

/* ==================== 详情内容 ==================== */
.detail-cols {
  display: grid; grid-template-columns: minmax(0, 1fr) 280px; gap: 24px; align-items: start;
  @media (max-width: 760px) { grid-template-columns: 1fr; gap: 20px; }
}
.detail-main { min-width: 0; display: flex; flex-direction: column; gap: 18px; }
.detail-side { min-width: 0; display: flex; flex-direction: column; gap: 14px; background: var(--ai-fill-1); border-radius: $radius; padding: 16px; }
.detail-block {
  min-width: 0;
  & + & { padding-top: 14px; border-top: 1px solid var(--ai-fill-3); }
  &__title { display: flex; align-items: center; gap: 6px; font-size: 12px; font-weight: 600; color: $text; margin-bottom: 9px; }
}
.detail-kv {
  margin: 0; display: flex; flex-direction: column; gap: 8px;
  &--two { display: grid; grid-template-columns: 1fr 1fr; gap: 8px 24px; @media (max-width: 600px) { grid-template-columns: 1fr; } }
  &__row { display: flex; align-items: baseline; gap: 10px; min-width: 0; }
  dt { font-size: 12px; color: $gray; flex-shrink: 0; width: 60px; }
  dd { margin: 0; font-size: 12.5px; font-weight: 500; color: $text; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  &__url { font-family: $mono; font-size: 11.5px; }
  &__mono { font-family: $mono; font-size: 12px; }
}
.detail-prompt { padding: 14px 16px; background: var(--ai-block-bg); border: 1px solid var(--ai-border-2); border-radius: $radius-sm; font-size: 13.5px; line-height: 1.7; color: $text;
  &--static { white-space: pre-wrap; }
}
.detail-side__btn { margin-top: 12px; width: 100%; justify-content: center; }

/* 健康状态卡（详情侧列） */
.health-card {
  display: flex; align-items: center; gap: 12px; padding: 14px;
  background: var(--ai-card-bg); border: 1px solid var(--ai-fill-3); border-radius: $radius-sm;
  &.is-1 { border-color: rgba(48,209,88,0.3); background: rgba(48,209,88,0.05); }
  &.is-2 { border-color: rgba(255,59,48,0.3); background: rgba(255,59,48,0.05); }
  &__icon { font-size: 28px; line-height: 1; }
  &__body { min-width: 0; flex: 1; }
  &__label { font-size: 14px; font-weight: 600; color: $text; }
  &__time { font-size: 11.5px; color: $text2; margin-top: 2px; }
  &__fail { font-size: 11px; color: $red; margin-top: 2px; font-weight: 500; }
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
  &__hint { display: block; margin-top: 6px; font-size: 11.5px; color: $gray; font-family: $mono; &--plain { font-family: $font; margin-left: 10px; } }
}
.toggle-row { display: flex; align-items: center; justify-content: space-between; padding: 12px 0; gap: 16px;
  & + & { border-top: 1px solid var(--ai-border); }
  &__info { display: flex; flex-direction: column; gap: 2px; } &__label { font-size: 14px; font-weight: 500; color: $text; } &__hint { font-size: 12px; color: $gray; }
}
</style>
