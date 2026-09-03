const items = [
  {
    n: '01',
    title: '多智能体',
    desc: '配置即装配。父子 Agent 协作，递归深度保护，提示词结构对 KV-cache 友好。',
  },
  {
    n: '02',
    title: '工具与 MCP',
    desc: '策略、预算与确认流。对接 MCP Server，把外部系统安全接入对话。',
  },
  {
    n: '03',
    title: '知识库 RAG',
    desc: '文档治理与向量检索，支持混合与图谱模式。证据带来源出处。',
  },
  {
    n: '04',
    title: 'Run 引擎',
    desc: '状态机驱动每一次对话。事件可订阅，Trace 可落库，失败可续跑。',
  },
]

export function Chapters() {
  return (
    <section className="chapters" id="pathways">
      <div className="chapters-inner">
        <div className="chapters-grid">
          {items.map((it, i) => (
            <article
              key={it.n}
              className="chapter-card"
              data-rv
              style={{ transitionDelay: `${i * 60}ms` }}
            >
              <div className="n">{it.n}</div>
              <h3>{it.title}</h3>
              <p>{it.desc}</p>
            </article>
          ))}
        </div>
      </div>
    </section>
  )
}
