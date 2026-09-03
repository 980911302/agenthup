<template>
  <section class="resource-view">
    <header class="resource-header">
      <h1>资源库</h1>
      <button class="primary-button" type="button" @click="openCreateModal">
        <PlusIcon />
        新建技能
      </button>
    </header>

    <main class="resource-content">
      <div class="resource-toolbar">
        <div class="resource-tabs" role="tablist" aria-label="资源范围">
          <button
            type="button"
            role="tab"
            :aria-selected="activeTab === 'my'"
            :class="{ active: activeTab === 'my' }"
            @click="activeTab = 'my'"
          >
            我的
          </button>
          <button
            type="button"
            role="tab"
            :aria-selected="activeTab === 'public'"
            :class="{ active: activeTab === 'public' }"
            @click="activeTab = 'public'"
          >
            公共目录
          </button>
        </div>

        <label class="resource-search">
          <SearchIcon />
          <input v-model="searchQuery" type="search" placeholder="搜索资源" />
          <button v-if="searchQuery" type="button" aria-label="清空搜索" @click="searchQuery = ''">
            <CloseIcon />
          </button>
        </label>
      </div>

      <AppPageLoader v-if="loading" compact label="正在加载资源…" />

      <div v-else-if="!visibleList.length" class="resource-state resource-state--empty app-page-enter">
        <div class="empty-icon"><BracesIcon /></div>
        <h2>{{ searchQuery ? '没有找到匹配的技能' : activeTab === 'my' ? '还没有自己的技能' : '暂无公共技能' }}</h2>
        <p v-if="!searchQuery && activeTab === 'my'">新建一个技能，或从公共目录保存到我的资源。</p>
        <button v-if="!searchQuery && activeTab === 'my'" class="secondary-button" type="button" @click="openCreateModal">
          <PlusIcon />
          新建技能
        </button>
      </div>

      <div v-else class="resource-list app-page-enter">
        <article v-for="item in visibleList" :key="item.id" class="resource-row" :class="{ 'resource-row--catalog': activeTab === 'public' }">
          <div class="resource-symbol" aria-hidden="true"><component :is="skillIcon(item)" /></div>

          <div class="resource-summary">
            <div class="resource-title-line">
              <h2>{{ item.name }}</h2>
              <span class="resource-type">技能</span>
            </div>
            <p>{{ item.description || '暂无使用说明' }}</p>
          </div>

          <div class="resource-actions">
            <template v-if="activeTab === 'my'">
              <button class="more-button" type="button" :aria-label="`更多操作：${item.name}`" @click.stop="menuId = menuId === item.id ? null : item.id">
                <MoreIcon />
              </button>
              <div v-if="menuId === item.id" class="resource-menu" @click.stop>
                <button type="button" :disabled="detailLoadingId === item.id" @click="openEditModal(item)"><EditIcon />编辑</button>
                <button type="button" class="danger" @click="confirmDeleteItem(item)"><TrashIcon />删除</button>
              </div>
            </template>
            <span v-else-if="isCatalogAdded(item)" class="added-label"><CheckIcon />已添加</span>
            <button v-else class="add-button" type="button" :disabled="cloningId === item.id" @click="cloneToMy(item)">
              <PlusIcon />{{ cloningId === item.id ? '添加中…' : '添加' }}
            </button>
          </div>
        </article>
      </div>
    </main>

    <Teleport to="body">
      <Transition name="app-modal">
        <div v-if="showModal" ref="modalRoot" class="modal-mask" @click.self="closeModal">
          <form class="resource-modal" data-dialog-surface @submit.prevent="submitForm">
        <header class="modal-header">
          <div>
            <h2>{{ editId ? '编辑技能' : '新建技能' }}</h2>
            <p>技能会在对话中作为可选择的能力使用。</p>
          </div>
          <button class="modal-close" type="button" aria-label="关闭" @click="closeModal"><CloseIcon /></button>
        </header>

        <div v-if="detailLoading" class="modal-loading">
          <span class="spinner" />
          正在读取技能信息…
        </div>

        <div v-else class="modal-body">
          <label v-if="!editId" class="field import-field">
            <span>导入技能文档</span>
            <textarea v-model="importText" rows="4" class="import-textarea" placeholder="---&#10;name: meeting-follow-up&#10;description: 从会议记录中提取决策和待办。&#10;---&#10;&#10;阅读会议记录并整理……" />
            <small>支持带 YAML 头信息的标准技能文档</small>
            <button class="import-button" type="button" :disabled="!importText.trim()" @click="applySkillImport">应用导入内容</button>
          </label>

          <label class="field">
            <span>技能名称 <em>*</em></span>
            <input v-model="form.name" maxlength="100" placeholder="例如：meeting-follow-up" />
            <small>简短、清晰地描述这项能力</small>
          </label>

          <label class="field">
            <span>何时使用 <em>*</em></span>
            <input v-model="form.description" maxlength="255" placeholder="说明什么情况下应该调用这个技能" />
          </label>

          <label class="field">
            <span>技能指令 <em>*</em></span>
            <textarea v-model="form.promptTemplate" rows="12" placeholder="填写这个技能应遵循的完整步骤、规则和输出要求" />
            <small>{{ form.promptTemplate.length }} 字</small>
          </label>

          <div class="field">
            <span>参考文件</span>
            <p v-if="!editId" class="skill-files__hint">保存技能后即可添加参考文件。</p>
            <template v-else>
              <div v-if="filesLoading" class="skill-files__hint">正在读取…</div>
              <ul v-else-if="files.length" class="skill-files">
                <li v-for="row in files" :key="row.fileId" class="skill-files__item">
                  <span class="skill-files__path">{{ row.relPath }}</span>
                  <span class="skill-files__size">{{ formatSize(row.fileSize) }}</span>
                  <button type="button" class="skill-files__remove" title="删除" @click="removeFile(row)">
                    <TrashIcon />
                  </button>
                </li>
              </ul>
              <p v-else class="skill-files__hint">还没有参考文件。</p>
              <button class="import-button" type="button" :disabled="uploadingFile" @click="pickFile">
                {{ uploadingFile ? '上传中…' : '添加参考文件' }}
              </button>
              <input
                ref="fileInputRef"
                type="file"
                class="skill-files__input"
                accept=".md,.txt,.json,.csv,.yaml,.yml,.xml,.sql"
                @change="onFilePicked"
              />
              <small>技能指令里写「详细规则见 REFERENCE.md」,模型需要时才会打开它 —— 文件内容不占用对话上下文。单个文件最大 1MB。</small>
            </template>
          </div>
        </div>

        <footer class="modal-footer">
          <button class="secondary-button" type="button" @click="closeModal">取消</button>
          <button class="primary-button" type="submit" :disabled="!canSubmit || submitting || detailLoading">
            {{ submitting ? '保存中…' : '保存技能' }}
          </button>
        </footer>
          </form>
        </div>
      </Transition>
    </Teleport>
  </section>
</template>

<script setup>
import { computed, h, onBeforeUnmount, onMounted, ref } from 'vue'
import { addSkill, delSkill, delSkillFile, getSkill, listDesktopSkills, listSkillFiles, updateSkill, uploadSkillFile } from '../api/skill'
import { confirmDanger, toast } from '../utils/confirm'
import AppPageLoader from '../components/AppPageLoader.vue'
import { useDialogLifecycle } from '../composables/useDialogLifecycle'

const emit = defineEmits(['open-chat', 'use-skill', 'skills-changed'])

const icon = (paths, attrs = {}) => ({
  render() {
    return h('svg', { width: 18, height: 18, viewBox: '0 0 24 24', fill: 'none', 'aria-hidden': 'true', ...attrs },
      paths.map(path => h('path', { stroke: 'currentColor', 'stroke-width': 1.8, 'stroke-linecap': 'round', 'stroke-linejoin': 'round', ...path })))
  }
})

const PlusIcon = icon([{ d: 'M12 5v14M5 12h14' }])
const SearchIcon = icon([{ d: 'm21 21-4.35-4.35' }, { d: 'M19 11a8 8 0 1 1-16 0 8 8 0 0 1 16 0Z' }])
const CloseIcon = icon([{ d: 'm6 6 12 12M18 6 6 18' }])
const SparkIcon = icon([{ d: 'm12 3 1.5 4.5L18 9l-4.5 1.5L12 15l-1.5-4.5L6 9l4.5-1.5L12 3Z' }, { d: 'm18.5 15 .75 2.25L21.5 18l-2.25.75L18.5 21l-.75-2.25L15.5 18l2.25-.75.75-2.25Z' }])
const BracesIcon = icon([
  { d: 'M9 4H7.5A2.5 2.5 0 0 0 5 6.5V9a3 3 0 0 1-2 3 3 3 0 0 1 2 3v2.5A2.5 2.5 0 0 0 7.5 20H9' },
  { d: 'M15 4h1.5A2.5 2.5 0 0 1 19 6.5V9a3 3 0 0 0 2 3 3 3 0 0 0-2 3v2.5a2.5 2.5 0 0 1-2.5 2.5H15' }
])
const EditIcon = icon([{ d: 'M13.5 6.5 17.5 10.5M4 20l3.3-.7L19 7.6a2.12 2.12 0 0 0-3-3L4.3 16.3 4 20Z' }])
const TrashIcon = icon([{ d: 'M4 7h16M9 7V4h6v3M7 7l1 13h8l1-13M10 11v5M14 11v5' }])
const MoreIcon = icon([{ d: 'M5 12h.01M12 12h.01M19 12h.01', 'stroke-width': 3 }])
const CheckIcon = icon([{ d: 'm5 12 4 4L19 6' }])
const CodeIcon = icon([{ d: 'm8 9-3 3 3 3M16 9l3 3-3 3M14 5l-4 14' }])
const WritingIcon = icon([{ d: 'M4 20h4L19 9a2.8 2.8 0 0 0-4-4L4 16v4ZM13.5 6.5l4 4' }])
const AnalysisIcon = icon([{ d: 'M5 19V9M12 19V5M19 19v-7M3 19h18' }])
const TranslateIcon = icon([{ d: 'M4 5h9M8.5 3v2M6 9c1.8 2.8 4.3 4.7 7 5.8M12 7c-1.3 3.5-4 6.4-8 8M14 20l3.5-8 3.5 8M15.4 17h4.2' }])
const DatabaseIcon = icon([{ d: 'M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3Z' }, { d: 'M4 6v6c0 1.7 3.6 3 8 3s8-1.3 8-3V6M4 12v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6' }])
const ResearchIcon = icon([{ d: 'M10.8 18.6A7.5 7.5 0 1 1 18.6 11M16 16l5 5' }])
const BrainIcon = icon([{ d: 'M9.5 4A3 3 0 0 0 4 5.5 3.5 3.5 0 0 0 5 12v2a4 4 0 0 0 4 4h1M14.5 4A3 3 0 0 1 20 5.5a3.5 3.5 0 0 1-1 6.5v2a4 4 0 0 1-4 4h-1M12 3v18M8 8h4M12 14h4' }])

const activeTab = ref('my')
const searchQuery = ref('')
// 技能附件(渐进披露第三层):正文只写「详细规则见 REFERENCE.md」,文件本身不进上下文,
// 模型在会话里用 read 按需打开。新建技能时还没有 skillId,只能保存后再管理。
const files = ref([])
const filesLoading = ref(false)
const uploadingFile = ref(false)
const fileInputRef = ref(null)
const loading = ref(false)
const myList = ref([])
const publicList = ref([])
const menuId = ref(null)

function skillIcon(item) {
  const text = [item?.name, item?.category, item?.description, item?.skillCode].join(' ').toLowerCase()
  if (/代码|编程|开发|程序|code|sql|api|debug|review/.test(text)) return CodeIcon
  if (/写作|文案|文章|总结|会议|write|content|meeting|summary/.test(text)) return WritingIcon
  if (/数据|数据库|知识库|检索|rag|database|knowledge/.test(text)) return DatabaseIcon
  if (/翻译|语言|translate|english|中文/.test(text)) return TranslateIcon
  if (/分析|报表|统计|洞察|analysis|report|chart/.test(text)) return AnalysisIcon
  if (/研究|搜索|调研|research|search/.test(text)) return ResearchIcon
  if (/思考|推理|规划|决策|brain|reason|plan/.test(text)) return BrainIcon
  const fallbacks = [SparkIcon, BrainIcon, ResearchIcon, WritingIcon, AnalysisIcon, DatabaseIcon]
  return fallbacks[Math.abs(Number(item?.id) || 0) % fallbacks.length]
}

function normalizeSkill(skill) {
  const code = String(skill.skillCode || '').replace(/^\//, '')
  return {
    id: Number(skill.skillId),
    name: skill.skillName || '未命名技能',
    command: code ? `/${code}` : '',
    skillCode: code,
    category: skill.category || '',
    description: skill.description || '',
    promptTemplate: skill.promptTemplate || '',
    visibility: String(skill.visibility || 'PRIVATE').toUpperCase()
  }
}

async function loadSkills() {
  loading.value = true
  try {
    const res = await listDesktopSkills()
    const rows = Array.isArray(res.data) ? res.data : Array.isArray(res.rows) ? res.rows : []
    const skills = rows.map(normalizeSkill)
    myList.value = skills.filter(item => item.visibility === 'PRIVATE')
    publicList.value = skills.filter(item => item.visibility === 'PUBLIC')
  } catch (error) {
    myList.value = []
    publicList.value = []
    toast(error.message || '资源加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

const visibleList = computed(() => {
  const source = activeTab.value === 'my' ? myList.value : publicList.value
  const keyword = searchQuery.value.trim().toLowerCase()
  if (!keyword) return source
  return source.filter(item => [item.name, item.command, item.category, item.description]
    .some(value => String(value || '').toLowerCase().includes(keyword)))
})

const showModal = ref(false)
const modalRoot = ref(null)
useDialogLifecycle(showModal, closeModal, modalRoot)
const editId = ref(null)
const detailLoading = ref(false)
const detailLoadingId = ref(null)
const submitting = ref(false)
const cloningId = ref(null)
const emptyForm = () => ({ name: '', command: '', category: '', description: '', promptTemplate: '' })
const form = ref(emptyForm())
const importText = ref('')
const canSubmit = computed(() => form.value.name.trim() && form.value.description.trim() && form.value.promptTemplate.trim())

function generatedSkillCode(name) {
  const slug = String(name || '').normalize('NFKD').toLowerCase()
    .replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 72)
  return slug || `skill-${Date.now().toString(36)}`
}

function applySkillImport() {
  const text = importText.value.trim()
  const match = text.match(/^---\s*\n([\s\S]*?)\n---\s*\n?([\s\S]*)$/)
  if (!match) {
    toast('技能文档格式不正确，请检查开头和结尾的 ---')
    return
  }
  const meta = {}
  for (const line of match[1].split('\n')) {
    const index = line.indexOf(':')
    if (index <= 0) continue
    const key = line.slice(0, index).trim().toLowerCase()
    const value = line.slice(index + 1).trim().replace(/^['"]|['"]$/g, '')
    meta[key] = value
  }
  const instructions = match[2].trim()
  if (!meta.name || !meta.description || !instructions) {
    toast('技能文档需要包含 name、description 和正文指令')
    return
  }
  form.value = {
    ...form.value,
    name: meta.name,
    command: generatedSkillCode(meta.name),
    description: meta.description,
    promptTemplate: instructions
  }
  toast('已应用技能文档内容')
}

function openCreateModal() {
  editId.value = null
  menuId.value = null
  importText.value = ''
  form.value = emptyForm()
  showModal.value = true
}

async function openEditModal(item) {
  menuId.value = null
  editId.value = item.id
  form.value = emptyForm()
  showModal.value = true
  detailLoading.value = true
  detailLoadingId.value = item.id
  try {
    const res = await getSkill(item.id)
    const detail = normalizeSkill(res.data || res)
    form.value = {
      name: detail.name,
      command: detail.skillCode,
      category: detail.category,
      description: detail.description,
      promptTemplate: detail.promptTemplate
    }
    await loadFiles(item.id)
  } catch (error) {
    closeModal()
    toast(error.message || '读取技能信息失败')
  } finally {
    detailLoading.value = false
    detailLoadingId.value = null
  }
}

async function loadFiles(skillId) {
  files.value = []
  if (!skillId) return
  filesLoading.value = true
  try {
    const res = await listSkillFiles(skillId)
    const rows = res.data || res || []
    files.value = Array.isArray(rows) ? rows : []
  } catch (error) {
    // 附件读不到不该挡住技能本身的编辑
    toast(error.message || '读取技能附件失败')
  } finally {
    filesLoading.value = false
  }
}

function pickFile() {
  fileInputRef.value?.click()
}

async function onFilePicked(event) {
  const file = event.target?.files?.[0]
  event.target.value = ''
  if (!file || !editId.value) return
  uploadingFile.value = true
  try {
    await uploadSkillFile(editId.value, file)
    await loadFiles(editId.value)
  } catch (error) {
    toast(error.message || '上传失败')
  } finally {
    uploadingFile.value = false
  }
}

async function removeFile(row) {
  if (!editId.value || !row?.fileId) return
  const ok = await confirmDanger('删除附件', `确定删除「${row.relPath}」吗?`,
    { okLabel: '删除', cancelLabel: '取消' })
  if (!ok) return
  try {
    await delSkillFile(editId.value, row.fileId)
    await loadFiles(editId.value)
  } catch (error) {
    toast(error.message || '删除失败')
  }
}

function formatSize(bytes) {
  const n = Number(bytes) || 0
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}

function closeModal() {
  if (submitting.value) return
  showModal.value = false
  editId.value = null
  files.value = []
  detailLoading.value = false
  detailLoadingId.value = null
}

async function submitForm() {
  if (!canSubmit.value || submitting.value) return
  submitting.value = true
  const payload = {
    skillName: form.value.name.trim(),
    skillCode: form.value.command.trim().replace(/^\//, '') || generatedSkillCode(form.value.name),
    category: form.value.category.trim(),
    description: form.value.description.trim(),
    promptTemplate: form.value.promptTemplate.trim(),
    visibility: 'PRIVATE',
    status: '0'
  }
  try {
    if (editId.value) {
      await updateSkill({ ...payload, skillId: editId.value })
    } else {
      await addSkill(payload)
    }
    toast(editId.value ? '技能已更新' : '技能已创建')
    showModal.value = false
    await loadSkills()
    emit('skills-changed')
  } catch (error) {
    toast(error.message || '保存失败，请检查技能标识是否重复')
  } finally {
    submitting.value = false
  }
}

async function confirmDeleteItem(item) {
  menuId.value = null
  const confirmed = await confirmDanger('删除技能', `确定删除「${item.name}」吗？此操作不可撤销。`, {
    okLabel: '删除',
    cancelLabel: '取消'
  })
  if (!confirmed) return
  try {
    await delSkill(item.id)
    toast('技能已删除')
    await loadSkills()
    emit('skills-changed')
  } catch (error) {
    toast(error.message || '删除失败')
  }
}

async function cloneToMy(item) {
  if (cloningId.value) return
  cloningId.value = item.id
  try {
    const res = await getSkill(item.id)
    const source = normalizeSkill(res.data || res)
    await addSkill({
      skillName: `${source.name} 副本`,
      skillCode: `${source.skillCode || 'skill'}-copy-${Date.now().toString().slice(-6)}`,
      category: source.category,
      description: source.description,
      promptTemplate: source.promptTemplate,
      visibility: 'PRIVATE',
      status: '0'
    })
    toast(`已添加「${item.name}」到我的技能`)
    await loadSkills()
    emit('skills-changed')
  } catch (error) {
    toast(error.message || '保存失败')
  } finally {
    cloningId.value = null
  }
}

function isCatalogAdded(item) {
  const sourceCode = String(item?.skillCode || '')
  return !!sourceCode && myList.value.some(owned => owned.skillCode.startsWith(`${sourceCode}-copy-`))
}

function closeRowMenu() { menuId.value = null }
onMounted(() => { loadSkills(); document.addEventListener('click', closeRowMenu) })
onBeforeUnmount(() => document.removeEventListener('click', closeRowMenu))
</script>

<style scoped lang="scss">
.skill-files {
  list-style: none;
  margin: 0 0 8px;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;

  &__item {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 6px 10px;
    border-radius: 8px;
    background: var(--surface-2, rgba(127, 127, 127, 0.08));
    font-size: 13px;
  }

  &__path {
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-family: var(--font-mono, ui-monospace, monospace);
  }

  &__size {
    opacity: 0.6;
    font-variant-numeric: tabular-nums;
  }

  &__remove {
    display: inline-flex;
    align-items: center;
    border: none;
    background: transparent;
    color: inherit;
    opacity: 0.6;
    cursor: pointer;
    padding: 2px;

    &:hover { opacity: 1; }
  }

  &__hint {
    margin: 0 0 8px;
    font-size: 13px;
    opacity: 0.65;
  }

  &__input { display: none; }
}

.resource-view {
  --resource-blue: var(--accent, #2563eb);
  --resource-blue-soft: var(--accent-weak, #eff6ff);
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100%;
  min-width: 0;
  color: var(--text, #172033);
  background: var(--bg, #f8fafc);
}

.resource-header {
  height: 56px;
  padding: 0 32px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex: 0 0 auto;
  border-bottom: 1px solid var(--border, #e5eaf2);
  background: var(--bg, #fff);

  h1 { margin: 0; font-size: 18px; line-height: 1; font-weight: 650; letter-spacing: -.02em; }
}

.resource-content {
  width: min(1040px, calc(100% - 64px));
  margin: 0 auto;
  padding: 24px 0 56px;
  overflow-y: auto;
}

.resource-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding-bottom: 18px;
  border-bottom: 1px solid var(--border, #e5eaf2);
}

.resource-tabs {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 3px;
  border: 1px solid var(--border, #e5eaf2);
  border-radius: 10px;
  background: var(--bg-subtle, #f1f5f9);

  button {
    height: 32px;
    padding: 0 18px;
    border: 0;
    border-radius: 7px;
    color: var(--text-muted, #64748b);
    background: transparent;
    font-size: 13px;
    font-weight: 550;
    cursor: pointer;
  }

  button.active {
    color: var(--text, #172033);
    background: var(--bg-elevated, #fff);
    box-shadow: 0 1px 3px rgba(15, 23, 42, .09);
  }
}

.resource-search {
  width: min(256px, 38vw);
  height: 36px;
  padding: 0 12px;
  display: flex;
  align-items: center;
  gap: 9px;
  border: 1px solid var(--border, #dfe5ee);
  border-radius: 10px;
  color: var(--text-muted, #7a8599);
  background: var(--bg-elevated, #fff);
  transition: border-color .16s, box-shadow .16s;

  &:focus-within { border-color: #93b4f5; box-shadow: 0 0 0 3px rgba(37, 99, 235, .08); }
  svg { width: 16px; height: 16px; flex: 0 0 auto; }
  input { flex: 1; min-width: 0; border: 0; outline: 0; color: var(--text, #172033); background: transparent; font-size: 13px; }
  input::-webkit-search-cancel-button { display: none; }
  button { display: grid; place-items: center; padding: 2px; border: 0; color: inherit; background: transparent; cursor: pointer; }
  button svg { width: 14px; height: 14px; }
}

.resource-list {
  margin-top: 0;
  border-bottom: 1px solid var(--border, #e0e6ef);
}

.resource-row {
  position: relative;
  min-height: 60px;
  padding: 12px 8px;
  display: grid;
  grid-template-columns: 36px minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  transition: background .15s;

  & + & { border-top: 1px solid var(--border, #e5eaf2); }
  &:hover { background: color-mix(in srgb, var(--bg-hover, #f8fafc) 60%, transparent); }
}

.resource-symbol {
  width: 36px;
  height: 36px;
  display: grid;
  place-items: center;
  border-radius: 9px;
  color: var(--text-muted, #6b778c);
  background: var(--bg-subtle, #f1f5f9);
}
.resource-row--catalog .resource-symbol { color: var(--resource-blue); background: var(--resource-blue-soft); }
.resource-symbol svg { width: 16px; height: 16px; }

.resource-summary { min-width: 0; }
.resource-title-line { display: flex; align-items: center; gap: 8px; min-width: 0; }
.resource-title-line h2 { margin: 0; overflow: hidden; color: var(--text, #172033); font-size: 14px; font-weight: 550; line-height: 1.35; text-overflow: ellipsis; white-space: nowrap; }
.resource-type { flex: 0 0 auto; padding: 1px 6px; border: 1px solid var(--border, #dbe5f5); border-radius: 999px; color: var(--text-muted, #667085); background: var(--bg-elevated, #fff); font-size: 10.5px; line-height: 15px; }
.resource-summary p { margin: 2px 0 0; overflow: hidden; color: var(--text-muted, #6b778c); font-size: 12.5px; line-height: 1.4; text-overflow: ellipsis; white-space: nowrap; }

.resource-actions { position: relative; display: flex; align-items: center; justify-content: flex-end; min-width: 36px; }
.text-button,
.icon-button,
.use-button,
.primary-button,
.secondary-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  border: 1px solid transparent;
  font: inherit;
  cursor: pointer;
  transition: color .15s, border-color .15s, background .15s, box-shadow .15s;
}
.text-button { height: 34px; padding: 0 10px; border-radius: 8px; color: var(--text-muted, #596579); background: transparent; font-size: 13px; }
.text-button:hover { color: var(--resource-blue); background: var(--resource-blue-soft); }
.text-button svg { width: 16px; height: 16px; }
.icon-button { width: 34px; height: 34px; padding: 0; border-radius: 8px; color: var(--text-muted, #6b778c); background: transparent; }
.icon-button svg { width: 16px; height: 16px; }
.icon-button--danger:hover { color: #dc2626; background: #fff1f2; }
.use-button { height: 34px; padding: 0 13px; border-color: #cbdcfb; border-radius: 8px; color: #2457a7; background: #f7faff; font-size: 13px; font-weight: 600; }
.use-button:hover { border-color: #9ebdf5; color: #174b9e; background: #eff6ff; }
.use-button svg { width: 14px; height: 14px; }
button:disabled { opacity: .55; cursor: not-allowed; }

.more-button { width: 32px; height: 32px; display: grid; place-items: center; padding: 0; border: 0; border-radius: 8px; color: var(--text-muted, #7b8799); background: transparent; cursor: pointer; opacity: 0; transition: .15s ease; }
.resource-row:hover .more-button, .more-button:focus-visible { opacity: 1; }
.more-button:hover { color: var(--text, #172033); background: var(--bg-subtle, #f1f5f9); }
.more-button svg { width: 15px; height: 15px; }
.resource-menu { position: absolute; z-index: 20; top: 34px; right: 0; width: 128px; padding: 4px; border: 1px solid var(--border, #dce3ed); border-radius: 9px; background: var(--bg-elevated, #fff); box-shadow: 0 10px 28px rgba(15, 23, 42, .14); }
.resource-menu button { width: 100%; height: 32px; display: flex; align-items: center; gap: 8px; padding: 0 9px; border: 0; border-radius: 6px; color: var(--text, #334155); background: transparent; font-size: 12.5px; cursor: pointer; }
.resource-menu button:hover { background: var(--bg-subtle, #f1f5f9); }
.resource-menu button.danger { color: #dc2626; }
.resource-menu svg { width: 14px; height: 14px; }
.add-button { height: 32px; display: inline-flex; align-items: center; gap: 5px; padding: 0 10px; border: 0; border-radius: 7px; color: var(--text, #334155); background: transparent; font-size: 12px; font-weight: 550; cursor: pointer; }
.add-button:hover { color: var(--resource-blue); background: var(--resource-blue-soft); }
.add-button svg, .added-label svg { width: 14px; height: 14px; }
.added-label { height: 32px; display: inline-flex; align-items: center; gap: 5px; padding: 0 8px; color: #27824a; font-size: 12px; }

.primary-button,
.secondary-button { height: 38px; padding: 0 16px; border-radius: 9px; font-size: 13px; font-weight: 600; }
.primary-button { color: #fff; background: var(--resource-blue); box-shadow: 0 1px 2px rgba(37, 99, 235, .2); }
.primary-button:hover:not(:disabled) { background: #1d4ed8; }
.primary-button svg,
.secondary-button svg { width: 16px; height: 16px; }
.secondary-button { border-color: var(--border, #dce3ed); color: var(--text, #172033); background: var(--bg-elevated, #fff); }
.secondary-button:hover { background: var(--bg-hover, #f8fafc); }

.resource-state {
  min-height: 280px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--text-muted, #718096);
  font-size: 13px;
}
.resource-state--empty h2 { margin: 16px 0 6px; color: var(--text, #172033); font-size: 16px; font-weight: 650; }
.resource-state--empty p { margin: 0 0 20px; }
.empty-icon { width: 46px; height: 46px; display: grid; place-items: center; border: 1px solid var(--accent-border, #d9e6ff); border-radius: 12px; color: var(--resource-blue); background: var(--resource-blue-soft); }
.empty-icon svg { width: 21px; height: 21px; }
:global(html.dark .resource-view .primary-button) { background: #2f6fae; box-shadow: 0 1px 2px rgba(0, 0, 0, .24); }
:global(html.dark .resource-view .primary-button:hover:not(:disabled)) { background: #397fbe; }
.spinner { width: 20px; height: 20px; border: 2px solid #d9e3f1; border-top-color: var(--resource-blue); border-radius: 50%; animation: spin .7s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

.modal-mask {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(15, 23, 42, .36);
  backdrop-filter: blur(2px);
}

.resource-modal {
  width: min(680px, 100%);
  max-height: min(760px, calc(100vh - 48px));
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--border, #dce3ed);
  border-radius: 16px;
  color: var(--text, #172033);
  background: var(--bg-elevated, #fff);
  box-shadow: 0 24px 70px rgba(15, 23, 42, .2);
}

.modal-header { padding: 22px 24px 18px; display: flex; align-items: flex-start; justify-content: space-between; border-bottom: 1px solid var(--border, #e5eaf2); }
.modal-header h2 { margin: 0; font-size: 18px; font-weight: 650; }
.modal-header p { margin: 5px 0 0; color: var(--text-muted, #728096); font-size: 12px; }
.modal-close { width: 30px; height: 30px; display: grid; place-items: center; border: 0; border-radius: 8px; color: var(--text-muted, #728096); background: transparent; cursor: pointer; }
.modal-close:hover { color: var(--text, #172033); background: var(--bg-hover, #f1f5f9); }
.modal-close svg { width: 17px; height: 17px; }
.modal-body { padding: 22px 24px; display: grid; gap: 17px; overflow-y: auto; }
.modal-loading { min-height: 360px; display: flex; align-items: center; justify-content: center; gap: 10px; color: var(--text-muted, #728096); font-size: 13px; }
.field-row { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.field { display: grid; gap: 7px; min-width: 0; }
.field > span { color: var(--text, #283449); font-size: 12px; font-weight: 600; }
.field em { color: #dc2626; font-style: normal; }
.field input,
.field textarea,
.command-input {
  width: 100%;
  border: 1px solid var(--border, #dbe3ed);
  border-radius: 9px;
  color: var(--text, #172033);
  background: var(--bg, #fff);
  outline: 0;
  font: inherit;
  font-size: 13px;
  transition: border-color .15s, box-shadow .15s;
}
.field input { height: 39px; padding: 0 11px; }
.field textarea { min-height: 170px; padding: 11px; resize: vertical; line-height: 1.6; }
.field input:focus,
.field textarea:focus,
.command-input:focus-within { border-color: #93b4f5; box-shadow: 0 0 0 3px rgba(37, 99, 235, .08); }
.field small { justify-self: end; color: var(--text-muted, #8a96a8); font-size: 11px; }
.command-input { display: flex; align-items: center; overflow: hidden; }
.command-input b { padding-left: 11px; color: #7b8799; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-weight: 500; }
.command-input input { border: 0; box-shadow: none !important; padding-left: 3px; background: transparent; }
.import-field { padding-bottom: 17px; border-bottom: 1px solid var(--border, #e5eaf2); }
.field .import-textarea { min-height: 104px; font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; font-size: 12px; }
.import-button { justify-self: end; height: 32px; padding: 0 11px; border: 1px solid var(--border, #dce3ed); border-radius: 8px; color: var(--text, #334155); background: var(--bg-elevated, #fff); font-size: 12px; cursor: pointer; }
.import-button:hover:not(:disabled) { border-color: #b8cae7; background: var(--bg-subtle, #f8fafc); }
.modal-footer { padding: 15px 24px; display: flex; justify-content: flex-end; gap: 10px; border-top: 1px solid var(--border, #e5eaf2); background: var(--bg-subtle, #fafbfd); }

@media (max-width: 820px) {
  .resource-header { padding: 0 20px; }
  .resource-content { width: calc(100% - 32px); padding-top: 24px; }
  .resource-toolbar { align-items: stretch; flex-direction: column; gap: 14px; }
  .resource-search { width: 100%; }
  .resource-row { grid-template-columns: 36px minmax(0, 1fr) auto; padding-inline: 2px; }
  .more-button { opacity: 1; }
  .field-row { grid-template-columns: 1fr; }
}
</style>
