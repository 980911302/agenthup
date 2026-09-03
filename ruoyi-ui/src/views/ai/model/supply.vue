<template>
  <Teleport to="body">
    <Transition name="sheet">
      <div v-if="visible" class="sheet-overlay" @click.self="cancel">
        <div class="sheet" role="dialog" aria-modal="true" aria-label="渠道供应管理">
          <!-- 渐变 hero：用模型编码取色，与列表卡片的色条呼应 -->
          <div class="hero" :style="{ background: gradientOf(model?.modelCode || '', '') }">
            <button type="button" class="hero__close" aria-label="关闭" @click="cancel">✕</button>
            <div class="hero__body">
              <div class="hero__avatar">{{ modelIcon }}</div>
              <div class="hero__text">
                <h2 class="hero__name">{{ modelTitle }}</h2>
                <div class="hero__sub">
                  <span class="hero__code">{{ model?.modelCode }}</span>
                  <span class="hero__hint">渠道供应</span>
                </div>
              </div>
            </div>
            <div class="hero__stats">
              <div class="hero__stat"><b>{{ activeCount }}</b><span>启用中</span></div>
              <div class="hero__stat"><b>{{ supplyList.length }}</b><span>总渠道</span></div>
            </div>
          </div>

          <!-- 顶部操作条 -->
          <div class="supply-toolbar">
            <p class="supply-toolbar__hint">路由时按权重分流，失败按重试次数在该渠道内重试。点「添加渠道」新增，点列表项编辑。</p>
            <button type="button" class="apple-btn apple-btn--primary" @click="openAdd" v-hasPermi="['ai:model:edit']">
              <svg width="13" height="13" viewBox="0 0 14 14" fill="none"><path d="M7 1v12M1 7h12" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>
              添加渠道
            </button>
          </div>

          <div class="sheet__body" v-loading="loading">
            <!-- 渠道列表（只读卡片，点行编辑） -->
            <div v-if="supplyList.length" class="supply-list">
              <article
                v-for="row in supplyList"
                :key="row.id"
                class="supply-item"
                :class="{ 'is-off': row.status !== '0' }"
                :style="{ '--accent': colorOf(row.channelName || '') }"
                @click="openEdit(row)"
              >
                <span class="supply-item__rail"></span>
                <div class="supply-item__main">
                  <div class="supply-item__head">
                    <span class="supply-item__dot"></span>
                    <h4 class="supply-item__name" :title="row.channelName">{{ row.channelName }}</h4>
                    <span class="supply-item__status" :class="row.status === '0' ? 'is-on' : 'is-off'">
                      <i></i>{{ row.status === '0' ? '已启用' : '已停用' }}
                    </span>
                  </div>
                  <div class="supply-item__meta">
                    <span class="meta">
                      <span class="meta__k">调用标识</span>
                      <span class="meta__v" :title="row.modelName || model?.modelCode">{{ row.modelName || model?.modelCode }}</span>
                      <el-tooltip v-if="row.upstreamMissing" content="该模型已不在渠道的上游清单中，可能已下架">
                        <el-tag type="danger" size="small">上游已下架</el-tag>
                      </el-tooltip>
                    </span>
                    <span class="meta"><span class="meta__k">权重</span><span class="meta__v">{{ row.weight }}</span></span>
                    <span class="meta"><span class="meta__k">重试</span><span class="meta__v">{{ row.retryCount }}</span></span>
                    <span class="meta"><span class="meta__k">输入价</span><span class="meta__v">{{ formatPrice(row.inputPrice) }} 元/千tok</span></span>
                    <span class="meta"><span class="meta__k">输出价</span><span class="meta__v">{{ formatPrice(row.outputPrice) }} 元/千tok</span></span>
                  </div>
                </div>
                <div class="supply-item__actions" @click.stop>
                  <button type="button" class="supply-item__action" title="编辑" @click.stop="openEdit(row)" v-hasPermi="['ai:model:edit']">
                    <svg width="14" height="14" viewBox="0 0 16 16" fill="none"><path d="M11.5 2.5l2 2L5 13H3v-2l8.5-8.5z" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/></svg>
                  </button>
                  <button type="button" class="supply-item__action supply-item__action--danger" title="删除" @click.stop="handleDelete(row)" v-hasPermi="['ai:model:edit']">
                    <svg width="14" height="14" viewBox="0 0 16 16" fill="none"><path d="M3 4.5h10M6.5 2.5h3M4.5 4.5l.5 9h6l.5-9M6.5 7v4M9.5 7v4" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round"/></svg>
                  </button>
                </div>
              </article>
            </div>

            <!-- 空态 -->
            <div v-else-if="!loading" class="supply-empty">
              <div class="supply-empty__icon">🔌</div>
              <p class="supply-empty__text">还没有任何渠道供应</p>
              <button type="button" class="apple-btn apple-btn--outline" @click="openAdd" v-hasPermi="['ai:model:edit']">添加第一个渠道</button>
            </div>
          </div>

          <div class="sheet__footer">
            <button type="button" class="apple-btn apple-btn--ghost" @click="cancel">关闭</button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>

  <!-- ==================== 新增 / 编辑 sheet（独立浮层，列表保持可见） ==================== -->
  <Teleport to="body">
    <Transition name="sheet">
      <div v-if="editOpen" class="sheet-overlay sheet-overlay--edit" @click.self="cancelEdit">
        <div class="sheet sheet--edit" role="dialog" aria-modal="true" :aria-label="editTitle">
          <div class="sheet__header">
            <h2 class="sheet__title">{{ editTitle }}</h2>
            <button type="button" class="sheet__close" aria-label="关闭" @click="cancelEdit">✕</button>
          </div>

          <div class="sheet__body">
            <el-form ref="formRef" :model="form" :rules="rules" label-position="top" class="aform" @submit.prevent="submitForm">
              <!-- 基本信息 -->
              <div class="aform__group">
                <div class="aform__group-label">基本信息</div>
                <el-form-item label="上游渠道" prop="channelId">
                  <!-- 编辑态：渠道锁定不可改（身份字段，要换就删了重加） -->
                  <el-input v-if="isEdit" :model-value="form.channelName" disabled />
                  <el-select v-else v-model="form.channelId" placeholder="仅显示清单中有当前模型、且尚未绑定的渠道" filterable style="width: 100%" :loading="candidateLoading" @change="onChannelChange">
                    <el-option v-for="c in candidateList" :key="c.channelId" :label="`${c.channelName} (${c.channelType})`" :value="c.channelId" />
                  </el-select>
                  <div v-if="!isEdit && !candidateLoading && !candidateList.length" class="candidate-empty">
                    没有可添加的渠道：其余渠道已绑定，或清单里没有当前模型。
                    <el-button link type="primary" @click="goChannel">去渠道管理</el-button>
                  </div>
                  <div v-if="!isEdit && excludedList.length" class="candidate-excluded">
                    <div class="candidate-excluded__title">以下渠道未进入下拉</div>
                    <div v-for="e in excludedList" :key="e.channelId || e.channelName" class="candidate-excluded__row">
                      <span class="candidate-excluded__name">{{ e.channelName }}</span>
                      <span class="candidate-excluded__reason">{{ e.reason }}</span>
                      <el-button v-if="e.reasonType === 'not_synced'" link type="primary" @click="goChannel">去同步</el-button>
                    </div>
                  </div>
                </el-form-item>
                <el-form-item label="渠道模型" prop="modelName">
                  <el-select v-model="form.modelName" placeholder="选择该渠道模型清单中的模型" filterable style="width: 100%" :loading="modelLoading" :disabled="!form.channelId">
                    <el-option v-for="m in modelOptions" :key="m.upstreamModelId" :label="m.displayName && m.displayName !== m.upstreamModelId ? `${m.displayName} (${m.upstreamModelId})` : m.upstreamModelId" :value="m.upstreamModelId" />
                  </el-select>
                </el-form-item>
              </div>

              <!-- 路由配置 -->
              <div class="aform__group">
                <div class="aform__group-label">路由配置</div>
                <div class="aform__row aform__row--2">
                  <el-form-item label="路由权重" prop="weight" class="aform__item">
                    <el-input-number v-model="form.weight" :min="1" :max="100" controls-position="right" style="width: 100%" />
                  </el-form-item>
                  <el-form-item label="重试次数" prop="retryCount" class="aform__item">
                    <el-input-number v-model="form.retryCount" :min="0" :max="10" controls-position="right" style="width: 100%" />
                  </el-form-item>
                </div>
              </div>

              <!-- 计费 -->
              <div class="aform__group">
                <div class="aform__group-label">计费（元/千 tokens）</div>
                <div class="aform__row aform__row--2">
                  <el-form-item label="输入价" prop="inputPrice" class="aform__item">
                    <el-input-number v-model="form.inputPrice" :min="0" :precision="4" :step="0.001" controls-position="right" style="width: 100%" />
                  </el-form-item>
                  <el-form-item label="输出价" prop="outputPrice" class="aform__item">
                    <el-input-number v-model="form.outputPrice" :min="0" :precision="4" :step="0.001" controls-position="right" style="width: 100%" />
                  </el-form-item>
                </div>
              </div>

              <!-- 状态 + 备注 -->
              <div class="aform__group aform__group--toggles">
                <div class="toggle-row">
                  <div class="toggle-row__info">
                    <span class="toggle-row__label">启用状态</span>
                    <span class="toggle-row__hint">停用后该供应不参与路由</span>
                  </div>
                  <el-switch v-model="form.status" active-value="0" inactive-value="1" />
                </div>
              </div>
              <div class="aform__group">
                <el-form-item label="备注">
                  <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="可选备注信息" />
                </el-form-item>
              </div>
            </el-form>
          </div>

          <div class="sheet__footer">
            <button type="button" class="apple-btn apple-btn--ghost" @click="cancelEdit">取消</button>
            <button type="button" class="apple-btn apple-btn--primary" :disabled="submitting" @click="submitForm">
              {{ submitting ? '保存中…' : '保存' }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup name="AiModelSupply">
import { listModelSupply, listSupplyCandidates, addModelSupply, updateModelSupply, delModelSupply } from '@/api/ai/model'
import { listUpstreamModel } from '@/api/ai/upstreamModel'
import { gradientOf, colorOf } from '@/utils/ai-palette'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  model: { type: Object, default: () => ({}) }
})
const emit = defineEmits(['update:modelValue', 'changed'])

const { proxy } = getCurrentInstance()
const { sys_normal_disable } = proxy.useDict('sys_normal_disable')

const modelTitle = computed(() => props.model?.displayName || props.model?.modelCode || '模型')
const modelIcon = computed(() => {
  const map = { CHAT: '💬', EMBEDDING: '🧬', RERANK: '🔀', IMAGE: '🖼️', VIDEO: '🎬', TTS: '🔊', STT: '🎙️', MODERATION: '🛡️' }
  return map[props.model?.modelType] || '🤖'
})

const loading = ref(false)
const supplyList = ref([])
const candidateList = ref([])
const excludedList = ref([])
const modelOptions = ref([])
const candidateLoading = ref(false)
const modelLoading = ref(false)
const submitting = ref(false)

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const activeCount = computed(() => supplyList.value.filter(s => s.status === '0').length)

function formatPrice(v) {
  if (v == null || v === '') return '-'
  return Number(v).toFixed(4)
}

watch(visible, (open) => {
  if (open && props.model?.modelId) loadSupplyList()
  if (!open) closeEdit()
})

function loadSupplyList() {
  if (!props.model?.modelId) return
  loading.value = true
  listModelSupply(props.model.modelId).then((res) => {
    supplyList.value = res.data || []
  }).finally(() => { loading.value = false })
}

/* ==================== 编辑 sheet 状态机 ==================== */

const editOpen = ref(false)
const isEdit = ref(false)
const editTitle = ref('')
let formSnapshot = ''

const data = reactive({
  form: {},
  rules: {
    channelId: [{ required: true, message: '请选择上游渠道', trigger: 'change' }],
    modelName: [{ required: true, message: '请选择渠道模型', trigger: 'change' }],
    weight: [{ required: true, message: '权重不能为空', trigger: 'blur' }],
    retryCount: [{ required: true, message: '重试次数不能为空', trigger: 'blur' }]
  }
})
const { form, rules } = toRefs(data)

function takeSnapshot() { formSnapshot = JSON.stringify(form.value) }
function isDirty() { return JSON.stringify(form.value) !== formSnapshot }

function makeAddForm() {
  form.value = {
    id: undefined,
    modelId: props.model.modelId,
    channelId: undefined,
    channelName: undefined,
    modelName: undefined,
    weight: 1,
    retryCount: 0,
    inputPrice: undefined,
    outputPrice: undefined,
    status: '0',
    remark: undefined
  }
}

function makeEditForm(row) {
  form.value = {
    id: row.id,
    modelId: row.modelId,
    channelId: row.channelId,
    channelName: row.channelName,
    modelName: row.modelName,
    weight: row.weight == null ? 1 : row.weight,
    retryCount: row.retryCount == null ? 0 : row.retryCount,
    inputPrice: row.inputPrice,
    outputPrice: row.outputPrice,
    status: row.status || '0',
    remark: row.remark
  }
}

function resetForm() {
  form.value = {}
  // 表单由 v-if(editOpen) 控制挂载，关闭时正在卸载；
  // 推迟到 nextTick，卸载完成后 $refs.formRef 被清空，ruoyi 判空会安全跳过（避免 resetFields 报错）
  nextTick(() => proxy.resetForm('formRef'))
}

async function openAdd() {
  if (editOpen.value && isDirty()) {
    try {
      await proxy.$modal.confirm('有未保存的修改，切换将丢失。确定吗？')
    } catch { return }
  }
  makeAddForm()
  isEdit.value = false
  editTitle.value = '添加渠道供应'
  candidateList.value = []
  excludedList.value = []
  modelOptions.value = []
  editOpen.value = true
  candidateLoading.value = true
  listSupplyCandidates(props.model.modelId).then((res) => {
    const data = res.data || {}
    if (Array.isArray(data)) {
      candidateList.value = data
      excludedList.value = []
    } else {
      candidateList.value = data.candidates || []
      excludedList.value = data.excluded || []
    }
    if (!candidateList.value.length) {
      const tip = excludedList.value.length
        ? '暂无可用渠道：见下方排除原因'
        : '暂无可用渠道：要么都已绑定，要么没有启用中的渠道'
      proxy.$modal.msgWarning(tip)
    }
  }).catch(() => {
    candidateList.value = []
    excludedList.value = []
  }).finally(() => { candidateLoading.value = false })
  nextTick(() => takeSnapshot())
}

function openEdit(row) {
  // 已有编辑 sheet 打开且脏 -> 先确认
  if (editOpen.value && isDirty()) {
    proxy.$modal.confirm('有未保存的修改，切换将丢失。确定吗？')
      .then(() => doOpenEdit(row))
      .catch(() => {})
    return
  }
  doOpenEdit(row)
}

function doOpenEdit(row) {
  makeEditForm(row)
  isEdit.value = true
  editTitle.value = '编辑 ' + row.channelName
  editOpen.value = true
  loadChannelModels(row.channelId, row.modelName)
  nextTick(() => takeSnapshot())
}

function cancelEdit() {
  if (isDirty()) {
    proxy.$modal.confirm('有未保存的修改，关闭后将丢失。确定关闭吗？')
      .then(closeEdit)
      .catch(() => {})
    return
  }
  closeEdit()
}

function closeEdit() {
  editOpen.value = false
  formSnapshot = ''
  resetForm()
}

function onChannelChange(channelId) {
  if (form.value.id) return
  const c = candidateList.value.find(x => Number(x.channelId) === Number(channelId))
  if (c) {
    form.value.channelName = c.channelName
  }
  form.value.modelName = props.model?.modelCode
  modelOptions.value = []
  if (channelId) {
    loadChannelModels(channelId, props.model?.modelCode)
  }
}

function loadChannelModels(channelId, currentModelName) {
  modelLoading.value = true
  listUpstreamModel({ channelId, pageNum: 1, pageSize: 10000 }).then(res => {
    modelOptions.value = res.rows || []
    if (currentModelName && !modelOptions.value.some(item => item.upstreamModelId === currentModelName)) {
      modelOptions.value.unshift({ upstreamModelId: currentModelName, displayName: `${currentModelName}（清单中已不存在）` })
    }
    if (!form.value.id && currentModelName) {
      form.value.modelName = currentModelName
    }
  }).finally(() => { modelLoading.value = false })
}

function submitForm() {
  proxy.$refs['formRef'].validate((valid) => {
    if (!valid) return
    submitting.value = true
    const req = isEdit.value
      ? updateModelSupply(form.value)
      : addModelSupply(props.model.modelId, form.value)
    req.then(() => {
      proxy.$modal.msgSuccess(isEdit.value ? '修改成功' : '添加成功')
      closeEdit()
      loadSupplyList()
      emit('changed')
    }).catch(() => {
      // 后端已统一弹错（如"已接入该渠道"）；此处仅复位按钮，编辑 sheet 保留供修正
    }).finally(() => { submitting.value = false })
  })
}

function handleDelete(row) {
  proxy.$modal.confirm(`确认移除渠道「${row.channelName}」的供应？`).then(() => {
    return delModelSupply(row.id)
  }).then(() => {
    proxy.$modal.msgSuccess('删除成功')
    loadSupplyList()
    emit('changed')
  }).catch(() => {})
}

/* ==================== 关闭主弹窗 ==================== */

function cancel() {
  // 主弹窗关闭前，若有未保存的编辑先确认
  if (editOpen.value && isDirty()) {
    proxy.$modal.confirm('有未保存的修改，关闭将丢失。确定吗？')
      .then(() => { visible.value = false })
      .catch(() => {})
    return
  }
  visible.value = false
}

function goChannel() {
  visible.value = false
  closeEdit()
  proxy.$router.push('/ai/model/channel')
}

/* ==================== 键盘交互 ==================== */

function onKeydown(e) {
  if (e.key === 'Escape') {
    if (editOpen.value) cancelEdit()
    else if (visible.value) cancel()
  }
}

onMounted(() => document.addEventListener('keydown', onKeydown))
onUnmounted(() => document.removeEventListener('keydown', onKeydown))
</script>

<style scoped lang="scss">
@use '../../../assets/styles/ai-tokens.scss' as *;

// 设计令牌见 @/assets/styles/ai-tokens.scss + ai-theme.scss（支持暗色）
$spring: cubic-bezier(0.34, 1.56, 0.64, 1);

/* 必须与 Element Plus 初始 z-index(2000) 对齐。第二层若抬到 2100，el-select 下拉(约 2000+) 会沉到遮罩后面。 */
.sheet-overlay { position: fixed; inset: 0; z-index: 2000; background: var(--ai-overlay); backdrop-filter: blur(5px); display: flex; align-items: center; justify-content: center; padding: 32px; }
.sheet {
  width: 100%; max-width: 880px; height: min(720px, 88vh); background: var(--ai-card-bg);
  border-radius: 20px; box-shadow: var(--ai-shadow-sheet);
  display: flex; flex-direction: column; overflow: hidden;
  &--edit { max-width: 560px; height: min(760px, 90vh); }
  &__header { display: flex; align-items: center; justify-content: space-between; padding: 22px 28px 0; flex-shrink: 0; }
  &__title { font-size: 21px; font-weight: 700; color: $text; margin: 0; }
  &__close { width: 28px; height: 28px; border: none; border-radius: 50%; background: var(--ai-fill-3); color: $gray; font-size: 12px; cursor: pointer; display: flex; align-items: center; justify-content: center; flex-shrink: 0; &:hover { background: var(--ai-hover-strong); color: $text; } }
  &__body { flex: 1; min-height: 0; overflow-y: auto; padding: 16px 28px 20px;
    &::-webkit-scrollbar { width: 5px; } &::-webkit-scrollbar-thumb { background: var(--ai-border-4); border-radius: 3px; } }
  &__footer { display: flex; justify-content: flex-end; gap: 10px; padding: 14px 28px 22px; border-top: 1px solid var(--ai-fill-3); flex-shrink: 0; }
}
.sheet-enter-active { transition: all 0.35s $spring; }
.sheet-leave-active { transition: all 0.2s ease-in; }
.sheet-enter-from { opacity: 0; .sheet { transform: scale(0.92) translateY(20px); opacity: 0; } }
.sheet-leave-to { opacity: 0; .sheet { transform: scale(0.96); opacity: 0; } }

.apple-btn {
  display: inline-flex; align-items: center; gap: 6px; font-family: $font; font-size: 14px; font-weight: 500;
  border: none; border-radius: 980px; padding: 8px 18px; cursor: pointer; transition: all 0.2s $ease; outline: none;
  &:active { transform: scale(0.96); }
  &--primary { background: $blue; color: #fff; padding: 10px 24px; box-shadow: 0 2px 10px rgba(10,132,255,0.32); &:hover { background: #0071e3; } &:disabled { opacity: 0.5; cursor: not-allowed; } }
  &--ghost { background: transparent; color: $blue; padding: 10px 16px; &:hover { background: rgba(10,132,255,0.08); } }
  &--outline { background: transparent; color: $blue; border: 1.5px solid rgba(10,132,255,0.35); padding: 7px 16px; &:hover { background: rgba(10,132,255,0.06); border-color: $blue; } }
}

/* hero 顶部 */
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
    font-size: 26px; background: rgba(255,255,255,0.24); backdrop-filter: blur(10px); border: 1px solid rgba(255,255,255,0.3);
  }
  &__text { min-width: 0; }
  &__name { font-size: 22px; font-weight: 700; margin: 0 0 6px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; text-shadow: 0 1px 3px var(--ai-border-4); }
  &__sub { display: flex; align-items: center; gap: 9px; flex-wrap: wrap; }
  &__code { font-family: $mono; font-size: 11px; background: rgba(255,255,255,0.2); padding: 2px 7px; border-radius: 5px; }
  &__hint { font-size: 12px; opacity: 0.85; }
  &__stats {
    position: relative; z-index: 1; display: flex; gap: 26px; margin-top: 18px;
    padding: 12px 2px; border-top: 1px solid rgba(255,255,255,0.22);
  }
  &__stat { display: flex; align-items: baseline; gap: 5px; b { font-size: 18px; font-weight: 700; font-variant-numeric: tabular-nums; } span { font-size: 12px; opacity: 0.82; } }
}

/* 工具栏 */
.supply-toolbar { display: flex; align-items: center; justify-content: space-between; padding: 14px 28px; flex-shrink: 0; border-bottom: 1px solid var(--ai-border); gap: 12px; flex-wrap: wrap;
  &__hint { font-size: 12.5px; color: $text2; margin: 0; line-height: 1.5; } }

/* 列表行 */
.supply-list { display: flex; flex-direction: column; gap: 8px; padding-top: 12px; }
.supply-item {
  position: relative; display: flex; align-items: center; gap: 12px; padding: 12px 14px 12px 16px;
  background: var(--ai-card-bg); border: 1px solid var(--ai-fill-3); border-radius: 13px;
  transition: all 0.2s $ease; overflow: hidden; cursor: pointer;
  &:hover { box-shadow: 0 6px 18px var(--ai-fill-4); border-color: var(--ai-fill-2);
    .supply-item__actions { opacity: 1; transform: translateX(0); } .supply-item__rail { opacity: 1; } }
  &.is-off { background: var(--ai-card-off); .supply-item__name { color: $text2; } }
  &__rail { position: absolute; left: 0; top: 0; bottom: 0; width: 3px; background: var(--accent); opacity: 0; transition: opacity 0.25s $ease; }
  &__main { flex: 1; min-width: 0; }
  &__head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 6px; }
  &__dot { width: 8px; height: 8px; border-radius: 50%; background: var(--accent); flex-shrink: 0; box-shadow: 0 0 0 2.5px color-mix(in srgb, var(--accent) 18%, transparent); }
  &__name { font-size: 14px; font-weight: 600; color: $text; margin: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  &__status { display: inline-flex; align-items: center; gap: 4px; font-size: 11px; margin-left: auto;
    i { width: 6px; height: 6px; border-radius: 50%; display: inline-block; }
    &.is-on { color: #248A3D; i { background: $green; box-shadow: 0 0 0 2.5px rgba(52,199,89,0.18); } }
    &.is-off { color: $gray; i { background: $gray2; } }
  }
  &__meta { display: flex; flex-wrap: wrap; gap: 4px 14px; }
  &__actions { display: flex; gap: 4px; opacity: 0; transform: translateX(4px); transition: all 0.2s $ease; flex-shrink: 0; }
  &__action {
    width: 28px; height: 28px; border: none; border-radius: 8px; background: var(--ai-border); color: $text2;
    cursor: pointer; display: flex; align-items: center; justify-content: center; transition: all 0.18s;
    &:hover { background: rgba(10,132,255,0.12); color: $blue; }
    &--danger:hover { background: rgba(255,59,48,0.12); color: $red; }
  }
}

.meta { display: inline-flex; align-items: baseline; gap: 5px; font-size: 12px;
  &__k { color: $gray; }
  &__v { color: $text; font-weight: 500; max-width: 180px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-variant-numeric: tabular-nums; }
}

.supply-empty { text-align: center; padding: 48px 0; &__icon { font-size: 44px; margin-bottom: 14px; } &__text { font-size: 15px; color: $gray; margin: 0 0 18px; } }

/* aform 表单覆写 */
.aform {
  :deep(.el-form-item) { margin-bottom: 14px; }
  :deep(.el-form-item__label) { font-size: 13px; font-weight: 500; color: $text2; padding-bottom: 4px; }
  :deep(.el-input__wrapper), :deep(.el-textarea__inner) { border-radius: $radius-sm; background: var(--ai-input-bg); box-shadow: 0 0 0 1px var(--ai-border-3) inset; transition: all 0.2s $ease;
    &:hover { box-shadow: 0 0 0 1px var(--ai-border-4) inset; } &.is-focus, &:focus { background: var(--ai-card-bg); box-shadow: 0 0 0 4px rgba(10,132,255,0.12), 0 0 0 1px $blue inset; } }
  :deep(.el-input.is-disabled .el-input__wrapper) { background: var(--ai-fill-2); }
  :deep(.el-switch.is-checked .el-switch__core) { background-color: $green; border-color: $green; }
  &__group { background: var(--ai-fill-1); border-radius: $radius; padding: 16px 20px; margin-bottom: 12px; &--toggles { padding: 6px 20px; } }
  &__group-label { font-size: 12px; font-weight: 600; color: $text; margin-bottom: 10px; }
  &__row { display: flex; gap: 14px; @media (max-width: 600px) { flex-direction: column; gap: 0; }
    &--2 { display: grid; grid-template-columns: 1fr 1fr; gap: 0 14px; @media (max-width: 600px) { grid-template-columns: 1fr; } } }
  &__item { min-width: 0; }
}
.toggle-row { display: flex; align-items: center; justify-content: space-between; padding: 12px 0; gap: 16px;
  & + & { border-top: 1px solid var(--ai-border); }
  &__info { display: flex; flex-direction: column; gap: 2px; } &__label { font-size: 14px; font-weight: 500; color: $text; } &__hint { font-size: 12px; color: $gray; }
}

/* 渠道模型清单为空时给出可操作提示 */
.candidate-empty {
  margin-top: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--ai-fill-2);
  font-size: 12px;
  line-height: 1.45;
  color: $gray;
}

.candidate-excluded {
  margin-top: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--ai-fill-2);
  font-size: 12px;
  line-height: 1.45;
  &__title { color: $gray; margin-bottom: 4px; font-weight: 500; }
  &__row { display: flex; flex-wrap: wrap; gap: 4px 8px; color: $text2; & + & { margin-top: 2px; } }
  &__name { font-weight: 500; color: $text; }
  &__reason { color: $gray; }
}
</style>
