<template>
  <div class="engine-page" v-loading="loading">
    <div v-if="!allowed && !loading" class="engine-deny">
      <p>仅平台管理员可修改全局设置</p>
    </div>

    <template v-else-if="allowed">
      <!-- 当前状态：一句话，不堆指标 -->
      <div class="engine-status" :class="{ 'is-warn': !form.embeddingModel || !publishedLabel }">
        <span class="engine-status__dot" :class="statusDotClass" />
        <div class="engine-status__text">
          <template v-if="!form.embeddingModel">
            还没选向量模型，新建知识库将无法处理文档
          </template>
          <template v-else-if="publishedLabel">
            新建库将使用 <b>{{ publishedLabel }}</b>
            <span v-if="upgradeCount > 0" class="engine-status__extra">
              · {{ upgradeCount }} 个已有库可升级（需在各库设置里手动升级）
            </span>
          </template>
          <template v-else>
            配置尚未生效，请保存后点「生效」
          </template>
        </div>
      </div>

      <div class="engine-form">
        <!-- 模型 -->
        <section class="engine-block">
          <h3 class="engine-block__title">模型</h3>
          <p class="engine-block__desc">业务用户不用选模型，统一用这里的配置。</p>
          <el-form label-position="top">
            <el-form-item label="向量模型" required>
              <el-select
                v-model="form.embeddingModel"
                filterable
                clearable
                style="width:100%"
                placeholder="用于文档检索的嵌入模型"
              >
                <el-option
                  v-for="m in embeddingOptions"
                  :key="m.modelCode"
                  :label="m.displayName || m.modelCode"
                  :value="m.modelCode"
                />
                <el-option
                  v-if="missingEmbedding"
                  :label="(form.embeddingModel || '') + '（已停用）'"
                  :value="form.embeddingModel"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="图谱抽取模型">
              <el-select
                v-model="form.extractModel"
                filterable
                clearable
                style="width:100%"
                placeholder="用于实体/关系抽取（可选）"
              >
                <el-option
                  v-for="m in chatOptions"
                  :key="m.modelCode"
                  :label="m.displayName || m.modelCode"
                  :value="m.modelCode"
                />
                <el-option
                  v-if="missingExtract"
                  :label="(form.extractModel || '') + '（已停用）'"
                  :value="form.extractModel"
                />
              </el-select>
            </el-form-item>
          </el-form>
        </section>

        <!-- 文档处理 -->
        <section class="engine-block">
          <h3 class="engine-block__title">文档怎么切</h3>
          <p class="engine-block__desc">本地算法，不调用大模型。一般保持默认即可。</p>
          <el-form label-position="top">
            <el-form-item label="切分方式">
              <el-select v-model="form.chunkStrategy" style="width:100%">
                <el-option label="按章节段落（推荐）" value="P" />
                <el-option label="固定长度" value="F" />
              </el-select>
            </el-form-item>
            <div class="engine-block__row">
              <el-form-item label="块大小" style="flex:1">
                <el-input-number
                  v-model="form.chunkSize"
                  :min="100"
                  :max="4000"
                  :step="50"
                  controls-position="right"
                  style="width:100%"
                />
              </el-form-item>
              <el-form-item label="重叠" style="flex:1">
                <el-input-number
                  v-model="form.chunkOverlap"
                  :min="0"
                  :max="500"
                  :step="10"
                  controls-position="right"
                  style="width:100%"
                />
              </el-form-item>
            </div>
          </el-form>
        </section>

        <!-- 图谱 -->
        <section class="engine-block">
          <h3 class="engine-block__title">知识图谱</h3>
          <div class="engine-switch-row">
            <div>
              <div class="engine-switch-row__label">新建库默认构建图谱</div>
              <div class="engine-switch-row__hint">开启后新库会抽实体与关系；已有库不受影响</div>
            </div>
            <el-switch v-model="form.graphEnabled" active-value="1" inactive-value="0" />
          </div>
        </section>
      </div>

      <!-- 检查结果：仅失败时显眼 -->
      <div v-if="checkReport && !checkReport.passed" class="engine-check">
        <div class="engine-check__title">生效前未通过</div>
        <ul>
          <li v-for="c in failChecks" :key="c.id">{{ c.message }}</li>
        </ul>
      </div>

      <!-- 操作：产品语言，不暴露草稿/观测 -->
      <footer class="engine-footer">
        <p class="engine-footer__hint">
          「生效」只影响<strong>之后新建</strong>的知识库；已有库不会自动重建。
        </p>
        <div class="engine-footer__actions">
          <button type="button" class="apple-btn" :disabled="saving" @click="save">
            {{ saving ? '保存中…' : '暂存' }}
          </button>
          <button type="button" class="apple-btn apple-btn--primary" :disabled="publishing || checking" @click="doPublish">
            {{ publishing || checking ? '处理中…' : '生效' }}
          </button>
        </div>
      </footer>

      <!-- 高级：折叠，默认收起 -->
      <details class="engine-advanced" v-if="versionStatus || ops">
        <summary>高级信息</summary>
        <div class="engine-advanced__body">
          <p v-if="publishedLabel">
            当前已发布：{{ publishedLabel }}
            <span v-if="versionStatus?.published?.publishedAt" class="muted">
              · {{ formatTime(versionStatus.published.publishedAt) }}
            </span>
          </p>
          <p v-if="upgradeCount > 0">待升级知识库 {{ upgradeCount }} 个 · 升级任务运行中 {{ runningJobs }}</p>
          <p v-if="ops">
            图谱库 {{ ops.dependencies?.neo4jAvailable ? '正常' : '未启用/降级' }}
            · 检索 {{ ops.searchMetrics?.total ?? '—' }} 次
            · 失败 {{ ops.searchMetrics?.failed ?? '—' }}
          </p>
          <p class="muted">版本历史、指纹、降级率等运维细节请通过接口或后台任务查看，日常不必关注。</p>
        </div>
      </details>
    </template>
  </div>
</template>

<script setup name="AiKbEngine">
import { getKbEngine, saveKbEngine, precheckEngine, publishEngine, getEngineOps } from '@/api/ai/kb'
import { listModel } from '@/api/ai/model'

const { proxy } = getCurrentInstance()
const router = useRouter()
const allowed = ref(false)
const loading = ref(false)
const saving = ref(false)
const checking = ref(false)
const publishing = ref(false)
const embeddingOptions = ref([])
const chatOptions = ref([])
const versionStatus = ref(null)
const checkReport = ref(null)
const ops = ref(null)
const form = reactive({
  embeddingModel: '',
  extractModel: '',
  chunkStrategy: 'P',
  chunkSize: 800,
  chunkOverlap: 100,
  graphEnabled: '0'
})

const missingEmbedding = computed(() =>
  !!form.embeddingModel && !embeddingOptions.value.some(m => m.modelCode === form.embeddingModel))
const missingExtract = computed(() =>
  !!form.extractModel && !chatOptions.value.some(m => m.modelCode === form.extractModel))

const publishedLabel = computed(() => {
  const p = versionStatus.value?.published
  if (!p) return ''
  const no = p.versionNo != null ? `v${p.versionNo}` : ''
  const label = p.versionLabel ? ` ${p.versionLabel}` : ''
  return (no + label).trim() || '已发布版本'
})

const upgradeCount = computed(() => {
  const n = versionStatus.value?.upgradeCandidates?.length
  if (n != null) return n
  return ops.value?.policy?.upgradeCandidateCount ?? 0
})

const runningJobs = computed(() =>
  versionStatus.value?.runningJobs ?? ops.value?.policy?.runningJobs ?? 0)

const failChecks = computed(() =>
  (checkReport.value?.checks || []).filter(c => !c.ok))

const statusDotClass = computed(() => {
  if (!form.embeddingModel) return 'is-warn'
  if (!publishedLabel.value) return 'is-warn'
  if (upgradeCount.value > 0) return 'is-info'
  return 'is-ok'
})

function formatTime(s) {
  if (!s) return '—'
  return String(s).replace('T', ' ').slice(0, 16)
}

function loadOps() {
  getEngineOps().then(res => {
    ops.value = res.data || null
  }).catch(() => { ops.value = null })
}

function loadModels() {
  return listModel({ pageNum: 1, pageSize: 999 }).then(res => {
    const rows = res.rows || []
    embeddingOptions.value = rows.filter(m => m.modelType === 'EMBEDDING')
    chatOptions.value = rows.filter(m => m.modelType !== 'EMBEDDING' && m.modelType !== 'IMAGE' && m.modelType !== 'VIDEO' && m.modelType !== 'TTS')
  })
}

function load() {
  loading.value = true
  getKbEngine()
    .then((res) => {
      allowed.value = true
      const d = res.data || {}
      form.embeddingModel = d.embeddingModel || ''
      form.extractModel = d.extractModel || ''
      form.chunkStrategy = d.chunkStrategy || 'P'
      form.chunkSize = Number(d.chunkSize) || 800
      form.chunkOverlap = Number(d.chunkOverlap) || 100
      form.graphEnabled = d.graphEnabled === '1' ? '1' : '0'
      versionStatus.value = d.versionStatus || null
      return loadModels()
    })
    .then(() => {
      if (allowed.value) loadOps()
    })
    .catch((err) => {
      allowed.value = false
      const code = err?.code
      const msg = String(err?.msg || err?.message || '')
      if (code === 403 || msg.includes('平台管理员') || msg.includes('无权') || msg.includes('权限')) {
        proxy.$modal.msgError('仅平台管理员可访问全局设置')
        router.replace('/ai/kb').catch(() => {})
      }
    })
    .finally(() => { loading.value = false })
}

function validateForm() {
  if (form.chunkOverlap >= form.chunkSize) {
    proxy.$modal.msgWarning('重叠必须小于块大小')
    return false
  }
  if (!form.embeddingModel) {
    proxy.$modal.msgWarning('请选择向量模型')
    return false
  }
  return true
}

function save() {
  if (!validateForm()) return
  saving.value = true
  saveKbEngine({ ...form })
    .then(() => proxy.$modal.msgSuccess('已暂存，尚未对新建库生效'))
    .finally(() => { saving.value = false })
}

/** 生效 = 自动检查 + 发布，省掉单独「发布前检查」按钮 */
async function doPublish() {
  if (!validateForm()) return
  try {
    await proxy.$modal.confirm(
      '确认让之后新建的知识库使用当前配置？\n已有知识库不会自动重建，需在库设置中手动升级。'
    )
  } catch {
    return
  }

  checking.value = true
  checkReport.value = null
  try {
    const pre = await precheckEngine({ ...form })
    checkReport.value = pre.data || {}
    if (checkReport.value.passed === false) {
      proxy.$modal.msgWarning('配置检查未通过，请先处理下方问题')
      return
    }
  } catch {
    // 检查接口失败时仍允许尝试发布（后端会再校验）
  } finally {
    checking.value = false
  }

  publishing.value = true
  try {
    // 先落盘草稿，再发布，避免只改了 UI 未保存
    await saveKbEngine({ ...form })
    const res = await publishEngine({ ...form })
    const v = res.data || {}
    proxy.$modal.msgSuccess(v.versionNo != null ? `已生效 v${v.versionNo}` : '已生效')
    load()
  } catch {
    /* request 层已提示 */
  } finally {
    publishing.value = false
  }
}

onMounted(load)
</script>

<style scoped lang="scss">
@use '../../../assets/styles/ai-tokens.scss' as *;

.engine-page {
  max-width: 100%;
  padding: 0 0 4px;
  font-family: $font;
  color: $text;
}

.engine-deny {
  text-align: center;
  padding: 40px 16px;
  color: $gray;
  p { margin: 0; font-size: 14px; }
}

.engine-status {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px 14px;
  margin-bottom: 16px;
  border-radius: 14px;
  background: rgba(52, 199, 89, 0.08);
  border: 1px solid rgba(52, 199, 89, 0.16);
  &.is-warn {
    background: rgba(255, 159, 10, 0.1);
    border-color: rgba(255, 159, 10, 0.2);
  }
  &__dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    margin-top: 5px;
    flex-shrink: 0;
    background: $green;
    &.is-warn { background: $orange; }
    &.is-info { background: $blue; }
    &.is-ok { background: $green; }
  }
  &__text {
    font-size: 13px;
    line-height: 1.5;
    color: $text2;
    b { color: $text; font-weight: 650; }
  }
  &__extra { color: $text2; }
}

.engine-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.engine-block {
  border: 1px solid var(--ai-border);
  border-radius: 16px;
  background: var(--ai-card-bg);
  padding: 16px 18px;
  box-shadow: 0 1px 2px var(--ai-fill-2);

  &__title {
    margin: 0 0 4px;
    font-size: 15px;
    font-weight: 700;
    letter-spacing: -0.2px;
    color: $text;
  }
  &__desc {
    margin: 0 0 14px;
    font-size: 12.5px;
    color: $text2;
    line-height: 1.45;
  }
  &__row {
    display: flex;
    gap: 12px;
    flex-wrap: wrap;
  }

  :deep(.el-form-item) { margin-bottom: 12px; }
  :deep(.el-form-item:last-child) { margin-bottom: 0; }
  :deep(.el-form-item__label) {
    font-size: 12.5px;
    font-weight: 500;
    color: $text2;
    padding-bottom: 4px;
  }
  :deep(.el-input__wrapper),
  :deep(.el-select__wrapper) {
    border-radius: $radius-sm;
    box-shadow: 0 0 0 1px var(--ai-border-3) inset;
    background: var(--ai-input-bg);
  }
  :deep(.el-switch.is-checked .el-switch__core) {
    background-color: $green;
    border-color: $green;
  }
}

.engine-switch-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  &__label {
    font-size: 13.5px;
    font-weight: 600;
    color: $text;
  }
  &__hint {
    margin-top: 3px;
    font-size: 12px;
    color: $text2;
    line-height: 1.4;
  }
}

.engine-check {
  margin-top: 12px;
  padding: 12px 14px;
  border-radius: 12px;
  background: rgba(255, 59, 48, 0.08);
  border: 1px solid rgba(255, 59, 48, 0.16);
  &__title {
    font-size: 13px;
    font-weight: 650;
    color: $red;
    margin-bottom: 6px;
  }
  ul {
    margin: 0;
    padding-left: 18px;
    font-size: 12.5px;
    color: $text2;
    line-height: 1.55;
  }
}

.engine-footer {
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px solid var(--ai-border);
  display: flex;
  flex-direction: column;
  gap: 12px;
  &__hint {
    margin: 0;
    font-size: 12px;
    color: $text2;
    line-height: 1.5;
    strong { color: $text; font-weight: 600; }
  }
  &__actions {
    display: flex;
    justify-content: flex-end;
    gap: 8px;
    flex-wrap: wrap;
  }
}

.engine-advanced {
  margin-top: 16px;
  border-radius: 12px;
  border: 1px solid var(--ai-border);
  background: var(--ai-fill-1);
  font-size: 12.5px;
  color: $text2;

  summary {
    cursor: pointer;
    padding: 10px 14px;
    font-weight: 550;
    color: $text2;
    list-style: none;
    user-select: none;
    &::-webkit-details-marker { display: none; }
    &::before {
      content: '▸';
      display: inline-block;
      margin-right: 6px;
      transition: transform 0.15s $ease;
    }
  }
  &[open] summary::before { transform: rotate(90deg); }

  &__body {
    padding: 0 14px 12px;
    p { margin: 0 0 6px; line-height: 1.45; }
    .muted { color: $gray; font-size: 11.5px; }
  }
}

.apple-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: none;
  background: var(--ai-card-bg);
  color: $text;
  font-weight: 500;
  font-size: 13px;
  font-family: $font;
  padding: 8px 16px;
  border-radius: 980px;
  cursor: pointer;
  box-shadow: 0 0 0 1px var(--ai-border-2);
  transition: all 0.18s $ease;
  &:hover:not(:disabled) { background: var(--ai-fill-1); }
  &:disabled { opacity: 0.55; cursor: not-allowed; }
}
.apple-btn--primary {
  background: $blue;
  color: #fff;
  box-shadow: 0 2px 10px rgba(10, 132, 255, 0.28);
  &:hover:not(:disabled) { background: #0071e3; }
}
</style>
