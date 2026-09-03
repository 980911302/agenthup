const steps = [
  {
    n: '01',
    title: '配置',
    desc: '在后台绑定模型、工具、子 Agent 与知识库——像配菜单一样拼装 AI 应用。',
    code: 'POST /ai/agent',
  },
  {
    n: '02',
    title: '调度',
    desc: '通过 REST 或 WebSocket 开跑。Run 进入状态机，上下文与操作者身份就绪。',
    code: 'POST /ai/chat/run',
  },
  {
    n: '03',
    title: '执行',
    desc: '工厂完成装配。模型决定是否调用工具、检索知识库，或委派子智能体。',
    code: 'AgentContextFactory',
  },
  {
    n: '04',
    title: '观测',
    desc: '事件流实时推送，Token 与 Trace 落库。支持回放、续跑与成本统计。',
    code: 'trace + meter',
  },
]

export function HowItWorks() {
  return (
    <section className="section shell" id="pipeline">
      <div className="section-head" data-rv>
        <div className="eyebrow">
          <span className="dot" />
          第三章 · 流程
          <span className="jp">PIPELINE</span>
        </div>
        <h2 className="display h-sec">一次 Run，四个阶段。</h2>
        <p className="body-lg">
          主路径清晰可预期。业务侧负责拼装，运行时负责把每一次对话跑完、记清、能回放。
        </p>
      </div>

      <div className="pipeline">
        {steps.map((s, i) => (
          <div
            key={s.n}
            className="pipe-step"
            data-rv
            style={{ transitionDelay: `${i * 70}ms` }}
          >
            <div className="n">{s.n}</div>
            <h3>{s.title}</h3>
            <p>{s.desc}</p>
            <div className="code">
              <b>$</b> {s.code}
            </div>
          </div>
        ))}
      </div>
    </section>
  )
}
