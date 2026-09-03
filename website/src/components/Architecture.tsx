const rows = [
  {
    ix: '01',
    title: 'Run 引擎驱动',
    desc: '状态机编排对话，事件经 Redis 广播，全程可订阅、可回放。',
  },
  {
    ix: '02',
    title: '装配与执行解耦',
    desc: 'Factory 产出不可变 Context，执行层只消费，不在运行时拼装配置。',
  },
  {
    ix: '03',
    title: '双数据源',
    desc: 'MySQL 承载业务与权限；PostgreSQL + pgvector 专管知识库向量。',
  },
  {
    ix: '04',
    title: '渠道动态模型',
    desc: '不绑定单一 SDK。按数据库渠道配置动态构造 Chat / Embed / Image。',
  },
]

const layers = [
  {
    label: '接入层',
    code: 'L0',
    nodes: ['REST API', 'WebSocket', 'SSE'],
  },
  {
    label: '编排层',
    code: 'L1',
    nodes: ['ChatRunService', 'Run 状态机', 'Agent 工厂'],
  },
  {
    label: '能力层',
    code: 'L2',
    nodes: ['工具 / MCP', '知识库', '工作空间', '记忆'],
  },
  {
    label: '模型层',
    code: 'L3',
    nodes: ['渠道路由', 'Chat', 'Embedding', 'Image'],
  },
  {
    label: '数据与外部',
    code: 'L4',
    nodes: ['MySQL', 'PG + pgvector', 'Redis', '上游大模型'],
  },
]

export function Architecture() {
  return (
    <section className="section shell" id="architecture">
      <div className="section-head" data-rv>
        <div className="eyebrow">
          <span className="dot" />
          第二章 · 架构
          <span className="jp">ARCHITECTURE</span>
        </div>
        <h2 className="display h-sec">系统如何被切开。</h2>
        <p className="body-lg">
          配置好的智能体 × 渠道模型 × 工具 × 知识库，最终跑成一次完整
          Run。分层清楚，方便二次开发与运维。
        </p>
      </div>

      <div className="feature-block" style={{ paddingTop: 0 }}>
        <div className="feature-meta" data-rv>
          <div className="eyebrow">设计原则</div>
          <h2 className="display feature-title">
            硬约束，
            <br />
            不是 PPT 架构图。
          </h2>
          <p className="body-lg">
            从请求进入到计量落库，主路径可预期。业务用户拼装，后端负责可靠执行。
          </p>
          <a className="link" href="#pipeline">
            查看执行流程 →
          </a>
        </div>

        <div className="feature-panel" data-rv>
          <div className="panel-rows">
            {rows.map((r) => (
              <div key={r.ix} className="panel-row">
                <span className="ix">{r.ix}</span>
                <div>
                  <h4>{r.title}</h4>
                  <p>{r.desc}</p>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>

      <div className="feature-panel layer-map" data-rv>
        <div className="eyebrow">
          分层拓扑
          <span className="jp">LAYER MAP</span>
        </div>
        <div className="layer-list">
          {layers.map((layer) => (
            <div key={layer.code} className="layer-row">
              <div className="layer-row-head">
                <span className="label">{layer.label}</span>
                <span className="code">{layer.code}</span>
              </div>
              <div className="layer-nodes">
                {layer.nodes.map((n) => (
                  <span key={n}>{n}</span>
                ))}
              </div>
            </div>
          ))}
        </div>
      </div>
    </section>
  )
}
