<template>
  <div class="chat-welcome">
    <div class="chat-welcome__avatar" :style="{ background: agentColor ? `linear-gradient(135deg, ${agentColor}, #5E5CE6)` : undefined }">
      {{ currentAgent ? (currentAgent.icon || '🤖') : '🤖' }}
    </div>
    <h2 class="chat-welcome__title">开始一段对话</h2>
    <p class="chat-welcome__desc">
      <template v-if="currentAgent">
        {{ currentAgent.agentDesc || currentAgent.agentName + ' 已就绪，系统提示词与工具由后端按智能体装配。' }}
      </template>
      <template v-else>请先选择一个智能体。</template>
    </p>
    <div class="chat-welcome__prompts">
      <div v-for="(p, i) in quickPrompts" :key="i" class="chat-welcome__prompt" @click="$emit('use-prompt', p)">
        <span class="chat-welcome__prompt-idx">{{ i + 1 }}</span>
        <span class="chat-welcome__prompt-text">{{ p }}</span>
      </div>
    </div>
  </div>
</template>

<script setup name="ChatWelcome">
/**
 * 空状态欢迎页：只有消息列表为空时出现，引导用户发起第一句话。
 * 快捷问题属于纯展示数据，不依赖任何状态，直接在本组件内维护。
 */
const quickPrompts = [
  '用一句话介绍你自己',
  '帮我写一段 Java 单例模式的代码',
  '解释一下 JSON-RPC WebSocket 如何断线恢复',
  '把这句话翻译成英文：你好，世界'
]

defineProps({
  currentAgent: { type: Object, default: null },
  agentColor: { type: String, default: '' }
})
defineEmits(['use-prompt'])
</script>

<style scoped lang="scss">
@use '../../../../assets/styles/ai-tokens.scss' as *;

/* 欢迎页 */
.chat-welcome { margin: auto; text-align: center; max-width: 560px; padding: 36px 0;
  &__avatar { width: 84px; height: 84px; border-radius: 26px; margin: 0 auto 20px; display: flex; align-items: center; justify-content: center; font-size: 40px; line-height: 1;
    background: linear-gradient(135deg, $blue, #5E5CE6);
    box-shadow: 0 12px 32px rgba(10,132,255,0.22), 0 2px 8px rgba(10,132,255,0.12);
  }
  &__title { font-size: $ai-fs-1; font-weight: 650; color: $text; margin: 0 0 10px; letter-spacing: -0.4px; }
  &__desc { font-size: $ai-fs-5; color: $ai-text3; line-height: $ai-lh-base; margin: 0 0 30px; }
  &__prompts { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; @media (max-width: 600px) { grid-template-columns: 1fr; } }
  &__prompt { display: flex; align-items: center; gap: 10px; padding: 13px 15px; background: var(--ai-card-bg); border: 1px solid var(--ai-border-2); border-radius: 14px; font-size: $ai-fs-5; color: $text; cursor: pointer; text-align: left; transition: all 0.22s $ease;
    &-idx { width: 22px; height: 22px; flex-shrink: 0; border-radius: 50%; background: rgba(10,132,255,0.1); color: $blue; font-size: $ai-fs-6; font-weight: 700; display: flex; align-items: center; justify-content: center; }
    &-text { flex: 1; min-width: 0; }
    &:hover { border-color: $blue; box-shadow: 0 6px 20px rgba(10,132,255,0.12); transform: translateY(-2px); }
  }
}
</style>
