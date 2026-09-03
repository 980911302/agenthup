<template>
  <el-dialog
    :model-value="visible"
    :fullscreen="fullscreen"
    :width="dialogWidth"
    append-to-body
    destroy-on-close
    :show-close="false"
    class="ws-preview-dlg"
    @close="onClose"
  >
    <template #header>
      <div class="ws-preview-dlg__head">
        <span class="ws-preview-dlg__path" :title="path">{{ path || '未选择文件' }}</span>
        <div class="ws-preview-dlg__actions">
          <button type="button" class="ws-preview-dlg__btn" title="下载此文件" :disabled="!path" @click="onDownload">
            <svg width="13" height="13" viewBox="0 0 14 14" fill="none">
              <path d="M7 1.5v7M4 6l3 3 3-3" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/>
              <path d="M2 10.5v1.2a.8.8 0 0 0 .8.8h8.4a.8.8 0 0 0 .8-.8v-1.2" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/>
            </svg>
          </button>
          <button type="button" class="ws-preview-dlg__btn" :title="fullscreen ? '还原窗口' : '最大化'" @click="fullscreen = !fullscreen">
            <svg v-if="!fullscreen" width="13" height="13" viewBox="0 0 14 14" fill="none">
              <path d="M2 5V2.8a.8.8 0 0 1 .8-.8H5M9 2h2.2a.8.8 0 0 1 .8.8V5M12 9v2.2a.8.8 0 0 1-.8.8H9M5 12H2.8a.8.8 0 0 1-.8-.8V9" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
            </svg>
            <svg v-else width="13" height="13" viewBox="0 0 14 14" fill="none">
              <path d="M5 2v2.2a.8.8 0 0 1-.8.8H2M9 2v2.2a.8.8 0 0 0 .8.8H12M5 12V9.8a.8.8 0 0 0-.8-.8H2M9 12V9.8a.8.8 0 0 1 .8-.8H12" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/>
            </svg>
          </button>
          <button type="button" class="ws-preview-dlg__btn" title="关闭" @click="onClose">
            <svg width="13" height="13" viewBox="0 0 14 14" fill="none">
              <path d="M3.5 3.5l7 7M10.5 3.5l-7 7" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/>
            </svg>
          </button>
        </div>
      </div>
    </template>

    <div class="ws-preview-dlg__body" v-loading="loading">
      <img v-if="mediaUrl && isImageFile" :src="mediaUrl" class="ws-preview-dlg__media ws-preview-dlg__img" alt="预览图片" />
      <video v-else-if="mediaUrl && isVideoFile" :src="mediaUrl" class="ws-preview-dlg__media ws-preview-dlg__video" controls preload="metadata" />
      <audio v-else-if="mediaUrl && isAudioFile" :src="mediaUrl" class="ws-preview-dlg__media ws-preview-dlg__audio" controls preload="metadata" />
      <div v-else-if="errorText" class="ws-preview-dlg__hint">{{ errorText }}</div>
      <div v-else-if="binary" class="ws-preview-dlg__hint">二进制文件，不支持预览(可下载)</div>
      <div v-else-if="tooLarge" class="ws-preview-dlg__hint">文件超过 200KB，不支持预览(可下载)</div>
      <MarkdownContent v-else-if="isMarkdownFile" :text="content" />
      <pre v-else-if="highlightedHtml" class="ws-preview-dlg__code" v-html="highlightedHtml"></pre>
      <pre v-else-if="content !== ''" class="ws-preview-dlg__code ws-preview-dlg__plain">{{ content }}</pre>
    </div>
  </el-dialog>
</template>

<script setup>
/**
 * 工作区文件弹框预览 —— 点击文件后的全屏体验层。
 *
 * <p>职责只做「给定 sessionId + path,把文件预览出来」:
 * 图片/视频/音频走下载接口拿 blob(带 token) -> objectURL 播放;
 * 其余调 `/file` 接口读文本,markdown 渲染,代码用 highlight.js 按后缀高亮。
 * 右上角提供下载 / 最大化(弹框内撑满视口) / 关闭。
 *
 * <p>高亮按需注册:不静态引入全套 hljs 语言(~1MB),而是 import.meta.glob 构建期
 * 扫成 async chunk + 运行时 registerLanguage,已注册的进 Set 缓存,
 * 全站共用一个 hljs 实例。
 */
import { ref, computed, watch, getCurrentInstance } from 'vue'
import { getWorkspaceFile, workspaceFileDownloadUrl } from '@/api/ai/workspace'
import { getToken } from '@/utils/auth'
import hljs from 'highlight.js/lib/core'
import MarkdownContent from './MarkdownContent.vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  sessionId: { type: String, default: null },
  path: { type: String, default: '' }
})
const emit = defineEmits(['update:visible'])

const { proxy } = getCurrentInstance()

const fullscreen = ref(false)
const loading = ref(false)
const content = ref('')
const errorText = ref('')
const binary = ref(false)
const tooLarge = ref(false)
const highlightedHtml = ref('')
const mediaUrl = ref('')
let mediaObjectUrl = null

const dialogWidth = 'min(920px, calc(100vw - 40px))'

/* ---- 媒体与文本识别 ---- */
const IMAGE_EXT = /\.(png|jpe?g|gif|webp|svg|bmp|ico)$/i
const VIDEO_EXT = /\.(mp4|webm|mov|m4v)$/i
const AUDIO_EXT = /\.(mp3|wav|ogg|m4a|aac)$/i
const isImageFile = computed(() => IMAGE_EXT.test(props.path))
const isVideoFile = computed(() => VIDEO_EXT.test(props.path))
const isAudioFile = computed(() => AUDIO_EXT.test(props.path))
const isMarkdownFile = computed(() => /\.md$/i.test(props.path))

/* ---- highlight.js 语言按需加载 ----
   后缀 -> hljs 模块名(对应 highlight.js/lib/languages/<name>)。
   不上 highgightAuto 兜底:某个前缀注册了语言后,未知后缀会被误判成那种语言,
   造出更丑的假彩色。宁可当纯文本。 */
const LANG_BY_EXT = {
  java: 'java',
  py: 'python',
  js: 'javascript', mjs: 'javascript', cjs: 'javascript', jsx: 'javascript',
  ts: 'typescript', tsx: 'typescript',
  go: 'go',
  rs: 'rust',
  c: 'c', h: 'cpp', cpp: 'cpp', cc: 'cpp', hpp: 'cpp',
  cs: 'csharp', kt: 'kotlin', swift: 'swift',
  php: 'php', rb: 'ruby',
  sh: 'bash', bash: 'bash', zsh: 'bash',
  bat: 'dos', cmd: 'dos', ps1: 'powershell',
  pl: 'perl', lua: 'lua', r: 'r', scala: 'scala', groovy: 'groovy', dart: 'dart',
  sql: 'sql',
  json: 'json', yaml: 'yaml', yml: 'yaml',
  xml: 'xml', html: 'xml', htm: 'xml', svg: 'xml', vue: 'xml', svelte: 'xml',
  css: 'css', scss: 'scss', less: 'scss',
  properties: 'properties', conf: 'properties',
  ini: 'ini', toml: 'ini', cfg: 'ini',
  diff: 'diff', patch: 'diff',
  md: 'markdown', markdown: 'markdown',
  dockerfile: 'dockerfile', gradle: 'gradle', makefile: 'makefile', proto: 'protobuf'
}
const registeredLangs = new Set()

/** 语言模块按需加载:import.meta.glob 在构建期把映射表用到的语言扫成 async chunk。
    Vite 对带变量的动态 import 无法静态分析,生产构建会留成运行时 import(node_modules
    不在线上)直接炸;裸路径 node_modules/... 也不被 glob 允许,须写 /node_modules/...
    从项目根解析。括号 {..} 收窄到下面这个集合,避免把 hljs 全部 ~386 个语言模块
    都扫进产物。key 就是完整匹配路径(如 /node_modules/highlight.js/lib/languages/java.js)。 */
const LANG_MODULES = import.meta.glob(
  '/node_modules/highlight.js/lib/languages/{java,python,javascript,typescript,go,rust,c,cpp,csharp,kotlin,swift,php,ruby,bash,dos,powershell,perl,lua,r,scala,groovy,dart,sql,json,yaml,xml,css,scss,properties,ini,diff,markdown,dockerfile,gradle,makefile,protobuf}.js'
)

/** 按路径尾部 /languages/<name>.js 反查加载器:Vite 对根相对模式展开的 key
    在 dev 与构建两种解析下前缀可能不同,不按整串匹配。 */
const LANG_LOADERS = new Map(
  Object.entries(LANG_MODULES)
    .map(([file, load]) => {
      const m = /\/languages\/([^/]+)\.js$/.exec(file)
      return m ? [m[1], load] : null
    })
    .filter(Boolean)
)

async function loadLang(lang) {
  const loader = LANG_LOADERS.get(lang)
  const mod = loader ? await loader() : null
  if (mod) hljs.registerLanguage(lang, mod.default || mod)
}

function langFor(path) {
  const i = String(path || '').lastIndexOf('.')
  if (i <= 0) return ''
  const ext = path.slice(i + 1).toLowerCase().replace('.', '')
  return LANG_BY_EXT[ext] || ''
}

async function highlight(code, lang) {
  if (!lang) return ''
  try {
    if (!registeredLangs.has(lang)) {
      await loadLang(lang)
      registeredLangs.add(lang)
    }
    if (!hljs.getLanguage(lang)) return ''
    return hljs.highlight(code, { language: lang }).value
  } catch (e) {
    console.warn('[workspace-preview] 语法高亮失败:', lang, e)
    return ''
  }
}

/* ---- 加载 ---- */
function reset() {
  content.value = ''
  errorText.value = ''
  binary.value = false
  tooLarge.value = false
  highlightedHtml.value = ''
  revokeMedia()
}

function revokeMedia() {
  if (mediaObjectUrl) {
    URL.revokeObjectURL(mediaObjectUrl)
    mediaObjectUrl = null
  }
  mediaUrl.value = ''
}

/** 图片/音视频:原生 fetch 下载接口(bearer 置于请求头),blob -> objectURL。 */
async function loadMedia(path) {
  const base = import.meta.env.VITE_APP_BASE_API || '/dev-api'
  const resp = await fetch(base + workspaceFileDownloadUrl(props.sessionId, path), {
    headers: { Authorization: 'Bearer ' + getToken() }
  })
  if (!resp.ok) throw new Error('HTTP ' + resp.status)
  const blob = await resp.blob()
  mediaObjectUrl = URL.createObjectURL(blob)
  mediaUrl.value = mediaObjectUrl
}

async function load() {
  const { sessionId, path } = props
  if (!sessionId || !path) {
    reset()
    return
  }
  reset()
  loading.value = true
  try {
    if (IMAGE_EXT.test(path) || VIDEO_EXT.test(path) || AUDIO_EXT.test(path)) {
      await loadMedia(path)
      return
    }
    const res = await getWorkspaceFile(sessionId, path)
    if (res.tooLarge) {
      tooLarge.value = true
    } else if (res.binary) {
      binary.value = true
    } else {
      content.value = res.content || ''
      if (!isMarkdownFile.value) {
        highlightedHtml.value = await highlight(content.value, langFor(path))
      }
    }
  } catch (e) {
    errorText.value = e?.message || '读取文件失败'
  } finally {
    loading.value = false
  }
}

function onClose() {
  // 关闭即清理:audio 等媒体元素会被 destroy-on-close 卸载停止播放
  revokeMedia()
  emit('update:visible', false)
}

/* 打开时(path 变更)才拉取;path 相同重开也要刷新,避免残留 */
watch([() => props.visible, () => props.path], ([vis]) => {
  if (vis) load()
}, { immediate: true })

/* ---- 下载 ---- */
function onDownload() {
  if (!props.sessionId || !props.path) return
  const filename = String(props.path).split('/').pop() || 'file'
  // 复用若依 $download.zip:axios blob + 带 token,服务端返回 JSON 错误时能弹出来
  proxy.$download.zip(workspaceFileDownloadUrl(props.sessionId, props.path), filename)
}
</script>

<style scoped lang="scss">
@use '../../../../assets/styles/ai-tokens.scss' as *;

/* 语法主题:弹框作用域下的自定义属性,浅色默认、html.dark 覆盖亮色系,
   与 ai-theme.scss 的深色切换保持一致(不写死 hex 到具体元素上) */
.ws-preview-dlg {
  --hl-keyword: #5E5CE6;
  --hl-string: #1F9D4D;
  --hl-comment: #8E8E93;
  --hl-number: #B25E00;
  --hl-title: #0A60D8;
  --hl-literal: #B25E00;
  --hl-attr: #0A60D8;
  --hl-var: #5B5D61;
  --hl-op: var(--ai-text3);
  --hl-code-bg: var(--ai-code-bg, #f5f5f7);
}
html.dark .ws-preview-dlg {
  --hl-keyword: #A5A2FF;
  --hl-string: #4AE06A;
  --hl-comment: #86868B;
  --hl-number: #FFB340;
  --hl-title: #4D9FFF;
  --hl-literal: #FFB340;
  --hl-attr: #4D9FFF;
  --hl-var: #C6C6CC;
  --hl-code-bg: var(--ai-code-bg, #2c2c2e);
}

.ws-preview-dlg {
  :deep(.el-dialog) {
    /* 压掉 element 默认的 15vh 上边距:配合下面 height:78vh + margin-bottom 才不会撑出滚动条 */
    --el-dialog-margin-top: 4vh;
    padding: 0; overflow: hidden; border-radius: 14px;
    height: min(78vh, 54rem); display: flex; flex-direction: column;
  }
  /* 全屏时把 element 的 15vh 上边距和 50px 下边距彻底清掉,高度 100% 才不会溢出 */
  :deep(.el-dialog--fullscreen) { height: 100%; border-radius: 0; --el-dialog-margin-top: 0; margin-bottom: 0; }
  :deep(.el-dialog__header) { flex-shrink: 0; margin: 0; padding: 0; }
  :deep(.el-dialog__body) {
    flex: 1 1 auto; min-height: 0; overflow: hidden; padding: 0;
    display: flex; flex-direction: column;
  }
}

.ws-preview-dlg__head {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  padding: 12px 16px; height: 52px; box-sizing: border-box;
  background: var(--ai-card-bg); border-bottom: 1px solid var(--ai-border);
}

.ws-preview-dlg__path {
  font-family: $mono; font-size: 12.5px; color: $text;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}

.ws-preview-dlg__actions {
  display: flex; align-items: center; gap: 4px; flex-shrink: 0;
}

.ws-preview-dlg__btn {
  width: 28px; height: 28px; border: none; background: transparent;
  color: $gray; border-radius: 8px; cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center;
  transition: all 0.18s $ease;
  &:hover { background: rgba(10,132,255,0.08); color: $blue; }
  &:disabled { opacity: 0.4; cursor: not-allowed; }
}

.ws-preview-dlg__body {
  color: $text;
}

.ws-preview-dlg__media {
  display: block; margin: auto; max-width: 100%; max-height: 100%;
  object-fit: contain; border-radius: 0;
}
.ws-preview-dlg__img {
  background:
    repeating-conic-gradient(var(--ai-fill-1) 0% 25%, transparent 0% 50%) 50% / 16px 16px;
}
.ws-preview-dlg__audio { width: min(100%, 480px); margin: auto; }

.ws-preview-dlg__hint {
  margin: auto; font-size: 13px; color: $gray2; letter-spacing: 0.01em;
}

.ws-preview-dlg__code {
  flex: 1 1 auto; min-height: 0; overflow: auto; margin: 0;
  padding: 14px 16px; background: var(--hl-code-bg);
  font-family: $mono; font-size: 12.5px; line-height: 1.65;
  white-space: pre-wrap; word-break: break-word; tab-size: 4;

  &::-webkit-scrollbar { width: 8px; height: 8px; }
  &::-webkit-scrollbar-thumb { background: var(--ai-border-4); border-radius: 4px; }

  /* v-html 进来的 span 由 hljs 生成,受这套主题约束 */
  .hljs-keyword, .hljs-module { color: var(--hl-keyword); }
  .hljs-string, .hljs-doctag, .hljs-addition, .hljs-regexp { color: var(--hl-string); }
  .hljs-comment, .hljs-quote { color: var(--hl-comment); font-style: italic; }
  .hljs-number, .hljs-literal, .hljs-symbol, .hljs-bullet { color: var(--hl-number); }
  .hljs-title,
  .hljs-title.function_,
  .hljs-title.class_ { color: var(--hl-title); }
  .hljs-attr, .hljs-tag, .hljs-name, .hljs-property,
  .hljs-selector-tag, .hljs-selector-class { color: var(--hl-attr); }
  .hljs-variable, .hljs-params, .hljs-template-variable { color: var(--hl-var); }
  .hljs-operator, .hljs-punctuation, .hljs-delimiter { color: var(--hl-op); }
  .hljs-built_in, .hljs-meta, .hljs-type, .hljs-attribute { color: var(--hl-title); }

  /* markdown 内的行内强调等 */
  .hljs-emphasis { font-style: italic; }
  .hljs-strong { font-weight: 600; }
  .hljs-deletion { color: var(--hl-number); }
}

.ws-preview-dlg__plain {
  color: $text;
}
</style>