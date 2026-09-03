<template>
  <div class="usage" v-loading="loading">
    <!-- 库状态与可见范围 -->
    <section class="usage-block">
      <div class="usage-block__head">
        <h3 class="usage-block__title">状态与可见范围</h3>
      </div>
      <div class="usage-field">
        <span class="usage-field__label">状态</span>
        <div class="usage-field__body">
          <template v-if="canManage">
            <el-switch
              v-model="formStatus"
              active-value="0"
              inactive-value="1"
              active-text="启用"
              inactive-text="停用"
              @change="onStatusChange"
            />
          </template>
          <template v-else>
            <span class="usage-status" :class="data.status === '0' ? 'is-on' : 'is-off'">
              <i />{{ data.status === '0' ? '已启用' : '已停用' }}
            </span>
          </template>
        </div>
      </div>
      <div class="usage-field">
        <span class="usage-field__label">负责人</span>
        <div class="usage-field__body usage-field__body--text">{{ ownerDisplay }}</div>
      </div>
      <div class="usage-field usage-field--stack">
        <span class="usage-field__label">可见范围</span>
        <div class="usage-field__body">
          <template v-if="canManage">
            <div class="usage-vis" role="radiogroup" aria-label="可见范围">
              <button
                v-for="opt in visibilityOptions"
                :key="opt.value"
                type="button"
                class="usage-vis__opt"
                :class="{ 'is-active': formVisibility === opt.value }"
                @click="pickVisibility(opt.value)"
              >
                <span class="usage-vis__name">{{ opt.label }}</span>
                <span class="usage-vis__desc">{{ opt.desc }}</span>
              </button>
            </div>
            <p class="usage-hint">{{ visibilityHint }}</p>
          </template>
          <template v-else>
            <span class="usage-field__body--text">{{ visLabel(data.visibility) }}</span>
          </template>
        </div>
      </div>
    </section>

    <!-- 成员与权限 -->
    <section class="usage-block">
      <div class="usage-block__head">
        <h3 class="usage-block__title">成员与权限</h3>
        <button v-if="canManage" type="button" class="usage-btn" @click="openMember">+ 添加</button>
      </div>
      <p class="usage-hint usage-hint--block">
        显式角色叠加在可见范围之上：移除成员后，私有库将完全失去访问；部门/所有可见范围内的用户仍保留查看与使用。
      </p>

      <div v-if="(data.members || []).length" class="usage-list">
        <div v-for="m in data.members" :key="m.userId" class="usage-row">
          <div class="usage-row__main">
            <div class="usage-row__name">{{ memberDisplay(m) }}</div>
            <div class="usage-row__meta">
              <span v-if="m.userName">@{{ m.userName }}</span>
              <span v-if="m.deptName">{{ m.deptName }}</span>
            </div>
          </div>
          <span class="usage-role">{{ roleLabel(m.role) }}</span>
          <div v-if="canManage" class="usage-row__ops">
            <button type="button" class="usage-link" @click="editMember(m)">改角色</button>
            <button type="button" class="usage-link is-danger" @click="doRemoveMember(m)">移除</button>
          </div>
        </div>
      </div>
      <div v-else class="usage-empty">暂无额外成员。可按姓名搜索添加协作人。</div>

      <div v-if="canDelete" class="usage-transfer">
        <button type="button" class="usage-btn usage-btn--ghost" @click="openTransfer">转移负责人…</button>
      </div>
    </section>

    <!-- 添加/改角色成员 -->
    <Teleport to="body">
      <Transition name="kb-mini">
        <div v-if="memberOpen" class="usage-dialog-mask" @click.self="memberOpen = false">
          <div class="usage-dialog" role="dialog" aria-modal="true">
            <header class="usage-dialog__head">
              <h3>{{ memberForm.editing ? '修改角色' : '添加成员' }}</h3>
              <button type="button" class="usage-dialog__close" @click="memberOpen = false">✕</button>
            </header>
            <div class="usage-dialog__body">
              <div v-if="!memberForm.editing" class="usage-dlg-field">
                <label>搜索用户</label>
                <el-select
                  v-model="memberForm.userId"
                  filterable
                  remote
                  clearable
                  reserve-keyword
                  placeholder="输入姓名/用户名过滤，或选择下方用户"
                  :remote-method="searchCandidates"
                  :loading="candidateLoading"
                  style="width:100%"
                >
                  <el-option
                    v-for="c in candidates"
                    :key="c.userId"
                    :label="candidateLabel(c)"
                    :value="c.userId"
                  />
                </el-select>
              </div>
              <div v-else class="usage-dlg-field">
                <label>成员</label>
                <div class="usage-field__body--text">{{ memberForm.display }}</div>
              </div>
              <div class="usage-dlg-field">
                <label>角色</label>
                <div class="usage-role-pick" role="radiogroup">
                  <button
                    v-for="r in roleOptions"
                    :key="r.value"
                    type="button"
                    class="usage-role-pick__opt"
                    :class="{ 'is-active': memberForm.role === r.value }"
                    @click="memberForm.role = r.value"
                  >
                    <span class="usage-role-pick__name">{{ r.label }}</span>
                    <span class="usage-role-pick__desc">{{ r.desc }}</span>
                  </button>
                </div>
              </div>
            </div>
            <footer class="usage-dialog__foot">
              <button type="button" class="usage-btn usage-btn--ghost" @click="memberOpen = false">取消</button>
              <button type="button" class="usage-btn" :disabled="memberSaving" @click="doUpsertMember">
                {{ memberSaving ? '保存中…' : '保存' }}
              </button>
            </footer>
          </div>
        </div>
      </Transition>
    </Teleport>

    <!-- 转移负责人 -->
    <Teleport to="body">
      <Transition name="kb-mini">
        <div v-if="transferOpen" class="usage-dialog-mask" @click.self="transferOpen = false">
          <div class="usage-dialog" role="dialog" aria-modal="true">
            <header class="usage-dialog__head">
              <h3>转移负责人</h3>
              <button type="button" class="usage-dialog__close" @click="transferOpen = false">✕</button>
            </header>
            <div class="usage-dialog__body">
              <div class="usage-dlg-field">
                <label>新负责人（须为当前成员）</label>
                <el-select v-model="transferUserId" filterable placeholder="选择成员" style="width:100%">
                  <el-option
                    v-for="m in data.members || []"
                    :key="m.userId"
                    :label="memberDisplay(m) + ' · ' + roleLabel(m.role)"
                    :value="m.userId"
                  />
                </el-select>
              </div>
              <p class="usage-hint">转移成功后，原负责人将变为编辑者。</p>
            </div>
            <footer class="usage-dialog__foot">
              <button type="button" class="usage-btn usage-btn--ghost" @click="transferOpen = false">取消</button>
              <button type="button" class="usage-btn" :disabled="transferring" @click="doTransfer">
                {{ transferring ? '处理中…' : '确认转移' }}
              </button>
            </footer>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<script setup>
import {
  getKbUsage,
  upsertKbMember,
  removeKbMember,
  transferKbOwner,
  listKbMemberCandidates,
  updateKb
} from '@/api/ai/kb'

const props = defineProps({
  kbId: { type: [Number, String], required: true },
  access: {
    type: Object,
    default: () => ({})
  }
})
const emit = defineEmits(['updated', 'close', 'transferred'])

const { proxy } = getCurrentInstance()
const loading = ref(false)
const data = ref({ members: [], owner: null })

const canManage = computed(() => !!props.access?.canManage)
const canDelete = computed(() => !!props.access?.canDelete)

const formStatus = ref('0')
const formVisibility = ref('PRIVATE')

const visibilityOptions = [
  { value: 'PRIVATE', label: '私有', desc: '仅负责人和已授权成员' },
  { value: 'DEPT', label: '部门可见', desc: '同部门可查看与使用' },
  { value: 'ORG', label: '所有人可见', desc: '所有人可查看与使用' }
]

const roleOptions = [
  { value: 'VIEWER', label: '查看者', desc: '查看文件、预览、测试' },
  { value: 'EDITOR', label: '编辑者', desc: '维护文件、编辑名称说明' },
  { value: 'QUALITY', label: '库管理员', desc: '分享、绑定、成员管理' }
]

const memberOpen = ref(false)
const memberSaving = ref(false)
const memberForm = reactive({
  userId: null,
  role: 'VIEWER',
  editing: false,
  display: ''
})
const candidates = ref([])
const candidateLoading = ref(false)

const transferOpen = ref(false)
const transferUserId = ref(null)
const transferring = ref(false)

const ownerDisplay = computed(() => {
  const o = data.value.owner
  if (o?.nickName) return o.nickName + (o.userName ? ` (@${o.userName})` : '')
  if (o?.userName) return o.userName
  if (data.value.ownerUserId) return '用户 ' + data.value.ownerUserId
  return data.value.createBy || '—'
})

const visibilityHint = computed(() => {
  const v = formVisibility.value
  if (v === 'PRIVATE') return '切为私有后，未列入成员的部门/其他用户将失去访问。'
  if (v === 'DEPT') return '同部门用户获得查看与使用；显式成员可获得更高权限。'
  if (v === 'ORG') return '所有人获得查看与使用；显式成员可获得更高权限。'
  return ''
})

function visLabel(v) {
  return ({
    PRIVATE: '私有（负责人和已授权成员）',
    MEMBERS: '私有（已授权成员）',
    DEPT: '部门可见',
    ORG: '所有人可见'
  })[v] || v || '—'
}
function roleLabel(r) {
  return ({ VIEWER: '查看者', EDITOR: '编辑者', QUALITY: '库管理员', OWNER: '所有者' })[r] || r
}
function memberDisplay(m) {
  if (!m) return '—'
  if (m.nickName) return m.nickName
  if (m.userName) return m.userName
  return '用户 ' + m.userId
}
function candidateLabel(c) {
  const name = c.nickName || c.userName || ('用户 ' + c.userId)
  const dept = c.deptName ? ` · ${c.deptName}` : ''
  const un = c.userName && c.nickName ? ` (@${c.userName})` : ''
  return name + un + dept
}

function load() {
  if (!props.kbId) return
  loading.value = true
  getKbUsage(props.kbId).then(res => {
    data.value = res.data || { members: [] }
    formStatus.value = data.value.status || '0'
    const vis = (data.value.visibility || 'PRIVATE').toUpperCase()
    formVisibility.value = vis === 'MEMBERS' ? 'PRIVATE' : vis
    emit('updated', data.value)
    loading.value = false
  }).catch((err) => {
    loading.value = false
    if (err?.code === 403 || String(err?.msg || '').includes('无权')) {
      proxy.$modal.msgError('无权管理此知识库')
      emit('close')
    }
  })
}

function onStatusChange(val) {
  const disable = val === '1'
  const tip = disable
    ? '停用后，将无法在该会话中检索。确认停用？'
    : '确认启用该知识库？'
  proxy.$modal.confirm(tip).then(() => {
    return updateKb({ kbId: props.kbId, status: val })
  }).then(() => {
    proxy.$modal.msgSuccess(disable ? '已停用' : '已启用')
    load()
  }).catch(() => {
    formStatus.value = data.value.status || '0'
  })
}

function pickVisibility(val) {
  if (!canManage.value || val === formVisibility.value) return
  const prev = formVisibility.value
  formVisibility.value = val
  onVisibilityChange(val, prev)
}

function onVisibilityChange(val, prev) {
  const labels = { PRIVATE: '私有', DEPT: '部门可见', ORG: '所有人可见' }
  const impact = visibilityHint.value
  proxy.$modal.confirm(`确认将可见范围改为「${labels[val] || val}」？\n${impact}`).then(() => {
    return updateKb({ kbId: props.kbId, visibility: val })
  }).then(() => {
    proxy.$modal.msgSuccess('已更新可见范围')
    load()
  }).catch(() => {
    formVisibility.value = prev
      || (data.value.visibility === 'MEMBERS' ? 'PRIVATE' : (data.value.visibility || 'PRIVATE'))
  })
}

function openMember() {
  memberForm.userId = null
  memberForm.role = 'VIEWER'
  memberForm.editing = false
  memberForm.display = ''
  candidates.value = []
  memberOpen.value = true
  // 打开即预载一页候选(空关键词)：默认按 userId 升序列出可添加用户，输入后转模糊过滤
  searchCandidates('')
}
function editMember(m) {
  memberForm.userId = m.userId
  memberForm.role = m.role || 'VIEWER'
  memberForm.editing = true
  memberForm.display = memberDisplay(m)
  memberOpen.value = true
}

function searchCandidates(q) {
  // 空关键词=预载默认列表(打开弹窗时)；非空=按姓名/用户名模糊过滤，1 个字也可搜
  const kw = (q || '').trim()
  candidateLoading.value = true
  listKbMemberCandidates(props.kbId, { keyword: kw, pageNum: 1, pageSize: 20 }).then(res => {
    candidates.value = res.rows || []
    candidateLoading.value = false
  }).catch(() => {
    candidates.value = []
    candidateLoading.value = false
  })
}

function doUpsertMember() {
  const uid = Number(memberForm.userId)
  if (!uid) {
    proxy.$modal.msgWarning('请选择用户')
    return
  }
  memberSaving.value = true
  upsertKbMember(props.kbId, { userId: uid, role: memberForm.role }).then(() => {
    proxy.$modal.msgSuccess('已保存')
    memberOpen.value = false
    memberSaving.value = false
    load()
  }).catch(() => { memberSaving.value = false })
}

function doRemoveMember(m) {
  const vis = (data.value.visibility || '').toUpperCase()
  let tip = `确认移除「${memberDisplay(m)}」？`
  if (vis === 'PRIVATE' || vis === 'MEMBERS') {
    tip += '\n移除后该用户将完全失去访问。'
  } else if (vis === 'DEPT' || vis === 'ORG') {
    tip += '\n将仅撤销其编辑/管理权限；范围内用户仍可查看与使用。'
  }
  proxy.$modal.confirm(tip).then(() => {
    return removeKbMember(props.kbId, m.userId)
  }).then(() => {
    proxy.$modal.msgSuccess('已移除')
    load()
  }).catch(() => {})
}

function openTransfer() {
  transferUserId.value = null
  transferOpen.value = true
}

function doTransfer() {
  const uid = Number(transferUserId.value)
  if (!uid) {
    proxy.$modal.msgWarning('请选择新负责人（须为当前成员）')
    return
  }
  proxy.$modal.confirm('确认转移负责人？原负责人将变为编辑者。').then(() => {
    transferring.value = true
    return transferKbOwner(props.kbId, uid)
  }).then(() => {
    proxy.$modal.msgSuccess('已转移')
    transferOpen.value = false
    transferring.value = false
    emit('transferred')
    load()
  }).catch(() => { transferring.value = false })
}

watch(() => props.kbId, () => load(), { immediate: true })
defineExpose({ reload: load })
</script>

<style scoped lang="scss">
@use '../../../../assets/styles/ai-tokens.scss' as *;

.usage {
  display: flex;
  flex-direction: column;
  gap: 12px;
  font-family: $font;
  color: $text;
}

.usage-block {
  background: var(--ai-fill-1);
  border-radius: $radius;
  padding: 16px 18px;
}
.usage-block__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.usage-block__title {
  margin: 0;
  font-size: 14px;
  font-weight: 650;
  color: $text;
  letter-spacing: -0.15px;
}

.usage-field {
  display: grid;
  grid-template-columns: 72px 1fr;
  gap: 10px;
  align-items: center;
  margin-bottom: 14px;
  &:last-child { margin-bottom: 0; }
  &--stack {
    grid-template-columns: 1fr;
    align-items: start;
    .usage-field__label { margin-bottom: 2px; }
  }
}
.usage-field__label {
  font-size: 12.5px;
  font-weight: 500;
  color: $gray;
}
.usage-field__body {
  min-width: 0;
  &--text {
    font-size: 14px;
    color: $text;
    font-weight: 500;
  }
}

.usage-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13.5px;
  i {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    display: inline-block;
  }
  &.is-on {
    color: #248A3D;
    i { background: $green; box-shadow: 0 0 0 2.5px rgba(52, 199, 89, 0.18); }
  }
  &.is-off {
    color: $gray;
    i { background: $gray2; }
  }
}

/* 可见范围：分段卡片，替代一长串 radio */
.usage-vis {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}
.usage-vis__opt {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  width: 100%;
  text-align: left;
  border: 1.5px solid var(--ai-border-2);
  background: var(--ai-card-bg);
  border-radius: 12px;
  padding: 10px 12px;
  cursor: pointer;
  font-family: $font;
  transition: all 0.18s $ease;
  &:hover { border-color: rgba(10, 132, 255, 0.28); }
  &.is-active {
    border-color: $blue;
    background: rgba(10, 132, 255, 0.05);
    box-shadow: 0 0 0 1px rgba(10, 132, 255, 0.12);
  }
}
.usage-vis__name {
  font-size: 13.5px;
  font-weight: 600;
  color: $text;
}
.usage-vis__desc {
  font-size: 12px;
  color: $text2;
  line-height: 1.4;
}

.usage-list {
  display: flex;
  flex-direction: column;
  gap: 0;
  border: 1px solid var(--ai-border);
  border-radius: 12px;
  overflow: hidden;
  background: var(--ai-card-bg);
}
.usage-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border-bottom: 1px solid var(--ai-border);
  &:last-child { border-bottom: none; }
}
.usage-row__main {
  flex: 1;
  min-width: 0;
}
.usage-row__name {
  font-size: 13.5px;
  font-weight: 600;
  color: $text;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.usage-row__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 10px;
  margin-top: 2px;
  font-size: 12px;
  color: $gray;
}
.usage-code {
  font-family: $mono;
  font-size: 11px;
  background: var(--ai-fill-2);
  padding: 1px 6px;
  border-radius: 4px;
}
.usage-role {
  flex-shrink: 0;
  font-size: 11.5px;
  font-weight: 600;
  color: $text2;
  background: var(--ai-fill-2);
  padding: 3px 8px;
  border-radius: 980px;
}
.usage-row__ops {
  display: flex;
  gap: 4px;
  flex-shrink: 0;
}

.usage-empty {
  font-size: 13px;
  color: $gray;
  padding: 14px 4px;
  line-height: 1.55;
}

.usage-btn {
  border: none;
  background: $blue;
  color: #fff;
  border-radius: 980px;
  padding: 6px 14px;
  font-size: 12.5px;
  font-weight: 500;
  font-family: $font;
  cursor: pointer;
  box-shadow: 0 2px 8px rgba(10, 132, 255, 0.25);
  transition: all 0.18s $ease;
  white-space: nowrap;
  &:hover:not(:disabled) { background: #0071e3; }
  &:disabled { opacity: 0.55; cursor: not-allowed; }
  &--ghost {
    background: transparent;
    color: $blue;
    box-shadow: 0 0 0 1.5px rgba(10, 132, 255, 0.35);
    &:hover:not(:disabled) { background: rgba(10, 132, 255, 0.06); }
  }
}
.usage-hint {
  margin: 8px 0 0;
  font-size: 12px;
  color: $gray;
  line-height: 1.5;
}
.usage-hint--block { margin: 0 0 12px; }
.usage-transfer { margin-top: 12px; }

.usage-link {
  border: none;
  background: transparent;
  color: $blue;
  cursor: pointer;
  font-size: 12.5px;
  padding: 4px 6px;
  font-family: $font;
  border-radius: 6px;
  transition: background 0.15s $ease;
  &:hover { background: rgba(10, 132, 255, 0.08); }
  &.is-danger {
    color: $red;
    &:hover { background: rgba(255, 59, 48, 0.08); }
  }
}

/* switch 主题色 */
.usage {
  :deep(.el-switch.is-checked .el-switch__core) {
    background-color: $green;
    border-color: $green;
  }
  :deep(.el-switch__label) {
    font-size: 12.5px;
    color: $text2;
    &.is-active { color: $text; }
  }
}

/* 子弹对话框 */
.usage-dialog-mask {
  position: fixed;
  inset: 0;
  z-index: 2100;
  background: var(--ai-overlay);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  box-sizing: border-box;
}
.usage-dialog {
  width: 100%;
  max-width: 440px;
  background: var(--ai-sheet-bg);
  border-radius: 18px;
  box-shadow: var(--ai-shadow-sheet);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  font-family: $font;
}
.usage-dialog__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 20px 0;
  h3 {
    margin: 0;
    font-size: 17px;
    font-weight: 700;
    color: $text;
  }
}
.usage-dialog__close {
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
  &:hover { background: var(--ai-hover-strong); color: $text; }
}
.usage-dialog__body {
  padding: 16px 20px;
}
.usage-dialog__foot {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 12px 20px 18px;
  border-top: 1px solid var(--ai-fill-3);
}
.usage-dlg-field {
  margin-bottom: 14px;
  &:last-child { margin-bottom: 0; }
  label {
    display: block;
    font-size: 12.5px;
    font-weight: 500;
    color: $text2;
    margin-bottom: 6px;
  }
  :deep(.el-select .el-select__wrapper),
  :deep(.el-input__wrapper) {
    border-radius: $radius-sm;
    box-shadow: 0 0 0 1px var(--ai-border-3) inset;
    background: var(--ai-input-bg);
    &.is-focus {
      background: var(--ai-card-bg);
      box-shadow: 0 0 0 4px rgba(10, 132, 255, 0.12), 0 0 0 1px $blue inset;
    }
  }
}

.usage-role-pick {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.usage-role-pick__opt {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  width: 100%;
  text-align: left;
  border: 1.5px solid var(--ai-border-2);
  background: var(--ai-card-bg);
  border-radius: 12px;
  padding: 10px 12px;
  cursor: pointer;
  font-family: $font;
  transition: all 0.18s $ease;
  &:hover { border-color: rgba(10, 132, 255, 0.28); }
  &.is-active {
    border-color: $blue;
    background: rgba(10, 132, 255, 0.05);
  }
}
.usage-role-pick__name {
  font-size: 13.5px;
  font-weight: 600;
  color: $text;
}
.usage-role-pick__desc {
  font-size: 12px;
  color: $text2;
}

.kb-mini-enter-active { transition: all 0.28s cubic-bezier(0.34, 1.56, 0.64, 1); }
.kb-mini-leave-active { transition: all 0.18s ease-in; }
.kb-mini-enter-from {
  opacity: 0;
  .usage-dialog { transform: scale(0.94) translateY(10px); opacity: 0; }
}
.kb-mini-leave-to {
  opacity: 0;
  .usage-dialog { transform: scale(0.97); opacity: 0; }
}
</style>
