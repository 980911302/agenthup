<template>
  <div class="app-container userfile-page">
    <el-alert
      class="userfile-page__notice"
      title="个人文件是用户自己的空间（desktop「文件」菜单）。这里只做监控与清理：删除会软删台账，并清理不再被任何记录引用的对象存储文件，删除后无法恢复。"
      type="warning"
      :closable="false"
      show-icon
    />

    <el-alert
      v-if="totals.storageEnabled === false"
      class="userfile-page__notice"
      title="对象存储未配置（ruoyi.ai.storage.enabled=false），用户无法上传文件；此处删除也只会软删台账，不会清理存储。"
      type="error"
      :closable="false"
      show-icon
    />

    <!-- 汇总卡片 -->
    <el-row :gutter="16" class="userfile-page__stats">
      <el-col :xs="12" :sm="6">
        <el-card shadow="never" class="userfile-stat">
          <div class="userfile-stat__label">文件总数</div>
          <div class="userfile-stat__value">{{ totals.fileCount || 0 }}</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="never" class="userfile-stat">
          <div class="userfile-stat__label">占用空间</div>
          <div class="userfile-stat__value">{{ formatSize(totals.usedBytes) }}</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="never" class="userfile-stat">
          <div class="userfile-stat__label">使用人数</div>
          <div class="userfile-stat__value">{{ totals.userCount || 0 }}</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="never" class="userfile-stat">
          <div class="userfile-stat__label">单用户配额</div>
          <div class="userfile-stat__value">{{ formatSize(totals.userQuotaBytes) }}</div>
          <div class="userfile-stat__sub">全局配置，非按用户</div>
        </el-card>
      </el-col>
    </el-row>

    <el-tabs v-model="activeTab" class="userfile-page__tabs">
      <!-- 文件明细 -->
      <el-tab-pane label="文件明细" name="files">
        <el-form ref="queryRef" :model="queryParams" :inline="true" @submit.prevent>
          <el-form-item label="用户 ID" prop="userId">
            <el-input v-model="queryParams.userId" placeholder="精确用户 ID" clearable @keyup.enter="handleQuery" />
          </el-form-item>
          <el-form-item label="用户名" prop="createBy">
            <el-input v-model="queryParams.createBy" placeholder="登录名模糊匹配" clearable @keyup.enter="handleQuery" />
          </el-form-item>
          <el-form-item label="文件名" prop="fileName">
            <el-input v-model="queryParams.fileName" placeholder="搜索文件名" clearable @keyup.enter="handleQuery" />
          </el-form-item>
          <el-form-item label="类型" prop="contentType">
            <el-select v-model="queryParams.contentType" placeholder="全部" clearable style="width: 130px">
              <el-option label="图片" value="image/" />
              <el-option label="文本" value="text/" />
              <el-option label="视频" value="video/" />
              <el-option label="音频" value="audio/" />
              <el-option label="PDF" value="application/pdf" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button>
            <el-button icon="Refresh" @click="resetQuery">重置</el-button>
          </el-form-item>
        </el-form>

        <el-row class="mb8">
          <el-col :span="1.5">
            <el-button
              type="danger"
              plain
              icon="Delete"
              :disabled="!selectedIds.length"
              v-hasPermi="['ai:userfile:remove']"
              @click="handleBatchDelete"
            >删除{{ selectedIds.length ? ` (${selectedIds.length})` : '' }}</el-button>
          </el-col>
        </el-row>

        <el-table v-loading="loading" :data="fileList" border stripe @selection-change="onSelectionChange">
          <el-table-column type="selection" width="46" align="center" />
          <el-table-column label="文件 ID" prop="fileId" width="90" />
          <el-table-column label="归属用户" min-width="140">
            <template #default="{ row }">
              <div>{{ row.nickName || row.userName || '—' }}</div>
              <span class="userfile-page__sub">ID {{ row.userId }}<template v-if="row.userName"> · {{ row.userName }}</template></span>
            </template>
          </el-table-column>
          <el-table-column label="文件名" prop="fileName" min-width="240" show-overflow-tooltip />
          <el-table-column label="类型" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="kindTag(row.contentType)">{{ kindLabel(row.fileName, row.contentType) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="大小" width="110" align="right">
            <template #default="{ row }">{{ formatSize(row.fileSize) }}</template>
          </el-table-column>
          <el-table-column label="上传时间" prop="createTime" width="170" />
          <el-table-column label="操作" width="92" fixed="right">
            <template #default="{ row }">
              <el-button link type="danger" icon="Delete" v-hasPermi="['ai:userfile:remove']" @click="handleDelete(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <pagination
          v-show="total > 0"
          v-model:page="queryParams.pageNum"
          v-model:limit="queryParams.pageSize"
          :total="total"
          @pagination="getList"
        />
      </el-tab-pane>

      <!-- 按用户统计 -->
      <el-tab-pane label="按用户统计" name="usage">
        <el-table v-loading="usageLoading" :data="usageList" border stripe>
          <el-table-column label="用户" min-width="180">
            <template #default="{ row }">
              <div>{{ row.nickName || row.userName || '—' }}</div>
              <span class="userfile-page__sub">ID {{ row.userId }}<template v-if="row.userName"> · {{ row.userName }}</template></span>
            </template>
          </el-table-column>
          <el-table-column label="文件数" prop="fileCount" width="110" align="right" />
          <el-table-column label="占用空间" width="130" align="right">
            <template #default="{ row }">{{ formatSize(row.usedBytes) }}</template>
          </el-table-column>
          <el-table-column label="配额使用率" min-width="220">
            <template #default="{ row }">
              <el-progress
                :percentage="quotaPercent(row.usedBytes)"
                :status="quotaPercent(row.usedBytes) >= 85 ? 'warning' : undefined"
                :stroke-width="12"
              />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="110" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="viewUserFiles(row)">查看文件</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup name="UserFile">
import { delUserFile, getUserFileTotals, getUserFileUsage, listUserFile } from '@/api/ai/userFile'

const { proxy } = getCurrentInstance()

const activeTab = ref('files')
const loading = ref(false)
const usageLoading = ref(false)
const total = ref(0)
const fileList = ref([])
const usageList = ref([])
const selectedIds = ref([])
const totals = ref({})
const queryRef = ref()

const queryParams = reactive({
  pageNum: 1,
  pageSize: 10,
  userId: undefined,
  createBy: undefined,
  fileName: undefined,
  contentType: undefined
})

function getList() {
  loading.value = true
  listUserFile(queryParams).then(response => {
    fileList.value = response.rows
    total.value = response.total
  }).finally(() => {
    loading.value = false
  })
}

function getTotals() {
  getUserFileTotals().then(response => {
    totals.value = response.data || {}
  })
}

function getUsage() {
  usageLoading.value = true
  getUserFileUsage().then(response => {
    usageList.value = response.data || []
  }).finally(() => {
    usageLoading.value = false
  })
}

function refreshAll() {
  getList()
  getTotals()
  getUsage()
}

function handleQuery() {
  queryParams.pageNum = 1
  getList()
}

function resetQuery() {
  queryRef.value?.resetFields()
  queryParams.userId = undefined
  queryParams.createBy = undefined
  queryParams.fileName = undefined
  queryParams.contentType = undefined
  handleQuery()
}

function onSelectionChange(rows) {
  selectedIds.value = rows.map(r => r.fileId)
}

/** 从统计页跳到明细页并带上该用户的筛选条件 */
function viewUserFiles(row) {
  queryParams.userId = row.userId
  queryParams.createBy = undefined
  queryParams.fileName = undefined
  activeTab.value = 'files'
  handleQuery()
}

function handleDelete(row) {
  proxy.$modal.confirm(
    `确认删除「${row.fileName}」？该文件属于用户 ${row.nickName || row.userName || row.userId}，删除后无法恢复。`
  ).then(() => delUserFile(row.fileId)).then(() => {
    proxy.$modal.msgSuccess('已删除')
    refreshAll()
  }).catch(() => {})
}

function handleBatchDelete() {
  const ids = selectedIds.value
  if (!ids.length) return
  proxy.$modal.confirm(`确认删除选中的 ${ids.length} 个文件？删除后无法恢复。`)
    .then(() => delUserFile(ids.join(','))).then(() => {
      proxy.$modal.msgSuccess(`已删除 ${ids.length} 个文件`)
      refreshAll()
    }).catch(() => {})
}

function formatSize(bytes) {
  const n = Number(bytes || 0)
  if (!n) return '0 B'
  if (n < 1024) return n + ' B'
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB'
  if (n < 1024 * 1024 * 1024) return (n / 1048576).toFixed(1) + ' MB'
  return (n / 1073741824).toFixed(2) + ' GB'
}

/** 配额是全局配置而非按用户存，这里用它做分母算使用率 */
function quotaPercent(usedBytes) {
  const quota = Number(totals.value.userQuotaBytes || 0)
  if (!quota) return 0
  return Math.min(100, Math.round((Number(usedBytes || 0) / quota) * 100))
}

function kindLabel(fileName, contentType) {
  if (contentType) {
    if (contentType.startsWith('image/')) return '图片'
    if (contentType.startsWith('video/')) return '视频'
    if (contentType.startsWith('audio/')) return '音频'
    if (contentType === 'application/pdf') return 'PDF'
  }
  const ext = String(fileName || '').split('.').pop()
  return ext && ext !== fileName ? ext.toUpperCase() : '文件'
}

function kindTag(contentType) {
  if (!contentType) return 'info'
  if (contentType.startsWith('image/')) return 'success'
  if (contentType.startsWith('video/') || contentType.startsWith('audio/')) return 'warning'
  if (contentType.startsWith('text/')) return ''
  return 'info'
}

onMounted(refreshAll)
</script>

<style scoped>
.userfile-page__notice {
  margin-bottom: 14px;
}

.userfile-page__stats {
  margin-bottom: 6px;
}

.userfile-stat {
  margin-bottom: 12px;
}

.userfile-stat__label {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.userfile-stat__value {
  margin-top: 6px;
  font-size: 22px;
  font-weight: 600;
  line-height: 1.2;
}

.userfile-stat__sub {
  margin-top: 4px;
  color: var(--el-text-color-placeholder);
  font-size: 12px;
}

.userfile-page__sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
