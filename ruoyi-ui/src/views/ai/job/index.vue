<template>
  <div class="job-page">
    <header class="job-header">
      <div class="job-header__left">
        <h1 class="job-header__title">定时任务</h1>
        <span class="job-header__count">{{ total }} 个</span>
      </div>
      <div class="job-header__actions">
        <button type="button" class="apple-btn apple-btn--add" @click="handleAdd" v-hasPermi="['ai:job:add']">
          <svg width="14" height="14" viewBox="0 0 14 14" fill="none"><path d="M7 1v12M1 7h12" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>
          新增任务
        </button>
      </div>
    </header>

    <div class="job-search">
      <div class="job-search__field">
        <svg class="job-search__icon" width="15" height="15" viewBox="0 0 15 15" fill="none">
          <circle cx="6.5" cy="6.5" r="5.5" stroke="currentColor" stroke-width="1.4"/>
          <path d="M10.5 10.5L14 14" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/>
        </svg>
        <input v-model="queryParams.jobName" class="job-search__input" placeholder="搜索任务名称…" @keyup.enter="handleQuery" />
        <button type="button" v-if="queryParams.jobName" class="job-search__clear" @click="queryParams.jobName = ''; handleQuery()">✕</button>
      </div>
      <select v-model="queryParams.status" class="job-select" @change="handleQuery">
        <option value="">全部状态</option>
        <option value="0">正常</option>
        <option value="1">暂停</option>
        <option value="2">已完成</option>
      </select>
      <select v-model="queryParams.triggerType" class="job-select" @change="handleQuery">
        <option value="">全部触发</option>
        <option value="cron">Cron 周期</option>
        <option value="once">一次性</option>
      </select>
      <select v-model="queryParams.source" class="job-select" @change="handleQuery">
        <option value="">全部来源</option>
        <option value="user">用户创建</option>
        <option value="agent">智能体自建</option>
      </select>
      <button type="button" class="apple-btn apple-btn--ghost" @click="resetQuery">重置</button>
    </div>

    <div class="job-table-wrap" v-loading="loading">
      <el-table :data="jobList" class="job-table" empty-text="暂无定时任务">
        <el-table-column label="任务名称" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="job-name-cell">
              <span class="job-name-cell__text">{{ row.jobName }}</span>
              <span v-if="row.source === 'agent'" class="job-source-badge" title="智能体自建">🤖</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="智能体" min-width="150">
          <template #default="{ row }">
            <div class="agent-chip" v-if="row.agentId">
              <span class="agent-chip__avatar" :style="{ background: gradientOf(row.agentCode || row.agentName, row.agentTheme) }">
                {{ row.agentIcon || '🤖' }}
              </span>
              <span class="agent-chip__name" :title="row.agentName">{{ row.agentName || ('#' + row.agentId) }}</span>
            </div>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="触发规则" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.triggerType === 'once'" class="rule-text">
              一次 · {{ parseTime(row.fireTime) || '—' }}
            </span>
            <span v-else class="rule-text rule-text--mono">{{ row.cronExpression || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="会话模式" width="100" align="center">
          <template #default="{ row }">
            <span class="mode-tag" :class="row.sessionMode === 'fixed' ? 'is-fixed' : 'is-new'">
              {{ row.sessionMode === 'fixed' ? '固定会话' : '每次新建' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110" align="center">
          <template #default="{ row }">
            <span class="status-pill" :class="'is-' + statusKey(row.status)">
              <i></i>{{ statusLabel(row.status) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="上次触发" width="160" align="center">
          <template #default="{ row }">
            <span class="time-cell">{{ parseTime(row.prevFireTime) || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="下次触发" width="160" align="center">
          <template #default="{ row }">
            <span class="time-cell">{{ parseTime(row.nextFireTime) || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="最近结果" width="120" align="center">
          <template #default="{ row }">
            <span v-if="row.lastStatus" class="result-tag" :class="'is-' + String(row.lastStatus).toLowerCase()">
              {{ row.lastStatus }}
            </span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" align="center" fixed="right">
          <template #default="{ row }">
            <el-tooltip content="编辑" placement="top">
              <el-button link type="primary" icon="Edit" @click="handleUpdate(row)" v-hasPermi="['ai:job:edit']" />
            </el-tooltip>
            <el-tooltip v-if="row.status !== '2'" :content="row.status === '0' ? '暂停' : '启用'" placement="top">
              <el-button
                link
                type="primary"
                :icon="row.status === '0' ? 'VideoPause' : 'VideoPlay'"
                @click="handleToggle(row)"
                v-hasPermi="['ai:job:changeStatus']"
              />
            </el-tooltip>
            <el-tooltip content="立即执行" placement="top">
              <el-button link type="primary" icon="CaretRight" @click="handleRun(row)" v-hasPermi="['ai:job:changeStatus']" />
            </el-tooltip>
            <el-tooltip content="触发日志" placement="top">
              <el-button link type="primary" icon="Operation" @click="handleJobLog(row)" v-hasPermi="['ai:job:list']" />
            </el-tooltip>
            <el-tooltip content="删除" placement="top">
              <el-button link type="primary" icon="Delete" @click="handleDelete(row)" v-hasPermi="['ai:job:remove']" />
            </el-tooltip>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div v-show="total > 0" class="job-pagination">
      <pagination :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" @pagination="getList" />
    </div>

    <!-- 新增 / 编辑 -->
    <Teleport to="body">
      <Transition name="sheet">
        <div v-if="open" class="sheet-overlay" @click.self="cancel">
          <div class="sheet" role="dialog" aria-modal="true" aria-label="编辑定时任务">
            <div class="sheet__header">
              <h2 class="sheet__title">{{ title }}</h2>
              <button type="button" class="sheet__close" aria-label="关闭" @click="cancel">✕</button>
            </div>
            <div class="sheet__body">
              <el-form ref="jobRef" :model="form" :rules="rules" label-position="top" class="aform" @submit.prevent>
                <div class="aform__group">
                  <div class="aform__row aform__row--2">
                    <el-form-item label="任务名称" prop="jobName" class="aform__item">
                      <el-input v-model="form.jobName" placeholder="给任务起个好记的名字" maxlength="100" />
                    </el-form-item>
                    <el-form-item label="执行智能体" prop="agentId" class="aform__item">
                      <el-select v-model="form.agentId" filterable placeholder="选择智能体" style="width: 100%">
                        <el-option v-for="a in agentOptions" :key="a.agentId" :label="a.agentName" :value="a.agentId">
                          <span class="opt-agent">
                            <span class="opt-agent__avatar" :style="{ background: gradientOf(a.agentCode || a.agentName, a.theme) }">{{ a.icon || '🤖' }}</span>
                            {{ a.agentName }}
                          </span>
                        </el-option>
                      </el-select>
                    </el-form-item>
                  </div>
                </div>

                <div class="aform__group">
                  <el-form-item label="触发类型" prop="triggerType">
                    <el-radio-group v-model="form.triggerType" @change="onTriggerTypeChange">
                      <el-radio-button value="cron">Cron 周期</el-radio-button>
                      <el-radio-button value="once">一次性</el-radio-button>
                    </el-radio-group>
                  </el-form-item>

                  <template v-if="form.triggerType === 'cron'">
                    <el-form-item label="Cron 表达式" prop="cronExpression">
                      <el-input v-model="form.cronExpression" placeholder="如 0 0 9 * * ?" @input="scheduleFirePreview">
                        <template #append>
                          <el-button @click="handleShowCron">生成</el-button>
                        </template>
                      </el-input>
                    </el-form-item>
                    <div class="fire-preview" v-if="form.cronExpression">
                      <div class="fire-preview__title">后续 5 次触发时刻</div>
                      <div v-if="firePreviewLoading" class="fire-preview__loading">计算中…</div>
                      <div v-else-if="firePreviewError" class="fire-preview__error">{{ firePreviewError }}</div>
                      <ul v-else-if="firePreviewTimes.length" class="fire-preview__list">
                        <li v-for="(t, i) in firePreviewTimes" :key="i">{{ t }}</li>
                      </ul>
                      <div v-else class="fire-preview__empty">输入合法 cron 后自动预览</div>
                    </div>
                  </template>
                  <el-form-item v-else label="执行时刻" prop="fireTime">
                    <el-date-picker
                      v-model="form.fireTime"
                      type="datetime"
                      value-format="YYYY-MM-DD HH:mm:ss"
                      placeholder="选择执行时间"
                      style="width: 100%"
                    />
                  </el-form-item>

                  <div class="aform__row aform__row--2">
                    <el-form-item label="时区" prop="timezone" class="aform__item">
                      <el-input v-model="form.timezone" placeholder="Asia/Shanghai" />
                    </el-form-item>
                    <el-form-item label="错过策略" prop="misfirePolicy" class="aform__item">
                      <!-- 故意不含 1 立即执行：会补跑停机期间全部错过触发 -->
                      <el-select v-model="form.misfirePolicy" style="width: 100%">
                        <el-option label="3 · 放弃执行（推荐）" value="3" />
                        <el-option label="2 · 补跑一次" value="2" />
                      </el-select>
                    </el-form-item>
                  </div>
                </div>

                <div class="aform__group">
                  <el-form-item label="会话模式" prop="sessionMode">
                    <el-radio-group v-model="form.sessionMode">
                      <el-radio value="new">每次新建会话</el-radio>
                      <el-radio value="fixed">固定会话追加</el-radio>
                    </el-radio-group>
                  </el-form-item>
                  <div v-if="form.sessionMode === 'fixed'" class="form-warn">
                    固定会话会持续累积上下文，超出模型窗口后早期内容会被自动压缩为摘要，记忆是有损的。
                  </div>
                  <el-form-item label="指令 Prompt" prop="prompt">
                    <el-input
                      v-model="form.prompt"
                      type="textarea"
                      :rows="6"
                      placeholder="指令要能脱离当前对话独立理解。到点后会把这段文字原样投喂给智能体。"
                      class="prompt-textarea"
                    />
                  </el-form-item>
                </div>

                <div class="aform__group">
                  <div class="aform__row aform__row--2">
                    <el-form-item label="超时(秒)" prop="timeoutSeconds" class="aform__item">
                      <el-input-number v-model="form.timeoutSeconds" :min="30" :max="86400" controls-position="right" style="width: 100%" />
                    </el-form-item>
                    <el-form-item label="失败重试" prop="maxRetry" class="aform__item">
                      <el-input-number v-model="form.maxRetry" :min="0" :max="10" controls-position="right" style="width: 100%" />
                    </el-form-item>
                  </div>
                  <div class="aform__row aform__row--2">
                    <el-form-item label="累计执行上限" prop="maxRuns" class="aform__item">
                      <el-input-number v-model="form.maxRuns" :min="1" :max="100000" controls-position="right" style="width: 100%" placeholder="不限可留空" />
                    </el-form-item>
                    <el-form-item label="过期时间" prop="expireTime" class="aform__item">
                      <el-date-picker
                        v-model="form.expireTime"
                        type="datetime"
                        value-format="YYYY-MM-DD HH:mm:ss"
                        placeholder="可选"
                        style="width: 100%"
                        clearable
                      />
                    </el-form-item>
                  </div>
                  <el-form-item label="备注" prop="remark">
                    <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="可选" />
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

    <el-dialog title="Cron 表达式生成器" v-model="openCron" append-to-body destroy-on-close>
      <crontab ref="crontabRef" @hide="openCron = false" @fill="crontabFill" :expression="expression" />
    </el-dialog>
  </div>
</template>

<script setup name="AiJob">
import Crontab from '@/components/Crontab'
import { listJob, getJob, addJob, updateJob, delJob, changeJobStatus, runJob, nextFireTimes } from '@/api/ai/job'
import { listAllAgent } from '@/api/ai/agent'
import { gradientOf } from '@/utils/ai-palette'

const { proxy } = getCurrentInstance()
const router = useRouter()

const jobList = ref([])
const agentOptions = ref([])
const loading = ref(true)
const total = ref(0)
const open = ref(false)
const title = ref('')
const openCron = ref(false)
const expression = ref('')

const firePreviewTimes = ref([])
const firePreviewLoading = ref(false)
const firePreviewError = ref('')
let firePreviewTimer = null

const data = reactive({
  queryParams: {
    pageNum: 1,
    pageSize: 10,
    jobName: undefined,
    status: undefined,
    triggerType: undefined,
    source: undefined
  },
  form: {},
  rules: {
    jobName: [{ required: true, message: '任务名称不能为空', trigger: 'blur' }],
    agentId: [{ required: true, message: '请选择智能体', trigger: 'change' }],
    triggerType: [{ required: true, message: '请选择触发类型', trigger: 'change' }],
    cronExpression: [{
      validator: (_r, v, cb) => {
        if (form.value.triggerType === 'cron' && !v) cb(new Error('Cron 表达式不能为空'))
        else cb()
      },
      trigger: 'blur'
    }],
    fireTime: [{
      validator: (_r, v, cb) => {
        if (form.value.triggerType === 'once' && !v) cb(new Error('执行时刻不能为空'))
        else cb()
      },
      trigger: 'change'
    }],
    prompt: [{ required: true, message: '指令不能为空', trigger: 'blur' }],
    sessionMode: [{ required: true, message: '请选择会话模式', trigger: 'change' }],
    misfirePolicy: [{ required: true, message: '请选择错过策略', trigger: 'change' }]
  }
})
const { queryParams, form, rules } = toRefs(data)

function statusLabel(s) {
  if (s === '0') return '正常'
  if (s === '1') return '暂停'
  if (s === '2') return '已完成'
  return s || '—'
}
function statusKey(s) {
  if (s === '0') return 'normal'
  if (s === '1') return 'pause'
  if (s === '2') return 'done'
  return 'unknown'
}

function loadAgents() {
  listAllAgent().then(res => {
    agentOptions.value = (res.data || []).filter(a => a.status === '0')
  }).catch(() => { agentOptions.value = [] })
}

function getList() {
  loading.value = true
  listJob(queryParams.value).then(res => {
    jobList.value = res.rows || []
    total.value = res.total || 0
  }).finally(() => { loading.value = false })
}

function handleQuery() {
  queryParams.value.pageNum = 1
  getList()
}

function resetQuery() {
  queryParams.value = {
    pageNum: 1,
    pageSize: queryParams.value.pageSize,
    jobName: undefined,
    status: undefined,
    triggerType: undefined,
    source: undefined
  }
  getList()
}

function reset() {
  form.value = {
    jobId: undefined,
    jobName: undefined,
    agentId: undefined,
    prompt: undefined,
    triggerType: 'cron',
    cronExpression: undefined,
    fireTime: undefined,
    timezone: 'Asia/Shanghai',
    misfirePolicy: '3',
    sessionMode: 'new',
    sessionId: undefined,
    timeoutSeconds: 600,
    maxRetry: 0,
    maxRuns: undefined,
    expireTime: undefined,
    remark: undefined
  }
  firePreviewTimes.value = []
  firePreviewError.value = ''
  proxy.resetForm('jobRef')
}

function handleAdd() {
  reset()
  open.value = true
  title.value = '新增定时任务'
}

function handleUpdate(row) {
  reset()
  getJob(row.jobId).then(res => {
    const d = res.data || {}
    form.value = {
      ...d,
      // 后端可能返回 Date 对象字符串化后的值，保持 value-format 兼容
      fireTime: d.fireTime ? proxy.parseTime(d.fireTime) : undefined,
      expireTime: d.expireTime ? proxy.parseTime(d.expireTime) : undefined,
      misfirePolicy: d.misfirePolicy || '3',
      timezone: d.timezone || 'Asia/Shanghai'
    }
    open.value = true
    title.value = '修改定时任务'
    if (form.value.triggerType === 'cron') scheduleFirePreview()
  })
}

function cancel() {
  open.value = false
  reset()
}

function onTriggerTypeChange() {
  firePreviewTimes.value = []
  firePreviewError.value = ''
  if (form.value.triggerType === 'cron') scheduleFirePreview()
}

function scheduleFirePreview() {
  if (firePreviewTimer) clearTimeout(firePreviewTimer)
  firePreviewTimer = setTimeout(loadFirePreview, 350)
}

function loadFirePreview() {
  const cron = form.value.cronExpression
  if (!cron || form.value.triggerType !== 'cron') {
    firePreviewTimes.value = []
    firePreviewError.value = ''
    return
  }
  firePreviewLoading.value = true
  firePreviewError.value = ''
  nextFireTimes(cron, form.value.timezone || 'Asia/Shanghai').then(res => {
    firePreviewTimes.value = res.data || []
    if (!firePreviewTimes.value.length) {
      firePreviewError.value = '未能算出触发时刻，请检查表达式'
    }
  }).catch(e => {
    firePreviewTimes.value = []
    firePreviewError.value = e?.msg || e?.message || '表达式无效'
  }).finally(() => { firePreviewLoading.value = false })
}

function handleShowCron() {
  expression.value = form.value.cronExpression || ''
  openCron.value = true
}

function crontabFill(value) {
  form.value.cronExpression = value
  scheduleFirePreview()
}

function submitForm() {
  proxy.$refs['jobRef'].validate(valid => {
    if (!valid) return
    const payload = { ...form.value }
    if (payload.triggerType === 'cron') {
      payload.fireTime = undefined
    } else {
      payload.cronExpression = undefined
    }
    // 空上限不要传 0
    if (payload.maxRuns === null || payload.maxRuns === undefined || payload.maxRuns === '') {
      payload.maxRuns = undefined
    }
    const req = payload.jobId ? updateJob(payload) : addJob(payload)
    req.then(() => {
      proxy.$modal.msgSuccess(payload.jobId ? '修改成功' : '新增成功')
      open.value = false
      getList()
    })
  })
}

function handleToggle(row) {
  if (row.status === '2') {
    proxy.$modal.msgWarning('已完成的任务不能启停')
    return
  }
  const next = row.status === '0' ? '1' : '0'
  const text = next === '0' ? '启用' : '暂停'
  proxy.$modal.confirm(`确认要${text}「${row.jobName}」吗？`).then(() => {
    return changeJobStatus(row.jobId, next)
  }).then(() => {
    proxy.$modal.msgSuccess(text + '成功')
    getList()
  }).catch(() => {})
}

function handleRun(row) {
  proxy.$modal.confirm(`确认立即执行一次「${row.jobName}」？`).then(() => {
    return runJob(row.jobId)
  }).then(() => {
    proxy.$modal.msgSuccess('已触发执行')
    getList()
  }).catch(() => {})
}

function handleDelete(row) {
  proxy.$modal.confirm(`确认删除任务「${row.jobName}」？`).then(() => {
    return delJob(row.jobId)
  }).then(() => {
    proxy.$modal.msgSuccess('删除成功')
    getList()
  }).catch(() => {})
}

function handleJobLog(row) {
  router.push('/ai/job-log/index/' + (row.jobId || 0))
}

onMounted(() => {
  loadAgents()
  getList()
})
</script>

<style scoped lang="scss">
@use '../../../assets/styles/ai-tokens.scss' as *;

// 设计令牌见 @/assets/styles/ai-tokens.scss + ai-theme.scss（支持暗色）
$spring: cubic-bezier(0.34, 1.56, 0.64, 1);

.job-page {
  font-family: $font; padding: 40px 48px; min-height: calc(100vh - 84px); -webkit-font-smoothing: antialiased;
  background:
    var(--ai-page-bg);
  @media (max-width: 768px) { padding: 24px 16px; }
}

.job-header {
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
}

.job-search { display: flex; align-items: center; gap: 10px; margin-bottom: 20px; flex-wrap: wrap;
  &__field { position: relative; flex: 1; min-width: 220px; max-width: 320px; }
  &__icon { position: absolute; left: 13px; top: 50%; transform: translateY(-50%); color: $gray2; pointer-events: none; }
  &__input {
    width: 100%; height: 38px; padding: 0 32px 0 36px; border: none; border-radius: 980px;
    background: var(--ai-search-bg); font-size: 14px; font-family: $font; color: $text; outline: none;
    transition: all 0.25s $ease; box-shadow: 0 1px 3px var(--ai-border);
    &::placeholder { color: $gray2; }
    &:focus { background: var(--ai-card-bg); box-shadow: 0 0 0 4px rgba(10,132,255,0.12), 0 2px 12px var(--ai-border-2); }
  }
  &__clear {
    position: absolute; right: 10px; top: 50%; transform: translateY(-50%); width: 18px; height: 18px;
    border: none; border-radius: 50%; background: $gray3; color: #fff; font-size: 9px; cursor: pointer;
    display: flex; align-items: center; justify-content: center; &:hover { background: $gray; }
  }
}
.job-select {
  height: 36px; padding: 0 28px 0 12px; border: none; border-radius: 8px; background: var(--ai-search-bg);
  box-shadow: 0 1px 3px var(--ai-border);
  font-size: 13px; font-family: $font; color: $text; appearance: none; cursor: pointer; outline: none;
  background-image: url("data:image/svg+xml,%3Csvg width='10' height='6' viewBox='0 0 10 6' fill='none' xmlns='http://www.w3.org/2000/svg'%3E%3Cpath d='M1 1l4 4 4-4' stroke='%238E8E93' stroke-width='1.5' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E");
  background-repeat: no-repeat; background-position: right 10px center;
}

.job-table-wrap {
  background: var(--ai-card-bg); border: 1px solid var(--ai-border); border-radius: 16px;
  box-shadow: 0 1px 2px var(--ai-fill-2); overflow: hidden; padding: 4px 0;
}
.job-table {
  :deep(.el-table__header th) {
    background: transparent; color: $gray; font-weight: 600; font-size: 12px;
  }
  :deep(.el-table__row td) { border-color: var(--ai-fill-2); }
}
.job-pagination { margin-top: 24px; display: flex; justify-content: center; }

.job-name-cell { display: inline-flex; align-items: center; gap: 6px; max-width: 100%;
  &__text { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 500; color: $text; }
}
.job-source-badge {
  flex-shrink: 0; font-size: 12px; line-height: 1;
  background: rgba(191,90,242,0.12); border-radius: 6px; padding: 2px 5px;
}
.agent-chip { display: inline-flex; align-items: center; gap: 8px; max-width: 100%;
  &__avatar {
    width: 28px; height: 28px; border-radius: 8px; display: inline-flex; align-items: center; justify-content: center;
    font-size: 14px; flex-shrink: 0; color: #fff;
  }
  &__name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; color: $text; }
}
.rule-text { font-size: 12.5px; color: $text2;
  &--mono { font-family: $mono; font-size: 12px; }
}
.mode-tag {
  display: inline-flex; font-size: 11.5px; font-weight: 600; padding: 2px 8px; border-radius: 980px;
  &.is-new { background: rgba(10,132,255,0.10); color: $blue; }
  &.is-fixed { background: rgba(255,159,10,0.12); color: #C24A00; }
}
.status-pill {
  display: inline-flex; align-items: center; gap: 5px; font-size: 12px; font-weight: 500;
  i { width: 6px; height: 6px; border-radius: 50%; display: inline-block; }
  &.is-normal { color: #248A3D; i { background: $green; box-shadow: 0 0 0 2.5px rgba(52,199,89,0.18); } }
  &.is-pause { color: $gray; i { background: $gray2; } }
  /* 已完成单独用蓝，禁止画成暂停灰 */
  &.is-done { color: $blue; i { background: $blue; box-shadow: 0 0 0 2.5px rgba(10,132,255,0.18); } }
}
.time-cell { font-size: 12px; color: $text2; font-variant-numeric: tabular-nums; }
.result-tag {
  font-size: 11px; font-weight: 600; padding: 2px 7px; border-radius: 6px; font-family: $mono;
  &.is-succeeded { background: rgba(52,199,89,0.12); color: #248A3D; }
  &.is-failed { background: rgba(255,59,48,0.12); color: $red; }
  &.is-skipped { background: var(--ai-fill-3); color: $gray; }
  &.is-timeout { background: rgba(255,159,10,0.14); color: #C24A00; }
  &.is-dispatched { background: rgba(10,132,255,0.12); color: $blue; }
  &.is-cancelled, &.is-interrupted { background: var(--ai-fill-3); color: $text2; }
}
.muted { color: $gray3; }

.opt-agent { display: inline-flex; align-items: center; gap: 8px;
  &__avatar { width: 22px; height: 22px; border-radius: 6px; display: inline-flex; align-items: center; justify-content: center; font-size: 12px; }
}

.fire-preview {
  margin: -4px 0 14px; padding: 12px 14px; border-radius: $radius-sm;
  background: rgba(10,132,255,0.05); border: 1px solid rgba(10,132,255,0.12);
  &__title { font-size: 12px; font-weight: 600; color: $text; margin-bottom: 8px; }
  &__list { margin: 0; padding-left: 18px; font-family: $mono; font-size: 12px; color: $text2; line-height: 1.7; }
  &__loading, &__empty { font-size: 12px; color: $gray; }
  &__error { font-size: 12px; color: $red; }
}
.form-warn {
  margin: -6px 0 14px; padding: 10px 12px; border-radius: $radius-sm;
  background: rgba(255,159,10,0.10); border: 1px solid rgba(255,159,10,0.22);
  font-size: 12.5px; color: #8A4500; line-height: 1.55;
}

/* Sheet */
.sheet-overlay { position: fixed; inset: 0; z-index: 2000; background: var(--ai-overlay); backdrop-filter: blur(5px); display: flex; align-items: center; justify-content: center; padding: 32px; }
.sheet {
  width: 100%; max-width: 760px; height: min(860px, 92vh); background: var(--ai-card-bg);
  border-radius: 20px; box-shadow: var(--ai-shadow-sheet);
  display: flex; flex-direction: column; overflow: hidden;
  &__header { display: flex; align-items: center; justify-content: space-between; padding: 22px 28px 0; flex-shrink: 0; }
  &__title { font-size: 21px; font-weight: 700; color: $text; margin: 0; }
  &__close { width: 28px; height: 28px; border: none; border-radius: 50%; background: var(--ai-fill-3); color: $gray; font-size: 12px; cursor: pointer; display: flex; align-items: center; justify-content: center; &:hover { background: var(--ai-hover-strong); color: $text; } }
  &__body { flex: 1; min-height: 0; overflow-y: auto; padding: 18px 28px 24px;
    &::-webkit-scrollbar { width: 5px; } &::-webkit-scrollbar-thumb { background: var(--ai-border-4); border-radius: 3px; } }
  &__footer { display: flex; justify-content: flex-end; gap: 10px; padding: 14px 28px 22px; border-top: 1px solid var(--ai-fill-3); flex-shrink: 0; }
}
.sheet-enter-active { transition: all 0.35s $spring; }
.sheet-leave-active { transition: all 0.2s ease-in; }
.sheet-enter-from { opacity: 0; .sheet { transform: scale(0.92) translateY(20px); opacity: 0; } }
.sheet-leave-to { opacity: 0; .sheet { transform: scale(0.96); opacity: 0; } }

.aform {
  :deep(.el-form-item) { margin-bottom: 14px; }
  :deep(.el-form-item__label) { font-size: 13px; font-weight: 500; color: $text2; padding-bottom: 4px; }
  :deep(.el-input__wrapper), :deep(.el-textarea__inner) {
    border-radius: $radius-sm; background: var(--ai-input-bg); box-shadow: 0 0 0 1px var(--ai-border-3) inset; transition: all 0.2s $ease;
    &:hover { box-shadow: 0 0 0 1px var(--ai-border-4) inset; }
    &.is-focus, &:focus { background: var(--ai-card-bg); box-shadow: 0 0 0 4px rgba(10,132,255,0.12), 0 0 0 1px $blue inset; }
  }
  &__group { background: var(--ai-fill-1); border-radius: $radius; padding: 16px 20px; margin-bottom: 12px; }
  &__row--2 { display: grid; grid-template-columns: 1fr 1fr; gap: 0 14px; @media (max-width: 600px) { grid-template-columns: 1fr; } }
  &__item { flex: 1; }
}
.prompt-textarea :deep(.el-textarea__inner) {
  font-family: $mono; font-size: 12.5px; line-height: 1.75;
}
</style>
