<template>
  <div class="topo">
    <!-- 根节点：智能体自己 -->
    <div class="topo__root">
      <div class="topo-node topo-node--root" :style="{ background: rootGradient, boxShadow: rootGlow }">
        <span class="topo-node__icon">{{ icon }}</span>
      </div>
      <div class="topo-node__label" :title="name">{{ name }}</div>
      <div class="topo-node__model" :class="{ 'is-missing': !model }">{{ model || '未绑定模型' }}</div>
    </div>

    <!-- 连接线：纯 CSS 树干 + 分支，不依赖测量，缩放不会错位 -->
    <div class="topo__trunk"><span class="topo__trunk-line"></span></div>

    <div class="topo__branches">
      <section class="topo-branch" v-for="b in branches" :key="b.key" :class="{ 'is-empty': !b.items.length }">
        <span class="topo-branch__link"></span>
        <header class="topo-branch__head" :style="{ background: b.soft, borderColor: b.color }">
          <span class="topo-branch__icon" :style="{ background: b.color }" v-html="b.glyph"></span>
          <span class="topo-branch__title">{{ b.label }}</span>
          <span class="topo-branch__count" :style="{ background: b.color }">{{ b.items.length }}</span>
        </header>

        <div class="topo-branch__body" v-if="b.items.length">
          <span
            class="topo-leaf"
            v-for="(it, i) in b.shown"
            :key="i"
            :title="it.hint || it.text"
            :style="{ borderColor: b.color }"
          >
            <i class="topo-leaf__dot" :style="{ background: it.color || b.color }"></i>
            <span class="topo-leaf__text">{{ it.text }}</span>
            <em v-if="it.tag" class="topo-leaf__tag">{{ it.tag }}</em>
          </span>
          <span class="topo-leaf topo-leaf--more" v-if="b.items.length > b.shown.length" @click="expand(b.key)">
            +{{ b.items.length - b.shown.length }}
          </span>
        </div>
        <div class="topo-branch__body" v-else>
          <span class="topo-branch__none">未配置</span>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { gradientOf, glowOf } from '@/utils/ai-palette'

const LIMIT = 8

const props = defineProps({
  name: { type: String, default: '' },
  code: { type: String, default: '' },
  theme: { type: String, default: '' },
  icon: { type: String, default: '🤖' },
  model: { type: String, default: '' },
  skills: { type: Array, default: () => [] },
  tools: { type: Array, default: () => [] },
  children: { type: Array, default: () => [] }
})

const expanded = ref([])

function expand(key) {
  if (!expanded.value.includes(key)) expanded.value = [...expanded.value, key]
}

const rootGradient = computed(() => gradientOf(props.code, props.theme))
const rootGlow = computed(() => glowOf(props.code, props.theme))

const GLYPHS = {
  skill: '<svg width="11" height="11" viewBox="0 0 16 16" fill="none"><path d="M9 1.5L3 9h4l-1 5.5L13 7H9l1-5.5z" stroke="#fff" stroke-width="1.5" stroke-linejoin="round"/></svg>',
  tool: '<svg width="11" height="11" viewBox="0 0 16 16" fill="none"><path d="M10.5 5.5a3 3 0 01-4 4L3 13l1.5 1.5L8 11a3 3 0 004-4l-1.5 1.5-2-2L10.5 5.5z" stroke="#fff" stroke-width="1.4" stroke-linejoin="round"/></svg>',
  child: '<svg width="11" height="11" viewBox="0 0 16 16" fill="none"><path d="M8 4v3M4.5 13v-1.5a2 2 0 012-2h3a2 2 0 012 2V13" stroke="#fff" stroke-width="1.5" stroke-linecap="round"/><circle cx="8" cy="2.8" r="1.6" stroke="#fff" stroke-width="1.5"/></svg>'
}

const branches = computed(() => {
  const defs = [
    { key: 'skill', label: '技能', color: '#5E5CE6', soft: 'rgba(94,92,230,0.09)', items: props.skills },
    { key: 'tool', label: '工具', color: '#26A85A', soft: 'rgba(48,209,88,0.11)', items: props.tools },
    { key: 'child', label: '子智能体', color: '#BF5AF2', soft: 'rgba(191,90,242,0.10)', items: props.children }
  ]
  return defs.map(d => ({
    ...d,
    glyph: GLYPHS[d.key],
    shown: expanded.value.includes(d.key) ? d.items : d.items.slice(0, LIMIT)
  }))
})
</script>

<style scoped lang="scss">
@use '../../../assets/styles/ai-tokens.scss' as *;

$line: rgba(0,0,0,0.14);

.topo {
  display: flex; align-items: stretch; gap: 0; font-family: $font;
  padding: 20px 18px; border-radius: 14px;
  background:
    radial-gradient(520px 200px at 12% 30%, rgba(10,132,255,0.05), transparent 62%),
    var(--ai-block-bg);
  border: 1px solid var(--ai-fill-3);
  @media (max-width: 700px) { flex-direction: column; align-items: center; }

  /* 根节点 */
  &__root {
    flex-shrink: 0; width: 132px; display: flex; flex-direction: column; align-items: center;
    justify-content: center; gap: 7px; text-align: center;
  }

  /* 树干：一条竖线 + 到根节点的横线 */
  &__trunk {
    flex-shrink: 0; width: 34px; position: relative; display: flex; align-items: center;
    &::before {
      content: ''; position: absolute; left: 0; top: 50%; width: 17px; height: 1.5px;
      background: $line; transform: translateY(-50%);
    }
  }
  &__trunk-line {
    position: absolute; left: 17px; top: 16%; bottom: 16%; width: 1.5px; background: $line; border-radius: 1px;
  }

  &__branches { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 10px; }

  @media (max-width: 700px) {
    &__trunk { width: 100%; height: 22px;
      &::before { left: 50%; top: 0; width: 1.5px; height: 22px; transform: none; } }
    &__trunk-line { display: none; }
    &__branches { width: 100%; }
  }
}

/* 节点 */
.topo-node {
  &--root {
    width: 62px; height: 62px; border-radius: 19px; display: flex; align-items: center; justify-content: center;
    color: #fff; position: relative;
    &::after {
      content: ''; position: absolute; inset: 0; border-radius: 19px;
      border: 1px solid rgba(255,255,255,0.28); pointer-events: none;
    }
  }
  &__icon { font-size: 28px; line-height: 1; }
  &__label {
    font-size: 13px; font-weight: 600; color: $text; max-width: 100%;
    overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  }
  &__model {
    font-size: 10.5px; color: $gray; max-width: 100%;
    overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    &.is-missing { color: #C24A00; }
  }
}

/* 分支 */
.topo-branch {
  position: relative; display: flex; align-items: flex-start; gap: 10px;
  padding-left: 18px; min-width: 0;
  &__link {
    position: absolute; left: 0; top: 13px; width: 14px; height: 1.5px; background: $line;
    &::after {
      content: ''; position: absolute; right: -2px; top: 50%; width: 4px; height: 4px;
      border-radius: 50%; background: $line; transform: translateY(-50%);
    }
  }
  &.is-empty { opacity: 0.62; .topo-branch__link { background: transparent; border-top: 1.5px dashed $line; } }

  &__head {
    flex-shrink: 0; display: inline-flex; align-items: center; gap: 6px;
    padding: 4px 9px 4px 5px; border-radius: 980px; border: 1px solid; margin-top: 4px;
  }
  &__icon {
    width: 18px; height: 18px; border-radius: 50%; display: inline-flex;
    align-items: center; justify-content: center; flex-shrink: 0;
  }
  &__title { font-size: 12px; font-weight: 600; color: $text; white-space: nowrap; }
  &__count {
    min-width: 17px; height: 17px; padding: 0 5px; border-radius: 980px; color: #fff;
    font-size: 10.5px; font-weight: 700; display: inline-flex; align-items: center; justify-content: center;
    font-variant-numeric: tabular-nums;
  }
  &__body { flex: 1; min-width: 0; display: flex; flex-wrap: wrap; gap: 5px; padding-top: 5px; }
  &__none { font-size: 12px; color: $gray3; padding: 4px 0; }

  @media (max-width: 700px) { flex-direction: column; gap: 6px; }
}

/* 叶子 */
.topo-leaf {
  display: inline-flex; align-items: center; gap: 5px; max-width: 100%;
  padding: 3px 9px; border-radius: 980px; background: var(--ai-card-bg); border: 1px solid;
  font-size: 11.5px; color: $text; min-width: 0;
  &__dot { width: 5px; height: 5px; border-radius: 50%; flex-shrink: 0; }
  &__text { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  &__tag {
    font-style: normal; font-size: 9px; font-weight: 700; color: $text2;
    background: var(--ai-fill-3); padding: 0 4px; border-radius: 3px; flex-shrink: 0;
  }
  &--more {
    cursor: pointer; color: $text2; border-style: dashed; border-color: $gray3 !important;
    font-weight: 600; &:hover { color: $text; background: var(--ai-fill-1); }
  }
}
</style>
