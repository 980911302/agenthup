<template>
  <div class="job-log-page">
    <header class="job-log-header">
      <div class="job-log-header__left">
        <h1 class="job-log-header__title">触发日志</h1>
        <span class="job-log-header__count">{{ total }} 条</span>
      </div>
      <div class="job-log-header__actions">
        <button type="button" class="apple-btn apple-btn--ghost" @click="handleClose">关闭</button>
      </div>
    </header>

    <div class="job-log-search">
      <input
        v-model="queryParams.jobName"
        class="job-log-search__input"
        placeholder="任务名称"
        @keyup.enter="handleQuery"
      />
      <select v-model="queryParams.status" class="job-log-select" @change="handleQuery">
        <option value="">全部状态</option>
        <option value="SUCCEEDED">SUCCEEDED</option>
        <option value="FAILED">FAILED</option>
        <option value="SKIPPED">SKIPPED</option>
        <option value="TIMEOUT">TIMEOUT</option>
        <option value="DISPATCHED">DISPATCHED</option>
        <option value="CANCELLED">CANCELLED</option>
      </select>
      <el-date-picker
        v-model="dateRange"
        value-format="YYYY-MM-DD HH:mm:ss"
        type="datetimerange"
        range-separator="-"
        start-placeholder="开始时间"
        end-placeholder="结束时间"
        :default-time="[new Date(2000, 0, 1, 0, 0, 0), new Date(2000, 0, 1, 23, 59, 59)]"
        class="job-log-daterange"
      />
      <button type="button" class="apple-btn apple-btn--primary" @click="handleQuery">搜索</button>
      <button type="button" class="apple-btn apple-btn--ghost" @click="resetQuery">重置</button>
    </div>

    <div class="job-log-table-wrap" v-loading="loading">
      <el-table :data="logList" @selection-change="handleSelectionChange" empty-text="暂无日志">
        <el-table-column type="selection" width="48" align="center" />
        <el-table-column label="日志ID" prop="logId" width="80" align="center" />
        <el-table-column label="任务" prop="jobName" min-width="140" show-overflow-tooltip />
        <el-table-column label="状态" width="130" align="center">
          <template #default="{ row }">
            <span class="log-status" :class="'is-' + statusClass(row.status)">
              <el-icon v-if="row.status === 'DISPATCHED'" class="is-loading"><Loading /></el-icon>
              {{ row.status }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="计划时间" width="160" align="center">
          <template #default="{ row }">
            <span class="time-cell">{{ parseTime(row.scheduledTime) || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="触发时间" width="160" align="center">
          <template #default="{ row }">
            <span class="time-cell">{{ parseTime(row.fireTime) || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="90" align="center">
          <template #default="{ row }">
            <span class="time-cell">{{ formatDuration(row.durationMs) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="Tokens" width="90" align="center" prop="tokensUsed" />
        <el-table-column label="结果摘要" min-width="180">
          <template #default="{ row }">
            <span
              v-if="row.resultSummary || row.errorMessage || row.skipReason"
              class="summary-link"
              @click="openSummary(row)"
            >{{ ellipsis(row.resultSummary || row.errorMessage || row.skipReason, 48) }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openSummary(row)">详情</el-button>
            <el-button
              v-if="row.sessionId"
              link
              type="primary"
              @click="goSession(row)"
            >会话</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="job-log-toolbar">
      <button
        type="button"
        class="apple-btn apple-btn--danger"
        :disabled="multiple"
        @click="handleDelete"
        v-hasPermi="['ai:job:remove']"
      >删除选中</button>
      <div v-show="total > 0" class="job-log-pagination">
        <pagination :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" @pagination="getList" />
      </div>
    </div>

    <el-drawer v-model="drawerOpen" title="日志详情" size="480px" append-to-body>
      <template v-if="current">
        <dl class="log-detail">
          <div class="log-detail__row"><dt>日志ID</dt><dd>{{ current.logId }}</dd></div>
          <div class="log-detail__row"><dt>任务</dt><dd>{{ current.jobName }} (#{{ current.jobId }})</dd></div>
          <div class="log-detail__row"><dt>状态</dt>
            <dd>
              <span class="log-status" :class="'is-' + statusClass(current.status)">{{ current.status }}</span>
            </dd>
          </div>
          <div class="log-detail__row"><dt>计划时间</dt><dd>{{ parseTime(current.scheduledTime) || '—' }}</dd></div>
          <div class="log-detail__row"><dt>触发时间</dt><dd>{{ parseTime(current.fireTime) || '—' }}</dd></div>
          <div class="log-detail__row"><dt>Run ID</dt><dd class="mono">{{ current.runId || '—' }}</dd></div>
          <div class="log-detail__row"><dt>Session</dt>
            <dd>
              <a v-if="current.sessionId" class="link" href="javascript:;" @click="goSession(current)">{{ current.sessionId }}</a>
              <span v-else>—</span>
            </dd>
          </div>
          <div class="log-detail__row"><dt>重试序号</dt><dd>{{ current.retryNo ?? 0 }}</dd></div>
          <div class="log-detail__row"><dt>耗时</dt><dd>{{ formatDuration(current.durationMs) }}</dd></div>
          <div class="log-detail__row"><dt>Tokens</dt><dd>{{ current.tokensUsed ?? 0 }}</dd></div>
          <div v-if="current.skipReason" class="log-detail__block">
            <dt>跳过原因</dt>
            <dd class="log-detail__body">{{ current.skipReason }}</dd>
          </div>
          <div v-if="current.errorMessage" class="log-detail__block">
            <dt>错误信息</dt>
            <dd class="log-detail__body is-error">{{ current.errorMessage }}</dd>
          </div>
          <div class="log-detail__block">
            <dt>结果摘要</dt>
            <dd class="log-detail__body">{{ current.resultSummary || '（无）' }}</dd>
          </div>
        </dl>
      </template>
    </el-drawer>
  </div>
</template>

<script setup name="AiJobLog">
import { Loading } from '@element-plus/icons-vue'
import { listJobLog, delJobLog, getJob } from '@/api/ai/job'

const { proxy } = getCurrentInstance()
const route = useRoute()
const router = useRouter()

const logList = ref([])
const loading = ref(true)
const total = ref(0)
const dateRange = ref([])
const ids = ref([])
const multiple = ref(true)
const drawerOpen = ref(false)
const current = ref(null)

const queryParams = ref({
  pageNum: 1,
  pageSize: 10,
  jobId: undefined,
  jobName: undefined,
  status: undefined
})

function statusClass(s) {
  return String(s || '').toLowerCase()
}

function ellipsis(s, n) {
  if (!s) return ''
  const t = String(s)
  return t.length > n ? t.slice(0, n) + '…' : t
}

function formatDuration(ms) {
  if (ms == null || ms === '') return '—'
  const n = Number(ms)
  if (Number.isNaN(n)) return '—'
  if (n < 1000) return n + 'ms'
  if (n < 60000) return (n / 1000).toFixed(1) + 's'
  return Math.round(n / 60000) + 'm'
}

function getList() {
  loading.value = true
  listJobLog(proxy.addDateRange(queryParams.value, dateRange.value)).then(res => {
    logList.value = res.rows || []
    total.value = res.total || 0
  }).finally(() => { loading.value = false })
}

function handleQuery() {
  queryParams.value.pageNum = 1
  getList()
}

function resetQuery() {
  dateRange.value = []
  queryParams.value = {
    pageNum: 1,
    pageSize: queryParams.value.pageSize,
    jobId: queryParams.value.jobId,
    jobName: undefined,
    status: undefined
  }
  // 从路由进入带 jobId 时保留 job 筛选
  if (queryParams.value.jobId) {
    getJob(queryParams.value.jobId).then(res => {
      queryParams.value.jobName = res.data?.jobName
    }).finally(handleQuery)
  } else {
    handleQuery()
  }
}

function handleSelectionChange(selection) {
  ids.value = selection.map(item => item.logId)
  multiple.value = !selection.length
}

function openSummary(row) {
  current.value = row
  drawerOpen.value = true
}

function goSession(row) {
  if (!row.sessionId) {
    proxy.$modal.msgWarning('该日志没有关联会话')
    return
  }
  // 对话页通过 sessionStorage 恢复上次会话
  sessionStorage.setItem('ai.chat.lastSessionId', row.sessionId)
  router.push({ path: '/ai/chat' })
}

function handleDelete() {
  const logIds = ids.value
  if (!logIds.length) return
  proxy.$modal.confirm('确认删除选中的 ' + logIds.length + ' 条日志？').then(() => {
    return delJobLog(logIds)
  }).then(() => {
    proxy.$modal.msgSuccess('删除成功')
    getList()
  }).catch(() => {})
}

function handleClose() {
  proxy.$tab.closeOpenPage({ path: '/ai/job' })
}

(() => {
  const jobId = route.params && route.params.jobId
  if (jobId !== undefined && jobId != 0) {
    queryParams.value.jobId = Number(jobId)
    getJob(jobId).then(res => {
      if (res.data) queryParams.value.jobName = res.data.jobName
    }).finally(getList)
  } else {
    getList()
  }
})()
</script>

<style scoped lang="scss">
@use '../../../assets/styles/ai-tokens.scss' as *;


.job-log-page {
  font-family: $font; padding: 40px 48px; min-height: calc(100vh - 84px); -webkit-font-smoothing: antialiased;
  background:
    var(--ai-page-bg);
  @media (max-width: 768px) { padding: 24px 16px; }
}

.job-log-header {
  display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 24px;
  &__left { display: flex; align-items: baseline; gap: 12px; }
  &__title { font-size: 34px; font-weight: 700; color: $text; letter-spacing: -0.4px; margin: 0; }
  &__count { font-size: 15px; color: $gray; }
}

.apple-btn {
  display: inline-flex; align-items: center; gap: 6px; font-family: $font; font-size: 14px; font-weight: 500;
  border: none; border-radius: 980px; padding: 8px 18px; cursor: pointer; transition: all 0.2s $ease; outline: none;
  &:active { transform: scale(0.96); }
  &:disabled { opacity: 0.4; cursor: not-allowed; }
  &--primary { background: $blue; color: #fff; box-shadow: 0 2px 10px rgba(10,132,255,0.32); &:hover:not(:disabled) { background: #0071e3; } }
  &--ghost { background: transparent; color: $blue; &:hover:not(:disabled) { background: rgba(10,132,255,0.08); } }
  &--danger { background: rgba(255,59,48,0.1); color: $red; &:hover:not(:disabled) { background: rgba(255,59,48,0.16); } }
}

.job-log-search {
  display: flex; align-items: center; gap: 10px; margin-bottom: 18px; flex-wrap: wrap;
  &__input {
    height: 38px; padding: 0 14px; border: none; border-radius: 980px; min-width: 180px;
    background: var(--ai-search-bg); font-size: 14px; font-family: $font; color: $text; outline: none;
    box-shadow: 0 1px 3px var(--ai-border);
    &:focus { background: var(--ai-card-bg); box-shadow: 0 0 0 4px rgba(10,132,255,0.12), 0 2px 12px var(--ai-border-2); }
  }
}
.job-log-select {
  height: 36px; padding: 0 28px 0 12px; border: none; border-radius: 8px; background: var(--ai-search-bg);
  box-shadow: 0 1px 3px var(--ai-border); font-size: 13px; font-family: $font; color: $text; appearance: none; cursor: pointer;
  background-image: url("data:image/svg+xml,%3Csvg width='10' height='6' viewBox='0 0 10 6' fill='none' xmlns='http://www.w3.org/2000/svg'%3E%3Cpath d='M1 1l4 4 4-4' stroke='%238E8E93' stroke-width='1.5' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E");
  background-repeat: no-repeat; background-position: right 10px center;
}
.job-log-daterange { max-width: 380px; }

.job-log-table-wrap {
  background: var(--ai-card-bg); border: 1px solid var(--ai-border); border-radius: 16px;
  box-shadow: 0 1px 2px var(--ai-fill-2); overflow: hidden; padding: 4px 0;
}
.job-log-toolbar { display: flex; align-items: center; justify-content: space-between; margin-top: 16px; gap: 12px; flex-wrap: wrap; }
.job-log-pagination { margin-left: auto; }

.log-status {
  display: inline-flex; align-items: center; gap: 4px; font-size: 11px; font-weight: 700;
  font-family: $mono; padding: 3px 8px; border-radius: 6px;
  &.is-succeeded { background: rgba(52,199,89,0.12); color: #248A3D; }
  &.is-failed { background: rgba(255,59,48,0.12); color: $red; }
  &.is-skipped { background: var(--ai-fill-3); color: $gray; }
  &.is-timeout { background: rgba(255,159,10,0.14); color: #C24A00; }
  &.is-dispatched { background: rgba(10,132,255,0.12); color: $blue; }
  &.is-cancelled, &.is-interrupted { background: var(--ai-fill-3); color: $text2; }
}
.time-cell { font-size: 12px; color: $text2; font-variant-numeric: tabular-nums; }
.summary-link {
  color: $text2; font-size: 12.5px; cursor: pointer;
  &:hover { color: $blue; text-decoration: underline; }
}
.muted { color: $gray3; }
.mono { font-family: $mono; font-size: 12px; word-break: break-all; }
.link { color: $blue; text-decoration: none; &:hover { text-decoration: underline; } }

.log-detail {
  margin: 0; display: flex; flex-direction: column; gap: 12px;
  &__row { display: flex; gap: 12px; align-items: baseline;
    dt { width: 72px; flex-shrink: 0; font-size: 12px; color: $gray; }
    dd { margin: 0; font-size: 13px; color: $text; font-weight: 500; min-width: 0; word-break: break-all; }
  }
  &__block {
    dt { font-size: 12px; color: $gray; margin-bottom: 6px; }
    dd { margin: 0; }
  }
  &__body {
    font-size: 13px; line-height: 1.65; color: $text; white-space: pre-wrap; word-break: break-word;
    background: var(--ai-fill-1); border-radius: 10px; padding: 12px 14px;
    &.is-error { color: $red; background: rgba(255,59,48,0.06); }
  }
}
</style>
