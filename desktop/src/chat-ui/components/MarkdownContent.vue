<template>
  <div class="md-content" v-html="html" @click="handleContentClick"></div>
</template>

<script setup>
import { computed } from 'vue'
import { Marked } from 'marked'
import DOMPurify from 'dompurify'

const props = defineProps({
  text: { type: String, default: '' }
})

const md = new Marked({
  breaks: true,
  gfm: true
})

md.use({
  renderer: {
    code({ text, lang }) {
      const language = (lang || '').trim()
      const langLabel = language ? language.toUpperCase() : 'CODE'
      const escaped = text
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;')

      return `
<div class="code-block" data-lang="${language}">
  <div class="code-block__header">
    <span class="code-block__lang">${langLabel}</span>
    <span class="code-block__spacer"></span>
    <button type="button" class="code-block__copy" title="复制代码">
      <svg width="13" height="13" viewBox="0 0 16 16" fill="none"><path d="M5.5 2.5h6A1.5 1.5 0 0 1 13 4v7a1.5 1.5 0 0 1-1.5 1.5h-6A1.5 1.5 0 0 1 4 11V4a1.5 1.5 0 0 1 1.5-1.5z" stroke="currentColor" stroke-width="1.3"/><path d="M3 5.5H2.5A1.5 1.5 0 0 0 1 7v6A1.5 1.5 0 0 0 2.5 14.5h6A1.5 1.5 0 0 0 10 13v-.5" stroke="currentColor" stroke-width="1.3"/></svg>
      <span class="code-block__copy-text">复制</span>
    </button>
  </div>
  <pre class="code-block__pre"><code class="code-block__code">${escaped}</code></pre>
</div>`
    }
  }
})

const html = computed(() => {
  if (!props.text) return ''
  const raw = md.parse(props.text)
  return DOMPurify.sanitize(raw, {
    ADD_ATTR: ['target', 'data-lang', 'viewBox', 'd', 'stroke', 'stroke-width', 'stroke-linecap', 'stroke-linejoin', 'fill'],
    ADD_TAGS: ['span', 'button', 'svg', 'path', 'div', 'pre', 'code']
  })
})

function handleContentClick(e) {
  const btn = e.target.closest('.code-block__copy')
  if (!btn) return
  const codeEl = btn.closest('.code-block')?.querySelector('.code-block__code')
  if (!codeEl) return
  const textToCopy = codeEl.textContent || ''
  if (!textToCopy) return

  const copyAction = () => {
    const textSpan = btn.querySelector('.code-block__copy-text')
    if (textSpan) textSpan.textContent = '已复制 ✓'
    btn.classList.add('is-copied')
    setTimeout(() => {
      if (textSpan) textSpan.textContent = '复制'
      btn.classList.remove('is-copied')
    }, 1800)
  }

  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(textToCopy).then(copyAction).catch(() => fallbackCopy(textToCopy, copyAction))
  } else {
    fallbackCopy(textToCopy, copyAction)
  }
}

function fallbackCopy(text, onSuccess) {
  const ta = document.createElement('textarea')
  ta.value = text
  ta.style.position = 'fixed'
  ta.style.opacity = '0'
  document.body.appendChild(ta)
  ta.select()
  try {
    document.execCommand('copy')
    if (onSuccess) onSuccess()
  } catch (_) {}
  document.body.removeChild(ta)
}
</script>

<style scoped lang="scss">
@use '../ai-tokens.scss' as *;

.md-content {
  word-break: break-word;
  color: var(--text);
  font-size: 14.5px;
  line-height: $ai-lh-base;

  :deep(> *:first-child) { margin-top: 0; }
  :deep(> *:last-child) { margin-bottom: 0; }

  :deep(p) { margin: 0 0 12px; line-height: $ai-lh-base; }

  /* 标题层级 */
  :deep(h1), :deep(h2), :deep(h3), :deep(h4), :deep(h5), :deep(h6) {
    margin: 20px 0 10px;
    font-weight: 650;
    color: var(--text);
    line-height: $ai-lh-tight;
    letter-spacing: -0.01em;
  }
  :deep(h1) { font-size: 20px; }
  :deep(h2) { font-size: 17.5px; }
  :deep(h3) { font-size: 15.5px; }
  :deep(h4), :deep(h5), :deep(h6) { font-size: 14.5px; }

  :deep(ul), :deep(ol) {
    margin: 0 0 12px;
    padding-left: 22px;
  }
  :deep(li) {
    margin: 4px 0;
    line-height: $ai-lh-base;
  }
  :deep(li > p) { margin: 0; }

  :deep(strong) { font-weight: 600; color: var(--text); }
  :deep(em) { font-style: italic; color: var(--text-secondary); }
  :deep(del) { opacity: 0.6; }

  :deep(a) {
    color: var(--accent);
    text-decoration: none;
    font-weight: 500;
    &:hover { text-decoration: underline; }
  }

  :deep(blockquote) {
    margin: 12px 0;
    padding: 6px 14px;
    border-left: 3px solid var(--accent);
    background: var(--ai-fill-1);
    border-radius: 0 8px 8px 0;
    color: var(--text-secondary);
  }

  :deep(hr) {
    margin: 18px 0;
    border: none;
    border-top: 1px solid var(--border);
  }

  /* 行内代码 */
  :deep(code:not(.code-block__code)) {
    font-family: $mono;
    font-size: 0.9em;
    background: var(--ai-fill-2);
    color: var(--accent);
    border: 1px solid var(--border);
    padding: 1.5px 5.5px;
    border-radius: 5px;
    margin: 0 2px;
  }

  /* 现代卡片风格代码块 */
  :deep(.code-block) {
    margin: 14px 0;
    border-radius: 10px;
    overflow: hidden;
    background: var(--bg-code);
    border: 1px solid var(--border);
    box-shadow: var(--shadow-card);

    .code-block__header {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 8px 12px;
      background: var(--bg-code-header);
      border-bottom: 1px solid var(--border);
    }

    .code-block__spacer {
      flex: 1;
    }

    .code-block__lang {
      font-family: $mono;
      font-size: 11.5px;
      font-weight: 600;
      color: var(--text-secondary);
      letter-spacing: 0.5px;
    }

    .code-block__copy {
      margin-left: auto;
      display: inline-flex;
      align-items: center;
      gap: 5px;
      padding: 3px 8px;
      border-radius: 5px;
      border: 1px solid var(--border);
      background: var(--ai-fill-2);
      color: var(--text-secondary);
      font-family: inherit;
      font-size: 11.5px;
      cursor: pointer;
      transition: all 0.15s ease;

      &:hover {
        background: var(--bg-hover);
        color: var(--text);
        border-color: var(--border-strong);
      }

      &.is-copied {
        background: rgba(16, 185, 129, 0.15);
        color: var(--ok-text);
        border-color: var(--ok);
      }
    }

    .code-block__pre {
      margin: 0;
      padding: 12px 14px;
      overflow-x: auto;
      font-family: $mono;
      font-size: 13.5px;
      line-height: 1.65;
      color: var(--text-code);
      white-space: pre;
    }

    .code-block__code {
      font-family: inherit;
      color: inherit;
      background: transparent;
      padding: 0;
      border: none;
    }
  }

  /* 表格 */
  :deep(table) {
    border-collapse: collapse;
    margin: 14px 0;
    font-size: 13.5px;
    width: 100%;
    overflow-x: auto;
    display: block;
    border-radius: 8px;
    border: 1px solid var(--border);
  }
  :deep(th), :deep(td) {
    border: 1px solid var(--border);
    padding: 8px 12px;
    text-align: left;
  }
  :deep(th) {
    background: var(--ai-fill-2);
    font-weight: 600;
    color: var(--text);
  }
  :deep(tr:hover td) {
    background: var(--ai-fill-1);
  }

  :deep(img) {
    max-width: 100%;
    border-radius: 10px;
    border: 1px solid var(--border);
    margin: 8px 0;
  }
}
</style>
