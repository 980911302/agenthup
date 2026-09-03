<template>
  <div class="chat-welcome">
    <!-- 背景弥散光晕 (Aura Glow) -->
    <div class="chat-welcome__aura" aria-hidden="true"></div>

    <div class="chat-welcome__hero">
      <h1 class="chat-welcome__title">
        {{ greetingPrefix }}, {{ userName }}. <span class="chat-welcome__title-sub">有什么我能帮上忙的？</span>
      </h1>
      <p class="chat-welcome__desc">
        {{ subDesc }}
      </p>
    </div>

    <!-- 首页居中超级输入框插槽 (Hero Omnibar) -->
    <div class="chat-welcome__input-slot">
      <slot name="input" />
    </div>

    <!-- 极简建议探索卡片 (3 栏横向卡片，参考 Aivory 质感) -->
    <div class="chat-welcome__prompts">
      <button
        v-for="(p, i) in quickPrompts"
        :key="i"
        type="button"
        class="chat-welcome__prompt-card"
        @click="$emit('use-prompt', p.prompt)"
      >
        <div class="chat-welcome__card-badge" :class="'badge--' + p.color">
          <!-- 魔法棒/灵感 -->
          <svg v-if="p.icon === 'wand'" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="m21.64 3.64-1.28-1.28a1.21 1.21 0 0 0-1.72 0L2.36 18.64a1.21 1.21 0 0 0 0 1.72l1.28 1.28a1.2 1.2 0 0 0 1.72 0L21.64 5.36a1.2 1.2 0 0 0 0-1.72Z"/>
            <path d="m14 7 3 3"/>
            <path d="M5 6v4"/>
            <path d="M19 14v4"/>
            <path d="M10 2v2"/>
            <path d="M7 8H3"/>
            <path d="M21 16h-4"/>
            <path d="M11 3H9"/>
          </svg>
          <!-- 写作/编辑 -->
          <svg v-else-if="p.icon === 'edit'" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z"/>
            <path d="m15 5 4 4"/>
          </svg>
          <!-- 代码 -->
          <svg v-else-if="p.icon === 'code'" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="16 18 22 12 16 6"/>
            <polyline points="8 6 2 12 8 18"/>
          </svg>
          <!-- 架构/闪电 -->
          <svg v-else width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/>
          </svg>
        </div>

        <div class="chat-welcome__card-body">
          <h3 class="chat-welcome__card-title">{{ p.title }}</h3>
          <p class="chat-welcome__card-desc">{{ p.desc }}</p>
        </div>
      </button>
    </div>

    <!-- 底部免责声明 -->
    <div class="chat-welcome__disclaimer">
      {{ brandName }} 也会出错。重要内容请自行核验。
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

const subDesc = computed(() => {
  const name = props.currentAgent?.name || props.currentAgent?.agentName || 'AgentHub'
  return `有问题，就和 ${name} 一起想。`
})

const quickPrompts = [
  {
    icon: 'wand',
    color: 'mint',
    title: '认真为一件事命名',
    desc: '给我的专栏起名字：一份评论性气候金融周报。',
    prompt: '我想为我的新专栏起一个引人注目、专业且有辨识度的名字，定位是一份评论性气候金融周报，请给出5个备选方案及命名寓意。'
  },
  {
    icon: 'edit',
    color: 'mint',
    title: '为长文拟提纲',
    desc: '帮我拟一份 2000 字的文章提纲，题目是「专注作为一种道德能力」。',
    prompt: '帮我拟一份 2000 字的深度文章提纲，题目是「专注作为一种道德能力」，包含引言、核心论点、分论点与结语。'
  },
  {
    icon: 'code',
    color: 'mint',
    title: '陪我读一段代码',
    desc: '我有一个 TypeScript 函数，每次后续输入就会失去焦点——帮我排查。',
    prompt: '我有一个前端输入组件，在用户输入时组件会重新渲染导致失焦，请帮我分析常见的排查思路与优化方案。'
  }
]
</script>

<style scoped lang="scss">
@use '../ai-tokens.scss' as *;

.chat-welcome {
  position: relative;
  margin: auto;
  text-align: center;
  max-width: 820px;
  width: 100%;
  padding: 44px 20px 20px;
  display: flex;
  flex-direction: column;
  align-items: center;
  animation: welcome-in 0.45s cubic-bezier(0.16, 1, 0.3, 1) both;

  /* 背景通透弥散光晕 (Aura Radial Glow) */
  &__aura {
    position: absolute;
    top: -40px;
    left: 50%;
    transform: translateX(-50%);
    width: 580px;
    height: 420px;
    background: radial-gradient(
      ellipse at 50% 45%,
      rgba(99, 102, 241, 0.14) 0%,
      rgba(168, 85, 247, 0.08) 35%,
      rgba(56, 189, 248, 0.04) 60%,
      transparent 75%
    );
    border-radius: 50%;
    filter: blur(52px);
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
    margin-bottom: 24px;
  }

  &__title {
    font-size: 28px;
    font-weight: 700;
    letter-spacing: -0.025em;
    color: var(--text);
    margin: 0 0 10px;
    line-height: 1.3;

    &-sub {
      color: var(--text);
      font-weight: 650;
    }
  }

  &__desc {
    font-size: 14.5px;
    color: var(--text-secondary);
    line-height: 1.5;
    margin: 0;
    font-weight: 450;
  }

  /* 居中大输入框插槽 (Hero Omnibar) */
  &__input-slot {
    position: relative;
    z-index: 10;
    width: 100%;
    max-width: 760px;
    margin-bottom: 20px;

    :deep(.chat-input-wrapper) {
      margin: 0;
      padding: 0;
      max-width: 100%;
    }
  }

  /* 建议卡片容器 (自适应 3 栏) */
  &__prompts {
    position: relative;
    z-index: 1;
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 14px;
    width: 100%;
    max-width: 760px;
    margin-bottom: 20px;

    @media (max-width: 768px) {
      grid-template-columns: 1fr;
      gap: 10px;
    }
  }

  /* 单个建议卡片 */
  &__prompt-card {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    text-align: left;
    padding: 16px 16px 14px;
    background: var(--bg-card);
    border: 1px solid var(--border);
    border-radius: 16px;
    cursor: pointer;
    box-shadow: 0 2px 8px -2px rgba(15, 23, 42, 0.04), 0 1px 2px -1px rgba(15, 23, 42, 0.02);
    transition: all 0.22s cubic-bezier(0.16, 1, 0.3, 1);
    min-height: 110px;

    &:hover {
      background: var(--bg-hover);
      border-color: var(--border-strong);
      transform: translateY(-3px);
      box-shadow: 0 8px 24px -4px rgba(15, 23, 42, 0.08), 0 2px 6px -1px rgba(15, 23, 42, 0.03);

      .chat-welcome__card-badge {
        transform: scale(1.1);
      }
    }

    &:active {
      transform: translateY(0) scale(0.98);
    }
  }

  /* 薄荷绿微勋章图标 */
  &__card-badge {
    width: 28px;
    height: 28px;
    border-radius: 8px;
    display: flex;
    align-items: center;
    justify-content: center;
    margin-bottom: 12px;
    transition: transform 0.2s ease;
    flex-shrink: 0;

    &.badge--mint {
      background: rgba(16, 185, 129, 0.12);
      color: #059669;
      border: 1px solid rgba(16, 185, 129, 0.2);
    }
  }

  &__card-body {
    display: flex;
    flex-direction: column;
    gap: 4px;
    width: 100%;
  }

  &__card-title {
    font-size: 13.5px;
    font-weight: 650;
    color: var(--text);
    margin: 0;
    letter-spacing: -0.01em;
    line-height: 1.35;
  }

  &__card-desc {
    font-size: 12px;
    color: var(--text-tertiary);
    line-height: 1.45;
    margin: 0;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  /* 底部免责声明 */
  &__disclaimer {
    position: relative;
    z-index: 1;
    font-size: 12px;
    color: var(--text-tertiary);
    margin-top: 8px;
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
