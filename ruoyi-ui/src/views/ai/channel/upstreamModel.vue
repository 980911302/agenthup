<template>
  <el-dialog v-model="visible" :title="`模型清单 - ${channel.channelName || ''}`" width="820px" append-to-body>
    <el-alert
      :type="isCustom ? 'info' : 'warning'"
      :closable="false"
      show-icon
      :title="isCustom ? '自定义渠道：清单由你手动维护，系统不会自动同步' : '非自定义渠道：清单由同步全量覆盖，不能手动新增或删除'"
    />
    <div class="toolbar">
      <el-button v-if="isCustom" type="primary" @click="openAdd">新增模型</el-button>
      <el-button v-else type="primary" :loading="syncing" @click="handleSync">同步模型</el-button>
      <el-button v-if="isCustom" type="danger" :disabled="!selected.length" @click="handleDelete">删除</el-button>
    </div>
    <el-table :data="list" v-loading="loading" @selection-change="selected = $event">
      <el-table-column v-if="isCustom" type="selection" width="48" />
      <el-table-column label="模型标识" prop="upstreamModelId" min-width="220" show-overflow-tooltip />
      <el-table-column label="展示名" prop="displayName" min-width="170" show-overflow-tooltip />
      <el-table-column label="归属" prop="ownedBy" width="120" />
      <el-table-column label="来源" width="90">
        <template #default="{ row }">
          <el-tag :type="row.source === '0' ? 'warning' : 'info'" size="small">{{ row.source === '0' ? '手动' : '同步' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="80">
        <template #default="{ row }"><el-button link type="primary" @click="openEdit(row)">编辑</el-button></template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="formVisible" :title="form.id ? '编辑模型' : '新增模型'" width="520px" append-to-body>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <el-form-item label="模型标识" prop="upstreamModelId">
          <el-input v-model="form.upstreamModelId" :disabled="!!form.id" placeholder="如 gpt-4o" />
        </el-form-item>
        <el-form-item label="展示名"><el-input v-model="form.displayName" /></el-form-item>
        <el-form-item label="归属方"><el-input v-model="form.ownedBy" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </el-dialog>
</template>

<script setup>
import { listUpstreamModel, addUpstreamModel, updateUpstreamModel, delUpstreamModel, syncUpstreamModel } from '@/api/ai/upstreamModel'

const { proxy } = getCurrentInstance()
const visible = ref(false)
const loading = ref(false)
const syncing = ref(false)
const submitting = ref(false)
const formVisible = ref(false)
const channel = ref({})
const list = ref([])
const selected = ref([])
const form = ref({})
const formRef = ref()
const isCustom = computed(() => channel.value.isCustom === '1')
const rules = { upstreamModelId: [{ required: true, message: '模型标识不能为空', trigger: 'blur' }] }

function open(row) {
  channel.value = { ...row }
  visible.value = true
  loadList()
}

function loadList() {
  loading.value = true
  listUpstreamModel({ channelId: channel.value.channelId, pageNum: 1, pageSize: 1000 })
    .then(res => { list.value = res.rows || [] })
    .finally(() => { loading.value = false })
}

function openAdd() {
  form.value = { channelId: channel.value.channelId, upstreamModelId: '', displayName: '', ownedBy: '', remark: '' }
  formVisible.value = true
}

function openEdit(row) {
  form.value = { id: row.id, channelId: row.channelId, upstreamModelId: row.upstreamModelId, displayName: row.displayName, ownedBy: row.ownedBy, remark: row.remark }
  formVisible.value = true
}

function submit() {
  formRef.value.validate(valid => {
    if (!valid) return
    submitting.value = true
    const request = form.value.id ? updateUpstreamModel(form.value) : addUpstreamModel(form.value)
    request.then(() => {
      proxy.$modal.msgSuccess(form.value.id ? '修改成功' : '新增成功')
      formVisible.value = false
      loadList()
    }).finally(() => { submitting.value = false })
  })
}

async function handleDelete() {
  await proxy.$modal.confirm(`确认删除选中的 ${selected.value.length} 个模型？`)
  await delUpstreamModel(selected.value.map(item => item.id).join(','))
  proxy.$modal.msgSuccess('删除成功')
  loadList()
}

async function handleSync() {
  await proxy.$modal.confirm(`同步会全量覆盖渠道「${channel.value.channelName}」的现有清单，是否继续？`)
  syncing.value = true
  try {
    const res = await syncUpstreamModel(channel.value.channelId)
    proxy.$modal.msgSuccess(res.msg || '同步完成')
    loadList()
  } finally {
    syncing.value = false
  }
}

defineExpose({ open })
</script>

<style scoped>
.toolbar { display: flex; gap: 10px; margin: 16px 0; }
</style>
