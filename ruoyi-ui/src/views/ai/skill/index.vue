<template>
  <div class="skill-page">
    <!-- 页面标题 -->
    <header class="skill-header">
      <div class="skill-header__left">
        <h1 class="skill-header__title">技能管理</h1>
        <span class="skill-header__count">{{ total }} 个</span>
      </div>
      <div class="skill-header__actions">
        <button type="button" class="apple-btn apple-btn--add" @click="handleAdd" v-hasPermi="['ai:skill:add']">
          <svg width="14" height="14" viewBox="0 0 14 14" fill="none"><path d="M7 1v12M1 7h12" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>
          新增技能
        </button>
      </div>
    </header>

    <!-- 搜索栏 -->
    <div class="skill-search">
      <div class="skill-search__field">
        <svg class="skill-search__icon" width="15" height="15" viewBox="0 0 15 15" fill="none">
          <circle cx="6.5" cy="6.5" r="5.5" stroke="currentColor" stroke-width="1.4"/>
          <path d="M10.5 10.5L14 14" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/>
        </svg>
        <input v-model="queryParams.skillName" class="skill-search__input" placeholder="搜索技能名称…" @keyup.enter="handleQuery" />
        <button type="button" v-if="queryParams.skillName" class="skill-search__clear" @click="queryParams.skillName = ''; handleQuery()">✕</button>
      </div>
      <input v-model="queryParams.skillCode" class="skill-search__input skill-search__input--mid" placeholder="按技能编码" @keyup.enter="handleQuery" />
      <input v-model="queryParams.category" class="skill-search__input skill-search__input--mid" placeholder="按分类" @keyup.enter="handleQuery" />
      <select v-model="queryParams.status" class="skill-select" @change="handleQuery">
        <option value="">全部状态</option>
        <option v-for="dict in sys_normal_disable" :key="dict.value" :value="dict.value">{{ dict.label }}</option>
      </select>
      <button type="button" class="apple-btn apple-btn--ghost" @click="resetQuery">重置</button>
    </div>

    <!-- 卡片列表 -->
    <div v-loading="loading" class="skill-grid">
      <article
        v-for="item in skillList"
        :key="item.skillId"
        class="skill-card"
        :class="{ 'is-off': item.status !== '0' }"
        :style="{ '--accent': colorOf(item.category || item.skillCode) }"
        @click="handleDetail(item)"
      >
        <span class="skill-card__rail"></span>
        <div class="skill-card__head">
          <div class="skill-card__ident">
            <h3 class="skill-card__name" :title="item.skillName">{{ item.skillName }}</h3>
            <div class="skill-card__sub">
              <span class="skill-card__code">{{ item.skillCode }}</span>
              <span class="skill-card__status" :class="item.status === '0' ? 'is-on' : 'is-off'">
                <i></i>{{ item.status === '0' ? '已启用' : '已停用' }}
              </span>
            </div>
          </div>
          <div class="skill-card__actions">
            <button type="button" class="skill-card__action" title="编辑" @click.stop="handleUpdate(item)" v-hasPermi="['ai:skill:edit']">
              <svg width="15" height="15" viewBox="0 0 16 16" fill="none"><path d="M11.5 2.5l2 2L5 13H3v-2l8.5-8.5z" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/></svg>
            </button>
            <button type="button" class="skill-card__action skill-card__action--danger" title="删除" @click.stop="handleDelete(item)" v-hasPermi="['ai:skill:remove']">
              <svg width="15" height="15" viewBox="0 0 16 16" fill="none"><path d="M3 4.5h10M6.5 2.5h3M4.5 4.5l.5 9h6l.5-9M6.5 7v4M9.5 7v4" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round"/></svg>
            </button>
          </div>
        </div>

        <div class="skill-card__props">
          <span v-if="item.category" class="skill-card__chip" :style="{ background: softOf(item.category), color: colorOf(item.category) }">
            <i class="skill-card__chip-dot" :style="{ background: colorOf(item.category) }"></i>
            {{ item.category }}
          </span>
          <span v-else class="skill-card__chip skill-card__chip--empty">未分类</span>
          <span class="skill-card__prop"><span class="skill-card__prop-k">字数</span><b>{{ formatLen(item.promptTemplate) }}</b></span>
          <span class="skill-card__prop"><span class="skill-card__prop-k">排序</span><b>{{ item.sort ?? 0 }}</b></span>
        </div>

        <p v-if="item.description" class="skill-card__desc" :title="item.description">{{ item.description }}</p>
        <pre v-if="item.promptTemplate" class="skill-card__prompt" :title="item.promptTemplate">{{ truncate(item.promptTemplate, 200) }}</pre>
      </article>

      <!-- 空态 -->
      <div v-if="!loading && skillList.length === 0" class="skill-empty">
        <div class="skill-empty__icon">✨</div>
        <p class="skill-empty__text">还没有技能</p>
        <button type="button" class="apple-btn apple-btn--add" @click="handleAdd" v-hasPermi="['ai:skill:add']">创建第一个技能</button>
      </div>
    </div>

    <!-- 分页 -->
    <div v-show="total > 0" class="skill-pagination">
      <pagination :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" @pagination="getList" />
    </div>

    <!-- ==================== 详情面板（只读） ==================== -->
    <Teleport to="body">
      <Transition name="sheet">
        <div v-if="detailOpen" class="sheet-overlay" @click.self="closeDetail">
          <div class="sheet sheet--wide" role="dialog" aria-modal="true" aria-label="技能详情">
            <div class="hero hero--no-avatar" :style="{ background: detail.status === '0' ? gradientOf(detail.category || detail.skillCode) : offGradient }">
              <button type="button" class="hero__close" aria-label="关闭" @click="closeDetail">✕</button>
              <div class="hero__body hero__body--solo">
                <div class="hero__text">
                  <h2 class="hero__name">{{ detail.skillName }}</h2>
                  <div class="hero__sub">
                    <span class="hero__chip" v-if="detail.category" :style="{ background: 'rgba(255,255,255,0.22)', color: '#fff' }">{{ detail.category }}</span>
                    <span class="hero__code">{{ detail.skillCode }}</span>
                    <span class="hero__status" :class="detail.status === '0' ? 'is-on' : 'is-off'">
                      <i :class="detail.status === '0' ? 'is-on' : 'is-off'"></i>{{ detail.status === '0' ? '已启用' : '已停用' }}
                    </span>
                  </div>
                </div>
              </div>
              <div class="hero__stats">
                <div class="hero__stat"><b>{{ formatLen(detail.promptTemplate) }}</b><span>提示词字数</span></div>
                <div class="hero__stat"><b>{{ detail.sort ?? 0 }}</b><span>排序</span></div>
                <div class="hero__stat"><b>{{ placeholderCount(detail.promptTemplate) }}</b><span>占位符</span></div>
              </div>
            </div>

            <div class="sheet__body">
              <div class="detail-cols">
                <div class="detail-main">
                  <div class="detail-block" v-if="detail.description">
                    <div class="detail-block__title">技能描述</div>
                    <div class="detail-prompt detail-prompt--static">{{ detail.description }}</div>
                  </div>

                  <div class="detail-block">
                    <div class="detail-block__title detail-block__title--with-meta">
                      <span>提示词模板</span>
                      <span class="detail-block__meta">
                        <span v-if="placeholderCount(detail.promptTemplate)" class="detail-block__ph">含 {{ placeholderCount(detail.promptTemplate) }} 个 {var} 占位符</span>
                        <span class="detail-block__len">{{ formatLen(detail.promptTemplate) }} 字</span>
                      </span>
                    </div>
                    <pre v-if="detail.promptTemplate" class="prompt-block">{{ detail.promptTemplate }}</pre>
                    <div v-else class="detail-hollow">
                      <span>未设置提示词模板</span>
                    </div>
                  </div>
                </div>

                <aside class="detail-side">
                  <div class="detail-block">
                    <div class="detail-block__title">基础信息</div>
                    <dl class="detail-kv">
                      <div class="detail-kv__row"><dt>编码</dt><dd :title="detail.skillCode" class="detail-kv__mono">{{ detail.skillCode }}</dd></div>
                      <div class="detail-kv__row"><dt>分类</dt><dd :class="{ 'is-missing': !detail.category }">{{ detail.category || '未分类' }}</dd></div>
                      <div class="detail-kv__row"><dt>排序</dt><dd>{{ detail.sort ?? 0 }}</dd></div>
                      <div class="detail-kv__row"><dt>状态</dt><dd>{{ detail.status === '0' ? '已启用' : '已停用' }}</dd></div>
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
              <button type="button" class="apple-btn apple-btn--primary" @click="editFromDetail" v-hasPermi="['ai:skill:edit']">编辑</button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>

    <!-- ==================== 编辑面板 ==================== -->
    <Teleport to="body">
      <Transition name="sheet">
        <div v-if="open" class="sheet-overlay" @click.self="cancel">
          <div class="sheet" role="dialog" aria-modal="true" aria-label="编辑技能">
            <div class="sheet__header">
              <h2 class="sheet__title">{{ title }}</h2>
              <button type="button" class="sheet__close" aria-label="关闭" @click="cancel">✕</button>
            </div>

            <div class="sheet__body sheet__body--wide">
              <el-form ref="skillRef" :model="form" :rules="rules" label-position="top" class="aform" @submit.prevent>
                <!-- 基本信息 -->
                <div class="aform__group">
                  <div class="aform__row aform__row--2">
                    <el-form-item v-if="form.skillId" label="技能编码" prop="skillCode" class="aform__item">
                      <el-input v-model="form.skillCode" placeholder="提交后自动生成" disabled />
                    </el-form-item>
                    <el-form-item label="技能名称" prop="skillName" class="aform__item">
                      <el-input v-model="form.skillName" placeholder="对外展示名" />
                    </el-form-item>
                  </div>
                  <div class="aform__row aform__row--2">
                    <el-form-item label="分类" prop="category" class="aform__item">
                      <el-input v-model="form.category" placeholder="写作 / 编程 / 分析 等" />
                    </el-form-item>
                    <el-form-item label="排序" prop="sort" class="aform__item">
                      <el-input-number v-model="form.sort" :min="0" controls-position="right" style="width: 100%" />
                    </el-form-item>
                  </div>
                </div>

                <!-- 描述 -->
                <div class="aform__group">
                  <el-form-item label="描述" prop="description">
                    <el-input v-model="form.description" type="textarea" :rows="2" placeholder="简单描述技能用途" />
                  </el-form-item>
                </div>

                <!-- 提示词模板（核心） -->
                <div class="aform__group aform__group--full">
                  <div class="aform__group-label">
                    提示词模板
                    <span class="aform__group-meta">
                      <span v-if="placeholderCount(form.promptTemplate)" class="aform__group-ph">{{ placeholderCount(form.promptTemplate) }} 个 {var} 占位符</span>
                      <span class="aform__group-len">{{ formatLen(form.promptTemplate) }} 字</span>
                    </span>
                  </div>
                  <el-form-item prop="promptTemplate">
                    <el-input v-model="form.promptTemplate" type="textarea" :rows="14" placeholder="支持 {var} 占位符，运行时替换&#10;&#10;示例：&#10;你是一个{category}领域的专家，请帮我{task}。" class="prompt-textarea" />
                  </el-form-item>
                </div>

                <!-- 状态 -->
                <div class="aform__group aform__group--toggles">
                  <div class="toggle-row">
                    <div class="toggle-row__info">
                      <span class="toggle-row__label">启用状态</span>
                      <span class="toggle-row__hint">停用后不可被智能体引用</span>
                    </div>
                    <el-switch v-model="form.status" active-value="0" inactive-value="1" />
                  </div>
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

<script setup name="AiSkill">
import { listSkill, getSkill, addSkill, updateSkill, delSkill } from '@/api/ai/skill'
import { gradientOf, colorOf, softOf } from '@/utils/ai-palette'

const { proxy } = getCurrentInstance()
const { sys_normal_disable } = proxy.useDict('sys_normal_disable')

const offGradient = 'linear-gradient(135deg, #A1A1A6, #C7C7CC)'

const skillList = ref([])
const loading = ref(true)
const total = ref(0)

const data = reactive({
  queryParams: {
    pageNum: 1, pageSize: 12,
    skillCode: undefined, skillName: undefined,
    category: undefined, status: undefined
  },
  form: {},
  rules: {
    // skillCode:新增时由后端兜底生成,UI 不展示、不校验;编辑时只读回显
    skillName: [{ required: true, message: '技能名称不能为空', trigger: 'blur' }],
    promptTemplate: [{ required: true, message: '提示词模板不能为空', trigger: 'blur' }]
  }
})
const { queryParams, form, rules } = toRefs(data)

function formatLen(s) {
  if (!s) return 0
  return s.length
}
function formatTime(s) {
  if (!s) return '—'
  return String(s).replace('T', ' ').slice(0, 16)
}
// 统计 {var} 占位符数量（用作运行时参数注入）
function placeholderCount(s) {
  if (!s) return 0
  const m = String(s).match(/\{[\w\u4e00-\u9fa5]+\}/g)
  return m ? m.length : 0
}
function truncate(s, n) {
  if (!s) return ''
  return s.length > n ? s.slice(0, n) + '…' : s
}

/* ==================== 列表 ==================== */

function getList() {
  loading.value = true
  listSkill(queryParams.value).then((res) => {
    skillList.value = res.rows
    total.value = res.total
    loading.value = false
  }).catch(() => { loading.value = false })
}

function handleQuery() {
  queryParams.value.pageNum = 1
  getList()
}

function resetQuery() {
  queryParams.value = { pageNum: 1, pageSize: queryParams.value.pageSize, skillCode: undefined, skillName: undefined, category: undefined, status: undefined }
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
  openEditor(copy, '编辑技能')
}

/* ==================== 编辑 ==================== */

const open = ref(false)
const title = ref('')
let formSnapshot = ''

function takeSnapshot() { formSnapshot = JSON.stringify(form.value) }
function isDirty() { return JSON.stringify(form.value) !== formSnapshot }

function reset() {
  form.value = {
    skillId: undefined,
    skillCode: undefined,
    skillName: undefined,
    category: undefined,
    description: undefined,
    promptTemplate: undefined,
    sort: 0,
    status: '0',
    remark: undefined
  }
  proxy.resetForm('skillRef')
}

function openEditor(payload, sheetTitle) {
  form.value = payload
  title.value = sheetTitle
  open.value = true
  nextTick(() => takeSnapshot())
}

function handleAdd() {
  reset()
  openEditor(form.value, '新增技能')
}

function handleUpdate(row) {
  reset()
  getSkill(row.skillId).then((res) => {
    openEditor(res.data, '编辑技能')
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
  proxy.$refs['skillRef'].validate((valid) => {
    if (!valid) return
    const req = form.value.skillId ? updateSkill(form.value) : addSkill(form.value)
    req.then(() => {
      proxy.$modal.msgSuccess(form.value.skillId ? '修改成功' : '新增成功')
      closeEditor()
      getList()
    })
  })
}

/* ==================== 删除 ==================== */

function handleDelete(row) {
  proxy.$modal.confirm(`确认删除技能「${row.skillName}」？删除后被智能体引用的会一并清除引用关系。`).then(() => {
    return delSkill(row.skillId)
  }).then(() => {
    proxy.$modal.msgSuccess('删除成功')
    if (detail.value.skillId === row.skillId) detailOpen.value = false
    getList()
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

.skill-page {
  font-family: $font; padding: 40px 48px; min-height: calc(100vh - 84px); -webkit-font-smoothing: antialiased;
  background:
    var(--ai-page-bg);
  @media (max-width: 768px) { padding: 24px 16px; }
}

/* Header */
.skill-header {
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
.skill-search { display: flex; align-items: center; gap: 10px; margin-bottom: 24px; flex-wrap: wrap;
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
.skill-select {
  height: 36px; padding: 0 28px 0 12px; border: none; border-radius: 8px; background: var(--ai-search-bg);
  box-shadow: 0 1px 3px var(--ai-border);
  font-size: 13px; font-family: $font; color: $text; appearance: none; cursor: pointer; outline: none;
  background-image: url("data:image/svg+xml,%3Csvg width='10' height='6' viewBox='0 0 10 6' fill='none' xmlns='http://www.w3.org/2000/svg'%3E%3Cpath d='M1 1l4 4 4-4' stroke='%238E8E93' stroke-width='1.5' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E");
  background-repeat: no-repeat; background-position: right 10px center;
}

/* 卡片网格 */
.skill-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(330px, 1fr)); gap: 16px; min-height: 180px; @media (max-width: 768px) { grid-template-columns: 1fr; } }
.skill-card {
  position: relative; display: flex; flex-direction: column; gap: 10px; padding: 16px 18px 14px;
  background: var(--ai-card-bg); border: 1px solid var(--ai-border); border-radius: 16px; cursor: pointer;
  box-shadow: 0 1px 2px var(--ai-fill-2); transition: all 0.28s $ease; overflow: hidden;
  &:hover { box-shadow: var(--ai-shadow-card); transform: translateY(-3px); border-color: var(--ai-input-bg);
    .skill-card__actions { opacity: 1; transform: translateY(0); } .skill-card__rail { opacity: 1; } }
  &:active { transform: translateY(-1px) scale(0.995); }
  &.is-off { background: var(--ai-card-off); .skill-card__name { color: $text2; } }
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
    &--empty { background: var(--ai-fill-2); color: $gray3; }
  }
  &__prop { display: inline-flex; align-items: baseline; gap: 4px; font-size: 12px;
    &-k { color: $gray; } b { font-weight: 600; color: $text; font-variant-numeric: tabular-nums; }
  }
  &__desc { font-size: 12.5px; color: $text2; margin: 0; line-height: 1.5; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
  &__prompt { font-family: $mono; font-size: 11.5px; line-height: 1.6; color: $text2; background: var(--ai-fill-1); padding: 9px 11px; border-radius: $radius-sm; margin: 0; max-height: 76px; overflow: hidden; white-space: pre-wrap; word-break: break-all; }
}
.skill-empty { grid-column: 1 / -1; text-align: center; padding: 72px 0; &__icon { font-size: 44px; margin-bottom: 14px; } &__text { font-size: 16px; color: $gray; margin: 0 0 18px; } }
.skill-pagination { margin-top: 28px; display: flex; justify-content: center; }

/* ==================== Sheet ==================== */
.sheet-overlay { position: fixed; inset: 0; z-index: 2000; background: var(--ai-overlay); backdrop-filter: blur(5px); display: flex; align-items: center; justify-content: center; padding: 32px; }
.sheet {
  width: 100%; max-width: 820px; height: min(760px, 88vh); background: var(--ai-card-bg);
  border-radius: 20px; box-shadow: var(--ai-shadow-sheet);
  display: flex; flex-direction: column; overflow: hidden;
  transition: max-width 0.3s $ease, height 0.3s $ease;
  &--wide { max-width: 1120px; height: min(880px, 94vh); }
  &__header { display: flex; align-items: center; justify-content: space-between; padding: 22px 28px 0; flex-shrink: 0; }
  &__title { font-size: 21px; font-weight: 700; color: $text; margin: 0; }
  &__close { width: 28px; height: 28px; border: none; border-radius: 50%; background: var(--ai-fill-3); color: $gray; font-size: 12px; cursor: pointer; display: flex; align-items: center; justify-content: center; flex-shrink: 0; &:hover { background: var(--ai-hover-strong); color: $text; } }
  &__body { flex: 1; min-height: 0; overflow-y: auto; padding: 18px 28px 24px;
    &--wide { padding: 18px 28px 24px; }
    &::-webkit-scrollbar { width: 5px; } &::-webkit-scrollbar-thumb { background: var(--ai-border-4); border-radius: 3px; } }
  &__footer { display: flex; justify-content: flex-end; gap: 10px; padding: 14px 28px 22px; border-top: 1px solid var(--ai-fill-3); flex-shrink: 0; }
}
.sheet-enter-active { transition: all 0.35s $spring; }
.sheet-leave-active { transition: all 0.2s ease-in; }
.sheet-enter-from { opacity: 0; .sheet { transform: scale(0.92) translateY(20px); opacity: 0; } }
.sheet-leave-to { opacity: 0; .sheet { transform: scale(0.96); opacity: 0; } }

/* ==================== Hero（无 avatar 变体） ==================== */
.hero {
  position: relative; flex-shrink: 0; padding: 24px 28px 0; color: #fff;
  &::after { content: ''; position: absolute; inset: 0; background: linear-gradient(180deg, var(--ai-fill-3), var(--ai-border-4)); pointer-events: none; }
  &--no-avatar { padding-top: 22px; }
  &__close {
    position: absolute; top: 16px; right: 16px; z-index: 2; width: 28px; height: 28px; border: none; border-radius: 50%;
    background: rgba(255,255,255,0.22); color: #fff; font-size: 12px; cursor: pointer;
    display: flex; align-items: center; justify-content: center; backdrop-filter: blur(6px);
    &:hover { background: rgba(255,255,255,0.34); }
  }
  &__body { position: relative; z-index: 1; display: flex; align-items: center; gap: 14px;
    &--solo { padding-top: 6px; }
  }
  &__text { min-width: 0; flex: 1; }
  &__name { font-size: 24px; font-weight: 700; margin: 0 0 6px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; text-shadow: 0 1px 3px var(--ai-border-4); letter-spacing: -0.3px; }
  &__sub { display: flex; align-items: center; gap: 9px; flex-wrap: wrap; }
  &__chip { font-size: 12px; font-weight: 600; padding: 2px 9px; border-radius: 980px; }
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
  &__title { display: flex; align-items: center; gap: 6px; font-size: 12px; font-weight: 600; color: $text; margin-bottom: 9px;
    &--with-meta { justify-content: space-between; }
  }
  &__meta { display: inline-flex; align-items: center; gap: 8px; }
  &__ph { font-size: 11px; color: $orange; font-weight: 500; }
  &__len { font-size: 11px; color: $gray; font-variant-numeric: tabular-nums; }
}
.detail-kv {
  margin: 0; display: flex; flex-direction: column; gap: 7px;
  &__row { display: flex; align-items: baseline; gap: 10px; min-width: 0; }
  dt { font-size: 12px; color: $gray; flex-shrink: 0; width: 50px; }
  dd { margin: 0; font-size: 12.5px; font-weight: 500; color: $text; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    &.is-missing { color: $gray3; } }
  &__mono { font-family: $mono; font-size: 12px; }
}
.detail-prompt { padding: 14px 16px; background: var(--ai-block-bg); border: 1px solid var(--ai-border-2); border-radius: $radius-sm; font-size: 13.5px; line-height: 1.7; color: $text;
  &--static { white-space: pre-wrap; }
}
.detail-hollow {
  display: flex; align-items: center; justify-content: center; min-height: 100px;
  border: 1px dashed var(--ai-border-4); border-radius: $radius-sm;
  background: var(--ai-fill-1); font-size: 13px; color: $gray3;
}
.prompt-block {
  font-family: $mono; font-size: 12.5px; line-height: 1.75; color: $text;
  background: #1d1d1f; color: #f5f5f7; padding: 16px 18px; border-radius: $radius-sm;
  margin: 0; max-height: 460px; overflow: auto;
  white-space: pre-wrap; word-break: break-word;
  &::-webkit-scrollbar { width: 5px; height: 5px; } &::-webkit-scrollbar-thumb { background: rgba(255,255,255,0.18); border-radius: 3px; }
}

/* ==================== 表单 ==================== */
.aform {
  :deep(.el-form-item) { margin-bottom: 14px; }
  :deep(.el-form-item__label) { font-size: 13px; font-weight: 500; color: $text2; padding-bottom: 4px; }
  :deep(.el-input__wrapper), :deep(.el-textarea__inner) { border-radius: $radius-sm; background: var(--ai-input-bg); box-shadow: 0 0 0 1px var(--ai-border-3) inset; transition: all 0.2s $ease;
    &:hover { box-shadow: 0 0 0 1px var(--ai-border-4) inset; }
    &.is-focus, &:focus { background: var(--ai-card-bg); box-shadow: 0 0 0 4px rgba(10,132,255,0.12), 0 0 0 1px $blue inset; } }
  :deep(.el-switch.is-checked .el-switch__core) { background-color: $green; border-color: $green; }
  &__group { background: var(--ai-fill-1); border-radius: $radius; padding: 16px 20px; margin-bottom: 12px; &--full { padding: 16px 20px; } &--toggles { padding: 6px 20px; } }
  &__row { display: flex; gap: 14px; @media (max-width: 600px) { flex-direction: column; gap: 0; } }
  &__item { flex: 1; }
  &__row--2 { display: grid; grid-template-columns: 1fr 1fr; gap: 0 14px; @media (max-width: 600px) { grid-template-columns: 1fr; } }
  &__group-label { font-size: 13px; font-weight: 600; color: $text; margin-bottom: 10px; display: flex; align-items: center; gap: 10px; }
  &__group-meta { margin-left: auto; display: inline-flex; align-items: center; gap: 8px; font-weight: 400; }
  &__group-ph { font-size: 11px; color: $orange; }
  &__group-len { font-size: 11px; color: $gray; font-variant-numeric: tabular-nums; }
}
.toggle-row { display: flex; align-items: center; justify-content: space-between; padding: 12px 0; gap: 16px;
  & + & { border-top: 1px solid var(--ai-border); }
  &__info { display: flex; flex-direction: column; gap: 2px; } &__label { font-size: 14px; font-weight: 500; color: $text; } &__hint { font-size: 12px; color: $gray; }
}
// 提示词 textarea：等宽字体
.prompt-textarea :deep(.el-textarea__inner) {
  font-family: $mono; font-size: 12.5px; line-height: 1.75;
}
</style>
