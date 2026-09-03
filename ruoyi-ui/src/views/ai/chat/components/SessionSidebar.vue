<template>
  <aside class="session-sidebar">
    <div class="session-sidebar__head">
      <AppleButton variant="primary" class="session-new-btn" @click="$emit('new')">
        <svg width="13" height="13" viewBox="0 0 14 14" fill="none"><path d="M7 1v12M1 7h12" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>
        新对话
      </AppleButton>
      <div class="session-type-tabs" role="tablist" aria-label="会话类型">
        <button
          v-for="t in typeTabs"
          :key="t.value"
          type="button"
          role="tab"
          class="session-type-tabs__btn"
          :class="{ 'is-active': modelType === t.value }"
          :aria-selected="modelType === t.value"
          @click="setType(t.value)"
        >{{ t.label }}</button>
      </div>
    </div>

    <!-- 会话列表:平铺,双行信息 + 清晰选中态 -->
    <div class="session-sidebar__list" v-loading="loading">
      <div
        v-for="s in sessions"
        :key="s.sessionId"
        class="session-item"
        :class="{ 'is-active': s.sessionId === currentSessionId }"
        @click="$emit('switch', s)"
      >
        <div class="session-item__body">
          <div class="session-item__top">
            <span
              v-if="s.sessionType === 'job'"
              class="session-item__job-icon"
              title="定时任务会话"
              aria-label="定时任务会话"
            >
              <svg width="11" height="11" viewBox="0 0 16 16" fill="none">
                <circle cx="8" cy="8" r="6.2" stroke="currentColor" stroke-width="1.4"/>
                <path d="M8 4.5V8l2.2 1.4" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </span>
            <div class="session-item__title" :title="s.title || '新会话'">{{ s.title || '新会话' }}</div>
            <span class="session-item__time" :title="s.updateTime">{{ relativeTime(s.updateTime) }}</span>
          </div>
          <div v-if="agentName(s)" class="session-item__sub">{{ agentName(s) }}</div>
        </div>
        <button type="button" class="session-item__del" title="删除会话" @click.stop="$emit('delete', s)" :disabled="streaming">
          <svg width="12" height="12" viewBox="0 0 14 14" fill="none"><path d="M3 4.5h8M5.5 2.5h3l-1 9h-4l-1-9" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/></svg>
        </button>
      </div>
      <div v-if="!loading && !sessions.length" class="session-empty">暂无历史会话</div>
    </div>
  </aside>
</template>

<script setup>
import AppleButton from '@/components/AppleButton/index.vue'

const props = defineProps({
  sessions: { type: Array, default: () => [] },
  currentSessionId: String,
  loading: Boolean,
  streaming: Boolean,
  /** 空串=全部, chat=普通对话, job=定时任务 */
  sessionType: { type: String, default: '' }
})
const emit = defineEmits(['new', 'switch', 'delete', 'filter'])

const typeTabs = [
  { value: '', label: '全部' },
  { value: 'chat', label: '对话' },
  { value: 'job', label: '任务' }
]

const modelType = computed({
  get: () => props.sessionType ?? '',
  set: (v) => emit('filter', v)
})

function setType(v) {
  if (modelType.value === v) return
  modelType.value = v
}

// ---------------------------------------------------------------------------
// 展示
// ---------------------------------------------------------------------------
function agentName(s) {
  if (s.supervisorAgentName) return s.supervisorAgentName
  if (s.agentCount) return s.agentCount + ' 个智能体'
  return ''
}

/** 相对时间:刚刚 / N 分钟前 / N 小时前 / 昨天 / N 天前 / 具体日期 */
function relativeTime(v) {
  if (!v) return ''
  const t = new Date(String(v).replace(/-/g, '/')).getTime()
  if (Number.isNaN(t)) return ''
  const diff = Date.now() - t
  const MIN = 60 * 1000, HOUR = 60 * MIN, DAY = 24 * HOUR
  if (diff < MIN) return '刚刚'
  if (diff < HOUR) return Math.floor(diff / MIN) + ' 分钟前'
  if (diff < DAY) return Math.floor(diff / HOUR) + ' 小时前'
  if (diff < 2 * DAY) return '昨天'
  if (diff < 7 * DAY) return Math.floor(diff / DAY) + ' 天前'
  const d = new Date(t)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
</script>

<style scoped lang="scss">
@use '../../../../assets/styles/ai-tokens.scss' as *;

.session-sidebar {
  width: 264px; flex-shrink: 0;
  display: flex; flex-direction: column;
  background: var(--ai-card-bg); border: 1px solid var(--ai-border); border-radius: 16px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.04); overflow: hidden;
  &__head { padding: 12px 12px 10px; border-bottom: 1px solid var(--ai-border); display: flex; flex-direction: column; gap: 10px; }
  &__list { flex: 1; min-height: 0; overflow-y: auto; padding: 10px 8px 14px;
    &::-webkit-scrollbar { width: 5px; } &::-webkit-scrollbar-thumb { background: var(--ai-border-4); border-radius: 3px; }
  }
}
:deep(.session-new-btn) { flex: 1; justify-content: center; }

.session-type-tabs {
  display: flex; padding: 3px; border-radius: 10px; background: var(--ai-fill-2); gap: 2px;
  &__btn {
    flex: 1; border: none; background: transparent; color: $gray; font-size: 12px; font-weight: 500;
    padding: 6px 0; border-radius: 8px; cursor: pointer; transition: all 0.18s $ease;
    &:hover { color: $text; }
    &.is-active {
      background: var(--ai-card-bg); color: $blue; box-shadow: 0 1px 3px var(--ai-fill-4);
    }
  }
}

/* 会话项:双行 + 呼吸感 + 清晰选中态 */
.session-item {
  position: relative;
  display: flex; align-items: center; gap: 4px;
  padding: 8px 10px; border-radius: 10px;
  cursor: pointer;
  transition: background 0.16s $ease;
  & + & { margin-top: 3px; }
  &:hover { background: var(--ai-fill-2); }
  &.is-active {
    background: rgba(10, 132, 255, 0.09);
    &::before {
      content: '';
      position: absolute; left: 0; top: 50%; transform: translateY(-50%);
      width: 3px; height: 18px; border-radius: 2px;
      background: linear-gradient(180deg, $blue, #5E5CE6);
    }
    .session-item__title { color: $blue; }
  }

  &__body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
  &__top { display: flex; align-items: center; gap: 6px; min-width: 0; }
  &__job-icon {
    flex-shrink: 0; width: 18px; height: 18px; border-radius: 6px;
    display: inline-flex; align-items: center; justify-content: center;
    color: #C24A00; background: rgba(255, 159, 10, 0.14);
  }
  &__title {
    flex: 1; min-width: 0;
    font-size: 13px; line-height: 1.35; font-weight: 500; color: $text;
    white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
  }
  &__time {
    flex-shrink: 0; font-size: 11px; line-height: 1; color: $gray; font-variant-numeric: tabular-nums;
  }
  &__sub {
    font-size: 11px; line-height: 1.2; color: $gray;
    white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
    padding-left: 1px;
  }
  &__del {
    flex-shrink: 0; width: 24px; height: 24px;
    border: none; background: transparent; color: $gray2; border-radius: 7px; cursor: pointer;
    display: flex; align-items: center; justify-content: center;
    opacity: 0; transition: all 0.16s $ease;
    &:hover { background: rgba(255, 59, 48, 0.1); color: $red; }
    &:disabled { cursor: not-allowed; opacity: 0.3; }
  }
  &:hover &__del, &.is-active &__del { opacity: 1; }
}

.session-empty { text-align: center; padding: 40px 12px; font-size: 12.5px; color: $gray2; }
</style>
