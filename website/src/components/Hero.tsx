import { motion } from 'framer-motion'

const fadeUp = {
  hidden: { opacity: 0, y: 28 },
  show: (i: number) => ({
    opacity: 1,
    y: 0,
    transition: {
      delay: 0.15 + i * 0.1,
      duration: 0.9,
      ease: [0.16, 1, 0.3, 1] as const,
    },
  }),
}

export function Hero() {
  return (
    <section className="hero" id="top">
      <div className="hero-bg" aria-hidden />

      <div className="hero-inner">
        <div className="hero-top">
          <motion.div
            className="eyebrow"
            custom={0}
            variants={fadeUp}
            initial="hidden"
            animate="show"
          >
            <span className="dot" />
            第一章 · 入口
            <span className="jp">CHAPTER 00</span>
          </motion.div>

          <motion.h1
            className="display h-hero"
            custom={1}
            variants={fadeUp}
            initial="hidden"
            animate="show"
          >
            把多 Agent
            <br />
            装进可管理的后台。
          </motion.h1>

          <motion.p
            className="body-lg hero-sub"
            custom={2}
            variants={fadeUp}
            initial="hidden"
            animate="show"
          >
            基于 RuoYi 与 Spring AI 的企业级运行时。智能体、模型渠道、工具与知识库一站拼装，每一次对话都是可观测、可计量、可回放的
            Run。
          </motion.p>

          <motion.div
            className="hero-actions"
            custom={3}
            variants={fadeUp}
            initial="hidden"
            animate="show"
          >
            <a className="btn btn-fill" href="#cta">
              开始构建
            </a>
            <a className="btn btn-ghost" href="#architecture">
              阅读架构
            </a>
          </motion.div>
        </div>

        <div className="hero-spacer" aria-hidden />

        <motion.div
          className="hero-foot"
          custom={4}
          variants={fadeUp}
          initial="hidden"
          animate="show"
        >
          <div className="hero-stats">
            <div className="hero-stat">
              <strong className="num">12+</strong>
              <span>领域模块</span>
            </div>
            <div className="hero-stat">
              <strong className="num">3 层</strong>
              <span>Agent 协作深度</span>
            </div>
            <div className="hero-stat">
              <strong className="num">全链路</strong>
              <span>可观测 · 可回放</span>
            </div>
          </div>
          <div className="hero-cue">
            滚动探索三维场景
            <span className="track">
              <i />
            </span>
          </div>
        </motion.div>
      </div>
    </section>
  )
}
