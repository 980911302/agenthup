import { useCallback, useEffect, useState } from 'react'
import './App.css'
import { useReveal } from './hooks/useReveal'
import { WebGLCanvas } from './components/WebGLCanvas'
import { Preloader } from './components/Preloader'
import { Navbar } from './components/Navbar'
import { Hero } from './components/Hero'
import { Chapters } from './components/Chapters'
import { Architecture } from './components/Architecture'
import { HowItWorks } from './components/HowItWorks'
import { Modules } from './components/Modules'
import { TechStack } from './components/TechStack'
import { CTA } from './components/CTA'
import { Footer } from './components/Footer'

export default function App() {
  const [glReady, setGlReady] = useState(false)
  const onReady = useCallback(() => setGlReady(true), [])

  useReveal(glReady)

  useEffect(() => {
    // 兜底：3s 仍未 ready 也放行（WebGL 失败 / 降级）
    const t = window.setTimeout(() => setGlReady(true), 3000)
    return () => window.clearTimeout(t)
  }, [])

  return (
    <>
      <WebGLCanvas onReady={onReady} />
      <div id="grain" aria-hidden />
      <div id="vignette" aria-hidden />
      <Preloader ready={glReady} />

      <div className="page">
        <Navbar />
        <main>
          <Hero />
          <Chapters />
          <Architecture />
          <HowItWorks />
          <Modules />
          <TechStack />
          <CTA />
        </main>
        <Footer />
      </div>
    </>
  )
}
