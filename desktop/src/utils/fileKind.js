import { h } from 'vue'

/**
 * 文件类型 → 线性图标 / 类型标签。
 *
 * 为什么不用 emoji:项目其余页面(KbView / ResourceView)零 emoji,统一用
 * 24x24 viewBox、stroke-width 1.8、round 端点的线性图标。emoji 在不同系统下
 * 字形和基线都不一致,也没法跟随主题着色。
 */
const icon = paths => ({
  render() {
    return h('svg', { width: 18, height: 18, viewBox: '0 0 24 24', fill: 'none', 'aria-hidden': 'true' },
      paths.map(d => h('path', {
        d, stroke: 'currentColor', 'stroke-width': 1.8,
        'stroke-linecap': 'round', 'stroke-linejoin': 'round'
      })))
  }
})

// 通用文件轮廓：带折角的纸张，其余类型图标都在这个骨架上做区分
export const FileIcon = icon(['M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8Z', 'M14 3v5h5'])
export const FileTextIcon = icon(['M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8Z', 'M14 3v5h5', 'M9 13h6M9 17h4'])
export const ImageIcon = icon(['M4 5.8A1.8 1.8 0 0 1 5.8 4h12.4A1.8 1.8 0 0 1 20 5.8v12.4a1.8 1.8 0 0 1-1.8 1.8H5.8A1.8 1.8 0 0 1 4 18.2Z', 'M8.5 10.5a1.4 1.4 0 1 0 0-2.8 1.4 1.4 0 0 0 0 2.8Z', 'm4.5 16.5 4-4 3.5 3.5 3-2.5 4.5 4'])
export const CodeIcon = icon(['m8 9-3 3 3 3M16 9l3 3-3 3M13.5 5.5l-3 13'])
export const SheetIcon = icon(['M4 5.8A1.8 1.8 0 0 1 5.8 4h12.4A1.8 1.8 0 0 1 20 5.8v12.4a1.8 1.8 0 0 1-1.8 1.8H5.8A1.8 1.8 0 0 1 4 18.2Z', 'M4 9.5h16M4 14.5h16M9.5 9.5V20M14.5 9.5V20'])
export const ArchiveIcon = icon(['M3.5 7.5A1.5 1.5 0 0 1 5 6h14a1.5 1.5 0 0 1 1.5 1.5v1A1.5 1.5 0 0 1 19 10H5a1.5 1.5 0 0 1-1.5-1.5Z', 'M5 10v8.2A1.8 1.8 0 0 0 6.8 20h10.4a1.8 1.8 0 0 0 1.8-1.8V10', 'M10.5 14h3'])
export const PdfIcon = icon(['M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8Z', 'M14 3v5h5', 'M9 16.5c2.4-1 3.6-3 4-5 .3 2.6 1.6 4.2 3 4.6'])
export const AudioIcon = icon(['M10 17.5V6.2l8-1.7v11', 'M8 20a2 2 0 1 0 0-4 2 2 0 0 0 0 4ZM16 18.3a2 2 0 1 0 0-4 2 2 0 0 0 0 4Z'])
export const VideoIcon = icon(['M3.5 7.8A1.8 1.8 0 0 1 5.3 6h8.4a1.8 1.8 0 0 1 1.8 1.8v8.4a1.8 1.8 0 0 1-1.8 1.8H5.3a1.8 1.8 0 0 1-1.8-1.8Z', 'm15.5 13.2 5 2.8V8l-5 2.8'])

const BY_EXTENSION = {
  png: ImageIcon, jpg: ImageIcon, jpeg: ImageIcon, gif: ImageIcon,
  webp: ImageIcon, svg: ImageIcon, bmp: ImageIcon, ico: ImageIcon, avif: ImageIcon,
  pdf: PdfIcon,
  csv: SheetIcon, xls: SheetIcon, xlsx: SheetIcon, tsv: SheetIcon,
  zip: ArchiveIcon, tar: ArchiveIcon, gz: ArchiveIcon, rar: ArchiveIcon, '7z': ArchiveIcon,
  mp3: AudioIcon, wav: AudioIcon, ogg: AudioIcon, m4a: AudioIcon, aac: AudioIcon, flac: AudioIcon,
  mp4: VideoIcon, webm: VideoIcon, mov: VideoIcon, m4v: VideoIcon, avi: VideoIcon,
  js: CodeIcon, ts: CodeIcon, jsx: CodeIcon, tsx: CodeIcon, vue: CodeIcon,
  java: CodeIcon, py: CodeIcon, go: CodeIcon, rs: CodeIcon, rb: CodeIcon, php: CodeIcon,
  c: CodeIcon, h: CodeIcon, cpp: CodeIcon, cs: CodeIcon, kt: CodeIcon, swift: CodeIcon,
  sh: CodeIcon, sql: CodeIcon, html: CodeIcon, css: CodeIcon, scss: CodeIcon,
  json: CodeIcon, xml: CodeIcon, yaml: CodeIcon, yml: CodeIcon, toml: CodeIcon,
  txt: FileTextIcon, md: FileTextIcon, markdown: FileTextIcon, log: FileTextIcon,
  doc: FileTextIcon, docx: FileTextIcon, rtf: FileTextIcon
}

/** 扩展名，小写、不含点；无扩展名返回空串 */
export function extensionOf(name = '') {
  const dot = String(name).lastIndexOf('.')
  if (dot < 0 || dot === name.length - 1) return ''
  return name.slice(dot + 1).toLowerCase()
}

/** 文件 → 图标组件。mime 优先于扩展名，因为它由服务端判定，更可信 */
export function fileIconFor(name = '', mime = '') {
  if (mime) {
    if (mime.startsWith('image/')) return ImageIcon
    if (mime.startsWith('audio/')) return AudioIcon
    if (mime.startsWith('video/')) return VideoIcon
    if (mime === 'application/pdf') return PdfIcon
  }
  return BY_EXTENSION[extensionOf(name)] || FileIcon
}

/** 列表里显示的类型角标，如 PNG、CSV；无扩展名时给「文件」 */
export function fileKindLabel(name = '') {
  const ext = extensionOf(name)
  return ext ? ext.toUpperCase() : '文件'
}

const TEXT_EXTENSIONS = new Set([
  'txt', 'md', 'markdown', 'json', 'csv', 'tsv', 'log', 'xml', 'yaml', 'yml', 'toml', 'ini', 'conf',
  'js', 'ts', 'jsx', 'tsx', 'vue', 'java', 'py', 'go', 'rs', 'rb', 'php', 'c', 'h', 'cpp', 'cs',
  'kt', 'swift', 'sh', 'sql', 'html', 'css', 'scss', 'less'
])

/** 能否在弹窗里当文本预览 */
export function isPreviewableText(name = '', mime = '') {
  if (mime && (mime.startsWith('text/') || mime === 'application/json' || mime === 'application/xml')) return true
  return TEXT_EXTENSIONS.has(extensionOf(name))
}
