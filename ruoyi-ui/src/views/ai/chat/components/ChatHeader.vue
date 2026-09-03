<template>
  <header class="chat-header">
    <div class="chat-header__left">
      <!-- 侧栏折叠开关常驻顶栏：侧栏开着显示收起图标，收起后显示展开图标。
           侧栏内部因此只留「新对话 + 会话列表」，更干净。 -->
      <button
        type="button"
        class="chat-header__icon"
        :title="sidebarCollapsed ? '展开侧栏' : '收起侧栏'"
        @click="$emit('toggle-sidebar')"
      >
        <svg v-if="sidebarCollapsed" width="16" height="16" viewBox="0 0 16 16" fill="none"><path d="M2.5 3.5h11M2.5 8h11M2.5 12.5h7" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>
        <svg v-else width="16" height="16" viewBox="0 0 16 16" fill="none"><path d="M10 3.5 5.5 8l4.5 4.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
      </button>
      <button v-if="sidebarCollapsed" type="button" class="chat-header__icon" title="新对话" @click="$emit('new-chat')">
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><path d="M8 2.5v11M2.5 8h11" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>
      </button>
      <h1 class="chat-header__title">{{ title }}</h1>
      <span v-if="sub" class="chat-header__sub">{{ sub }}</span>
    </div>
    <div class="chat-header__right">
      <!-- 实时连接指示灯。离线时事件推不过来，只剩 5s 轮询兜底，用户有权知道。 -->
      <span class="chat-conn" :class="`chat-conn--${connectionTone}`" :title="connectionTip">
        <i class="chat-conn__dot"></i>{{ connectionLabel }}
      </span>
      <button type="button" class="chat-header__icon" title="工作区" @click="$emit('toggle-workspace')">
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><path d="M1.8 4h4.4l1.1 1.7h6.9v7.5a1 1 0 0 1-1 1H2.8a1 1 0 0 1-1-1z" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round"/></svg>
      </button>
      <el-dropdown trigger="click" @command="$emit('command', $event)">
        <button type="button" class="chat-header__icon" title="更多">
          <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><circle cx="8" cy="3.2" r="1.3" fill="currentColor"/><circle cx="8" cy="8" r="1.3" fill="currentColor"/><circle cx="8" cy="12.8" r="1.3" fill="currentColor"/></svg>
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="traces" :disabled="!hasTurns">链路追踪</el-dropdown-item>
            <el-dropdown-item command="clear" :disabled="streaming || !hasTurns">清空当前对话</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </header>
</template>

<script setup name="ChatHeader">
/**
 * 顶栏：会话身份(标题 + 用量概览) + 连接指示灯 + 工作区/更多操作。
 * 纯展示组件，所有状态由父级传入，动作通过事件上抛。
 */
const props = defineProps({
  title: { type: String, default: '' },
  sub: { type: String, default: '' },
  connectionLabel: { type: String, default: '离线' },
  connectionTone: { type: String, default: 'offline' },
  connectionTip: { type: String, default: '' },
  streaming: { type: Boolean, default: false },
  hasTurns: { type: Boolean, default: false },
  /** 侧栏收起时顶栏补位：展开侧栏 + 新对话 */
  sidebarCollapsed: { type: Boolean, default: false },
})
const emit = defineEmits(['toggle-workspace', 'command', 'toggle-sidebar', 'new-chat'])

</script>

<style scoped lang="scss">
@use '../../../../assets/styles/ai-tokens.scss' as *;

/* 顶部:去掉卡片外壳,只是一行会话身份 —— 原先的白底卡片 + 8 个控件太重 */
.chat-header {
  display: flex; align-items: center; justify-content: space-between; gap: 14px;
  padding: 0 4px; margin-bottom: 10px; height: 32px; flex-shrink: 0;
  &__left { display: flex; align-items: baseline; gap: 10px; min-width: 0; }
  &__title {
    font-size: $ai-fs-4; font-weight: 600; color: $text; margin: 0;
    letter-spacing: -0.2px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
    max-width: 420px;
  }
  &__sub {
    font-size: $ai-fs-6; color: $ai-text3; white-space: nowrap;
    overflow: hidden; text-overflow: ellipsis; min-width: 0;
  }
  &__right { display: flex; align-items: center; gap: 4px; flex-shrink: 0; }
  &__icon {
    width: 30px; height: 30px; border-radius: 50%; border: none; background: transparent;
    color: $ai-text3; cursor: pointer; display: flex; align-items: center; justify-content: center;
    transition: background 0.16s $ease, color 0.16s $ease;
    &:hover { background: var(--ai-fill-3); color: $text; }
  }
}

/* 实时连接指示灯 */
.chat-conn {
  display: inline-flex; align-items: center; gap: 5px;
  height: 22px; padding: 0 9px 0 7px; margin-right: 2px;
  border-radius: 11px; font-size: $ai-fs-6; line-height: 1;
  white-space: nowrap; cursor: default; user-select: none;
  transition: background 0.16s $ease, color 0.16s $ease;

  &__dot {
    width: 6px; height: 6px; border-radius: 50%; flex-shrink: 0;
    background: currentColor;
  }

  &--online { color: #1d8a4c; background: rgba(52, 199, 89, 0.12); }
  &--pending {
    color: #a8690a; background: rgba(255, 159, 10, 0.14);
    .chat-conn__dot { animation: chat-conn-blink 1.1s $ease infinite; }
  }
  &--offline { color: #c0392b; background: rgba(255, 59, 48, 0.12); }
}

@keyframes chat-conn-blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.25; }
}
</style>
