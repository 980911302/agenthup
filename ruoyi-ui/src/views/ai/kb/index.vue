<template>
  <div class="kb-page">
    <header class="kb-header">
      <div class="kb-header__left">
        <h1 class="kb-header__title">知识库</h1>
        <span class="kb-header__count">{{ summary.total || 0 }} 个</span>
      </div>
      <div class="kb-header__actions">
        <button
          v-if="isPlatformAdmin"
          type="button"
          class="apple-btn apple-btn--outline"
          @click="engineOpen = true"
          title="平台全局：向量/抽取模型、分块与图谱策略"
        >全局设置</button>
        <button type="button" class="apple-btn apple-btn--add" @click="openCreate" v-hasPermi="['ai:kb:add']">
          <svg width="14" height="14" viewBox="0 0 14 14" fill="none"><path d="M7 1v12M1 7h12" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>
          新建
        </button>
      </div>
    </header>

    <!-- 平台知识引擎（仅 isPlatformAdmin）：系统 sheet，不用 Element Drawer -->
    <Teleport to="body">
      <Transition name="kb-sheet">
        <div v-if="engineOpen" class="kb-sheet-overlay" @click.self="engineOpen = false">
          <div class="kb-sheet kb-sheet--engine" role="dialog" aria-modal="true" aria-label="全局设置">
            <header class="kb-sheet__header">
              <div class="kb-sheet__titles">
                <h2 class="kb-sheet__title">全局设置</h2>
                <p class="kb-sheet__sub">新建知识库默认用的模型与处理规则 · 已有库不会自动变更</p>
              </div>
              <button type="button" class="kb-sheet__close" aria-label="关闭" @click="engineOpen = false">✕</button>
            </header>
            <div class="kb-sheet__body kb-sheet__body--engine">
              <KbEnginePanel v-if="engineOpen" />
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>

    <!-- 搜索栏：对齐智能体 -->
    <div class="kb-search">
      <div class="kb-search__field">
        <svg class="kb-search__icon" width="15" height="15" viewBox="0 0 15 15" fill="none">
          <circle cx="6.5" cy="6.5" r="5.5" stroke="currentColor" stroke-width="1.4"/>
          <path d="M10.5 10.5L14 14" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/>
        </svg>
        <input
          v-model="queryParams.kbName"
          class="kb-search__input"
          placeholder="搜索知识库…"
          @keyup.enter="load"
        />
        <button
          type="button"
          v-if="queryParams.kbName"
          class="kb-search__clear"
          @click="queryParams.kbName = ''; load()"
        >✕</button>
      </div>
      <select v-model="queryParams.status" class="kb-select" @change="load">
        <option value="">全部状态</option>
        <option value="0">已启用</option>
        <option value="1">已停用</option>
      </select>
      <div class="kb-scope" role="tablist" aria-label="归属筛选">
        <button
          v-for="s in scopeOptions"
          :key="s.value"
          type="button"
          class="kb-scope__btn"
          :class="{ 'is-active': scopeFilter === s.value }"
          role="tab"
          :aria-selected="scopeFilter === s.value"
          @click="scopeFilter = s.value"
        >{{ s.label }}</button>
      </div>
      <button type="button" class="apple-btn apple-btn--ghost" @click="resetQuery">重置</button>
    </div>

    <!-- 健康摘要 -->
    <div class="kb-chips" v-if="!loadError && summary.total > 0">
      <button
        type="button"
        class="kb-chip"
        :class="{ 'is-active': healthFilter === 'PROCESSING' }"
        @click="toggleHealth('PROCESSING')"
        v-if="summary.processing"
      >处理中 {{ summary.processing }}</button>
      <button
        type="button"
        class="kb-chip is-warn"
        :class="{ 'is-active': healthFilter === 'ISSUE' }"
        @click="toggleHealth('ISSUE')"
        v-if="summary.issue"
      >有问题 {{ summary.issue }}</button>
      <button
        type="button"
        class="kb-chip"
        :class="{ 'is-active': healthFilter === 'EMPTY' }"
        @click="toggleHealth('EMPTY')"
        v-if="summary.empty"
      >空库 {{ summary.empty }}</button>
      <button type="button" class="kb-chip is-ok" v-if="summary.ready">可用 {{ summary.ready }}</button>
      <button type="button" class="kb-chip" v-if="summary.disabled">已停用 {{ summary.disabled }}</button>
    </div>

    <div v-loading="loading" class="kb-grid">
      <div v-if="loadError && !loading" class="kb-empty kb-empty--error">
        <p class="kb-empty__text">{{ loadError }}</p>
        <button type="button" class="apple-btn apple-btn--primary" @click="load">重试</button>
      </div>

      <template v-else-if="displayRows.length">
        <KbCard
          v-for="row in displayRows"
          :key="row.kbId"
          :item="row"
          :current-user-id="currentUserId"
          :is-platform-admin="isPlatformAdmin"
          :scope-filter="scopeFilter"
          @open="openDetail"
          @edit="onEdit"
          @share="onShare"
          @toggle-status="onToggleStatus"
          @delete="onDelete"
        />
      </template>

      <div v-else-if="!loading" class="kb-empty">
        <div class="kb-empty__icon">📚</div>
        <p class="kb-empty__text">{{ emptyTitle }}</p>
        <p v-if="hasActiveFilters" class="kb-empty__hint">试试调整筛选条件</p>
        <button
          v-else
          type="button"
          class="apple-btn apple-btn--add"
          @click="openCreate"
          v-hasPermi="['ai:kb:add']"
        >创建第一个</button>
      </div>
    </div>

    <!-- 单屏新建抽屉 -->
    <el-drawer
      v-model="createOpen"
      title="新建知识库"
      size="min(440px, 96vw)"
      append-to-body
      destroy-on-close
      @closed="resetCreateForm"
    >
      <el-form ref="createRef" :model="createForm" :rules="createRules" label-position="top">
        <el-form-item label="名称" prop="kbName">
          <el-input
            v-model="createForm.kbName"
            placeholder="如：产品帮助中心"
            maxlength="100"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="用途说明" prop="description">
          <el-input
            v-model="createForm.description"
            type="textarea"
            :rows="3"
            placeholder="面向谁、解决什么问题（选填）"
            maxlength="500"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="可见范围" prop="visibility">
          <el-radio-group v-model="createForm.visibility" class="kb-vis-group">
            <el-radio value="PRIVATE">私有（负责人和已授权成员）</el-radio>
            <el-radio value="DEPT">部门可见</el-radio>
            <el-radio value="ORG">所有人可见</el-radio>
          </el-radio-group>
          <p class="kb-form-hint">索引与模型由平台统一配置；创建后可在文件页添加文档。</p>
        </el-form-item>
      </el-form>
      <div class="kb-drawer-foot kb-drawer-foot--inline">
        <button type="button" class="apple-btn apple-btn--ghost" @click="createOpen = false">取消</button>
        <button type="button" class="apple-btn apple-btn--primary" :disabled="creating" @click="submitCreate">
          {{ creating ? '创建中…' : '创建并打开' }}
        </button>
      </div>
    </el-drawer>

    <!-- 编辑资料（名称/说明/启停）—— 库级，不在文件页 -->
    <Teleport to="body">
      <Transition name="kb-sheet">
        <div v-if="editOpen" class="kb-sheet-overlay" @click.self="editOpen = false">
          <div class="kb-sheet kb-sheet--edit" role="dialog" aria-modal="true" aria-label="编辑资料">
            <header class="kb-sheet__header">
              <h2 class="kb-sheet__title">编辑资料</h2>
              <button type="button" class="kb-sheet__close" aria-label="关闭" @click="editOpen = false">✕</button>
            </header>
            <div class="kb-sheet__body">
              <el-form :model="editForm" label-position="top" class="kb-sheet-form">
                <el-form-item label="名称">
                  <el-input v-model="editForm.kbName" maxlength="100" show-word-limit />
                </el-form-item>
                <el-form-item label="说明">
                  <el-input v-model="editForm.description" type="textarea" :rows="3" />
                </el-form-item>
                <el-form-item label="启用">
                  <el-switch v-model="editForm.status" active-value="0" inactive-value="1" />
                </el-form-item>
              </el-form>
            </div>
            <footer class="kb-sheet__footer">
              <button type="button" class="apple-btn apple-btn--ghost" @click="editOpen = false">取消</button>
              <button type="button" class="apple-btn apple-btn--primary" @click="saveEdit">保存</button>
            </footer>
          </div>
        </div>
      </Transition>
    </Teleport>

    <!-- 库管理（成员 / 可见范围 / 智能体）—— 库级，不在文件页 -->
    <Teleport to="body">
      <Transition name="kb-sheet">
        <div v-if="manageOpen" class="kb-sheet-overlay" @click.self="closeManage">
          <div class="kb-sheet kb-sheet--manage" role="dialog" aria-modal="true" aria-label="库管理">
            <header class="kb-sheet__header">
              <div class="kb-sheet__titles">
                <h2 class="kb-sheet__title">库管理</h2>
                <p class="kb-sheet__sub">{{ manageRow?.kbName || '成员权限、可见范围' }}</p>
              </div>
              <button type="button" class="kb-sheet__close" aria-label="关闭" @click="closeManage">✕</button>
            </header>
            <div class="kb-sheet__body" v-loading="manageLoading">
              <UsagePanel
                v-if="manageOpen && manageKbId && manageAccess"
                :kb-id="manageKbId"
                :access="manageAccess"
                @close="closeManage"
                @transferred="onManageTransferred"
              />
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<script setup name="AiKb">
import { listKbWorkbench, addKb, updateKb, delKb, deleteKbImpact, getKbAccess } from '@/api/ai/kb'
import useUserStore from '@/store/modules/user'
import KbEnginePanel from './engine.vue'
import KbCard from './components/KbCard.vue'
import UsagePanel from './components/UsagePanel.vue'

const { proxy } = getCurrentInstance()
const router = useRouter()
const userStore = useUserStore()

const currentUserId = computed(() => {
  const id = userStore.id
  return id === '' || id == null ? null : Number(id)
})

/** 仅信任 workbench 返回的 isPlatformAdmin，禁止 roles.includes 猜测 */
const isPlatformAdmin = ref(false)
const engineOpen = ref(false)

const loading = ref(false)
const loadError = ref('')
const rows = ref([])
const summary = ref({ total: 0, processing: 0, issue: 0, empty: 0, ready: 0, disabled: 0 })
const queryParams = reactive({ kbName: undefined, status: undefined })
const healthFilter = ref('')
const scopeFilter = ref('all') // all | mine | shared

const scopeOptions = [
  { value: 'all', label: '全部' },
  { value: 'mine', label: '我负责的' },
  { value: 'shared', label: '与我共享' }
]

function effectiveOwnerId(row) {
  const o = row?.ownerUserId ?? row?.createUserId
  return o != null ? Number(o) : null
}

const displayRows = computed(() => {
  let list = rows.value || []
  const uid = currentUserId.value
  if (scopeFilter.value === 'mine' && uid != null) {
    list = list.filter(r => effectiveOwnerId(r) === uid)
  } else if (scopeFilter.value === 'shared' && uid != null) {
    list = list.filter(r => effectiveOwnerId(r) !== uid)
  }
  if (healthFilter.value) {
    list = list.filter(r => r.health === healthFilter.value)
  }
  return list
})

const hasActiveFilters = computed(() => {
  return !!(queryParams.kbName || queryParams.status || healthFilter.value || scopeFilter.value !== 'all')
})

const emptyTitle = computed(() => {
  if (scopeFilter.value === 'mine') return '你还没有负责的知识库'
  if (scopeFilter.value === 'shared') return '暂无共享给你的知识库'
  if (hasActiveFilters.value) return '没有匹配的知识库'
  return '还没有知识库'
})

function toggleHealth(h) {
  healthFilter.value = healthFilter.value === h ? '' : h
}

function load() {
  loading.value = true
  loadError.value = ''
  listKbWorkbench(queryParams).then(res => {
    const data = res.data || {}
    rows.value = data.rows || []
    summary.value = data.summary || { total: 0 }
    // 后端计算的平台管理员标记
    isPlatformAdmin.value = !!data.isPlatformAdmin
    loading.value = false
  }).catch((err) => {
    rows.value = []
    summary.value = { total: 0 }
    loadError.value = err?.msg || err?.message || '无法加载知识库列表'
    loading.value = false
  })
}

function resetQuery() {
  queryParams.kbName = undefined
  queryParams.status = undefined
  healthFilter.value = ''
  scopeFilter.value = 'all'
  load()
}

function openDetail(row) {
  if (!row?.kbId) return
  router.push({
    path: '/ai/kb-detail/index/' + row.kbId,
    query: { tab: 'content' }
  })
}

// ---- 新建 ----
const createOpen = ref(false)
const creating = ref(false)
const createRef = ref(null)
const createForm = reactive({
  kbName: '',
  description: '',
  visibility: 'PRIVATE'
})
const createRules = {
  kbName: [
    { required: true, message: '请填写名称', trigger: 'blur' },
    { min: 1, max: 100, message: '名称不超过 100 字', trigger: 'blur' }
  ]
}

function openCreate() {
  resetCreateForm()
  createOpen.value = true
}

function resetCreateForm() {
  createForm.kbName = ''
  createForm.description = ''
  createForm.visibility = 'PRIVATE'
  creating.value = false
}

function submitCreate() {
  createRef.value?.validate((valid) => {
    if (!valid) return
    creating.value = true
    addKb({
      kbName: createForm.kbName.trim(),
      description: (createForm.description || '').trim() || undefined,
      visibility: createForm.visibility || 'PRIVATE',
      status: '0'
    }).then((res) => {
      const kbId = res.data?.kbId || res.kbId
      proxy.$modal.msgSuccess('知识库已创建')
      creating.value = false
      createOpen.value = false
      if (kbId) {
        router.push({
          path: '/ai/kb-detail/index/' + kbId,
          query: { tab: 'content' }
        })
      } else {
        load()
      }
    }).catch(() => {
      creating.value = false
    })
  })
}

// ---- 库级：编辑资料 / 库管理 / 启停 / 删除（文件页不做这些） ----
const editOpen = ref(false)
const editForm = reactive({ kbId: undefined, kbName: '', description: '', status: '0' })

const manageOpen = ref(false)
const manageLoading = ref(false)
const manageKbId = ref(null)
const manageRow = ref(null)
const manageAccess = ref(null)

function onEdit(row) {
  editForm.kbId = row.kbId
  editForm.kbName = row.kbName
  editForm.description = row.description
  editForm.status = row.status
  editOpen.value = true
}

function onShare(row) {
  // 库管理留在列表层，打开 sheet；不进文件页
  manageRow.value = row
  manageKbId.value = row.kbId
  manageAccess.value = null
  manageOpen.value = true
  manageLoading.value = true
  getKbAccess(row.kbId).then(res => {
    const acc = res.data || {}
    if (!acc.canManage) {
      proxy.$modal.msgError('无权管理此知识库')
      closeManage()
      return
    }
    manageAccess.value = {
      canRead: !!acc.canRead,
      canUse: !!acc.canUse,
      canWrite: !!acc.canWrite,
      canManage: !!acc.canManage,
      canDelete: !!acc.canDelete,
      isPlatformAdmin: !!acc.isPlatformAdmin,
      role: acc.role,
      source: acc.source
    }
    manageLoading.value = false
  }).catch(err => {
    manageLoading.value = false
    proxy.$modal.msgError(err?.msg || err?.message || '无法打开库管理')
    closeManage()
  })
}

function closeManage() {
  manageOpen.value = false
  manageKbId.value = null
  manageRow.value = null
  manageAccess.value = null
  manageLoading.value = false
}

function onManageTransferred() {
  closeManage()
  load()
}

function onToggleStatus(row) {
  const disable = row.status !== '1'
  const tip = disable
    ? '停用后，将无法在该会话中检索。确认停用？'
    : '确认重新启用该知识库？'
  proxy.$modal.confirm(tip).then(() => {
    return updateKb({
      kbId: row.kbId,
      status: disable ? '1' : '0'
    })
  }).then(() => {
    proxy.$modal.msgSuccess(disable ? '已停用' : '已启用')
    load()
  }).catch(() => {
    // 取消或失败：不乐观更新，重拉保证一致
    load()
  })
}

function onDelete(row) {
  deleteKbImpact(row.kbId).then(res => {
    const d = res.data || {}
    const tip = d.warning || `确认删除「${row.kbName}」？`
    return proxy.$modal.confirm(tip + '\n\n删除后需输入名称确认。').then(() => {
      return proxy.$prompt('请输入知识库名称以确认删除', '删除确认', {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        inputPattern: new RegExp('^' + escapeReg(row.kbName) + '$'),
        inputErrorMessage: '名称不匹配'
      })
    })
  }).then(() => delKb(row.kbId))
    .then(() => { proxy.$modal.msgSuccess('已删除'); load() })
    .catch(() => {})
}

function escapeReg(s) {
  return String(s).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

function saveEdit() {
  const name = (editForm.kbName || '').trim()
  if (!name) {
    proxy.$modal.msgError('名称不能为空')
    return
  }
  if (name.length > 100) {
    proxy.$modal.msgError('名称不能超过 100 字')
    return
  }
  updateKb({
    kbId: editForm.kbId,
    kbName: name,
    description: editForm.description,
    status: editForm.status
  }).then(() => {
    proxy.$modal.msgSuccess('已保存')
    editOpen.value = false
    load()
  })
}

onMounted(load)
// keep-alive 返回时必须刷新，避免详情改名/上传/转移后显示旧卡片
onActivated(load)
</script>

<style scoped lang="scss">
@use '../../../assets/styles/ai-tokens.scss' as *;

.kb-page {
  font-family: $font;
  padding: 40px 48px;
  min-height: calc(100vh - 84px);
  -webkit-font-smoothing: antialiased;
  background: var(--ai-page-bg);
  box-sizing: border-box;
  @media (max-width: 768px) { padding: 24px 16px; }
}

/* Header —— 对齐 agent-header */
.kb-header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 28px;
  gap: 12px;
  flex-wrap: wrap;
  &__left { display: flex; align-items: baseline; gap: 12px; }
  &__title {
    font-size: 34px;
    font-weight: 700;
    color: $text;
    letter-spacing: -0.4px;
    margin: 0;
  }
  &__count { font-size: 15px; color: $gray; }
  &__actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
}

/* Buttons —— 对齐 apple-btn */
.apple-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-family: $font;
  font-size: 14px;
  font-weight: 500;
  border: none;
  border-radius: 980px;
  padding: 8px 18px;
  cursor: pointer;
  transition: all 0.2s $ease;
  outline: none;
  &:active { transform: scale(0.96); }
  &--add {
    background: $blue;
    color: #fff;
    box-shadow: 0 2px 10px rgba(10, 132, 255, 0.32);
    &:hover { background: #0071e3; }
  }
  &--primary {
    background: $blue;
    color: #fff;
    padding: 10px 24px;
    box-shadow: 0 2px 10px rgba(10, 132, 255, 0.32);
    &:hover { background: #0071e3; }
  }
  &--ghost {
    background: transparent;
    color: $blue;
    padding: 10px 16px;
    &:hover { background: rgba(10, 132, 255, 0.08); }
  }
  &--outline {
    background: transparent;
    color: $blue;
    border: 1.5px solid rgba(10, 132, 255, 0.35);
    padding: 7px 16px;
    &:hover { background: rgba(10, 132, 255, 0.06); border-color: $blue; }
  }
  &:disabled { opacity: 0.55; cursor: not-allowed; transform: none; }
}

/* Search —— 对齐 agent-search */
.kb-search {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 18px;
  flex-wrap: wrap;
  &__field { position: relative; flex: 1; min-width: 220px; max-width: 360px; }
  &__icon {
    position: absolute;
    left: 13px;
    top: 50%;
    transform: translateY(-50%);
    color: $gray2;
    pointer-events: none;
  }
  &__input {
    width: 100%;
    height: 38px;
    padding: 0 32px 0 36px;
    border: none;
    border-radius: 980px;
    background: var(--ai-search-bg);
    font-size: 14px;
    font-family: $font;
    color: $text;
    outline: none;
    transition: all 0.25s $ease;
    box-shadow: 0 1px 3px var(--ai-border);
    box-sizing: border-box;
    &::placeholder { color: $gray2; }
    &:focus {
      background: var(--ai-card-bg);
      box-shadow: 0 0 0 4px rgba(10, 132, 255, 0.12), 0 2px 12px var(--ai-border-2);
    }
  }
  &__clear {
    position: absolute;
    right: 10px;
    top: 50%;
    transform: translateY(-50%);
    width: 18px;
    height: 18px;
    border: none;
    border-radius: 50%;
    background: $gray3;
    color: #fff;
    font-size: 9px;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    &:hover { background: $gray; }
  }
}

.kb-select {
  height: 36px;
  padding: 0 28px 0 12px;
  border: none;
  border-radius: 8px;
  background: var(--ai-search-bg);
  box-shadow: 0 1px 3px var(--ai-border);
  font-size: 13px;
  font-family: $font;
  color: $text;
  appearance: none;
  cursor: pointer;
  outline: none;
  background-image: url("data:image/svg+xml,%3Csvg width='10' height='6' viewBox='0 0 10 6' fill='none' xmlns='http://www.w3.org/2000/svg'%3E%3Cpath d='M1 1l4 4 4-4' stroke='%238E8E93' stroke-width='1.5' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E");
  background-repeat: no-repeat;
  background-position: right 10px center;
}

.kb-scope {
  display: inline-flex;
  padding: 3px;
  background: var(--ai-fill-2);
  border-radius: 10px;
  gap: 2px;
}
.kb-scope__btn {
  border: none;
  background: transparent;
  border-radius: 8px;
  padding: 6px 12px;
  font-size: 12.5px;
  font-weight: 550;
  color: $text2;
  cursor: pointer;
  font-family: $font;
  transition: all 0.18s $ease;
  &.is-active {
    background: var(--ai-card-bg);
    color: $text;
    box-shadow: 0 1px 3px var(--ai-border);
  }
}

.kb-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 18px;
}
.kb-chip {
  border: 1px solid var(--ai-border-2);
  background: var(--ai-fill-1);
  border-radius: 980px;
  padding: 5px 12px;
  font-size: 12.5px;
  font-family: $font;
  color: $text2;
  cursor: pointer;
  transition: all 0.18s $ease;
  &.is-warn { background: rgba(255, 159, 10, 0.1); border-color: rgba(255, 159, 10, 0.25); color: #C24A00; }
  &.is-ok { background: rgba(52, 199, 89, 0.1); border-color: rgba(52, 199, 89, 0.22); color: #248A3D; }
  &.is-active {
    outline: 2px solid rgba(10, 132, 255, 0.45);
    outline-offset: 1px;
  }
}

/* Grid —— 对齐 agent-grid */
.kb-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(330px, 1fr));
  gap: 16px;
  min-height: 180px;
  @media (max-width: 768px) { grid-template-columns: 1fr; }
}

.kb-empty {
  grid-column: 1 / -1;
  text-align: center;
  padding: 72px 0;
  &__icon { font-size: 44px; margin-bottom: 14px; }
  &__text { font-size: 16px; color: $gray; margin: 0 0 10px; }
  &__hint { font-size: 13px; color: $gray3; margin: 0 0 18px; }
  &--error .kb-empty__text { color: #C24A00; }
}

.kb-vis-group {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
}
.kb-form-hint {
  margin: 8px 0 0;
  font-size: 12px;
  color: $gray;
  line-height: 1.45;
}
/* 库级 sheet（编辑 / 库管理） */
.kb-sheet-overlay {
  position: fixed;
  inset: 0;
  z-index: 2000;
  background: var(--ai-overlay);
  backdrop-filter: blur(5px);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px 20px;
  box-sizing: border-box;
}
.kb-sheet {
  width: 100%;
  max-width: 560px;
  height: min(760px, 90vh);
  background: var(--ai-sheet-bg);
  border-radius: 20px;
  box-shadow: var(--ai-shadow-sheet);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  font-family: $font;
  &--manage {
    max-width: 640px;
    height: min(820px, 92vh);
  }
  &--engine {
    max-width: 880px;
    height: min(880px, 94vh);
  }
  &--edit {
    max-width: 460px;
    height: auto;
    max-height: 88vh;
  }
  &__header {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 16px;
    padding: 22px 24px 0;
    flex-shrink: 0;
  }
  &__titles { min-width: 0; flex: 1; }
  &__title {
    margin: 0;
    font-size: 21px;
    font-weight: 700;
    color: $text;
    letter-spacing: -0.3px;
  }
  &__sub {
    margin: 4px 0 0;
    font-size: 13px;
    color: $text2;
    line-height: 1.4;
  }
  &__close {
    width: 28px;
    height: 28px;
    border: none;
    border-radius: 50%;
    background: var(--ai-fill-3);
    color: $gray;
    font-size: 12px;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
    &:hover { background: var(--ai-hover-strong); color: $text; }
  }
  &__body {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    padding: 18px 24px 24px;
    &::-webkit-scrollbar { width: 5px; }
    &::-webkit-scrollbar-thumb { background: var(--ai-border-4); border-radius: 3px; }
    &--engine {
      padding: 12px 18px 20px;
      background: var(--ai-page-base, var(--ai-sheet-bg));
    }
  }
  &__footer {
    display: flex;
    justify-content: flex-end;
    gap: 10px;
    padding: 14px 24px 22px;
    border-top: 1px solid var(--ai-fill-3);
    flex-shrink: 0;
  }
}
.kb-sheet-form {
  :deep(.el-form-item) { margin-bottom: 16px; }
  :deep(.el-form-item__label) {
    font-size: 13px;
    font-weight: 500;
    color: $text2;
    padding-bottom: 4px;
  }
}
.kb-sheet-enter-active { transition: all 0.32s cubic-bezier(0.34, 1.56, 0.64, 1); }
.kb-sheet-leave-active { transition: all 0.2s ease-in; }
.kb-sheet-enter-from {
  opacity: 0;
  .kb-sheet { transform: scale(0.92) translateY(16px); opacity: 0; }
}
.kb-sheet-leave-to {
  opacity: 0;
  .kb-sheet { transform: scale(0.96); opacity: 0; }
}

.kb-drawer-foot {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  &--inline {
    margin-top: 24px;
    padding-top: 16px;
    border-top: 1px solid var(--ai-border);
  }
}
</style>
