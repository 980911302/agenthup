<template>
  <div class="kb-detail" v-loading="loading">
    <!-- 加载失败 -->
    <div v-if="loadError && !loading" class="kb-detail__error">
      <p class="kb-detail__error-title">无法打开知识库</p>
      <p class="kb-detail__error-text">{{ loadError }}</p>
      <div class="kb-detail__error-actions">
        <button type="button" class="kb-btn" @click="goBack">返回知识库</button>
        <button type="button" class="kb-btn kb-btn--primary" @click="loadHeader">重试</button>
      </div>
    </div>

    <template v-else>
      <!-- 紧凑顶栏：返回 + 标题/统计 + 分段导航，把纵向空间留给文件区 -->
      <header class="kb-detail__head">
        <button
          type="button"
          class="kb-detail__back"
          aria-label="返回知识库列表"
          title="返回知识库"
          @click="goBack"
        >
          <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M10 3.5L5.5 8 10 12.5" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </button>

        <div class="kb-detail__titles">
          <div class="kb-detail__title-row">
            <h1 class="kb-detail__title" :title="header.kbName">{{ header.kbName || '知识库' }}</h1>
            <span class="kb-detail__badge" :class="'is-' + statusKey">
              <i class="kb-detail__badge-dot" aria-hidden="true" />{{ statusLabel }}
            </span>
            <span class="kb-detail__meta">
              <span>{{ header.docCount || 0 }} 个文件</span>
              <button
                v-if="(header.processingCount || 0) > 0"
                type="button"
                class="kb-detail__stat-link"
                @click="goTab('content')"
              >{{ header.processingCount }} 处理中</button>
              <button
                v-if="(header.failedCount || 0) > 0"
                type="button"
                class="kb-detail__stat-link is-warn"
                @click="goTab('content')"
              >{{ header.failedCount }} 失败</button>
            </span>
          </div>
        </div>

        <nav class="kb-detail__seg" aria-label="知识库文件导航">
          <button
            type="button"
            class="kb-detail__seg-item"
            :class="{ 'is-active': mainNav === 'content' }"
            @click="goTab('content')"
          >文件</button>
          <button
            type="button"
            class="kb-detail__seg-item"
            :class="{ 'is-active': mainNav === 'search' }"
            @click="goTab('search')"
          >测试</button>
          <button
            type="button"
            class="kb-detail__seg-item"
            @click="openKbGraph"
            title="查看全库知识图谱"
          >图谱</button>
        </nav>
      </header>

      <div class="kb-detail__body">
        <DocPanel
          v-show="mainNav === 'content'"
          ref="docPanelRef"
          :kb-id="kbId"
          :access="access"
          mode="content"
          @doc-count="onDocCount"
          @open-graph="openDocGraph"
        />

        <SearchPanel
          v-if="searchMounted"
          v-show="mainNav === 'search'"
          :kb-id="kbId"
          :access="access"
          @open-document="onOpenDocumentFromSearch"
        />
      </div>

      <!-- 全库 / 单文件知识图谱 -->
      <GraphViewerSheet
        v-model:open="graphOpen"
        :kb-id="kbId"
        :doc-id="graphDocId"
        :doc-name="graphDocName"
      />
    </template>
  </div>
</template>

<script setup name="AiKbDetail">
import { listKbWorkbench, getKbAccess } from '@/api/ai/kb'
import DocPanel from './components/DocPanel.vue'
import SearchPanel from './components/SearchPanel.vue'
import GraphViewerSheet from './components/GraphViewerSheet.vue'

const route = useRoute()
const router = useRouter()

const kbId = computed(() => Number(route.params.kbId))
const loading = ref(true)
const loadError = ref('')
const header = ref({})
const access = ref({
  canRead: false,
  canUse: false,
  canWrite: false,
  canManage: false,
  canDelete: false,
  isPlatformAdmin: false
})

const docPanelRef = ref(null)
const searchMounted = ref(false)
/** content | search */
const mainNav = ref('content')
const graphOpen = ref(false)
const graphDocId = ref(null)
const graphDocName = ref('')

function openKbGraph() {
  graphDocId.value = null
  graphDocName.value = ''
  graphOpen.value = true
}

function openDocGraph(doc) {
  if (!doc?.docId) return
  graphDocId.value = doc.docId
  graphDocName.value = doc.docName || ''
  graphOpen.value = true
}

const statusKey = computed(() => {
  if (header.value.status === '1' || header.value.health === 'DISABLED') return 'disabled'
  if (header.value.health === 'ISSUE' || (header.value.failedCount || 0) > 0) return 'issue'
  if (header.value.health === 'PROCESSING' || (header.value.processingCount || 0) > 0) return 'processing'
  if (header.value.health === 'EMPTY' || !(header.value.docCount > 0)) return 'empty'
  return 'ready'
})

const statusLabel = computed(() => ({
  disabled: '已停用',
  issue: '有问题',
  processing: '处理中',
  empty: '空库',
  ready: '可用'
})[statusKey.value] || '可用')

/**
 * 路由 tab 兼容：库管理/编辑/图谱等旧链接不再在本页打开，统一落到文件或测试。
 */
function resolveRouteTab(raw) {
  const t = raw == null || raw === '' ? 'content' : String(raw)
  if (t === 'search') return 'search'
  // usage / settings / graph / quality … 全部回文件工作台
  return 'content'
}

function applyTab(raw, { pushQuery = true } = {}) {
  const tab = resolveRouteTab(raw)
  if (tab === 'search') {
    mainNav.value = 'search'
    searchMounted.value = true
  } else {
    mainNav.value = 'content'
  }

  if (pushQuery) {
    const q = { ...route.query }
    if (mainNav.value === 'search') q.tab = 'search'
    else delete q.tab
    // 清掉历史 usage/settings 等 query，避免书签反复带上
    router.replace({ path: route.path, query: q }).catch(() => {})
  }
}

function goTab(key) {
  applyTab(key, { pushQuery: true })
}

function loadHeader() {
  if (!kbId.value) return
  loading.value = true
  loadError.value = ''
  Promise.all([
    listKbWorkbench({ kbId: kbId.value }),
    getKbAccess(kbId.value)
  ]).then(([wbRes, accRes]) => {
    const wb = wbRes.data || {}
    const rows = wb.rows || []
    header.value = rows[0] || { kbId: kbId.value }
    const acc = accRes.data || {}
    access.value = {
      canRead: !!acc.canRead,
      canUse: !!acc.canUse,
      canWrite: !!acc.canWrite,
      canManage: !!acc.canManage,
      canDelete: !!acc.canDelete,
      isPlatformAdmin: !!acc.isPlatformAdmin,
      role: acc.role,
      source: acc.source
    }
    loading.value = false
    applyTab(route.query.tab, { pushQuery: true })
  }).catch((err) => {
    loading.value = false
    loadError.value = err?.msg || err?.message || '知识库不存在或无权访问'
  })
}

function onDocCount(n) {
  if (header.value) header.value.docCount = n || 0
}

function onOpenDocumentFromSearch(docId) {
  if (!docId) return
  mainNav.value = 'content'
  nextTick(() => {
    docPanelRef.value?.openDocument?.(docId)
  })
  const q = { ...route.query }
  delete q.tab
  router.replace({ path: route.path, query: q }).catch(() => {})
}

function goBack() {
  router.push('/ai/kb').catch(() => {
    if (window.history.length > 1) router.back()
  })
}

watch(kbId, () => {
  searchMounted.value = false
  mainNav.value = 'content'
  loadHeader()
}, { immediate: true })

watch(() => route.query.tab, (t) => {
  if (loading.value || loadError.value) return
  applyTab(t, { pushQuery: false })
})
</script>

<style scoped lang="scss">
@use '../../../assets/styles/ai-tokens.scss' as *;

.kb-detail {
  font-family: $font;
  padding: 16px 32px 28px;
  min-height: calc(100vh - 84px);
  background: var(--ai-page-bg);
  -webkit-font-smoothing: antialiased;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  @media (max-width: 768px) { padding: 12px 14px 20px; }
}

.kb-detail__error {
  text-align: center;
  padding: 80px 20px;
}
.kb-detail__error-title {
  margin: 0 0 8px;
  font-size: 16px;
  font-weight: 650;
  color: $text;
}
.kb-detail__error-text {
  margin: 0 0 16px;
  color: $gray;
  font-size: 13px;
}
.kb-detail__error-actions {
  display: flex;
  gap: 10px;
  justify-content: center;
}

/* 单行紧凑顶栏 */
.kb-detail__head {
  display: flex;
  align-items: center;
  gap: 10px 12px;
  flex-wrap: wrap;
  margin-bottom: 12px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--ai-border);
  flex-shrink: 0;
}
.kb-detail__back {
  width: 32px;
  height: 32px;
  flex-shrink: 0;
  border: none;
  border-radius: 50%;
  background: var(--ai-fill-2);
  color: $text;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: all 0.18s $ease;
  &:hover {
    background: var(--ai-hover-strong);
    color: $blue;
    transform: translateX(-1px);
  }
  &:active { transform: scale(0.94); }
  &:focus-visible {
    outline: none;
    box-shadow: 0 0 0 3px rgba(10, 132, 255, 0.22);
  }
}
.kb-detail__titles {
  min-width: 0;
  flex: 1;
}
.kb-detail__title-row {
  display: flex;
  align-items: center;
  gap: 8px 10px;
  flex-wrap: wrap;
  min-width: 0;
}
.kb-detail__title {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: $text;
  letter-spacing: -0.3px;
  line-height: 1.25;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: min(100%, 420px);
}
.kb-detail__badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 980px;
  flex-shrink: 0;
  &.is-ready { color: #248A3D; background: rgba(52,199,89,0.12); .kb-detail__badge-dot { background: $green; box-shadow: 0 0 0 2px rgba(52,199,89,0.18); } }
  &.is-processing { color: $blue; background: rgba(10,132,255,0.1); .kb-detail__badge-dot { background: $blue; box-shadow: 0 0 0 2px rgba(10,132,255,0.18); } }
  &.is-issue { color: #C24A00; background: rgba(255,159,10,0.12); .kb-detail__badge-dot { background: $orange; box-shadow: 0 0 0 2px rgba(255,159,10,0.2); } }
  &.is-empty { color: $gray; background: var(--ai-fill-2); .kb-detail__badge-dot { background: $gray2; } }
  &.is-disabled { color: $gray; background: var(--ai-fill-2); .kb-detail__badge-dot { background: $gray2; } }
}
.kb-detail__badge-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  display: inline-block;
}
.kb-detail__meta {
  display: inline-flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px 12px;
  font-size: 12px;
  color: $gray;
  font-variant-numeric: tabular-nums;
}
.kb-detail__stat-link {
  border: none;
  background: transparent;
  color: $blue;
  cursor: pointer;
  font-size: 12px;
  font-family: $font;
  padding: 0;
  font-weight: 500;
  &.is-warn { color: #C24A00; }
  &:hover { opacity: 0.8; }
}

/* 分段导航：顶栏右侧，省掉一整行 tab bar */
.kb-detail__seg {
  display: inline-flex;
  padding: 3px;
  background: var(--ai-fill-2);
  border-radius: 10px;
  gap: 2px;
  flex-shrink: 0;
}
.kb-detail__seg-item {
  border: none;
  background: transparent;
  border-radius: 8px;
  padding: 6px 14px;
  font-size: 12.5px;
  font-weight: 600;
  font-family: $font;
  color: $text2;
  cursor: pointer;
  transition: all 0.18s $ease;
  &:hover { color: $text; }
  &.is-active {
    background: var(--ai-card-bg);
    color: $text;
    box-shadow: 0 1px 3px var(--ai-border);
  }
}

.kb-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: none;
  background: var(--ai-card-bg);
  color: $text;
  border-radius: 980px;
  padding: 8px 16px;
  font-size: 13.5px;
  font-weight: 500;
  font-family: $font;
  cursor: pointer;
  box-shadow: 0 0 0 1px var(--ai-border-2);
  transition: all 0.2s $ease;
  &:hover { background: var(--ai-fill-1); }
  &--primary {
    background: $blue;
    color: #fff;
    box-shadow: 0 2px 10px rgba(10,132,255,0.32);
    &:hover { background: #0071e3; }
  }
  &:disabled { opacity: 0.55; cursor: not-allowed; }
}

.kb-detail__body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
</style>
