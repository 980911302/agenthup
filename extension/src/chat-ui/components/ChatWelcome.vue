<template>
  <div class="chat-welcome">
    <!-- 背景弥散光晕 (Aura Glow) -->
    <div class="chat-welcome__aura" aria-hidden="true"></div>

    <div class="chat-welcome__hero">
      <!-- 智能体徽标:渐变方块 + 悬浮微动画 -->
      <div class="chat-welcome__badge" aria-hidden="true">
        <svg width="22" height="22" viewBox="0 0 24 24" fill="currentColor">
          <path d="M12 2.6 14.1 9 20.5 11 14.1 13 12 19.4 9.9 13 3.5 11 9.9 9 12 2.6Z" />
          <circle cx="19" cy="4.6" r="1.15" />
        </svg>
      </div>
      <h1 class="chat-welcome__title">{{ greetingPrefix }}，{{ userName }}</h1>
      <p class="chat-welcome__subtitle">今天想一起完成什么？</p>
    </div>

    <!-- 首页居中超级输入框插槽 (Hero Omnibar) -->
    <div class="chat-welcome__input-slot">
      <slot name="input" />
    </div>

    <!-- 浏览器场景快捷指令 (点击填入输入框) -->
    <div class="chat-welcome__prompts">
      <button
        v-for="(p, i) in quickPrompts"
        :key="i"
        type="button"
        class="chat-welcome__prompt"
        :style="{ animationDelay: 0.16 + i * 0.05 + 's' }"
        @click="$emit('use-prompt', p.prompt)"
      >
        <span class="chat-welcome__prompt-icon" :class="'is-' + p.color">
          <!-- 总结:文档 -->
          <svg v-if="p.icon === 'doc'" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z" />
            <path d="M14 2v4a2 2 0 0 0 2 2h4" />
            <path d="M10 9H8" />
            <path d="M16 13H8" />
            <path d="M16 17H8" />
          </svg>
          <!-- 解读:灯泡 -->
          <svg v-else-if="p.icon === 'bulb'" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M15 14c.2-1 .7-1.7 1.5-2.5 1-.9 1.5-2.2 1.5-3.5A6 6 0 0 0 6 8c0 1.3.5 2.6 1.5 3.5.8.8 1.3 1.5 1.5 2.5" />
            <path d="M9 18h6" />
            <path d="M10 22h4" />
          </svg>
          <!-- 翻译:语言 -->
          <svg v-else-if="p.icon === 'lang'" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="m5 8 6 6" />
            <path d="m4 14 6-6 2-3" />
            <path d="M2 5h12" />
            <path d="M7 2h1" />
            <path d="m22 22-5-10-5 10" />
            <path d="M14 18h6" />
          </svg>
          <!-- 润色:笔 -->
          <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M12 20h9" />
            <path d="M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4Z" />
          </svg>
        </span>
        <span class="chat-welcome__prompt-body">
          <strong>{{ p.title }}</strong>
          <small>{{ p.desc }}</small>
        </span>
      </button>
    </div>

    <!-- 底部免责声明 -->
    <div class="chat-welcome__disclaimer">
      <svg width="12" height="12" viewBox="0 0 16 16" fill="none" aria-hidden="true">
        <path d="M8 1.8 13 3.7v3.7c0 3.1-2 5.7-5 6.8-3-1.1-5-3.7-5-6.8V3.7L8 1.8Z" stroke="currentColor" stroke-width="1.3" stroke-linejoin="round" />
        <path d="m5.8 8 1.4 1.4 3-3" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
      <span>{{ brandName }} 也会出错，重要内容请自行核验</span>
    </div>
  </div>
</template>

<script setup name="ChatWelcome">
import { computed } from 'vue'

const props = defineProps({
  currentAgent: { type: Object, default: null },
  agentColor: { type: String, default: '' },
  user: { type: Object, default: null }
})

defineEmits(['use-prompt'])

const brandName = computed(() => {
  return props.currentAgent?.name || props.currentAgent?.agentName || 'AgentHub'
})

const userName = computed(() => {
  return props.user?.nickName || props.user?.userName || 'Lin'
})

const greetingPrefix = computed(() => {
  const hour = new Date().getHours()
  if (hour >= 5 && hour < 11) return '早上好'
  if (hour >= 11 && hour < 13) return '中午好'
  if (hour >= 13 && hour < 18) return '下午好'
  return '晚上好'
})

/** 浏览器助手场景的快捷指令:prompt 直接填入输入框,用户可改后发送 */
const quickPrompts = [
  { icon: 'doc', color: 'blue', title: '总结此页', desc: '提炼当前网页要点', prompt: '帮我总结当前网页的核心内容，用要点列出' },
  { icon: 'bulb', color: 'violet', title: '深度解读', desc: '关键信息与注意点', prompt: '当前页面讲了什么？帮我提炼关键信息和值得注意的点' },
  { icon: 'lang', color: 'cyan', title: '翻译页面', desc: '整页内容译成英文', prompt: '帮我把当前网页的主要内容翻译成英文' },
  { icon: 'pen', color: 'amber', title: '润色选中', desc: '改写优化选中文字', prompt: '帮我润色我选中的文字，让它更专业、更流畅' }
]
</script>

<style scoped lang="scss">
@use '../ai-tokens.scss' as *;

.chat-welcome {
  position: relative;
  margin: 0 auto;
  text-align: center;
  max-width: 520px;
  width: 100%;
  padding: 18px 4px 12px;
  /* 撑满首屏并垂直居中:欢迎页内容少时不顶在头部 */
  min-height: calc(100dvh - 46px);
  display: flex;
  flex-direction: column;
  justify-content: center;
  animation: welcome-in 0.45s cubic-bezier(0.16, 1, 0.3, 1) both;

  /* 背景通透弥散光晕 (Aura Radial Glow) */
  &__aura {
    position: absolute;
    top: -30px;
    left: 50%;
    transform: translateX(-50%);
    width: 460px;
    height: 320px;
    background: radial-gradient(
      ellipse at 50% 42%,
      rgba(37, 99, 235, 0.14) 0%,
      rgba(99, 102, 241, 0.08) 38%,
      rgba(56, 189, 248, 0.04) 62%,
      transparent 76%
    );
    border-radius: 50%;
    filter: blur(50px);
    pointer-events: none;
    z-index: 0;
    animation: aura-ambient 7s ease-in-out infinite alternate;
  }

  &__hero {
    position: relative;
    z-index: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    margin-bottom: 18px;
  }

  /* 智能体徽标 */
  &__badge {
    width: 46px;
    height: 46px;
    border-radius: 14px;
    display: flex;
    align-items: center;
    justify-content: center;
    margin-bottom: 14px;
    color: #fff;
    background: linear-gradient(135deg, #2e7cf6 0%, #4f6ef7 55%, #6366f1 100%);
    box-shadow:
      0 12px 26px -8px rgba(37, 99, 235, 0.5),
      0 2px 6px -2px rgba(37, 99, 235, 0.3),
      inset 0 1px 0 rgba(255, 255, 255, 0.32);
    animation: badge-float 4.5s ease-in-out infinite alternate;
  }

  &__title {
    margin: 0 0 5px;
    font-size: 20px;
    font-weight: 720;
    letter-spacing: -0.03em;
    color: var(--text);
    line-height: 1.3;
  }

  &__subtitle {
    margin: 0;
    font-size: 13px;
    font-weight: 480;
    color: var(--text-secondary);
    line-height: 1.5;
  }

  /* 居中大输入框插槽 (Hero Omnibar) */
  &__input-slot {
    position: relative;
    z-index: 10;
    width: 100%;
    max-width: none;
    margin-bottom: 12px;
    animation: welcome-in 0.45s cubic-bezier(0.16, 1, 0.3, 0.08) both;

    :deep(.chat-input-wrapper) {
      margin: 0;
      padding: 0;
      max-width: 100%;
    }

    :deep(.chat-input) {
      border-color: var(--border-strong);
      box-shadow: 0 12px 28px -18px rgba(30, 64, 175, 0.38), 0 2px 8px rgba(30, 64, 175, 0.04);
    }

    :deep(.chat-input.is-focus) {
      border-color: var(--accent-border);
      box-shadow: var(--ring), 0 16px 34px -20px rgba(37, 99, 235, 0.48);
    }
  }

  /* 快捷指令卡:2 栏网格 */
  &__prompts {
    position: relative;
    z-index: 1;
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 8px;
    width: 100%;
    margin-bottom: 14px;
  }

  &__prompt {
    display: flex;
    align-items: center;
    gap: 9px;
    padding: 10px 11px;
    text-align: left;
    background: var(--bg-card);
    border: 1px solid var(--border);
    border-radius: 13px;
    cursor: pointer;
    min-width: 0;
    box-shadow: 0 1px 3px -1px rgba(15, 23, 42, 0.05);
    transition: transform 0.2s cubic-bezier(0.16, 1, 0.3, 1), border-color 0.15s ease,
      box-shadow 0.2s ease, background 0.15s ease;
    animation: welcome-in 0.4s cubic-bezier(0.16, 1, 0.3, 1) both;

    &:hover {
      background: var(--bg-hover);
      border-color: var(--border-strong);
      transform: translateY(-2px);
      box-shadow: 0 8px 18px -10px rgba(15, 23, 42, 0.16);
    }

    &:active {
      transform: translateY(0) scale(0.97);
    }

    &:focus-visible {
      outline: none;
      border-color: var(--accent-border);
      box-shadow: var(--ring);
    }
  }

  &__prompt-icon {
    width: 28px;
    height: 28px;
    border-radius: 9px;
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;

    &.is-blue { background: rgba(37, 99, 235, 0.1); color: #2563eb; border: 1px solid rgba(37, 99, 235, 0.16); }
    &.is-violet { background: rgba(124, 58, 237, 0.1); color: #7c3aed; border: 1px solid rgba(124, 58, 237, 0.16); }
    &.is-cyan { background: rgba(8, 145, 178, 0.1); color: #0891b2; border: 1px solid rgba(8, 145, 178, 0.18); }
    &.is-amber { background: rgba(217, 119, 6, 0.1); color: #d97706; border: 1px solid rgba(217, 119, 6, 0.18); }
  }

  &__prompt-body {
    display: flex;
    flex-direction: column;
    gap: 1px;
    min-width: 0;

    strong {
      font-size: 12.5px;
      font-weight: 620;
      color: var(--text);
      letter-spacing: -0.01em;
      line-height: 1.35;
    }

    small {
      font-size: 10.5px;
      color: var(--text-tertiary);
      line-height: 1.4;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
  }

  /* 底部免责声明 */
  &__disclaimer {
    position: relative;
    z-index: 1;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 5px;
    font-size: 10px;
    color: var(--text-tertiary);
    margin-top: 2px;
    letter-spacing: 0.01em;
  }
}

@keyframes aura-ambient {
  0% {
    transform: translateX(-50%) scale(1) translateY(0);
    opacity: 0.85;
  }
  100% {
    transform: translateX(-50%) scale(1.1) translateY(10px);
    opacity: 1;
  }
}

@keyframes badge-float {
  0% {
    transform: translateY(0);
  }
  100% {
    transform: translateY(-4px);
  }
}

@keyframes welcome-in {
  from {
    opacity: 0;
    transform: translateY(12px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>
