import { useEffect, useState } from 'react'

type Props = {
  ready: boolean
}

export function Preloader({ ready }: Props) {
  const [progress, setProgress] = useState(0)
  const [hiding, setHiding] = useState(false)
  const [gone, setGone] = useState(false)

  useEffect(() => {
    let raf = 0
    let p = 0
    const tick = () => {
      // 伪进度，ready 后冲到 100
      const target = ready ? 100 : Math.min(p + (90 - p) * 0.04, 90)
      p += (target - p) * 0.12
      if (ready && p > 99.2) p = 100
      setProgress(p)
      if (!(ready && p >= 100)) raf = requestAnimationFrame(tick)
      else {
        setHiding(true)
        window.setTimeout(() => setGone(true), 850)
      }
    }
    raf = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(raf)
  }, [ready])

  if (gone) return null

  return (
    <div id="pre" className={hiding ? 'done' : ''} aria-busy={!ready}>
      <div className="pre-in">
        <div className="pre-mark" aria-hidden>
          <svg viewBox="0 0 44 44" fill="none">
            <rect width="44" height="44" fill="#05070a" />
            <circle cx="22" cy="25" r="9" fill="#e0231c" />
            <rect x="7" y="11" width="30" height="2.8" fill="#dfe7e0" />
            <rect x="11" y="18" width="22" height="2.2" fill="#dfe7e0" />
          </svg>
        </div>
        <div className="pre-jp">运行时初始化</div>
        <div className="pre-title">AGENT JAVA</div>
        <div className="pre-bar">
          <i style={{ right: `${100 - progress}%` }} />
        </div>
        <div className="pre-meta">
          <span>LOADING SCENE</span>
          <b>{Math.round(progress)}%</b>
        </div>
      </div>
    </div>
  )
}
