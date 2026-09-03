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
