<template>
  <div>
    <!-- 抽屉遮罩 -->
    <transition name="drawer-fade">
      <div v-if="visible" class="ws-drawer-mask" @click="emit('close')"></div>
    </transition>

    <!-- 右侧滑出抽屉，内部 UI 1:1 对齐 PC 端 WorkspacePanel -->
    <aside class="ws-drawer" :class="{ 'is-open': visible }">
      <div class="workspace-panel">
        <!-- 头部 -->
        <div class="workspace-panel__head">
          <span class="workspace-panel__title">{{ projectId ? '项目工作区' : '工作区' }}</span>
          <div class="workspace-panel__actions">
            <button
              type="button"
              class="ws-icon-btn"
              title="刷新"
              :disabled="loading || !workspaceAvailable"
              @click="loadTree"
            >
              <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
                <path d="M2 7a5 5 0 0 1 8.5-3.5M12 7a5 5 0 0 1-8.5 3.5" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
                <path d="M10.5 1.5v2.5H8M3.5 12.5V10H6" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </button>
            <button
              type="button"
              class="ws-icon-btn"
              title="打包下载整个工作区"
              :disabled="loading || !workspaceAvailable || !treeData.length"
              @click="onDownloadAll"
            >
              <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
                <path d="M7 1.5v7M4 6l3 3 3-3" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>
                <path d="M2 10.5v1.2a.8.8 0 0 0 .8.8h8.4a.8.8 0 0 0 .8-.8v-1.2" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
              </svg>
            </button>
            <button
              type="button"
              class="ws-icon-btn ws-icon-btn--danger"
              title="清空工作区"
              :disabled="loading || !workspaceAvailable || !treeData.length"
              @click="onClearWorkspace"
            >
              <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
                <path d="M3 4.5h8M5.5 2.5h3l-1 9h-4l-1-9M6 6v4M8 6v4" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </button>
            <button
              type="button"
              class="ws-icon-btn"
              title="关闭"
              @click="emit('close')"
            >
              <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
                <path d="M3.5 3.5l7 7M10.5 3.5l-7 7" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/>
              </svg>
            </button>
          </div>
        </div>

        <div class="workspace-panel__body">
          <!-- 文件搜索：文件多时按名称过滤 (PC 款胶囊搜索框) -->
          <div v-if="sessionId" class="workspace-search">
            <svg class="workspace-search__icon" width="13" height="13" viewBox="0 0 16 16" fill="none">
              <circle cx="7" cy="7" r="4.6" stroke="currentColor" stroke-width="1.4"/>
              <path d="M10.6 10.6L14 14" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/>
            </svg>
            <input
              v-model="search"
              class="workspace-search__input"
              type="text"
              placeholder="搜索文件…"
              spellcheck="false"
            />
            <button
              v-if="search"
              type="button"
              class="workspace-search__clear"
              title="清空"
              @click="search = ''"
            >
              <svg width="10" height="10" viewBox="0 0 12 12" fill="none">
                <path d="M2.5 2.5l7 7M9.5 2.5l-7 7" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
              </svg>
            </button>
          </div>

          <!-- 空态：尚未开始会话 -->
          <div v-if="!workspaceAvailable" class="workspace-empty">
            <div class="workspace-empty__title">当前会话尚未开始</div>
            <div class="workspace-empty__hint">在下方输入消息，AI 智能体产出的代码与资源将自动保存在这里</div>
          </div>

          <!-- 空态：目录为空 -->
          <div v-else-if="!loading && !treeData.length" class="workspace-empty">
            <div class="workspace-empty__title">工作区暂无文件</div>
            <div class="workspace-empty__hint">把文件拖进输入框上传，或让 Agent 帮你创建</div>
          </div>

          <!-- 树形文件列表 (PC 端 1:1 样式) -->
          <div v-else class="workspace-tree-wrap" ref="treeWrapRef">
            <div class="ws-tree-list">
              <div
                v-for="node in flattenedTree"
                :key="node.path"
                :data-tree-path="node.path"
                class="ws-tree-node-row"
                :class="{
                  'is-file': !node.isDir,
                  'is-expanded': node.isDir && expandedPaths.has(node.path),
                  'is-highlighted': highlightedPath === node.path
                }"
                :style="{ paddingLeft: (node.depth * 18 + 10) + 'px' }"
                :title="nodeTip(node)"
                @click="onRowClick(node)"
              >
                <!-- 目录折叠三角形 (PC 端小三角) -->
                <span v-if="node.isDir" class="ws-tree-caret" @click.stop="toggleExpand(node.path)">
                  <svg
                    width="9"
                    height="9"
                    viewBox="0 0 10 10"
                    fill="currentColor"
                    class="ws-tree-caret__svg"
                    :class="{ 'is-open': expandedPaths.has(node.path) }"
                  >
                    <path d="M3 2l4 3-4 3z"/>
                  </svg>
                </span>
                <span v-else class="ws-tree-caret-placeholder"></span>

                <!-- 图标 -->
                <span class="ws-tree-node__icon" :class="'kind-' + fileKind(node)">
                  <svg v-if="node.isDir" width="15" height="15" viewBox="0 0 16 16" fill="none">
                    <path d="M1.8 3.4h4.6l1.2 1.5h6.6a.8.8 0 0 1 .8.8v6.4a.8.8 0 0 1-.8.8H2.6a.8.8 0 0 1-.8-.8z" stroke="#F2A93B" stroke-width="1.3" stroke-linejoin="round"/>
                  </svg>
                  <svg v-else width="14" height="14" viewBox="0 0 16 16" fill="none">
                    <path d="M9 1.6H4.4a1 1 0 0 0-1 1v10.8a1 1 0 0 0 1 1h7.2a1 1 0 0 0 1-1V5.4z" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/>
                    <path d="M9 1.6V5.4h3.6" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/>
                  </svg>
                  <i v-if="!node.isDir" class="ws-tree-node__dot" :class="'ws-tree-node__dot--' + fileKind(node)"></i>
                </span>

                <!-- 文件名 -->
                <span class="ws-tree-node__label">{{ nodeLabel(node) }}</span>

                <!-- 文件体积 -->
                <span v-if="!node.isDir && node.size" class="ws-tree-node__size">{{ fmtSize(node.size) }}</span>

                <!-- 悬浮操作按钮组 (存到文件 + 下载 + 删除) -->
                <div class="ws-tree-node__actions">
                  <button
                    v-if="!node.isDir"
                    type="button"
                    class="ws-tree-node__act-btn"
                    :class="{
                      'is-saving': savingPath === node.path,
                      'is-saved': savedPaths.has(node.path)
                    }"
                    :disabled="savingPath === node.path || savedPaths.has(node.path)"
                    :title="savedPaths.has(node.path)
                      ? '已保存到「文件」'
                      : savingPath === node.path
                        ? '正在保存…'
                        : '保存到「文件」，工作区清空后仍然留着'"
                    @click.stop="onSaveToUserFiles(node)"
                  >
                    <!-- 已保存：打勾。反馈就落在手指点的位置上，
                         顶部居中的 toast 离右侧抽屉太远，视线根本扫不到 -->
                    <svg v-if="savedPaths.has(node.path)" width="12" height="12" viewBox="0 0 14 14" fill="none">
                      <path d="m2.5 7.5 3 3 6-6.5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/>
                    </svg>
                    <!-- 保存中：转圈 -->
                    <svg v-else-if="savingPath === node.path" class="ws-save-spin" width="12" height="12" viewBox="0 0 14 14" fill="none">
                      <circle cx="7" cy="7" r="5" stroke="currentColor" stroke-width="1.5" stroke-opacity=".25"/>
                      <path d="M12 7a5 5 0 0 0-5-5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
                    </svg>
                    <!-- 默认：存起来 -->
                    <svg v-else width="12" height="12" viewBox="0 0 14 14" fill="none">
                      <path d="M7 9.5v-7M4 6l3-3 3 3" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>
                      <path d="M2 10.5v1.2a.8.8 0 0 0 .8.8h8.4a.8.8 0 0 0 .8-.8v-1.2" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/>
                    </svg>
                  </button>
                  <button
                    type="button"
                    class="ws-tree-node__act-btn"
                    :title="node.isDir ? '打包下载此目录' : '下载此文件'"
                    @click.stop="onDownloadNode(node)"
                  >
                    <svg width="12" height="12" viewBox="0 0 14 14" fill="none">
                      <path d="M7 1.5v7M4 6l3 3 3-3" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>
                      <path d="M2 10.5v1.2a.8.8 0 0 0 .8.8h8.4a.8.8 0 0 0 .8-.8v-1.2" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/>
                    </svg>
                  </button>
                  <button
                    type="button"
                    class="ws-tree-node__act-btn ws-tree-node__act-btn--danger"
                    :title="node.isDir ? '删除此目录' : '删除此文件'"
                    @click.stop="onDeleteNode(node)"
                  >
                    <svg width="12" height="12" viewBox="0 0 14 14" fill="none">
                      <path d="M3 4.5h8M5.5 2.5h3l-1 9h-4l-1-9M6 6v4M8 6v4" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>
                    </svg>
                  </button>
                </div>
              </div>
            </div>

            <div v-if="truncated" class="workspace-truncated">文件过多，仅显示前 500 项</div>
          </div>
        </div>
      </div>
    </aside>

    <!-- 文件代码与内容预览弹窗 -->
    <WorkspacePreviewModal
      :visible="previewVisible"
      :session-id="sessionId"
      :project-id="projectId"
      :path="previewPath"
      @close="previewVisible = false"
    />
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import {
  clearWorkspace,
  downloadWorkspaceFileBlob,
  downloadWorkspaceZipBlob,
  getWorkspaceTree,
  uploadWorkspaceFile
} from '../api/workspace'
import { saveWorkspaceFileToUserFiles } from '../api/userFile'
import { confirmDanger, toast } from '../utils/confirm'
import WorkspacePreviewModal from './WorkspacePreviewModal.vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  sessionId: { type: String, default: null },
  sessionPersisted: { type: Boolean, default: false },
  projectId: { type: Number, default: null },
  targetPath: { type: String, default: '' }
})

const emit = defineEmits(['close'])

const treeWrapRef = ref(null)
/** 正在保存到个人文件的路径，用于禁用该行按钮防重复点击 */
const savingPath = ref('')
/**
 * 本次已存到个人文件的路径。按钮据此变成打勾并禁用 —— 既是就地反馈，
 * 也顺带挡住重复点击造成的重复记录。刷新目录树时清空，届时可再存一次。
 */
const savedPaths = reactive(new Set())
const loading = ref(false)
const search = ref('')
const treeData = ref([])
const truncated = ref(false)
const previewVisible = ref(false)
const previewPath = ref('')
const expandedPaths = reactive(new Set())
const highlightedPath = ref('')
const workspaceAvailable = computed(() => !!props.sessionId && (props.sessionPersisted || !!props.projectId))
let highlightTimer = null

const IMAGE_EXT = /\.(png|jpe?g|gif|webp|svg|bmp|ico)$/i
const VIDEO_EXT = /\.(mp4|webm|mov|m4v)$/i
const AUDIO_EXT = /\.(mp3|wav|ogg|m4a|aac)$/i

async function loadTree() {
  if (!workspaceAvailable.value) {
    treeData.value = []
    truncated.value = false
    return
  }
  loading.value = true
  // 目录树重新拉取意味着内容可能已变，之前的「已保存」标记不再可靠
  savedPaths.clear()
  try {
    const res = await getWorkspaceTree(props.sessionId, props.projectId)
    const rawNodes = res.nodes || res.data?.nodes || (Array.isArray(res.data) ? res.data : [])
    treeData.value = rawNodes || []
    truncated.value = !!(res.truncated || res.data?.truncated)
    autoExpandDirs(treeData.value)
  } catch (e) {
    treeData.value = []
    truncated.value = false
  } finally {
    loading.value = false
  }
}

function autoExpandDirs(nodes) {
  for (const n of nodes || []) {
    if (n.type === 'dir' && n.path) {
      expandedPaths.add(n.path)
      if (n.children && n.children.length) autoExpandDirs(n.children)
    }
  }
}

function toggleExpand(path) {
  if (expandedPaths.has(path)) expandedPaths.delete(path)
  else expandedPaths.add(path)
}

function locateAndHighlight(filePath) {
  if (!filePath) return
  highlightedPath.value = filePath

  // 自动展开目标文件所在的全部父目录
  const parts = filePath.split('/')
  let cur = ''
  for (let i = 0; i < parts.length - 1; i++) {
    cur = cur ? `${cur}/${parts[i]}` : parts[i]
    expandedPaths.add(cur)
  }

  // 平滑滚动至目标节点并播放聚焦框选高亮动效
  nextTick(() => {
    setTimeout(() => {
      const container = treeWrapRef.value
      if (!container) return
      const targetRow = container.querySelector(`[data-tree-path="${CSS.escape(filePath)}"]`)
      if (targetRow) {
        targetRow.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
      }
    }, 120)
  })

  if (highlightTimer) clearTimeout(highlightTimer)
  highlightTimer = setTimeout(() => {
    // 保持轻柔聚焦状态
  }, 4000)
}

defineExpose({ locateAndHighlight, loadTree })

watch([() => props.visible, () => props.sessionId, () => props.sessionPersisted, () => props.projectId], ([vis]) => {
  if (vis && workspaceAvailable.value) {
    loadTree().then(() => {
      if (props.targetPath) locateAndHighlight(props.targetPath)
    })
  }
})

watch(() => props.targetPath, (p) => {
  if (p && props.visible) locateAndHighlight(p)
})

onMounted(() => {
  if (props.visible && workspaceAvailable.value) {
    loadTree().then(() => {
      if (props.targetPath) locateAndHighlight(props.targetPath)
    })
  }
})

/** 递归展平并按搜索关键词过滤 */
const flattenedTree = computed(() => {
  const kw = search.value.trim().toLowerCase()
  const list = []

  function traverse(nodes, depth = 0) {
    for (const node of nodes || []) {
      const isDir = node.type === 'dir'
      const searchable = `${node.name || ''} ${node.displayName || ''}`.toLowerCase()
      const match = !kw || searchable.includes(kw)

      if (isDir) {
        const children = node.children || []
        const hasMatchingChild = kw && children.some(c =>
          `${c.name || ''} ${c.displayName || ''}`.toLowerCase().includes(kw))
        if (!kw || match || hasMatchingChild) {
          list.push({ ...node, depth, isDir: true })
          if (!kw && !expandedPaths.has(node.path)) {
            continue
          }
          traverse(children, depth + 1)
        }
      } else {
        if (!kw || match) {
          list.push({ ...node, depth, isDir: false })
        }
      }
    }
  }

  traverse(treeData.value, 0)
  return list
})

function fileKind(data) {
  if (data.isDir || data.type === 'dir') return 'dir'
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

function fmtSize(b) {
  const v = Number(b) || 0
  if (v < 1024) return v + ' B'
  if (v < 1024 * 1024) return (v / 1024).toFixed(1).replace(/\.0$/, '') + ' KB'
  return (v / 1024 / 1024).toFixed(1).replace(/\.0$/, '') + ' MB'
}

function nodeTip(data) {
  if (!data) return ''
  const bits = [nodeLabel(data)]
  if (!data.isDir && data.size != null) bits.push(fmtSize(data.size))
  if (data.mtime) {
    const d = new Date(data.mtime)
    bits.push(`修改于 ${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`)
  }
  return bits.join(' · ')
}

function onRowClick(node) {
  if (node.isDir) {
    toggleExpand(node.path)
  } else {
    previewPath.value = node.path
    previewVisible.value = true
  }
}

async function onDeleteNode(node) {
  if (!props.sessionId || !node) return
  const isDir = node.isDir
  const ok = await confirmDanger(
    isDir ? '删除目录' : '删除文件',
    `确定要删除「${node.name}」吗？${isDir ? '目录下的所有内容将被一同删除，' : ''}删除后不可恢复。`,
    { okLabel: '删除', cancelLabel: '取消' }
  )
  if (!ok) return
  try {
    await deleteWorkspaceFile(props.sessionId, node.path, props.projectId)
    toast('删除成功')
    await loadTree()
    if (previewPath.value === node.path) {
      previewVisible.value = false
    }
  } catch (e) {
    toast(e.message || '删除失败')
  }
}

/**
 * 把工作区里的这个文件收进个人文件空间。
 * 工作区随会话删除而清空，存进个人空间才留得住 —— 这是 /ai/files/attach 的反方向。
 */
async function onSaveToUserFiles(node) {
  if (!node || node.isDir || savingPath.value) return
  savingPath.value = node.path
  try {
    await saveWorkspaceFileToUserFiles(props.sessionId, node.path, props.projectId ?? null)
    savedPaths.add(node.path)
    toast(`「${node.name}」已存到左侧「文件」，工作区清空后仍在`)
  } catch (_) {
    // 失败原因(超配额/超限)已由 request 拦截器 toast
  } finally {
    savingPath.value = ''
  }
}

async function onDownloadNode(node) {
  if (!props.sessionId || !node) return
  try {
    if (node.isDir) {
      await downloadWorkspaceZipBlob(props.sessionId, node.path, (node.name || 'folder') + '.zip', props.projectId)
    } else {
      await downloadWorkspaceFileBlob(props.sessionId, node.path, node.name, props.projectId)
    }
  } catch (e) {
    toast(e.message || '下载失败')
  }
}

async function onDownloadAll() {
  if (!props.sessionId) return
  try {
    await downloadWorkspaceZipBlob(props.sessionId, '', 'workspace.zip', props.projectId)
  } catch (e) {
    toast(e.message || '全量打包下载失败')
  }
}

async function onClearWorkspace() {
  if (!workspaceAvailable.value) return
  const message = props.projectId
    ? '确认清空整个项目工作区？项目下所有会话共享这些文件，清空后不可恢复。'
    : '确认清空当前会话工作区？文件将从磁盘永久删除，不可恢复。'
  const ok = await confirmDanger('清空工作区', message, { okLabel: '清空', cancelLabel: '取消' })
  if (!ok) return
  loading.value = true
  try {
    await clearWorkspace(props.sessionId, props.projectId)
    treeData.value = []
    toast('工作区已清空')
  } catch (e) {
    toast(e.message || '清空失败')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped lang="scss">
@use '../chat-ui/ai-tokens.scss' as *;

.ws-drawer-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  backdrop-filter: blur(2px);
  z-index: 1000;
}

.ws-drawer {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  width: 320px;
  background: var(--bg-raised, #ffffff);
  border-left: 1px solid var(--border, rgba(0, 0, 0, 0.08));
  box-shadow: var(--shadow, -4px 0 24px rgba(0, 0, 0, 0.15));
  z-index: 1001;
  display: flex;
  flex-direction: column;
  transform: translateX(100%);
  transition: transform 0.24s cubic-bezier(0.16, 1, 0.3, 1);

  &.is-open {
    transform: translateX(0);
  }
}

.workspace-panel {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--bg-raised, #ffffff);
  color: var(--text, #1c1c1e);
  overflow: hidden;

  &__head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 14px 16px;
    border-bottom: 1px solid var(--border, rgba(0, 0, 0, 0.06));
    flex-shrink: 0;
  }

  &__title {
    font-size: 15px;
    font-weight: 600;
    color: var(--text, #1c1c1e);
  }

  &__actions {
    display: flex;
    align-items: center;
    gap: 4px;
  }

  &__body {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
    overflow: hidden;
  }
}

.ws-icon-btn {
  width: 28px;
  height: 28px;
  border: none;
  background: transparent;
  color: var(--text-tertiary, #8e8e93);
  border-radius: 6px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: all 0.16s ease;

  &:hover {
    background: var(--accent-weak, rgba(10, 132, 255, 0.08));
    color: var(--accent, #007aff);
  }

  &--danger:hover {
    background: var(--danger-weak, rgba(255, 59, 48, 0.1));
    color: var(--danger, #ff3b30);
  }

  &:disabled {
    opacity: 0.3;
    cursor: not-allowed;
  }
}

.workspace-search {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 12px 14px 4px;
  padding: 0 10px;
  height: 32px;
  background: var(--bg-hover, #f2f2f7);
  border: 1px solid var(--border, transparent);
  border-radius: 980px;
  transition: all 0.16s ease;

  &:focus-within {
    background: var(--bg-input, #ffffff);
    border-color: var(--accent, rgba(0, 122, 255, 0.4));
    box-shadow: 0 0 0 3px var(--accent-weak, rgba(0, 122, 255, 0.1));
  }

  &__icon {
    color: var(--text-tertiary, #8e8e93);
    flex-shrink: 0;
  }

  &__input {
    flex: 1;
    min-width: 0;
    border: none;
    outline: none;
    background: transparent;
    font-size: 12.5px;
    color: var(--text, #1c1c1e);

    &::placeholder {
      color: var(--text-tertiary, #aeaeb2);
    }
  }

  &__clear {
    flex-shrink: 0;
    width: 16px;
    height: 16px;
    border: none;
    border-radius: 50%;
    background: var(--bg-active, #e5e5ea);
    color: var(--text-tertiary, #8e8e93);
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;

    &:hover {
      background: var(--border-strong, #d1d1d6);
      color: var(--text, #1c1c1e);
    }
  }
}

.workspace-empty {
  text-align: center;
  padding: 50px 20px;
  color: var(--text-tertiary, #8e8e93);

  &__title {
    font-size: 13.5px;
    font-weight: 500;
    color: var(--text-secondary, #3a3a3c);
    margin-bottom: 6px;
  }

  &__hint {
    font-size: 12px;
    color: var(--text-tertiary, #8e8e93);
    line-height: 1.6;
  }
}

.workspace-tree-wrap {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 8px 8px;

  &::-webkit-scrollbar {
    width: 5px;
  }
  &::-webkit-scrollbar-thumb {
    background: var(--border-strong, rgba(0, 0, 0, 0.15));
    border-radius: 3px;
  }
}

.ws-tree-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.ws-tree-node-row {
  position: relative;
  display: flex;
  align-items: center;
  gap: 6px;
  height: 32px;
  border-radius: 7px;
  cursor: pointer;
  user-select: none;
  font-size: 13px;
  color: var(--text, #1c1c1e);
  transition: all 0.16s cubic-bezier(0.16, 1, 0.3, 1);

  &:hover {
    background: var(--bg-hover, rgba(0, 122, 255, 0.06));
  }

  &.is-file {
    color: var(--text-secondary, #3a3a3c);
  }

  /* 酷炫高亮聚焦框选动画 */
  &.is-highlighted {
    animation: highlight-pulse 2.2s cubic-bezier(0.16, 1, 0.3, 1) forwards;
  }
}

@keyframes highlight-pulse {
  0% {
    background: var(--accent-weak, rgba(0, 122, 255, 0.3));
    box-shadow: 0 0 0 2px var(--accent, #007aff), 0 0 16px rgba(0, 122, 255, 0.45);
    transform: scale(1.02);
  }
  30% {
    background: var(--accent-weak, rgba(0, 122, 255, 0.18));
    box-shadow: 0 0 0 2px var(--accent, #007aff), 0 0 10px rgba(0, 122, 255, 0.3);
    transform: scale(1);
  }
  60% {
    background: var(--accent-weak, rgba(0, 122, 255, 0.25));
    box-shadow: 0 0 0 2px var(--accent, #007aff), 0 0 14px rgba(0, 122, 255, 0.38);
  }
  100% {
    background: var(--accent-weak, rgba(0, 122, 255, 0.12));
    box-shadow: 0 0 0 1.5px var(--accent, #007aff), 0 0 8px rgba(0, 122, 255, 0.2);
    transform: scale(1);
  }
}

.ws-tree-caret {
  width: 14px;
  height: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-tertiary, #8e8e93);
  flex-shrink: 0;

  &__svg {
    transition: transform 0.16s ease;
    &.is-open {
      transform: rotate(90deg);
    }
  }
}

.ws-tree-caret-placeholder {
  width: 14px;
  flex-shrink: 0;
}

.ws-tree-node {
  &__icon {
    position: relative;
    flex-shrink: 0;
    width: 16px;
    height: 16px;
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--text-tertiary, #8e8e93);

    &.kind-dir {
      color: #f2a93b;
    }
  }

  &__dot {
    position: absolute;
    right: -1px;
    bottom: -1px;
    width: 5px;
    height: 5px;
    border-radius: 50%;
    border: 1px solid var(--bg-raised, #ffffff);

    &--code { background: var(--accent, #007aff); }
    &--data { background: #af52de; }
    &--doc { background: var(--text-tertiary, #8e8e93); }
    &--img { background: #34c759; }
    &--vid { background: #5856d6; }
    &--arc { background: #ff9500; }
    &--shell { background: var(--danger, #ff3b30); }
    &--file { background: var(--text-tertiary, #aeaeb2); }
  }

  &__label {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 13px;
    color: inherit;
  }

  &__size {
    flex-shrink: 0;
    font-size: 11px;
    color: var(--text-tertiary, #8e8e93);
    font-variant-numeric: tabular-nums;
    margin-right: 4px;
  }

  &__actions {
    margin-left: auto;
    display: flex;
    align-items: center;
    gap: 2px;
    opacity: 0;
    transition: opacity 0.14s ease;
  }

  &__act-btn {
    width: 22px;
    height: 22px;
    border: none;
    border-radius: 4px;
    background: transparent;
    color: var(--text-tertiary, #8e8e93);
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    transition: all 0.14s ease;

    &:hover {
      background: var(--accent-weak, rgba(0, 122, 255, 0.12));
      color: var(--accent, #007aff);
    }

    &--danger:hover {
      background: var(--danger-weak, rgba(255, 59, 48, 0.12));
      color: var(--danger, #ff3b30);
    }

    /* 已存到个人文件：绿勾常驻，替代那条会被忽略的顶部 toast */
    &.is-saved {
      color: var(--ok, #34d399);
      background: rgba(52, 211, 153, .12);
      opacity: 1;
      cursor: default;
    }

    &.is-saving {
      color: var(--accent, #007aff);
      cursor: default;
    }

    &:disabled { cursor: default; }
  }
}

/* 已保存的行让操作组常驻可见，否则鼠标一移开绿勾就消失，等于没反馈 */
.ws-tree-node__actions:has(.is-saved) { opacity: 1; }

.ws-save-spin { animation: ws-save-spin 0.7s linear infinite; }

@keyframes ws-save-spin {
  to { transform: rotate(360deg); }
}

.ws-tree-node-row:hover .ws-tree-node__actions {
  opacity: 1;
}

.workspace-truncated {
  margin: 8px 6px 4px;
  padding: 6px 8px;
  font-size: 11px;
  color: var(--text-tertiary, #8e8e93);
  background: var(--bg-hover, #f2f2f7);
  border-radius: 6px;
  text-align: center;
}

.drawer-fade-enter-active,
.drawer-fade-leave-active {
  transition: opacity 0.2s ease;
}
.drawer-fade-enter-from,
.drawer-fade-leave-to {
  opacity: 0;
}
</style>
