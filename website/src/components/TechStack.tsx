const stack = [
  { tag: '语言', name: 'Java 17', desc: 'LTS 运行时' },
  { tag: '框架', name: 'Spring Boot 3.5', desc: '应用骨架' },
  { tag: 'AI', name: 'Spring AI 1.1', desc: '对话 / 工具 / 向量' },
  { tag: '底座', name: 'RuoYi-Vue', desc: '权限 · 菜单 · RBAC' },
  { tag: '主库', name: 'MySQL 8', desc: '业务与 ai_* 表' },
  { tag: '向量', name: 'PostgreSQL', desc: 'pgvector 知识库' },
  { tag: '总线', name: 'Redis', desc: '事件 · 缓存' },
  { tag: '调度', name: 'Quartz', desc: 'AI 定时任务' },
  { tag: '管理台', name: 'Vue 3', desc: 'Element Plus' },
  { tag: '安全', name: 'JWT', desc: 'Spring Security' },
]

export function TechStack() {
  return (
    <section className="section shell" id="stack">
      <div className="section-head" data-rv>
        <div className="eyebrow">
          <span className="dot" />
          第五章 · 技术栈
          <span className="jp">STACK</span>
        </div>
        <h2 className="display h-sec">熟悉的企业栈，长出 AI 能力。</h2>
        <p className="body-lg">
          站在团队已经会运维的技术上，而不是另起一套难落地的玩具框架。
        </p>
      </div>

      <div className="stack-row" data-rv>
        {stack.map((t) => (
          <div key={t.name} className="stack-cell">
            <div className="tag">{t.tag}</div>
            <h3>{t.name}</h3>
            <p>{t.desc}</p>
          </div>
        ))}
      </div>
    </section>
  )
}
