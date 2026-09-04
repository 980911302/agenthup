<template>
  <aside class="workspace-panel" :class="{ 'is-embedded': embedded, 'is-readonly': readonly }">
    <div class="workspace-panel__head">
      <span class="workspace-panel__title">工作区</span>
      <div class="workspace-panel__actions">
        <button type="button" class="ws-icon-btn" title="刷新" :disabled="loading || !sessionId" @click="refresh">
          <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
            <path d="M2 7a5 5 0 0 1 8.5-3.5M12 7a5 5 0 0 1-8.5 3.5" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
            <path d="M10.5 1.5v2.5H8M3.5 12.5V10H6" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </button>
        <button type="button" class="ws-icon-btn" title="打包下载整个工作区" :disabled="loading || !sessionId || !treeData.length" @click="onDownloadAll">
          <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
            <path d="M7 1.5v7M4 6l3 3 3-3" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>
            <path d="M2 10.5v1.2a.8.8 0 0 0 .8.8h8.4a.8.8 0 0 0 .8-.8v-1.2" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
          </svg>
        </button>
        <button v-if="!readonly" type="button" class="ws-icon-btn ws-icon-btn--danger" title="清空工作区" :disabled="loading || !sessionId" @click="onClear">
          <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
            <path d="M3 4.5h8M5.5 2.5h3l-1 9h-4l-1-9M6 6v4M8 6v4" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </button>
        <button v-if="!embedded" type="button" class="ws-icon-btn" title="关闭" @click="$emit('close')">
          <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
            <path d="M3.5 3.5l7 7M10.5 3.5l-7 7" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/>
          </svg>
        </button>
      </div>
    </div>

    <p v-if="readonly" class="workspace-panel__hint">卡片正在执行,工作区只读。请先暂停再改文件。</p>
    <div class="workspace-panel__body" v-loading="loading">
      <!-- 文件搜索：文件多时按名称过滤 -->
      <div v-if="sessionId" class="workspace-search">
        <svg class="workspace-search__icon" width="13" height="13" viewBox="0 0 16 16" fill="none"><circle cx="7" cy="7" r="4.6" stroke="currentColor" stroke-width="1.4"/><path d="M10.6 10.6L14 14" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/></svg>
        <input v-model="search" class="workspace-search__input" type="text" placeholder="搜索文件…" spellcheck="false" />
        <button v-if="search" type="button" class="workspace-search__clear" title="清空" @click="search = ''">
          <svg width="10" height="10" viewBox="0 0 12 12" fill="none"><path d="M2.5 2.5l7 7M9.5 2.5l-7 7" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>
        </button>
      </div>

      <!-- 空态：尚未开始会话 -->
      <div v-if="!sessionId" class="workspace-empty">
        当前会话尚未开始
      </div>

      <!-- 空态：目录为空 -->
      <div v-else-if="!loading && !treeData.length" class="workspace-empty">
        <div class="workspace-empty__title">工作区暂无文件</div>
        <div class="workspace-empty__hint">把文件拖进输入框上传，或让 Agent 帮你创建</div>
      </div>

      <template v-else-if="sessionId">
        <div class="workspace-tree-wrap">
          <el-tree
            :data="filteredTree"
            node-key="path"
            :props="treeProps"
            highlight-current
            default-expand-all
            @node-click="onNodeClick"
          >
            <template #default="{ data }">
              <span class="ws-tree-node" :class="{ 'is-file': data.type === 'file' }" :title="nodeTip(data)">
                <span class="ws-tree-node__icon" :class="'kind-' + fileKind(data)">
                  <svg v-if="data.type === 'dir'" width="15" height="15" viewBox="0 0 16 16" fill="none"><path d="M1.8 3.4h4.6l1.2 1.5h6.6a.8.8 0 0 1 .8.8v6.4a.8.8 0 0 1-.8.8H2.6a.8.8 0 0 1-.8-.8z" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/></svg>
                  <svg v-else width="14" height="14" viewBox="0 0 16 16" fill="none"><path d="M9 1.6H4.4a1 1 0 0 0-1 1v10.8a1 1 0 0 0 1 1h7.2a1 1 0 0 0 1-1V5.4z" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/><path d="M9 1.6V5.4h3.6" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/></svg>
                  <i v-if="data.type === 'file'" class="ws-tree-node__dot"></i>
                </span>
                <span class="ws-tree-node__label">{{ nodeLabel(data) }}</span>
                <span v-if="data.type === 'file' && data.size" class="ws-tree-node__size">{{ fmtSize(data.size) }}</span>
                <div class="ws-tree-node__actions">
                  <button
                    type="button"
                    class="ws-tree-node__act"
                    :title="data.type === 'dir' ? '打包下载此目录' : '下载此文件'"
                    @click.stop="onDownloadNode(data)"
                  >
                    <svg width="12" height="12" viewBox="0 0 14 14" fill="none">
                      <path d="M7 1.5v7M4 6l3 3 3-3" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>
                      <path d="M2 10.5v1.2a.8.8 0 0 0 .8.8h8.4a.8.8 0 0 0 .8-.8v-1.2" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/>
                    </svg>
                  </button>
                  <button
                    v-if="!readonly"
                    type="button"
                    class="ws-tree-node__act ws-tree-node__act--danger"
                    :title="data.type === 'dir' ? '删除此目录' : '删除此文件'"
                    @click.stop="onDeleteNode(data)"
                  >
                    <svg width="12" height="12" viewBox="0 0 14 14" fill="none">
                      <path d="M3 4.5h8M5.5 2.5h3l-1 9h-4l-1-9M6 6v4M8 6v4" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>
                    </svg>
                  </button>
                </div>
              </span>
            </template>
          </el-tree>
          <div v-if="truncated" class="workspace-truncated">文件过多，仅显示前 500 项</div>
        </div>
      </template>
    </div>
  </aside>

  <WorkspacePreviewDialog
    v-model:visible="previewDialogVisible"
    :session-id="sessionId"
    :path="previewDialogPath"
  />
</template>

<script setup>
import { ref, watch, computed, getCurrentInstance } from 'vue'
import { getWorkspaceTree, clearWorkspace, workspaceFileDownloadUrl, workspaceZipDownloadUrl } from '@/api/ai/workspace'
import WorkspacePreviewDialog from './WorkspacePreviewDialog.vue'

const { proxy } = getCurrentInstance()

const props = defineProps({
  sessionId: { type: String, default: null },
  visible: { type: Boolean, default: true },
  readonly: { type: Boolean, default: false },
  embedded: { type: Boolean, default: false }
})

defineEmits(['close'])

const treeProps = { children: 'children', label: 'name' }
const treeData = ref([])
const truncated = ref(false)
const loading = ref(false)
const search = ref('')

/** 弹框预览:点击文件由 WorkspacePreviewDialog 自行加载内容,面板只记录打开状态与路径 */
const previewDialogVisible = ref(false)
const previewDialogPath = ref('')

const IMAGE_EXT = /\.(png|jpe?g|gif|webp|svg|bmp|ico)$/i
const VIDEO_EXT = /\.(mp4|webm|mov|m4v)$/i
const AUDIO_EXT = /\.(mp3|wav|ogg|m4a|aac)$/i

/** 搜索过滤:命中名称则保留,目录递归(命中子文件保留目录壳) */
const filteredTree = computed(() => {
  const kw = search.value.trim().toLowerCase()
  if (!kw) return treeData.value
  const walk = (nodes) => {
    const out = []
    for (const n of nodes) {
      if (`${n.name || ''} ${n.displayName || ''}`.toLowerCase().includes(kw)) out.push(n)
      else if (n.children && n.children.length) {
        const ch = walk(n.children)
        if (ch.length) out.push({ ...n, children: ch })
      }
    }
    return out
  }
  return walk(treeData.value)
})

/** 文件类型 -> 彩色类别(图标右下角圆点) */
function fileKind(data) {
  if (data.type === 'dir') return 'dir'
  const name = (data.name || '').toLowerCase()
  if (/\.(js|ts|jsx|tsx|java|py|go|rs|php|c|cpp|h|cs|kt|swift)$/.test(name)) return 'code'
  if (/\.(json|ya?ml|xml|properties|toml|ini|conf)$/.test(name)) return 'data'
  if (/\.(md|txt|rst|pdf|docx?)$/.test(name)) return 'doc'
  if (IMAGE_EXT.test(name)) return 'img'
  if (VIDEO_EXT.test(name)) return 'vid'
  if (AUDIO_EXT.test(name)) return 'aud'
  if (/\.(zip|tar|gz|7z|rar)$/.test(name)) return 'arc'
  if (/\.(sh|bat|command)$/.test(name)) return 'shell'
  return 'file'
}

function nodeLabel(data) {
  return data?.displayName || data?.name || ''
}

/** 文件大小: B / KB / MB */
function fmtSize(b) {
  const v = Number(b) || 0
  if (v < 1024) return v + ' B'
  if (v < 1024 * 1024) return (v / 1024).toFixed(1).replace(/\.0$/, '') + ' KB'
  return (v / 1024 / 1024).toFixed(1).replace(/\.0$/, '') + ' MB'
}

/** 树节点 tooltip:文件附大小/修改时间 */
function nodeTip(data) {
  if (!data) return ''
  const bits = [nodeLabel(data)]
  if (data.type === 'file' && data.size != null) bits.push(fmtSize(data.size))
  if (data.mtime) {
    const d = new Date(data.mtime)
    bits.push(`修改于 ${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`)
  }
  return bits.join(' · ')
}

async function refresh() {
  if (!props.sessionId) {
    closePreviewDialog()
    treeData.value = []
    truncated.value = false
    return
  }
  loading.value = true
  try {
    const res = await getWorkspaceTree(props.sessionId)
    treeData.value = res.nodes || []
    truncated.value = !!res.truncated
  } catch (e) {
    treeData.value = []
    truncated.value = false
  } finally {
    loading.value = false
  }
}

/** 从外部入口打开工作区中的指定文件。 */
function openPath(path) {
  if (!path || !props.sessionId) return
  openPreview(path)
}

/** 打开弹框预览指定路径文件。 */
function openPreview(path) {
  if (!path || !props.sessionId) return
  previewDialogPath.value = path
  previewDialogVisible.value = true
}

function closePreviewDialog() {
  previewDialogVisible.value = false
  previewDialogPath.value = ''
}

function onNodeClick(data) {
  if (!data || data.type !== 'file' || !props.sessionId) return
  openPreview(data.path)
}

/* ---- 下载 ----
   统一走 $download.zip：它用 axios + responseType:blob 并带上 token，
   而且会在服务端返回 JSON 错误(而非文件流)时把错误弹出来 ——
   直接 window.open 拿不到 token，也没法区分「下载成功」和「返回了一段错误 JSON」。 */
function onDownloadAll() {
  if (!props.sessionId) return
  proxy.$download.zip(workspaceZipDownloadUrl(props.sessionId), 'workspace.zip')
}

/** 目录 -> 打包下载；文件 -> 直接下载 */
async function onDeleteNode(data) {
  if (!props.sessionId || !data) return
  const isDir = data.type === 'dir'
  try {
    await proxy.$modal.confirm(`确定要删除「${data.name}」吗？${isDir ? '目录下的所有文件将被一同删除，' : ''}删除后不可恢复。`)
    await deleteWorkspaceFile(props.sessionId, data.path)
    proxy.$modal.msgSuccess('删除成功')
    loadTree()
  } catch (_) {}
}

function onDownloadNode(data) {
  if (!props.sessionId || !data) return
  if (data.type === 'dir') {
    proxy.$download.zip(workspaceZipDownloadUrl(props.sessionId, data.path), (data.name || 'folder') + '.zip')
  } else {
    onDownloadPath(data.path, data.name)
  }
}

function onDownloadPath(path, name) {
  if (!props.sessionId || !path) return
  const filename = name || String(path).split('/').pop() || 'file'
  proxy.$download.zip(workspaceFileDownloadUrl(props.sessionId, path), filename)
}

async function onClear() {
  if (!props.sessionId) return
  try {
    await proxy.$modal.confirm('确认清空当前会话工作区？文件将从磁盘永久删除，不可恢复。')
  } catch {
    return
  }
  loading.value = true
  try {
    await clearWorkspace(props.sessionId)
    proxy.$modal.msgSuccess('工作区已清空')
    await refresh()
  } catch (e) {
    // request 拦截器已提示
  } finally {
    loading.value = false
  }
}

watch(() => props.sessionId, () => {
  closePreviewDialog()
  refresh()
}, { immediate: true })

defineExpose({ refresh, openPath })
</script>

<style scoped lang="scss">
@use '../../../../assets/styles/ai-tokens.scss' as *;

$radius: 14px;
$radius-sm: 10px;

.workspace-panel {
  width: 300px; flex-shrink: 0;
  display: flex; flex-direction: column;
  background: var(--ai-card-bg); border: 1px solid var(--ai-border); border-radius: $radius;
  box-shadow: 0 1px 2px var(--ai-fill-2); overflow: hidden;
  &.is-embedded { width: 100%; min-height: 240px; max-height: 420px; box-shadow: none; }
}

.workspace-panel__hint {
  margin: 0; padding: 8px 14px; font-size: 12px; color: #C24A00; background: rgba(255,159,10,0.10);
}

.workspace-panel__head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 12px 14px; border-bottom: 1px solid var(--ai-border);
}


.workspace-panel__title {
  font-family: $font; font-size: 14px; font-weight: 600; color: $text;
}

.workspace-panel__actions {
  display: flex; align-items: center; gap: 4px;
}

.ws-icon-btn {
  width: 28px; height: 28px; border: none; background: transparent;
  color: $gray; border-radius: 8px; cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center;
  transition: all 0.18s $ease;
  &:hover { background: rgba(10,132,255,0.08); color: $blue; }
  &--danger:hover { background: rgba(255,59,48,0.1); color: #FF3B30; }
  &:disabled { opacity: 0.4; cursor: not-allowed; }
}

.workspace-panel__body {
  flex: 1; min-height: 0; display: flex; flex-direction: column; overflow: hidden;
}

.workspace-search {
  display: flex; align-items: center; gap: 6px;
  margin: 10px 12px 2px; padding: 0 10px; height: 30px;
  background: var(--ai-fill-1); border: 1px solid transparent; border-radius: 980px;
  transition: background 0.16s $ease, border-color 0.16s $ease, box-shadow 0.16s $ease;
  &:focus-within {
    background: var(--ai-card-bg);
    border-color: rgba(10, 132, 255, 0.35);
    box-shadow: 0 0 0 3px rgba(10, 132, 255, 0.08);
  }
  &__icon { color: $gray; flex-shrink: 0; }
  &__input {
    flex: 1; min-width: 0; border: none; outline: none; background: transparent;
    font-family: $font; font-size: 12.5px; color: $text;
    &::placeholder { color: $gray2; }
  }
  &__clear {
    flex-shrink: 0; width: 16px; height: 16px; border: none; border-radius: 50%;
    background: var(--ai-fill-3); color: $text2; cursor: pointer;
    display: flex; align-items: center; justify-content: center;
    &:hover { background: var(--ai-fill-4); color: $text; }
  }
}

.workspace-empty {
  text-align: center; padding: 40px 16px; font-size: 12.5px; color: $gray2; font-family: $font;
  &__title { font-size: 13px; color: $gray; margin-bottom: 6px; }
  &__hint { font-size: 11.5px; color: $gray2; line-height: 1.6; }
}

.workspace-tree-wrap {
  flex: 1; min-height: 0; overflow-y: auto; padding: 8px 6px;
  &::-webkit-scrollbar { width: 5px; }
  &::-webkit-scrollbar-thumb { background: var(--ai-border-4); border-radius: 3px; }
  :deep(.el-tree) {
    background: transparent;
    --el-tree-node-hover-bg-color: rgba(10,132,255,0.06);
  }
  :deep(.el-tree-node__content) {
    height: 30px; border-radius: 8px;
  }
}

.ws-tree-node {
  display: inline-flex; align-items: center; gap: 6px; font-size: 13px; color: $text;
  min-width: 0; width: 100%;
  &__icon {
    position: relative; flex-shrink: 0; width: 18px; height: 18px;
    display: flex; align-items: center; justify-content: center;
    color: var(--ai-text3);
    &.kind-dir { color: #F2A93B; }
    &.kind-vid { color: #5E5CE6; }
  }
  &__dot {
    position: absolute; right: -1px; bottom: -1px;
    width: 6px; height: 6px; border-radius: 50%;
    border: 1.5px solid var(--ai-card-bg);
    // 类型类别色
    &--code { background: #0A84FF; }
    &--data { background: #AF52DE; }
    &--doc { background: #8E8E93; }
    &--img { background: #34C759; }
    &--vid { background: #5E5CE6; }
    &--arc { background: #FF9F0A; }
    &--shell { background: #FF3B30; }
    &--file { background: #AEAEB2; }
  }
  &__label { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  &__size { flex-shrink: 0; font-size: 10.5px; color: $gray2; font-variant-numeric: tabular-nums; }
  &.is-file { color: #3A3A3C; }
  // 下载按钮常驻会让树很吵，hover 到该行才显形
  &__dl {
    margin-left: auto; flex-shrink: 0;
    width: 20px; height: 20px; border: none; border-radius: 5px;
    background: transparent; color: var(--ai-gray); cursor: pointer;
    display: flex; align-items: center; justify-content: center;
    opacity: 0; transition: opacity 0.15s, background 0.15s, color 0.15s;
    &:hover { background: rgba(10,132,255,0.12); color: #0A84FF; }
  }
  &:hover &__dl { opacity: 1; }
}

.workspace-truncated {
  margin: 8px 8px 4px; padding: 6px 8px; font-size: 11px; color: $gray;
  background: var(--ai-fill-1); border-radius: 8px; text-align: center;
}
</style>
