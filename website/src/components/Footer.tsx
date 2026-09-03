function BrandMark() {
  return (
    <svg className="brand-mark" viewBox="0 0 32 32" fill="none" aria-hidden>
      <rect width="32" height="32" fill="#05070a" />
      <circle cx="16" cy="18" r="7" fill="#e0231c" />
      <rect x="5" y="8" width="22" height="2.2" fill="#dfe7e0" />
      <rect x="8" y="13" width="16" height="1.8" fill="#dfe7e0" />
    </svg>
  )
}

export function Footer() {
  return (
    <footer className="footer">
      <div className="footer-inner">
        <div className="footer-top">
          <div className="footer-brand">
            <a href="#top" className="brand">
              <BrandMark />
              <span className="brand-tx">
                <b>AGENT JAVA</b>
                <i>多智能体运行时</i>
              </span>
            </a>
            <p>
              基于 RuoYi 与 Spring AI 的企业级多 Agent 平台。可观测、可计量、可回放。
            </p>
          </div>
          <div className="footer-cols">
            <div className="footer-col">
              <h4>产品</h4>
              <a href="#pathways">核心能力</a>
              <a href="#modules">功能模块</a>
              <a href="#pipeline">运行流程</a>
            </div>
            <div className="footer-col">
              <h4>工程</h4>
              <a href="#architecture">系统架构</a>
              <a href="#stack">技术栈</a>
              <a href="#cta">获取源码</a>
            </div>
            <div className="footer-col">
              <h4>生态</h4>
              <a href="https://ruoyi.vip" target="_blank" rel="noreferrer">
                若依官网
              </a>
              <a
                href="https://docs.spring.io/spring-ai/reference/"
                target="_blank"
                rel="noreferrer"
              >
                Spring AI
              </a>
              <a href="https://modelcontextprotocol.io" target="_blank" rel="noreferrer">
                MCP 协议
              </a>
            </div>
          </div>
        </div>
        <div className="footer-bot">
          <span>© {new Date().getFullYear()} Agent Java</span>
          <span>Java 17 · Spring Boot 3 · Spring AI 1.1</span>
        </div>
      </div>
    </footer>
  )
}
