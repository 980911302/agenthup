<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

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
const projectMenuId = ref(null)
const projectMenuStyle = ref({})

const activeProject = computed(() =>
  props.projects.find(p => Number(p.projectId) === Number(projectMenuId.value)) || null
)

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

function isProjectExpanded(id) {
  return props.expandedProjectIds.some(item => Number(item) === Number(id))
}

async function focusSearch() {
  if (props.collapsed) {
    emit('toggle-collapse')
    await nextTick()
  }
  searchInputRef.value?.focus()
}

function onShortcut(e) {
  if (e.key === 'Escape' && projectMenuId.value != null) {
    e.preventDefault()
    closeProjectMenu()
    return
  }
  if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    focusSearch()
  }
}

function closeProjectMenu() {
  projectMenuId.value = null
}

function toggleProjectMenu(event, projectId) {
  event.stopPropagation()
  if (Number(projectMenuId.value) === Number(projectId)) {
    closeProjectMenu()
    return
  }
  const rect = event.currentTarget.getBoundingClientRect()
  const menuWidth = 152
  const menuHeight = 88
  const gap = 6
  const pad = 8
  let left = rect.right - menuWidth
  let top = rect.bottom + gap
  left = Math.max(pad, Math.min(left, window.innerWidth - menuWidth - pad))
  if (top + menuHeight > window.innerHeight - pad) {
    top = Math.max(pad, rect.top - menuHeight - gap)
  }
  projectMenuStyle.value = {
    top: `${Math.round(top)}px`,
    left: `${Math.round(left)}px`
  }
  projectMenuId.value = projectId
}

function onDocClick() {
  closeProjectMenu()
}

watch(() => props.collapsed, (collapsed) => {
  if (collapsed) closeProjectMenu()
})

onMounted(() => {
  window.addEventListener('keydown', onShortcut)
  document.addEventListener('click', onDocClick)
  window.addEventListener('resize', closeProjectMenu)
  window.addEventListener('scroll', closeProjectMenu, true)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onShortcut)
  document.removeEventListener('click', onDocClick)
  window.removeEventListener('resize', closeProjectMenu)
  window.removeEventListener('scroll', closeProjectMenu, true)
})
</script>

<template>
  <div>
    <transition name="drawer-fade">
      <div v-if="!collapsed" class="sess-drawer-mask" @click="emit('toggle-collapse')" />
    </transition>
    <aside class="sidebar sess-drawer" :class="{ 'is-open': !collapsed }">
      <div class="sidebar__atmosphere" aria-hidden="true">
        <span class="sidebar__orb sidebar__orb--one" />
        <span class="sidebar__orb sidebar__orb--two" />
      </div>

      <div class="sidebar__header">
        <div class="sidebar__heading">
          <span class="sidebar__eyebrow"><i /> AGENTHUB</span>
          <div class="sidebar__title-row">
            <span class="sidebar__name">会话</span>
            <span class="sidebar__session-count">{{ sessions.length }}</span>
          </div>
        </div>
        <div class="sidebar__header-actions">
          <button type="button" class="sidebar__icon-btn" title="关闭" aria-label="关闭" @click="emit('toggle-collapse')">
            <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
              <path d="M3.5 3.5l7 7M10.5 3.5l-7 7" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/>
            </svg>
          </button>
        </div>
      </div>

      <button type="button" class="sidebar__new" title="新对话" @click="emit('new')">
        <span class="sidebar__new-icon">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path d="M12 3.5 13.7 8.3 18.5 10l-4.8 1.7L12 16.5l-1.7-4.8L5.5 10l4.8-1.7L12 3.5Z" fill="currentColor" />
            <path d="m18.5 15 .8 2.2 2.2.8-2.2.8-.8 2.2-.8-2.2-2.2-.8 2.2-.8.8-2.2Z" fill="currentColor" opacity=".72" />
          </svg>
        </span>
        <span class="sidebar__new-copy">
          <strong>开启新对话</strong>
          <small>把下一个想法变成行动</small>
        </span>
        <svg class="sidebar__new-arrow" width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true">
          <path d="m6 3.5 4.5 4.5L6 12.5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </button>

      <!-- 快捷搜索条 -->
      <div class="sidebar__search">
        <svg width="13" height="13" viewBox="0 0 16 16" fill="none" aria-hidden="true">
          <circle cx="7" cy="7" r="5" stroke="currentColor" stroke-width="1.5"/>
          <path d="M11 11l3.5 3.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
        </svg>
        <input ref="searchInputRef" v-model="query" class="sidebar__search-input" placeholder="搜索会话…" />
        <div class="sidebar__kbd-group">
          <kbd class="sidebar__kbd">⌘</kbd>
          <kbd class="sidebar__kbd">K</kbd>
        </div>
      </div>

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
            :class="{ active: isProjectExpanded(p.projectId) }"
            :title="p.description || p.projectName"
            @click="emit('toggle-project', p.projectId)"
          >
            <svg
              class="sidebar__project-chevron"
              :class="{ 'is-open': isProjectExpanded(p.projectId) }"
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
                :class="{ 'is-open': Number(projectMenuId) === Number(p.projectId) }"
                @click="toggleProjectMenu($event, p.projectId)"
              >
                <span>•••</span>
              </button>
            </span>
          </div>
          <div v-if="isProjectExpanded(p.projectId)" class="sidebar__project-sessions">
            <div
              v-for="s in projectSessions[p.projectId] || []"
              :key="s.sessionId"
              class="sidebar__project-session"
              :class="{ active: s.sessionId === currentSessionId && activeNav === 'chat' }"
              :title="s.title || '未命名对话'"
              @click="emit('switch', s.sessionId)"
            >
              <span>{{ s.title || '未命名对话' }}</span>
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
            <p v-if="!(projectSessions[p.projectId] || []).length" class="sidebar__project-sessions-empty">项目内还没有会话</p>
          </div>
        </template>
        <div v-if="!projects.length" class="sidebar__projects-empty">暂无项目。</div>
      </div>

      <!-- 历史会话列表 -->
      <div class="sidebar__history-head">
        <span>最近会话</span>
        <span>{{ filtered.length }} 条</span>
      </div>
      <div class="sidebar__list">
        <div v-if="loading" class="sidebar__empty">
          <span class="sidebar__empty-icon is-loading" aria-hidden="true" />
          <strong>正在整理会话</strong>
          <span>马上就好</span>
        </div>
        <div v-else-if="!filtered.length" class="sidebar__empty">
          <span class="sidebar__empty-icon" aria-hidden="true">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
              <path d="M20 11.5a7.5 7.5 0 0 1-8 7.47L7 21v-3.13a7.5 7.5 0 1 1 13-6.37Z" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </span>
          <strong>{{ query ? '没有找到相关会话' : '还没有历史会话' }}</strong>
          <span>{{ query ? '换个关键词再试试' : '从上面的新对话开始吧' }}</span>
        </div>
        <template v-else>
          <section v-for="group in groups" :key="group.label" class="sidebar__group">
            <div class="sidebar__group-label">
              <span>{{ group.label }}</span>
              <i />
              <small>{{ group.items.length }}</small>
            </div>
            <div
              v-for="s in group.items"
              :key="s.sessionId"
              class="sidebar__item"
              :class="{ active: s.sessionId === currentSessionId && activeNav === 'chat' }"
              @click="emit('switch', s.sessionId)"
            >
              <span class="sidebar__item-main">
                <span class="sidebar__item-title">{{ s.title || '未命名对话' }}</span>
                <span class="sidebar__item-meta">
                  <span>{{ fmtTime(s.updateTime || s.createTime) || '最近' }}</span>
                  <span v-if="s.sessionId === currentSessionId && activeNav === 'chat'" class="sidebar__item-current"><i /> 当前会话</span>
                </span>
              </span>
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

      <div class="sidebar__foot">
        <div class="sidebar__user-card">
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
          <span class="sidebar__user-details">
            <span class="sidebar__user-name">{{ nickName }}</span>
            <span class="sidebar__user-caption"><i /> AgentHub 账户</span>
          </span>
          <button type="button" class="sidebar__logout" title="退出登录" @click.stop="emit('logout')">退出</button>
        </div>
      </div>
    </aside>

    <Teleport to="body">
      <div
        v-if="!collapsed && activeProject"
        class="sidebar__project-menu"
        :style="projectMenuStyle"
        @click.stop
      >
        <button type="button" @click="emit('edit-project', activeProject); closeProjectMenu()">编辑项目</button>
        <span class="sidebar__project-menu-divider"></span>
        <button type="button" class="is-danger" @click="emit('delete-project', activeProject); closeProjectMenu()">删除项目</button>
      </div>
    </Teleport>
  </div>
</template>

<style scoped lang="scss">
.sess-drawer-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  backdrop-filter: blur(2px);
  z-index: 1000;
}

.drawer-fade-enter-active,
.drawer-fade-leave-active {
  transition: opacity 0.2s ease;
}
.drawer-fade-enter-from,
.drawer-fade-leave-to {
  opacity: 0;
}

.sidebar {
  position: fixed;
  top: 0;
  left: 0;
  bottom: 0;
  z-index: 1001;
  width: 320px;
  height: 100%;
  background:
    linear-gradient(180deg, color-mix(in srgb, var(--accent) 6%, var(--bg-raised)) 0, var(--bg-raised) 190px),
    var(--bg-raised, var(--bg-sidebar));
  border-right: 1px solid var(--border);
  box-shadow: var(--shadow, 4px 0 24px rgba(0, 0, 0, 0.15));
  display: flex;
  flex-direction: column;
  user-select: none;
  transform: translateX(-100%);
  transition: transform 0.24s cubic-bezier(0.16, 1, 0.3, 1);
  overflow: hidden;

  > :not(.sidebar__atmosphere) {
    position: relative;
    z-index: 1;
  }

  &.is-open {
    transform: translateX(0);
  }

  &__atmosphere {
    position: absolute;
    inset: 0 0 auto;
    height: 230px;
    overflow: hidden;
    pointer-events: none;
  }

  &__orb {
    position: absolute;
    display: block;
    border-radius: 50%;
    filter: blur(1px);

    &--one {
      width: 176px;
      height: 176px;
      top: -112px;
      right: -62px;
      background: radial-gradient(circle, color-mix(in srgb, var(--accent) 22%, transparent), transparent 68%);
    }

    &--two {
      width: 120px;
      height: 120px;
      top: 74px;
      left: -86px;
      background: radial-gradient(circle, color-mix(in srgb, #8b5cf6 13%, transparent), transparent 70%);
    }
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
    height: auto;
    min-height: 82px;
    padding: 17px 16px 15px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    border-bottom: 0;
    flex-shrink: 0;
  }

  &__heading {
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 5px;
  }

  &__eyebrow {
    display: flex;
    align-items: center;
    gap: 6px;
    color: var(--accent);
    font-size: 9px;
    font-weight: 750;
    letter-spacing: 0.14em;

    i {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background: var(--ok);
      box-shadow: 0 0 0 4px var(--ok-weak);
    }
  }

  &__title-row {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  &__session-count {
    min-width: 22px;
    height: 20px;
    padding: 0 6px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    border: 1px solid var(--accent-border);
    border-radius: 999px;
    background: var(--accent-weak);
    color: var(--accent);
    font-size: 10.5px;
    font-weight: 700;
  }

  &__header-actions {
    display: flex;
    align-items: center;
    gap: 4px;
  }

  &__icon-btn {
    width: 32px;
    height: 32px;
    border: 1px solid var(--border);
    background: color-mix(in srgb, var(--bg-raised) 72%, transparent);
    color: var(--text-tertiary);
    border-radius: 10px;
    cursor: pointer;
    display: inline-flex;
    align-items: center;
    justify-content: center;

    &:hover {
      background: var(--accent-weak);
      color: var(--accent);
      border-color: var(--accent-border);
    }
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
    font-size: 21px;
    line-height: 1.15;
    font-weight: 720;
    letter-spacing: -0.04em;
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

  /* 新建对话 */
  &__new {
    position: relative;
    margin: 0 12px 10px;
    min-height: 60px;
    border-radius: 16px;
    background: linear-gradient(135deg, #2563eb 0%, #3b82f6 55%, #6d5ce7 112%);
    border: none;
    padding: 10px 12px;
    display: flex;
    align-items: center;
    gap: 11px;
    cursor: pointer;
    color: #fff;
    overflow: hidden;
    box-shadow: 0 10px 24px -12px rgba(37, 99, 235, 0.82), 0 4px 10px -6px rgba(37, 99, 235, 0.58);
    transition: transform 0.16s ease, box-shadow 0.16s ease;

    &::after {
      content: '';
      position: absolute;
      width: 90px;
      height: 90px;
      right: -44px;
      top: -54px;
      border: 1px solid rgba(255, 255, 255, 0.22);
      border-radius: 50%;
      box-shadow: 0 0 0 16px rgba(255, 255, 255, 0.04);
    }

    &:hover {
      transform: translateY(-1px);
      box-shadow: 0 15px 28px -13px rgba(37, 99, 235, 0.9), 0 6px 12px -7px rgba(37, 99, 235, 0.6);
    }

    &:active {
      transform: translateY(0) scale(0.985);
    }

    &-icon {
      width: 36px;
      height: 36px;
      flex: 0 0 36px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      border: 1px solid rgba(255, 255, 255, 0.24);
      border-radius: 12px;
      background: rgba(255, 255, 255, 0.14);
      color: #fff;
      box-shadow: inset 0 1px rgba(255, 255, 255, 0.2);
    }

    &-copy {
      position: relative;
      z-index: 1;
      flex: 1;
      min-width: 0;
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      gap: 2px;

      strong {
        color: #fff;
        font-size: 13.5px;
        font-weight: 700;
      }

      small {
        overflow: hidden;
        color: rgba(255, 255, 255, 0.72);
        font-size: 10.5px;
        line-height: 1.3;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
    }

    &-arrow {
      position: relative;
      z-index: 1;
      flex: 0 0 auto;
      color: rgba(255, 255, 255, 0.76);
    }
  }

  /* 快捷搜索 (1:1 还原参考图) */
  &__search {
    margin: 0 12px 8px;
    height: 40px;
    border-radius: 12px;
    background: color-mix(in srgb, var(--bg-raised) 92%, var(--accent) 8%);
    border: 1px solid var(--border);
    padding: 0 12px;
    display: flex;
    align-items: center;
    gap: 9px;
    color: var(--text-tertiary);
    box-shadow: 0 3px 10px rgba(30, 64, 175, 0.035);
    transition: border-color 0.14s ease, box-shadow 0.14s ease, background 0.14s ease;

    &:hover {
      border-color: var(--border-strong);
    }

    &:focus-within {
      background: var(--bg-raised);
      border-color: var(--accent-border);
      box-shadow: var(--ring);
      color: var(--accent);
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
    padding: 10px 14px 5px;
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
    max-height: 38%;
    overflow-y: auto;
    flex-shrink: 0;
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
    &-more.is-open { background: var(--bg-raised); color: var(--text); }
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
      display: flex;
      align-items: center;
      gap: 6px;
      overflow: hidden;
      text-align: left;
      cursor: pointer;
      span {
        flex: 1;
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      &:hover, &.active { background: var(--bg-hover); color: var(--text); }
      &.active { font-weight: 600; }
      &:hover .sidebar__item-del { opacity: 1; }
    }
    &-sessions-empty { margin: 2px 0 6px 8px; color: var(--text-tertiary); font-size: 11.5px; }
  }

  /* 历史会话列表 */
  &__history-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 12px 16px 5px;
    color: var(--text);
    font-size: 12px;
    font-weight: 680;

    span:last-child {
      color: var(--text-tertiary);
      font-size: 10.5px;
      font-weight: 550;
    }
  }

  &__list {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    padding: 0 10px;
  }

  &__empty {
    min-height: 160px;
    padding: 28px 12px;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 5px;
    text-align: center;
    color: var(--text-tertiary);

    strong {
      color: var(--text-secondary);
      font-size: 12.5px;
      font-weight: 650;
    }

    > span:last-child {
      font-size: 11px;
    }

    &-icon {
      width: 38px;
      height: 38px;
      margin-bottom: 5px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      border: 1px solid var(--accent-border);
      border-radius: 13px;
      background: var(--accent-weak);
      color: var(--accent);

      &.is-loading {
        width: 24px;
        height: 24px;
        border: 2px solid var(--accent-weak);
        border-top-color: var(--accent);
        border-radius: 50%;
        background: transparent;
        animation: sidebar-spin 0.8s linear infinite;
      }
    }
  }

  &__group {
    margin-bottom: 14px;

    &-label {
      display: flex;
      align-items: center;
      gap: 7px;
      font-size: 12px;
      font-weight: 620;
      color: var(--text-tertiary);
      padding: 8px 7px 5px;

      i {
        height: 1px;
        flex: 1;
        background: var(--divider);
      }

      small {
        min-width: 17px;
        color: var(--text-tertiary);
        font-size: 9.5px;
        font-weight: 600;
        text-align: right;
      }
    }
  }

  &__item {
    position: relative;
    display: flex;
    align-items: center;
    justify-content: space-between;
    min-height: 52px;
    padding: 7px 9px;
    border: 1px solid transparent;
    border-radius: 12px;
    font-size: 13.5px;
    color: var(--text-secondary);
    cursor: pointer;
    transition: background 0.14s ease, border-color 0.14s ease, box-shadow 0.14s ease, transform 0.14s ease;

    &:hover {
      background: var(--bg-hover);
      border-color: var(--border);
      color: var(--text);
      transform: translateX(1px);

      .sidebar__item-del {
        opacity: 1;
      }
    }

    &.active {
      background: linear-gradient(105deg, var(--accent-weak), var(--bg-raised) 68%) !important;
      border-color: var(--accent-border);
      color: var(--text) !important;
      font-weight: 650;
      box-shadow: 0 8px 20px -15px color-mix(in srgb, var(--accent) 78%, transparent), var(--shadow-card);
      transform: none;

      .sidebar__item-title {
        color: var(--text);
        font-weight: 650;
      }
    }

    &-main {
      flex: 1;
      min-width: 0;
      display: flex;
      flex-direction: column;
      gap: 3px;
    }

    &-title {
      display: block;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    &-meta {
      display: flex;
      align-items: center;
      gap: 8px;
      color: var(--text-tertiary);
      font-size: 9.5px;
      font-weight: 500;
    }

    &-current {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      color: var(--accent);

      i {
        width: 5px;
        height: 5px;
        border-radius: 50%;
        background: var(--ok);
        box-shadow: 0 0 0 2px var(--ok-weak);
      }
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
    padding: 8px 10px 10px;
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

  &__user-card {
    flex: 1;
    min-width: 0;
    display: flex;
    align-items: center;
    gap: 10px;
    min-height: 50px;
    padding: 5px 7px;
    border: 1px solid var(--border);
    border-radius: 14px;
    background: color-mix(in srgb, var(--bg-raised) 90%, transparent);
    box-shadow: 0 5px 18px rgba(30, 64, 175, 0.04);
  }

  &__logout {
    flex-shrink: 0;
    margin-left: auto;
    height: 28px;
    padding: 0 10px;
    border: 0;
    border-radius: 8px;
    background: transparent;
    color: var(--text-secondary);
    font-size: 12.5px;
    cursor: pointer;

    &:hover {
      color: var(--danger);
      background: var(--danger-weak);
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

  &__user-name {
    font-size: 13.5px;
    font-weight: 650;
    color: var(--text);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__user-details {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  &__user-caption {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    color: var(--text-tertiary);
    font-size: 9.5px;

    i {
      width: 5px;
      height: 5px;
      border-radius: 50%;
      background: var(--ok);
    }
  }

}

.sidebar__project-menu {
  position: fixed;
  z-index: 1200;
  min-width: 152px;
  padding: 6px;
  border: 1px solid var(--border-strong);
  border-radius: 12px;
  background: var(--bg-elevated);
  box-shadow: var(--shadow);

  button {
    width: 100%;
    height: 32px;
    padding: 0 10px;
    border: none;
    border-radius: 8px;
    background: transparent;
    color: var(--text);
    font: inherit;
    font-size: 12.5px;
    text-align: left;
    cursor: pointer;

    &:hover {
      background: var(--bg-hover);
    }

    &.is-danger {
      color: var(--danger, #dc2626);

      &:hover {
        background: var(--danger-weak, rgba(220, 38, 38, 0.1));
        color: var(--danger-text, #b91c1c);
      }
    }
  }
}

.sidebar__project-menu-divider {
  display: block;
  height: 1px;
  margin: 4px 6px;
  background: var(--divider);
}

@keyframes sidebar-spin {
  to { transform: rotate(360deg); }
}

@media (prefers-reduced-motion: reduce) {
  .sidebar,
  .sidebar__new,
  .sidebar__item {
    transition: none;
  }

  .sidebar__empty-icon.is-loading {
    animation-duration: 1.6s;
  }
}
</style>
