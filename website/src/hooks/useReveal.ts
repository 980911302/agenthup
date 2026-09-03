import { useEffect } from 'react'

/** 滚动进入视口时给 [data-rv] 加上 .rv-in */
export function useReveal(enabled = true) {
  useEffect(() => {
    if (!enabled) return

    const nodes = Array.from(document.querySelectorAll<HTMLElement>('[data-rv]'))
    if (!nodes.length) return

    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      nodes.forEach((n) => n.classList.add('rv-in'))
      return
    }

    const io = new IntersectionObserver(
      (entries) => {
        entries.forEach((e) => {
          if (e.isIntersecting) {
            e.target.classList.add('rv-in')
            io.unobserve(e.target)
          }
        })
      },
      { threshold: 0.12, rootMargin: '0px 0px -8% 0px' },
    )

    nodes.forEach((n) => io.observe(n))
    return () => io.disconnect()
  }, [enabled])
}
