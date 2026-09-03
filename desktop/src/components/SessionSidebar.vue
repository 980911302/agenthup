<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import logoMark from '../assets/agenthub-logo-mark.svg'
import { toast } from '../utils/confirm'

const props = defineProps({
  sessions: { type: Array, default: () => [] },
  currentSessionId: { type: String, default: '' },
  loading: { type: Boolean, default: false },
  streaming: { type: Boolean, default: false },
  user: { type: Object, default: null },
  collapsed: { type: Boolean, default: false },
  activeNav: { type: String, default: 'chat' },
  projects: { type: Array, default: () => [] },
  projectSessions: { type: Object, default: () => ({}) },
  expandedProjectIds: { type: Array, default: () => [] }
})

const emit = defineEmits([
  'new',
  'switch',
  'delete',
  'logout',
  'settings',
  'open-files',
  'open-kb',
  'open-resources',
  'toggle-collapse',
  'create-project',
  'new-project-chat',
  'toggle-project',
  'edit-project',
  'delete-project'
])

const query = ref('')
const avatarFailed = ref(false)
const searchInputRef = ref(null)

const filtered = computed(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return props.sessions
  return props.sessions.filter(s => (s.title || '').toLowerCase().includes(q))
})

const groups = computed(() => {
  const now = new Date()
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const startOfYesterday = startOfToday - 86400000
  const weekAgo = startOfToday - 7 * 86400000
  const buckets = [
    { label: '今天', items: [] },
    { label: '昨天', items: [] },
    { label: '过去 7 天', items: [] },
    { label: '更早', items: [] }
  ]
  for (const s of filtered.value) {
    const t = new Date(s.updateTime || s.createTime).getTime() || 0
    if (t >= startOfToday) buckets[0].items.push(s)
    else if (t >= startOfYesterday) buckets[1].items.push(s)
    else if (t >= weekAgo) buckets[2].items.push(s)
    else buckets[3].items.push(s)
  }
  return buckets.filter(b => b.items.length)
})

function fmtTime(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  const now = new Date()
  const sameDay = d.toDateString() === now.toDateString()
  const pad = n => String(n).padStart(2, '0')
  if (sameDay) return `${pad(d.getHours())}:${pad(d.getMinutes())}`
  return `${d.getMonth() + 1}/${d.getDate()}`
}

const displayName = computed(() => props.user?.userName || props.user?.nickName || '未登录')

const avatarBase = import.meta.env.VITE_APP_BASE_API || '/dev-api'
const avatarUrl = computed(() => {
  const raw = props.user?.avatar || ''
  if (!raw || avatarFailed.value) return ''
  return /^https?:\/\//.test(raw) || raw.startsWith('data:') ? raw : avatarBase + raw
})
const nickName = computed(() => props.user?.nickName || props.user?.userName || '未登录')

function onAvatarError() {
  avatarFailed.value = true
}

/* ---- 底部用户菜单弹窗逻辑 (1:1 像素级对齐参考图) ---- */
const userMenuOpen = ref(false)
const userCardRef = ref(null)
const projectMenuId = ref(null)

function onDocClick(e) {
  if (userMenuOpen.value && userCardRef.value && !userCardRef.value.contains(e.target)) {
    userMenuOpen.value = false
  }
  projectMenuId.value = null
}

async function focusSearch() {
  if (props.collapsed) {
    emit('toggle-collapse')
    await nextTick()
  }
  searchInputRef.value?.focus()
}

function onShortcut(e) {
  if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    focusSearch()
  }
}

onMounted(() => {
  window.addEventListener('click', onDocClick)
  window.addEventListener('keydown', onShortcut)
})

onBeforeUnmount(() => {
  window.removeEventListener('click', onDocClick)
  window.removeEventListener('keydown', onShortcut)
})

function toggleUserMenu(e) {
  e?.stopPropagation?.()
  userMenuOpen.value = !userMenuOpen.value
}

function handleMenuAction(action) {
  userMenuOpen.value = false
  if (action === 'settings') {
    emit('settings')
  } else if (action === 'language') {
    toast('当前语言：简体中文')
  } else if (action === 'help') {
    toast('AgentHub Desktop · 帮助中心即将上线')
  } else if (action === 'logout') {
    emit('logout')
  }
}
</script>

<template>
  <aside class="sidebar" :class="{ 'is-collapsed': collapsed }">
    <!-- 折叠状态 Rail 栏 -->
    <template v-if="collapsed">
      <div class="sidebar__rail">
        <button
          type="button"
          class="sidebar__rail-brand sidebar__rail-action"
          data-tooltip="展开侧栏 (⌘B)"
          aria-label="展开侧栏"
          @click="emit('toggle-collapse')"
        >
          <img :src="logoMark" alt="AgentHub" />
        </button>

        <button
          type="button"
          class="sidebar__rail-action sidebar__rail-action--accent"
          data-tooltip="新对话 (⌘N)"
          aria-label="新建对话"
          @click="emit('new')"
        >
          <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path d="M12 5v14M5 12h14" stroke="currentColor" stroke-width="1.9" stroke-linecap="round"/>
          </svg>
        </button>

        <button
          type="button"
          class="sidebar__rail-action"
          data-tooltip="搜索 (⌘K)"
          aria-label="搜索会话"
          @click="focusSearch"
        >
          <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <circle cx="11" cy="11" r="7" stroke="currentColor" stroke-width="1.8"/>
            <path d="m16.2 16.2 4.1 4.1" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>
          </svg>
        </button>

        <div class="sidebar__rail-divider" aria-hidden="true"></div>

        <button
          type="button"
          class="sidebar__rail-action"
          :class="{ 'is-active': activeNav === 'files' }"
          data-tooltip="文件"
          aria-label="文件"
          @click="emit('open-files')"
        >
          <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path d="M3.5 18.5V7.8A2.3 2.3 0 0 1 5.8 5.5h4l2.1 2.4h6.3a2.3 2.3 0 0 1 2.3 2.3v8.3a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2Z" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"/>
            <path d="M3.7 11h16.5" stroke="currentColor" stroke-width="1.7"/>
          </svg>
        </button>

        <button
          type="button"
          class="sidebar__rail-action"
          :class="{ 'is-active': activeNav === 'kb' }"
          data-tooltip="知识库"
          aria-label="知识库"
          @click="emit('open-kb')"
        >
          <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <ellipse cx="12" cy="5" rx="8.5" ry="3" stroke="currentColor" stroke-width="1.7"/>
            <path d="M3.5 5v7c0 1.66 3.8 3 8.5 3s8.5-1.34 8.5-3V5M3.5 12v7c0 1.66 3.8 3 8.5 3s8.5-1.34 8.5-3v-7" stroke="currentColor" stroke-width="1.7"/>
          </svg>
        </button>

        <button
          type="button"
          class="sidebar__rail-action"
          :class="{ 'is-active': activeNav === 'resources' }"
          data-tooltip="资源库"
          aria-label="资源库"
          @click="emit('open-resources')"
        >
          <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path d="M5 4.5A2.5 2.5 0 0 1 7.5 2H20v19.5H7.5A2.5 2.5 0 0 1 5 19V4.5Z" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"/>
            <path d="M9 2v19.5M13 6h4M13 10h4" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/>
          </svg>
        </button>

        <div class="sidebar__rail-spacer"></div>

        <button
          type="button"
          class="sidebar__rail-action sidebar__rail-action--profile"
          data-tooltip="偏好设置"
          aria-label="偏好设置"
          @click="emit('settings')"
        >
          <img
            v-if="avatarUrl"
            :src="avatarUrl"
            :alt="nickName"
            class="sidebar__rail-avatar"
            @error="onAvatarError"
          />
          <span v-else class="sidebar__rail-avatar-fallback">{{ displayName.slice(0, 1).toUpperCase() }}</span>
        </button>
      </div>
    </template>

    <!-- 展开状态栏 -->
    <template v-else>
      <div class="sidebar__header">
        <div class="sidebar__brand">
          <img class="brand-mark" :src="logoMark" alt="" aria-hidden="true" />
          <span class="sidebar__name">AgentHub</span>
        </div>
        <button type="button" class="sidebar__collapse-btn" title="收起侧边栏 (Cmd+B)" @click="emit('toggle-collapse')">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <rect width="18" height="18" x="3" y="3" rx="2" ry="2"/>
            <line x1="9" x2="9" y1="3" y2="21"/>
            <path d="m14 9-3 3 3 3"/>
          </svg>
        </button>
      </div>

      <!-- 新建对话按钮 (1:1 还原参考图) -->
      <button type="button" class="sidebar__new" title="新建对话" @click="emit('new')">
        <div class="sidebar__new-left">
          <svg class="sidebar__new-icon" width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path d="M8 3v10M3 8h10" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>
          </svg>
          <span class="sidebar__new-text">新对话</span>
        </div>
        <div class="sidebar__kbd-group">
          <kbd class="sidebar__kbd">⌘</kbd>
          <kbd class="sidebar__kbd">Shift</kbd>
          <kbd class="sidebar__kbd">O</kbd>
        </div>
      </button>

      <!-- 快捷搜索条 (1:1 还原参考图) -->
      <div class="sidebar__search">
        <svg width="13" height="13" viewBox="0 0 16 16" fill="none" aria-hidden="true">
          <circle cx="7" cy="7" r="5" stroke="currentColor" stroke-width="1.5"/>
          <path d="M11 11l3.5 3.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
        </svg>
        <input ref="searchInputRef" v-model="query" class="sidebar__search-input" placeholder="搜索" />
        <div class="sidebar__kbd-group">
          <kbd class="sidebar__kbd">⌘</kbd>
          <kbd class="sidebar__kbd">K</kbd>
        </div>
      </div>

      <!-- 快速导航入口 (1:1 还原参考图: 文件 / 知识库 / 资源库) -->
      <nav class="sidebar__nav">
        <button
          type="button"
          class="sidebar__nav-item"
          :class="{ 'is-active': activeNav === 'files' }"
          title="文件"
          @click="emit('open-files')"
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/>
          </svg>
          <span>文件</span>
        </button>
        <button
          type="button"
          class="sidebar__nav-item"
          :class="{ 'is-active': activeNav === 'kb' }"
          title="知识库"
          @click="emit('open-kb')"
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <ellipse cx="12" cy="5" rx="9" ry="3"/>
            <path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"/>
            <path d="M3 12c0 1.66 4 3 9 3s9-1.34 9-3"/>
          </svg>
          <span>知识库</span>
        </button>
        <button
          type="button"
          class="sidebar__nav-item"
          :class="{ 'is-active': activeNav === 'resources' }"
          title="资源库"
          @click="emit('open-resources')"
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path d="M4 19.5v-15A2.5 2.5 0 0 1 6.5 2H20v20H6.5a2.5 2.5 0 0 1-2.5-2.5Z"/>
            <path d="M6 6h10"/>
            <path d="M6 10h10"/>
          </svg>
          <span>资源库</span>
        </button>
      </nav>

      <!-- 项目分组栏 (1:1 还原参考图) -->
      <div class="sidebar__projects-head">
        <span>项目</span>
        <button type="button" class="sidebar__projects-add" title="新建项目" @click="emit('create-project')">
          <svg width="12" height="12" viewBox="0 0 16 16" fill="none"><path d="M8 3v10M3 8h10" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>
        </button>
      </div>
      <div class="sidebar__projects">
        <template v-for="p in projects" :key="p.projectId">
          <div
            class="sidebar__project"
            :class="{ active: expandedProjectIds.includes(p.projectId) }"
            :title="p.description || p.projectName"
            @click="emit('toggle-project', p.projectId)"
          >
            <svg
              class="sidebar__project-chevron"
              :class="{ 'is-open': expandedProjectIds.includes(p.projectId) }"
              width="14"
              height="14"
              viewBox="0 0 16 16"
              fill="none"
              aria-hidden="true"
            >
              <path d="M6 3.5 10.5 8 6 12.5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
            <svg class="sidebar__project-folder" width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M3 7.5A2.5 2.5 0 0 1 5.5 5H10l2 2.5h6.5A2.5 2.5 0 0 1 21 10v7.5a2.5 2.5 0 0 1-2.5 2.5h-13A2.5 2.5 0 0 1 3 17.5v-10Z" />
            </svg>
            <span class="sidebar__project-name">{{ p.projectName }}</span>
            <span class="sidebar__project-meta" @click.stop>
              <button
                type="button"
                class="sidebar__project-compose"
                title="在此项目中新建对话"
                @click="emit('new-project-chat', p.projectId)"
              >
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                  <path d="M12 20h9"/><path d="M16.5 3.5a2.12 2.12 0 0 1 3 3L8 18l-4 1 1-4 11.5-11.5Z"/>
                </svg>
              </button>
              <button
                type="button"
                class="sidebar__project-more"
                title="项目操作"
                @click="projectMenuId = projectMenuId === p.projectId ? null : p.projectId"
              >
                <span>•••</span>
              </button>
              <div v-if="projectMenuId === p.projectId" class="sidebar__project-menu" @click.stop>
                <button type="button" @click="emit('edit-project', p); projectMenuId = null">编辑项目</button>
                <span class="sidebar__project-menu-divider"></span>
                <button type="button" class="is-danger" @click="emit('delete-project', p); projectMenuId = null">删除项目</button>
              </div>
            </span>
          </div>
          <div v-if="expandedProjectIds.includes(p.projectId)" class="sidebar__project-sessions">
            <button
              v-for="s in projectSessions[p.projectId] || []"
              :key="s.sessionId"
              type="button"
              class="sidebar__project-session"
              :class="{ active: s.sessionId === currentSessionId && activeNav === 'chat' }"
              :title="s.title || '未命名对话'"
              @click="emit('switch', s.sessionId)"
            >
              {{ s.title || '未命名对话' }}
            </button>
            <p v-if="!(projectSessions[p.projectId] || []).length" class="sidebar__project-sessions-empty">项目内还没有会话</p>
          </div>
        </template>
        <div v-if="!projects.length" class="sidebar__projects-empty">暂无项目。</div>
      </div>

      <!-- 历史会话列表 (1:1 还原参考图) -->
      <div class="sidebar__list">
        <div v-if="loading" class="sidebar__empty">加载中…</div>
        <div v-else-if="!filtered.length" class="sidebar__empty">
          {{ query ? '没有匹配的会话' : '暂无历史会话' }}
        </div>
        <template v-else>
          <section v-for="group in groups" :key="group.label" class="sidebar__group">
            <div class="sidebar__group-label">{{ group.label }}</div>
            <div
              v-for="s in group.items"
              :key="s.sessionId"
              class="sidebar__item"
              :class="{ active: s.sessionId === currentSessionId && activeNav === 'chat' }"
              @click="emit('switch', s.sessionId)"
            >
              <span class="sidebar__item-title">{{ s.title || '未命名对话' }}</span>
              <button
                type="button"
                class="sidebar__item-del"
                title="删除会话"
                @click.stop="emit('delete', s.sessionId)"
              >
                <svg width="11" height="11" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                  <path d="M4 4l8 8M12 4l-8 8" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
                </svg>
              </button>
            </div>
          </section>
        </template>
      </div>

      <!-- 左下角个人资料与弹窗菜单 (1:1 像素级还原参考图) -->
      <div class="sidebar__foot" ref="userCardRef">
        <!-- 弹出的用户菜单 (1:1 像素级对齐参考图) -->
        <div v-if="userMenuOpen" class="sidebar__user-menu">
          <!-- 第一组：实际可用的设置 -->
          <div class="sidebar__menu-group">
            <button type="button" class="sidebar__menu-item" @click="handleMenuAction('settings')">
              <div class="sidebar__menu-item-left">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <circle cx="12" cy="12" r="3"/>
                  <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/>
                </svg>
                <span>设置</span>
              </div>
            </button>
          </div>

          <div class="sidebar__menu-divider"></div>

          <!-- 第二组：语言与帮助 -->
          <div class="sidebar__menu-group">
            <button type="button" class="sidebar__menu-item sidebar__menu-item--has-sub" @click="handleMenuAction('language')">
              <div class="sidebar__menu-item-left">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <path d="m5 8 6 6"/>
                  <path d="m4 14 6-6 2-3"/>
                  <path d="M2 5h12"/>
                  <path d="M7 2h1"/>
                  <path d="m22 22-5-10-5 10"/>
                  <path d="M14 18h6"/>
                </svg>
                <span>语言</span>
              </div>
              <svg class="sidebar__menu-arrow" width="12" height="12" viewBox="0 0 16 16" fill="none"><path d="M6 3.5l4.5 4.5-4.5 4.5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>
            </button>
            <button type="button" class="sidebar__menu-item sidebar__menu-item--has-sub" @click="handleMenuAction('help')">
              <div class="sidebar__menu-item-left">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <circle cx="12" cy="12" r="10"/>
                  <path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3"/>
                  <path d="M12 17h.01"/>
                </svg>
                <span>帮助</span>
              </div>
              <svg class="sidebar__menu-arrow" width="12" height="12" viewBox="0 0 16 16" fill="none"><path d="M6 3.5l4.5 4.5-4.5 4.5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>
            </button>
          </div>

          <div class="sidebar__menu-divider"></div>

          <!-- 第四组: 退出登录 -->
          <div class="sidebar__menu-group">
            <button type="button" class="sidebar__menu-item sidebar__menu-item--danger" @click="handleMenuAction('logout')">
              <div class="sidebar__menu-item-left">
                <span>退出登录</span>
              </div>
            </button>
          </div>
        </div>

        <!-- 底部一排：UserMenu + SpaceSwitcherButton (1:1 像素级还原 Aivory 官方源码) -->
        <div class="sidebar__footer-row">
          <button
            type="button"
            class="sidebar__user-card"
            :class="{ 'is-active': userMenuOpen }"
            title="账号与设置"
            @click="toggleUserMenu"
          >
            <div class="sidebar__avatar-wrap">
              <img
                v-if="avatarUrl"
                :src="avatarUrl"
                :alt="nickName"
                class="sidebar__avatar-img"
                @error="onAvatarError"
              />
              <span v-else class="sidebar__avatar">{{ displayName.slice(0, 2).toUpperCase() }}</span>
            </div>

            <div class="sidebar__user-details">
              <div class="sidebar__user-name-row">
                <span class="sidebar__user-name">{{ nickName }}</span>
              </div>
            </div>
          </button>

        </div>
      </div>
    </template>
  </aside>
</template>

<style scoped lang="scss">
.sidebar {
  position: relative;
  z-index: 40;
  width: 272px;
  height: 100%;
  background: var(--bg-sidebar);
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  user-select: none;
  transition: width 0.2s cubic-bezier(0.16, 1, 0.3, 1);
  flex-shrink: 0;
  overflow: visible;

  &.is-collapsed {
    width: 54px;
  }

  &__rail {
    position: relative;
    z-index: 30;
    height: 100%;
    display: flex;
    flex-direction: column;
    align-items: center;
    padding: 15px 0 12px;
    gap: 7px;
    overflow: visible;
  }

  &__rail-brand,
  &__rail-action {
    position: relative;
    width: 36px;
    height: 36px;
    flex: 0 0 36px;
    display: flex;
    align-items: center;
    justify-content: center;
    border: 0;
    border-radius: 10px;
    background: transparent;
    color: var(--text-secondary);
    cursor: pointer;
    transition: color 140ms ease, background 140ms ease, transform 140ms ease;

    > svg {
      width: 21px;
      height: 21px;
    }

    &:hover,
    &:focus-visible {
      color: var(--text);
      background: var(--bg-hover);
      outline: none;
    }

    &:active {
      transform: scale(0.94);
    }

    &.is-active {
      color: var(--accent);
      background: var(--accent-weak);
    }

    &::before,
    &::after {
      position: absolute;
      left: calc(100% + 11px);
      top: 50%;
      pointer-events: none;
      opacity: 0;
      visibility: hidden;
      transition: opacity 120ms ease, transform 120ms ease, visibility 120ms;
      z-index: 80;
    }

    &::before {
      content: '';
      width: 9px;
      height: 9px;
      border-radius: 2px;
      background: #f4f3f8;
      transform: translate(-4px, -50%) rotate(45deg) scale(0.9);
      box-shadow: -1px 1px 1px rgba(15, 18, 27, 0.04);
    }

    &::after {
      content: attr(data-tooltip);
      min-height: 36px;
      display: flex;
      align-items: center;
      padding: 0 13px;
      border-radius: 11px;
      background: #f4f3f8;
      color: #222434;
      box-shadow: 0 8px 24px rgba(0, 0, 0, 0.16);
      font-size: 13px;
      font-weight: 650;
      line-height: 1;
      white-space: nowrap;
      transform: translate(3px, -50%) scale(0.97);
      transform-origin: left center;
    }

    &:hover::before,
    &:hover::after,
    &:focus-visible::before,
    &:focus-visible::after {
      opacity: 1;
      visibility: visible;
    }

    &:hover::before,
    &:focus-visible::before {
      transform: translate(-4px, -50%) rotate(45deg) scale(1);
    }

    &:hover::after,
    &:focus-visible::after {
      transform: translate(3px, -50%) scale(1);
    }
  }

  &__rail-brand {
    margin-bottom: 7px;

    img {
      width: 29px;
      height: 29px;
      display: block;
      border-radius: 9px;
    }
  }

  &__rail-action--accent {
    color: var(--accent);
  }

  &__rail-divider {
    width: 28px;
    height: 1px;
    margin: 2px 0 5px;
    background: var(--border);
  }

  &__rail-spacer {
    flex: 1;
    min-height: 14px;
  }

  &__rail-action--profile {
    padding: 5px;
  }

  &__rail-avatar, &__rail-avatar-fallback {
    width: 26px;
    height: 26px;
    border-radius: 50%;
    object-fit: cover;
  }

  &__rail-avatar-fallback {
    background: var(--accent-gradient);
    color: #fff;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 12px;
    font-weight: 700;
  }

  /* 顶部 Header */
  &__header {
    height: 52px;
    padding: 0 14px;
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  &__brand {
    display: flex;
    align-items: center;
    gap: 9px;
  }

  .brand-mark {
    width: 24px;
    height: 24px;
    display: block;
  }

  &__name {
    font-size: 15px;
    font-weight: 700;
    letter-spacing: -0.01em;
    color: var(--text);
  }

  &__collapse-btn {
    width: 28px;
    height: 28px;
    border-radius: 7px;
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--text-tertiary);
    border: none;
    background: transparent;
    cursor: pointer;
    transition: background 0.14s ease, color 0.14s ease;

    &:hover {
      background: var(--bg-hover);
      color: var(--text);
    }
  }

  /* 新建对话 (1:1 还原参考图) */
  &__new {
    margin: 4px 10px;
    height: 36px;
    border-radius: 10px;
    background: transparent;
    border: none;
    padding: 0 10px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    cursor: pointer;
    transition: all 0.14s ease;

    &:hover {
      background: var(--bg-hover);
    }

    &-left {
      display: flex;
      align-items: center;
      gap: 9px;
    }

    &-icon {
      color: var(--accent);
    }

    &-text {
      font-size: 13.5px;
      font-weight: 500;
      color: var(--text);
    }
  }

  /* 快捷搜索 (1:1 还原参考图) */
  &__search {
    margin: 2px 10px 8px;
    height: 36px;
    border-radius: 10px;
    background: transparent;
    border: none;
    padding: 0 10px;
    display: flex;
    align-items: center;
    gap: 9px;
    color: var(--text-tertiary);
    transition: all 0.14s ease;

    &:hover {
      background: var(--bg-hover);
    }

    &:focus-within {
      background: var(--bg-raised);
      box-shadow: var(--shadow-card);
      color: var(--text);
    }

    &-input {
      flex: 1;
      min-width: 0;
      border: none;
      background: transparent;
      outline: none;
      color: var(--text);
      font-size: 13.5px;
      font-family: inherit;

      &::placeholder {
        color: var(--text-tertiary);
      }
    }
  }

  &__kbd-group {
    display: inline-flex;
    align-items: center;
    gap: 3px;
  }

  &__kbd {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    height: 18px;
    padding: 0 5px;
    min-width: 16px;
    font-size: 10.5px;
    font-family: inherit;
    font-weight: 500;
    border-radius: 4px;
    background: var(--bg-raised);
    color: var(--text-tertiary);
    border: 1px solid var(--border);
    box-shadow: var(--shadow-card);
  }

  /* 快速导航入口 [文件 | 知识库 | 资源库] */
  &__nav {
    display: flex;
    flex-direction: column;
    gap: 2px;
    padding: 2px 10px 6px;
  }

  &__nav-item {
    display: flex;
    align-items: center;
    gap: 10px;
    height: 36px;
    padding: 0 10px;
    border-radius: 10px;
    border: none;
    background: transparent;
    color: var(--text-secondary);
    font-size: 13.5px;
    font-weight: 500;
    cursor: pointer;
    transition: all 0.14s ease;

    svg {
      color: var(--text-secondary);
      flex-shrink: 0;
    }

    &:hover {
      background: var(--bg-hover);
      color: var(--text);
    }

    &.is-active {
      background: var(--bg-raised) !important;
      color: var(--text) !important;
      font-weight: 600;
      border-radius: 12px;
      box-shadow: var(--shadow-card);

      svg {
        color: var(--accent) !important;
      }
    }
  }

  /* 项目分组 */
  &__projects-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 16px 14px 5px;
    font-size: 12px;
    font-weight: 650;
    letter-spacing: 0.02em;
    color: var(--text-tertiary);
  }

  &__projects-add {
    width: 18px;
    height: 18px;
    border-radius: 6px;
    border: 1px solid transparent;
    background: transparent;
    color: var(--text-tertiary);
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;

    &:hover {
      border-color: var(--border);
      background: var(--bg-hover);
      color: var(--accent);
    }
  }

  &__projects-empty {
    padding: 2px 14px 12px;
    font-size: 12px;
    color: var(--text-tertiary);
  }

  &__projects {
    padding: 0 10px 8px;
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  &__project {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 7px;
    min-height: 34px;
    padding: 6px 10px;
    border-radius: 8px;
    font-size: 13px;
    color: var(--text-secondary);
    cursor: pointer;

    &:hover { background: var(--bg-hover); color: var(--text); }
    &.active {
      background: var(--bg-hover);
      color: var(--text);
      font-weight: 600;
    }
    &-chevron { flex: 0 0 auto; color: var(--text-tertiary); transition: transform 0.16s ease; }
    &-chevron.is-open { transform: rotate(90deg); color: var(--accent); }
    &-folder { flex: 0 0 auto; color: var(--text-secondary); }
    &.active &-folder { color: var(--accent); }
    &-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    &-meta { position: relative; display: flex; align-items: center; gap: 1px; flex-shrink: 0; }
    &-compose, &-more {
      width: 24px; height: 24px; border: none; border-radius: 6px;
      background: transparent; color: var(--text-tertiary);
      display: flex; align-items: center; justify-content: center; cursor: pointer;
      transition: all 0.12s ease;
      &:hover { background: var(--bg-raised); color: var(--text); }
    }
    &-more span { display: block; transform: translateY(-2px); font-size: 13px; letter-spacing: 1px; }
    &-menu {
      position: absolute;
      z-index: 30;
      top: calc(100% + 5px);
      right: 0;
      min-width: 136px;
      padding: 5px;
      border: 1px solid var(--border-strong);
      border-radius: 10px;
      background: var(--bg-elevated);
      box-shadow: var(--shadow);

      button {
        width: 100%;
        height: 30px;
        padding: 0 8px;
        border: none;
        border-radius: 6px;
        background: transparent;
        color: var(--text-secondary);
        font: inherit;
        font-size: 12px;
        text-align: left;
        cursor: pointer;
        &:hover { background: var(--bg-hover); color: var(--text); }
        &.is-danger:hover { background: var(--danger-weak); color: var(--danger-text); }
      }
      &-divider { display: block; height: 1px; margin: 4px; background: var(--divider); }
    }
    &-sessions { display: flex; flex-direction: column; gap: 1px; margin: 1px 0 5px 25px; }
    &-session {
      width: 100%;
      height: 30px;
      padding: 0 8px;
      border: none;
      border-radius: 6px;
      background: transparent;
      color: var(--text-secondary);
      font: inherit;
      font-size: 12.5px;
      overflow: hidden;
      text-align: left;
      text-overflow: ellipsis;
      white-space: nowrap;
      cursor: pointer;
      &:hover, &.active { background: var(--bg-hover); color: var(--text); }
      &.active { font-weight: 600; }
    }
    &-sessions-empty { margin: 2px 0 6px 8px; color: var(--text-tertiary); font-size: 11.5px; }
  }

  /* 历史会话列表 */
  &__list {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    padding: 0 10px;
  }

  &__empty {
    padding: 24px 12px;
    text-align: center;
    font-size: 12px;
    color: var(--text-tertiary);
  }

  &__group {
    margin-bottom: 14px;

    &-label {
      font-size: 12px;
      font-weight: 500;
      color: var(--text-tertiary);
      padding: 8px 10px 4px;
    }
  }

  &__item {
    position: relative;
    display: flex;
    align-items: center;
    justify-content: space-between;
    min-height: 36px;
    padding: 8px 10px;
    border-radius: 10px;
    font-size: 13.5px;
    color: var(--text-secondary);
    cursor: pointer;
    transition: all 0.14s ease;

    &:hover {
      background: var(--bg-hover);
      color: var(--text);

      .sidebar__item-del {
        opacity: 1;
      }
    }

    /* 选中卡片 (1:1 还原参考图中「问候」的纯白浮起卡片质感) */
    &.active {
      background: var(--bg-raised) !important;
      color: var(--text) !important;
      font-weight: 650;
      border-radius: 12px;
      box-shadow: var(--shadow-card);
      padding: 10px 14px;
      min-height: 42px;

      .sidebar__item-title {
        color: var(--text);
        font-weight: 650;
      }
    }

    &-title {
      flex: 1;
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    &-del {
      opacity: 0;
      width: 18px;
      height: 18px;
      border-radius: 4px;
      border: none;
      background: transparent;
      color: var(--text-tertiary);
      display: flex;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      transition: all 0.12s ease;

      &:hover {
        background: rgba(239, 68, 68, 0.1);
        color: var(--danger);
      }
    }
  }

  /* 底部区域 (1:1 像素级还原 Aivory 官方源码) */
  &__foot {
    position: relative;
    padding: 8px;
    margin-top: auto;
    background: transparent;
    border-top: none;
  }

  &__footer-row {
    display: flex;
    align-items: center;
    gap: 4px;
  }

  /* 弹出式用户菜单 */
  &__user-menu {
    position: absolute;
    bottom: calc(100% + 8px);
    left: 8px;
    right: 8px;
    background: var(--bg-elevated);
    border: 1px solid var(--border-strong);
    border-radius: 18px;
    box-shadow: var(--shadow);
    padding: 6px;
    z-index: 99;
    backdrop-filter: blur(20px);
    animation: menu-pop 0.16s cubic-bezier(0.16, 1, 0.3, 1) both;
    display: flex;
    flex-direction: column;
  }

  &__menu-group {
    display: flex;
    flex-direction: column;
    gap: 1px;
  }

  &__menu-divider {
    height: 1px;
    background: var(--divider);
    margin: 4px 6px;
  }

  &__menu-item {
    display: flex;
    align-items: center;
    justify-content: space-between;
    width: 100%;
    padding: 8px 10px;
    border-radius: 8px;
    border: none;
    background: transparent;
    color: var(--text);
    font-size: 13px;
    font-weight: 550;
    cursor: pointer;
    text-align: left;
    transition: all 0.12s ease;

    svg {
      color: var(--text-tertiary);
      flex-shrink: 0;
      transition: color 0.12s ease;
    }

    &:hover {
      background: var(--bg-hover);
      svg { color: var(--text); }
    }

    &-left {
      display: flex;
      align-items: center;
      gap: 10px;
    }

    &--danger {
      color: var(--text);
      &:hover {
        background: rgba(239, 68, 68, 0.08);
        color: var(--danger);
      }
    }
  }

  &__menu-arrow {
    color: var(--text-tertiary);
  }

  /* 用户卡片按钮 (UserMenu 1:1 还原 Aivory 官方源码) */
  &__user-card {
    flex: 1;
    min-width: 0;
    display: flex;
    align-items: center;
    gap: 10px;
    min-height: 44px;
    padding: 4px 8px;
    border-radius: 10px;
    background: transparent;
    border: none;
    cursor: pointer;
    text-align: left;
    transition: all 0.14s ease;

    &:hover, &.is-active {
      background: var(--bg-raised);
      box-shadow: var(--shadow-card);
    }
  }

  &__avatar-wrap {
    position: relative;
    width: 32px;
    height: 32px;
    flex-shrink: 0;
  }

  &__avatar, &__avatar-img {
    width: 100%;
    height: 100%;
    border-radius: 50%;
  }

  &__avatar {
    background: var(--accent-weak);
    color: var(--accent);
    font-size: 12px;
    font-weight: 600;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  &__avatar-img {
    object-fit: cover;
  }

  &__user-details {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 1px;
  }

  &__user-name-row {
    display: flex;
    align-items: center;
    gap: 6px;
  }

  &__user-name {
    font-size: 13.5px;
    font-weight: 500;
    color: var(--text);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

}
</style>
