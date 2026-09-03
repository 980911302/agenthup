const modules = [
  {
    id: 'M01',
    path: 'agent/',
    title: '智能体核心',
    desc: '按 Agent 装配模型、工具、技能、知识库与子智能体，运行时一次解析到位。',
  },
  {
    id: 'M02',
    path: 'chat/run',
    title: '执行引擎',
    desc: 'Run 状态机、Turn 循环与事件总线。支持流式输出、工具循环与续跑。',
  },
  {
    id: 'M03',
    path: 'kb/',
    title: '知识库',
    desc: '文档治理、多模式检索、权限控制与图谱探索，动态注入检索工具。',
  },
  {
    id: 'M04',
    path: 'tool/mcp',
    title: '工具生态',
    desc: '工具策略、MCP Server、Skill 装载，以及预算与人工确认。',
  },
  {
    id: 'M05',
    path: 'model/',
    title: '模型渠道',
    desc: '上游协议适配、权重路由，Chat / Embed / Image 统一管理面。',
  },
  {
    id: 'M06',
    path: 'job/stat',
    title: '任务与计量',
    desc: 'Quartz 驱动 AI 定时任务；Token、延迟与缓存命中率全链路采集。',
  },
]

export function Modules() {
  return (
    <section className="section shell" id="modules">
      <div className="section-head" data-rv>
        <div className="eyebrow">
          <span className="dot" />
          第四章 · 模块
          <span className="jp">MODULES</span>
        </div>
        <h2 className="display h-sec">按域切开，按需扩展。</h2>
        <p className="body-lg">
          源码与 docs/ 文档一一对应。打开模块就能改，不用在黑盒里猜。
        </p>
      </div>

      <div className="mod-grid">
        {modules.map((m, i) => (
          <article
            key={m.id}
            className="mod-card"
            data-rv
            style={{ transitionDelay: `${i * 50}ms` }}
          >
            <div className="path">{m.path}</div>
            <h3>{m.title}</h3>
            <p>{m.desc}</p>
            <div className="id">{m.id}</div>
          </article>
        ))}
      </div>
    </section>
  )
}
