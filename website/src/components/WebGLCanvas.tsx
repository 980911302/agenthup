import { useEffect, useRef } from 'react'
import type { RuntimeSceneApi } from '../three/createRuntimeScene'

type Props = {
  onReady?: () => void
}

export function WebGLCanvas({ onReady }: Props) {
  const hostRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const host = hostRef.current
    if (!host) return

    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    if (reduce) {
      onReady?.()
      return
    }

    let cancelled = false
    let api: RuntimeSceneApi | null = null
    let removeListeners: (() => void) | null = null

    ;(async () => {
      try {
        const { createRuntimeScene } = await import('../three/createRuntimeScene')
        if (cancelled || !hostRef.current) {
          onReady?.()
          return
        }

        api = createRuntimeScene(hostRef.current, onReady)
        api.start()

        const onScroll = () => {
          if (!api) return
          const max = Math.max(
            1,
            document.documentElement.scrollHeight - window.innerHeight,
          )
          api.setScroll(window.scrollY / max)
        }

        const onMove = (e: PointerEvent) => {
          if (!api) return
          const nx = (e.clientX / window.innerWidth) * 2 - 1
          const ny = (e.clientY / window.innerHeight) * 2 - 1
          api.setPointer(nx, -ny)
        }

        const onResize = () => {
          api?.resize(window.innerWidth, window.innerHeight)
        }

        onScroll()
        onResize()
        window.addEventListener('scroll', onScroll, { passive: true })
        window.addEventListener('pointermove', onMove, { passive: true })
        window.addEventListener('resize', onResize)

        removeListeners = () => {
          window.removeEventListener('scroll', onScroll)
          window.removeEventListener('pointermove', onMove)
          window.removeEventListener('resize', onResize)
        }
      } catch {
        if (!cancelled) onReady?.()
      }
    })()

    return () => {
      cancelled = true
      removeListeners?.()
      api?.dispose()
      api = null
    }
  }, [onReady])

  return <div ref={hostRef} className="gl-host" aria-hidden />
}
