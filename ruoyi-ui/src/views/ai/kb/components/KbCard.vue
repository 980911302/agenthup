<template>
  <article
    class="kb-card"
    :class="{ 'is-off': statusKey === 'disabled', 'is-issue': statusKey === 'issue' }"
    :style="{ '--accent': accent }"
  >
    <span class="kb-card__rail" aria-hidden="true"></span>

    <!-- 主体点击进入；更多菜单独立，不嵌套在链接内 -->
    <div class="kb-card__main" role="link" tabindex="0" @click="emit('open', item)" @keydown.enter.prevent="emit('open', item)">
      <div class="kb-card__head">
        <div class="kb-card__avatar" :style="{ background: avatarBg }">
          {{ avatarGlyph }}
        </div>
        <div class="kb-card__ident">
          <h3 class="kb-card__name" :title="item.kbName">{{ item.kbName || '未命名' }}</h3>
          <div class="kb-card__sub">
            <span class="kb-card__vis">{{ visibilityLabel }}</span>
            <span class="kb-card__status" :class="'is-' + statusKey">
              <i></i>{{ statusLabel }}
            </span>
          </div>
        </div>
      </div>

      <p class="kb-card__desc" :title="descriptionText">{{ descriptionText }}</p>

      <div class="kb-card__foot">
        <div class="kb-card__caps">
          <span class="cap-badge" :class="{ 'is-zero': !(item.docCount > 0) }" title="文件数">
            <svg width="12" height="12" viewBox="0 0 16 16" fill="none"><path d="M4 2.5h5.5L12.5 5.5V13a1 1 0 01-1 1H4a1 1 0 01-1-1V3.5a1 1 0 011-1z" stroke="currentColor" stroke-width="1.3"/><path d="M9 2.5V6h3.5" stroke="currentColor" stroke-width="1.3"/></svg>
            {{ item.docCount || 0 }}
          </span>
          <span v-if="statusKey === 'issue'" class="cap-badge is-warn" title="失败">
            {{ item.failedCount || 0 }} 失败
          </span>
          <span v-else-if="statusKey === 'processing'" class="cap-badge is-busy" title="处理中">
            {{ item.processingCount || 0 }} 处理中
          </span>
          <span v-else-if="statusKey === 'empty'" class="cap-badge is-hint">添加文件</span>
        </div>
        <span class="kb-card__meta" :title="metaRight">{{ metaRight }}</span>
      </div>
    </div>

    <!-- 库级操作：编辑资料 / 库管理 / 启停 / 删除 —— 不在文件页做 -->
    <div v-if="showMore" class="kb-card__actions" @click.stop>
      <button type="button" class="kb-card__action" title="编辑资料" @click="emit('edit', item)">
        <svg width="15" height="15" viewBox="0 0 16 16" fill="none"><path d="M11.5 2.5l2 2L5 13H3v-2l8.5-8.5z" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/></svg>
      </button>
      <button type="button" class="kb-card__action" title="库管理" @click="emit('share', item)">
        <svg width="15" height="15" viewBox="0 0 16 16" fill="none"><path d="M8 8.5a2 2 0 100-4 2 2 0 000 4zM3.5 13c.4-1.8 2-3 4.5-3s4.1 1.2 4.5 3M12.5 5.5l1.2.4.4 1.2-.4 1.2-1.2.4-1.2-.4-.4-1.2.4-1.2 1.2-.4z" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round"/></svg>
      </button>
      <button type="button" class="kb-card__action" :title="item.status === '1' ? '启用' : '停用'" @click="emit('toggle-status', item)">
        <svg v-if="item.status !== '1'" width="15" height="15" viewBox="0 0 16 16" fill="none"><circle cx="8" cy="8" r="5.5" stroke="currentColor" stroke-width="1.3"/><path d="M8 5v3.5l2 1.5" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/></svg>
        <svg v-else width="15" height="15" viewBox="0 0 16 16" fill="none"><path d="M5 8l2.2 2.2L11 5.5" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/></svg>
      </button>
      <button type="button" class="kb-card__action kb-card__action--danger" title="删除知识库" @click="emit('delete', item)">
        <svg width="15" height="15" viewBox="0 0 16 16" fill="none"><path d="M3 4.5h10M6.5 2.5h3M4.5 4.5l.5 9h6l.5-9M6.5 7v4M9.5 7v4" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round"/></svg>
      </button>
    </div>
  </article>
</template>

<script setup>
import { gradientOf, colorOf } from '@/utils/ai-palette'

const props = defineProps({
  item: { type: Object, required: true },
  currentUserId: { type: [Number, String], default: null },
  isPlatformAdmin: { type: Boolean, default: false },
  scopeFilter: { type: String, default: 'all' }
})

const emit = defineEmits(['open', 'edit', 'share', 'toggle-status', 'delete'])

const effectiveOwnerId = computed(() => {
  const o = props.item?.ownerUserId ?? props.item?.createUserId
  return o != null ? Number(o) : null
})

const isOwner = computed(() => {
  if (props.currentUserId == null || effectiveOwnerId.value == null) return false
  return Number(props.currentUserId) === effectiveOwnerId.value
})

const showMore = computed(() => props.isPlatformAdmin || isOwner.value)

const accent = computed(() => colorOf(props.item?.kbName || props.item?.kbId || 'kb'))
const avatarBg = computed(() =>
  statusKey.value === 'disabled'
    ? 'linear-gradient(135deg, #A1A1A6, #C7C7CC)'
    : gradientOf(props.item?.kbName || props.item?.kbId || 'kb')
)

const avatarGlyph = computed(() => {
  const name = (props.item?.kbName || '库').trim()
  return name.charAt(0).toUpperCase()
})

const statusKey = computed(() => {
  const h = (props.item?.health || '').toUpperCase()
  if (props.item?.status === '1' || h === 'DISABLED') return 'disabled'
  if (h === 'ISSUE' || (props.item?.failedCount || 0) > 0) return 'issue'
  if (h === 'PROCESSING' || (props.item?.processingCount || 0) > 0) return 'processing'
  if (h === 'EMPTY' || !(props.item?.docCount > 0)) return 'empty'
  return 'ready'
})

const statusLabel = computed(() => ({
  disabled: '已停用',
  issue: '有问题',
  processing: '处理中',
  empty: '空库',
  ready: '可用'
})[statusKey.value] || '可用')

const visibilityLabel = computed(() => {
  const v = (props.item?.visibility || props.item?.visibilityCode || '').toUpperCase()
  return ({ PRIVATE: '私有', MEMBERS: '私有', DEPT: '部门', ORG: '组织', UNKNOWN: '—' })[v] || '私有'
})

const descriptionText = computed(() => {
  const d = (props.item?.description || '').trim()
  return d || '暂无说明'
})

const ownerDisplay = computed(() => {
  const o = props.item?.owner
  if (o?.nickName) return o.nickName
  if (o?.userName) return o.userName
  return '负责人'
})

function formatRelative(s) {
  if (!s) return '—'
  const t = new Date(String(s).replace('T', ' ').replace(/-/g, '/'))
  if (Number.isNaN(t.getTime())) return String(s).replace('T', ' ').slice(0, 16)
  const diff = Date.now() - t.getTime()
  const m = Math.floor(diff / 60000)
  if (m < 1) return '刚刚'
  if (m < 60) return m + ' 分钟前'
  const h = Math.floor(m / 60)
  if (h < 24) return h + ' 小时前'
  const d = Math.floor(h / 24)
  if (d < 30) return d + ' 天前'
  return String(s).replace('T', ' ').slice(0, 16)
}

const metaRight = computed(() => {
  if (props.scopeFilter === 'shared') return ownerDisplay.value
  return formatRelative(props.item?.lastContentTime || props.item?.updateTime || props.item?.createTime)
})
</script>

<style scoped lang="scss">
@use '../../../../assets/styles/ai-tokens.scss' as *;

.kb-card {
  position: relative;
  display: flex;
  flex-direction: column;
  padding: 16px 18px 14px;
  background: var(--ai-card-bg);
  border: 1px solid var(--ai-border);
  border-radius: 16px;
  box-shadow: 0 1px 2px var(--ai-fill-2);
  transition: all 0.28s $ease;
  overflow: hidden;
  cursor: pointer;

  &:hover {
    box-shadow: var(--ai-shadow-card);
    transform: translateY(-3px);
    border-color: var(--ai-input-bg);
    .kb-card__actions { opacity: 1; transform: translateY(0); }
    .kb-card__rail { opacity: 1; }
  }
  &:active { transform: translateY(-1px) scale(0.995); }

  &.is-off {
    background: var(--ai-card-off);
    .kb-card__name { color: $text2; }
  }
  &.is-issue {
    border-color: rgba(255, 159, 10, 0.28);
  }

  &__rail {
    position: absolute;
    left: 0; top: 0; bottom: 0;
    width: 3px;
    background: var(--accent, $blue);
    opacity: 0;
    transition: opacity 0.28s $ease;
  }

  &__main {
    display: flex;
    flex-direction: column;
    gap: 10px;
    outline: none;
    min-width: 0;
    &:focus-visible {
      box-shadow: inset 0 0 0 2px rgba(10, 132, 255, 0.35);
      border-radius: 8px;
    }
  }

  &__head {
    display: flex;
    align-items: center;
    gap: 11px;
    padding-right: 72px;
  }

  &__avatar {
    width: 40px;
    height: 40px;
    border-radius: 12px;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 16px;
    font-weight: 700;
    color: #fff;
    flex-shrink: 0;
    box-shadow: 0 3px 10px rgba(0, 0, 0, 0.14);
  }

  &__ident { flex: 1; min-width: 0; }

  &__name {
    font-size: 15px;
    font-weight: 600;
    color: $text;
    margin: 0 0 3px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__sub {
    display: flex;
    align-items: center;
    gap: 7px;
    min-width: 0;
  }

  &__vis {
    font-size: 10.5px;
    color: $gray;
    background: var(--ai-fill-2);
    padding: 1.5px 6px;
    border-radius: 4px;
  }

  &__status {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    font-size: 11px;
    flex-shrink: 0;
    i {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      display: inline-block;
    }
    &.is-ready { color: #248A3D; i { background: $green; box-shadow: 0 0 0 2.5px rgba(52,199,89,0.18); } }
    &.is-processing { color: $blue; i { background: $blue; box-shadow: 0 0 0 2.5px rgba(10,132,255,0.18); } }
    &.is-issue { color: #C24A00; i { background: $orange; box-shadow: 0 0 0 2.5px rgba(255,159,10,0.2); } }
    &.is-empty { color: $gray; i { background: $gray2; } }
    &.is-disabled { color: $gray; i { background: $gray2; } }
  }

  &__desc {
    font-size: 13px;
    color: $text2;
    margin: 0;
    line-height: 1.5;
    min-height: 39px;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }

  &__foot {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    padding-top: 11px;
    border-top: 1px solid var(--ai-border);
  }

  &__caps {
    display: flex;
    align-items: center;
    gap: 6px;
    flex-wrap: wrap;
    min-width: 0;
  }

  &__meta {
    font-size: 11.5px;
    color: $text2;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    min-width: 0;
    max-width: 42%;
  }

  &__actions {
    position: absolute;
    top: 12px;
    right: 12px;
    display: flex;
    gap: 4px;
    opacity: 0;
    transform: translateY(-3px);
    transition: all 0.22s $ease;
    z-index: 2;
  }

  &__action {
    width: 27px;
    height: 27px;
    border: none;
    border-radius: 8px;
    background: var(--ai-border);
    color: $text2;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    transition: all 0.18s;
    &:hover { background: rgba(10,132,255,0.12); color: $blue; }
    &--danger:hover { background: rgba(255,59,48,0.12); color: $red; }
  }
}

.cap-badge {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: 11.5px;
  font-weight: 600;
  color: $text2;
  background: var(--ai-fill-2);
  padding: 3px 7px;
  border-radius: 6px;
  font-variant-numeric: tabular-nums;
  svg { opacity: 0.75; }
  &.is-zero { color: $gray3; background: var(--ai-fill-1); }
  &.is-warn { color: #C24A00; background: rgba(255,159,10,0.12); }
  &.is-busy { color: $blue; background: rgba(10,132,255,0.1); }
  &.is-hint { color: $blue; background: rgba(10,132,255,0.08); font-weight: 550; }
}
</style>
