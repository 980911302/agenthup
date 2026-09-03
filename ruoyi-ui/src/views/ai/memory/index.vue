<template>
  <div class="app-container memory-page">
    <el-alert
      class="memory-page__notice"
      title="仅管理员可管理长期记忆。删除会同步清理 PostgreSQL 向量，删除后该内容不会再被跨会话检索。"
      type="warning"
      :closable="false"
      show-icon
    />

    <el-form ref="queryRef" :model="queryParams" :inline="true" class="memory-page__query" @submit.prevent>
      <el-form-item label="用户 ID" prop="userId">
        <el-input v-model="queryParams.userId" placeholder="精确用户 ID" clearable @keyup.enter="handleQuery" />
      </el-form-item>
      <el-form-item label="智能体 ID" prop="agentId">
        <el-input v-model="queryParams.agentId" placeholder="0 为用户层" clearable @keyup.enter="handleQuery" />
      </el-form-item>
      <el-form-item label="类型" prop="type">
        <el-select v-model="queryParams.type" placeholder="全部" clearable>
          <el-option v-for="item in types" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-select v-model="queryParams.status" placeholder="全部" clearable>
          <el-option label="有效" value="active" />
          <el-option label="已覆盖" value="superseded" />
        </el-select>
      </el-form-item>
      <el-form-item label="内容" prop="content">
        <el-input v-model="queryParams.content" placeholder="搜索记忆正文" clearable @keyup.enter="handleQuery" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button>
        <el-button icon="Refresh" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="memoryList" border stripe>
      <el-table-column label="记忆 ID" prop="memoryId" width="100" />
      <el-table-column label="用户 / 层级" min-width="130">
        <template #default="{ row }">
          <div>{{ row.userId }}</div>
          <span class="memory-page__sub">{{ row.agentId === 0 ? '用户层' : '智能体 #' + row.agentId }}</span>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="typeTag(row.type)">{{ typeLabel(row.type) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'active' ? 'success' : 'info'">
            {{ row.status === 'active' ? '有效' : '已覆盖' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="记忆内容" prop="content" min-width="330" show-overflow-tooltip />
      <el-table-column label="向量" min-width="150">
        <template #default="{ row }">
          <template v-if="row.embeddingDim">
            <el-tag size="small" type="success">{{ row.embeddingDim }} 维</el-tag>
            <span class="memory-page__sub">{{ row.embeddingModel || '未知模型' }}</span>
          </template>
          <el-tag v-else size="small" type="warning">待补向量</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="命中" width="90">
        <template #default="{ row }">{{ row.hitCount || 0 }}</template>
      </el-table-column>
      <el-table-column label="创建时间" prop="createTime" width="170" />
      <el-table-column label="操作" width="92" fixed="right">
        <template #default="{ row }">
          <el-button link type="danger" icon="Delete" v-hasPermi="['ai:memory:remove']" @click="handleDelete(row)">删除</el-button>
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
  </div>
</template>

<script setup name="Memory">
import { delMemory, listMemory } from '@/api/ai/memory'

const { proxy } = getCurrentInstance()
const loading = ref(false)
const total = ref(0)
const memoryList = ref([])
const queryRef = ref()

const queryParams = reactive({
  pageNum: 1,
  pageSize: 10,
  userId: undefined,
  agentId: undefined,
  type: undefined,
  status: undefined,
  content: undefined
})

const types = [
  { value: 'fact', label: '事实' },
  { value: 'preference', label: '偏好' },
  { value: 'event', label: '事件' },
  { value: 'goal', label: '目标' },
  { value: 'rule', label: '规则' }
]

function getList() {
  loading.value = true
  listMemory(queryParams).then(response => {
    memoryList.value = response.rows
    total.value = response.total
  }).finally(() => {
    loading.value = false
  })
}

function handleQuery() {
  queryParams.pageNum = 1
  getList()
}

function resetQuery() {
  queryRef.value?.resetFields()
  handleQuery()
}

function handleDelete(row) {
  proxy.$modal.confirm(
    `确认删除长期记忆 #${row.memoryId}？系统会同步删除关联向量，删除后无法恢复。`
  ).then(() => delMemory(row.memoryId)).then(() => {
    proxy.$modal.msgSuccess('长期记忆及关联向量已删除')
    getList()
  }).catch(() => {})
}

function typeLabel(type) {
  return types.find(item => item.value === type)?.label || type || '未知'
}

function typeTag(type) {
  return ({ fact: '', preference: 'primary', event: 'warning', goal: 'success', rule: 'danger' })[type] || 'info'
}

getList()
</script>

<style scoped lang="scss">
.memory-page {
  &__notice { margin-bottom: 18px; }
  &__query { margin-bottom: 4px; }
  &__sub { display: block; margin-top: 3px; color: #909399; font-size: 12px; }
}
</style>
