import { ref, watch, computed } from 'vue'

/**
 * 步骤展开状态 + 常用格式化，四个 step 组件共用。
 *
 * <p>展开状态不能直接绑 `:open="step.streaming"`：流式一结束 streaming 变 false，
 * details 会自动收起 —— 用户正在读工具返回时被收走。这里的规则是
 * 「流式中默认展开，结束后自动收起；但用户手动操作过之后就不再自动接管」。
 *
 * @param {Function} streamingGetter 返回当前是否流式中
 */
export function useStepToggle(streamingGetter) {
  const open = ref(!!streamingGetter())
  const touched = ref(false)

  function onToggle(e) {
    const next = e.target.open
    if (next !== open.value) {
      touched.value = true
      open.value = next
    }
  }

  watch(streamingGetter, (streaming) => {
    if (streaming && !touched.value) open.value = true
    if (!streaming && !touched.value) open.value = false
  })

  return { open, onToggle }
}

/** 耗时：1234ms 四位数难读，超过 1s 换成 1.2s */
export function formatMs(ms) {
  const v = Number(ms) || 0
  if (v >= 1000) return (v / 1000).toFixed(1).replace(/\.0$/, '') + 's'
  return v + 'ms'
}

/**
 * 折叠行上的入参摘要 —— 不展开也要知道这一步干了什么。
 * 工具入参通常是 {"path":"a/b.md"} 这种，取第一个有值的字段。
 */
export function briefOfArgs(args, max = 90) {
  if (!args) return ''
  let text = String(args)
  try {
    const obj = JSON.parse(args)
    if (obj && typeof obj === 'object') {
      const vals = Object.values(obj).filter(v => v !== null && v !== undefined && v !== '')
      if (vals.length) {
        text = typeof vals[0] === 'object' ? JSON.stringify(vals[0]) : String(vals[0])
      }
    }
  } catch (e) {
    // 非 JSON，按原样截断
  }
  text = text.replace(/\s+/g, ' ').trim()
  return text.length > max ? text.slice(0, max) + '…' : text
}

/** 入参代码块：JSON 能解析就美化，否则原样 */
export function prettyJson(s) {
  try {
    return JSON.stringify(JSON.parse(s), null, 2)
  } catch (e) {
    return s
  }
}

/**
 * 工具名 -> 图标类型。
 *
 * <p>改造前只有 mcp / 其余两种图标，一屏十几个工具调用长得一模一样，
 * 扫一眼分不出「读文件」和「跑命令」。这里按**动作语义**分组而不是按注册来源分，
 * 因为用户关心的是「它干了什么」。
 *
 * <p>只加图标不加底色 —— 颜色在本设计里唯一表示状态(见 _step.scss 顶部约定)，
 * 类型再占用颜色通道会让失败态不再一眼可见。
 */
export function toolIconKind(step) {
  if (!step) return 'default'
  if (step.source === 'mcp') return 'mcp'

  const n = String(step.name || '')
  if (/^(read|ls|find)$/.test(n)) return 'read'
  if (/^(write|edit)$/.test(n)) return 'write'
  if (/^(bash)$/.test(n)) return 'shell'
  if (/^(grep|search|batch_search)$/.test(n) || /search/i.test(n)) return 'search'
  if (/^(list|query)[A-Z]/.test(n)) return 'list'
  if (/ScheduledJob/.test(n)) return 'schedule'
  return 'default'
}

/**
 * 长文本折叠。
 *
 * <p>为什么必须折叠而不是只靠 CSS 限高：工具返回可以到几万字符
 * (runShell 单次上限 30,000)，`max-height + overflow` 只是把它藏起来，
 * 整串仍然进 DOM —— 一轮几十次调用后页面直接卡死。
 * 这里折叠态**只渲染截断后的片段**，展开才吐全文。
 *
 * @param {Function} textGetter 返回完整文本
 * @param {Object}   opts       maxLines 默认 12 行；maxChars 默认 600 字符，先到者为准
 */
/** 知识库检索结果格式:[n] 《文档》 路径 (channel)\n    片段内容(4 空格缩进,块间空行)。 */
export function parseKbHits(text) {
  if (!text) return []
  const blocks = String(text).split(/\n\s*\n/)
  const hits = []
  for (const block of blocks) {
    const lines = block.split('\n')
    const head = lines[0] || ''
    const m = head.match(/^\[(\d+)\]\s*《(.*?)》\s*(.*)$/)
    if (!m) continue
    const [, index, docName, rest] = m
    let headingPath = ''
    let channel = ''
    let tail = (rest || '').trim()
    // 尾部可能是 "章节路径 (channel)" 或 "(channel)" 或空
    const chM = tail.match(/^(.*?)\s*\(([^)]*)\)\s*$/)
    if (chM && chM[2]) {
      headingPath = (chM[1] || '').trim()
      channel = chM[2]
    } else {
      headingPath = tail
    }
    const content = lines.slice(1)
      .map(l => l.replace(/^ {4}/, ''))
      .join('\n')
      .trim()
    if (!content) continue
    hits.push({ index: Number(index) || hits.length + 1, docName, headingPath, channel, content })
  }
  return hits
}

export function useCollapsibleText(textGetter, opts = {}) {
  const maxLines = opts.maxLines ?? 12
  const maxChars = opts.maxChars ?? 600
  // 展开态也截断:工具返回可能上万行/几万字符,全部铺进 DOM 会刷屏拖慢页面。
  // 超过上限的部分不再展示,只提示省略行数(见 ToolStep 的「已省略」文案)。
  const maxExpandedLines = opts.maxExpandedLines ?? 100
  const maxExpandedChars = opts.maxExpandedChars ?? 8000
  const expanded = ref(false)

  const full = computed(() => {
    const v = textGetter()
    return v === null || v === undefined ? '' : String(v)
  })

  const totalLines = computed(() => (full.value ? full.value.split('\n').length : 0))

  const collapsible = computed(() =>
    totalLines.value > maxLines || full.value.length > maxChars
  )

  const shown = computed(() => {
    if (!collapsible.value) return full.value
    if (!expanded.value) {
      let out = full.value.split('\n').slice(0, maxLines).join('\n')
      if (out.length > maxChars) out = out.slice(0, maxChars)
      return out
    }
    // 展开态:行数/字符数仍受上限约束,超出即省略
    let out = full.value.split('\n').slice(0, maxExpandedLines).join('\n')
    if (out.length > maxExpandedChars) out = out.slice(0, maxExpandedChars)
    return out
  })

  /** 折叠时被藏起来的行数，用于按钮文案；不足一行按字符差兜底 */
  const hiddenLines = computed(() => Math.max(totalLines.value - maxLines, 0))

  /** 展开后仍被省略的行数(>0 表示展示内容不完整) */
  const hiddenExpandedLines = computed(() => Math.max(totalLines.value - maxExpandedLines, 0))

  /** 展开态是否还有被省略的内容 */
  const hasMore = computed(() =>
    expanded.value && (totalLines.value > maxExpandedLines || full.value.length > maxExpandedChars)
  )

  function toggle() {
    expanded.value = !expanded.value
  }

  return { expanded, shown, collapsible, totalLines, hiddenLines, hiddenExpandedLines, hasMore, toggle }
}
