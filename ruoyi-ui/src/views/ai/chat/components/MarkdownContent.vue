<template>
  <div class="md-content" v-html="html"></div>
</template>

<script setup>
import { computed } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

const props = defineProps({
  text: { type: String, default: '' }
})

/**
 * 完整 Markdown 渲染(gfm + 软换行)。
 *
 * 之前是手写的极简渲染,只认代码块和行内代码,`**加粗**`、`- 列表`、`# 标题`
 * 全部原样漏到界面上。marked 项目里已在用(见 ai/agent/index.vue),这里复用。
 *
 * 安全:聊天正文是模型产出 + 工具执行结果,属于不可信内容 --
 * 工具抓回一段带 <img onerror=...> 的网页塞进回复,直接 v-html 就是 XSS。
 * 所以 marked 的输出必须过 DOMPurify 才能上屏。
 *
 * 不用 marked.setOptions() 改全局配置:参数按次传,避免和其他页面(agent/index.vue)
 * 共享的全局状态互相覆盖。
 */
const html = computed(() => {
  if (!props.text) return ''
  const raw = marked.parse(props.text, { breaks: true, gfm: true, async: false })
  return DOMPurify.sanitize(raw, { ADD_ATTR: ['target'] })
})
</script>

<style scoped lang="scss">
@use '../../../../assets/styles/ai-tokens.scss' as *;

.md-content {
  word-break: break-word;

  /* v-html 内容不受 scoped 作用域约束,必须用 :deep() 才能命中 */
  :deep(> *:first-child) { margin-top: 0; }
  :deep(> *:last-child) { margin-bottom: 0; }

  :deep(p) { margin: 0 0 10px; line-height: $ai-lh-base; }

  /* 标题层级:原先 h1 只比正文大 29%、h4 与正文同号,结构等于没有。
     AI 回答大量用 ### / #### 组织结构,层级必须拉开。 */
  :deep(h1), :deep(h2), :deep(h3), :deep(h4), :deep(h5), :deep(h6) {
    margin: 18px 0 9px; font-weight: 650; color: $text; line-height: $ai-lh-tight;
  }
  :deep(h1) { font-size: $ai-fs-1; }
  :deep(h2) { font-size: $ai-fs-2; }
  :deep(h3) { font-size: $ai-fs-3; }
  /* h4 及以下与正文同号,靠字重和字距区分,不再无差别 */
  :deep(h4), :deep(h5), :deep(h6) { font-size: $ai-fs-4; letter-spacing: 0.01em; }

  :deep(ul), :deep(ol) { margin: 0 0 10px; padding-left: 24px; }
  :deep(li) { margin: 4px 0; line-height: $ai-lh-base; }
  :deep(li > p) { margin: 0; }

  :deep(strong) { font-weight: 600; color: $text; }
  :deep(em) { font-style: italic; }
  :deep(del) { opacity: 0.6; }

  :deep(a) { color: $blue; text-decoration: none; &:hover { text-decoration: underline; } }

  :deep(blockquote) {
    margin: 8px 0; padding: 2px 0 2px 12px;
    border-left: 3px solid rgba(10,132,255,0.3); color: $text2;
  }

  :deep(hr) { margin: 12px 0; border: none; border-top: 1px solid var(--ai-fill-4); }

  /* 代码不再小于正文:等宽字体同字号下视觉本就更小,压到 12.5px 会更难读 */
  :deep(code) {
    font-family: $mono; font-size: 0.94em;
    background: rgba(10,132,255,0.1); color: $blue;
    padding: 1.5px 6px; border-radius: 4px;
  }

  :deep(pre) {
    display: block; margin: 10px 0; padding: 13px 15px;
    background: #1d1d1f; color: #f5f5f7;
    font-family: $mono; font-size: 14.5px; line-height: 1.65;
    border-radius: 10px; white-space: pre; overflow-x: auto;
    code { background: none; padding: 0; color: inherit; font-size: inherit; }
  }

  :deep(table) {
    border-collapse: collapse; margin: 10px 0; font-size: $ai-fs-5;
    display: block; overflow-x: auto; max-width: 100%;
  }
  :deep(th), :deep(td) { border: 1px solid var(--ai-fill-4); padding: 6px 10px; text-align: left; }
  :deep(th) { background: var(--ai-fill-1); font-weight: 600; }

  :deep(img) { max-width: 100%; border-radius: 8px; }
}
</style>
