import { useEffect, useState } from 'react'

const links = [
  { href: '#pathways', label: '能力', alt: '能力' },
  { href: '#architecture', label: '架构', alt: '架构' },
  { href: '#pipeline', label: '流程', alt: '流程' },
  { href: '#modules', label: '模块', alt: '模块' },
  { href: '#stack', label: '技术栈', alt: '技术栈' },
]

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

export function Navbar() {
  const [stuck, setStuck] = useState(false)
  const [open, setOpen] = useState(false)

  useEffect(() => {
    const onScroll = () => setStuck(window.scrollY > 20)
    onScroll()
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => window.removeEventListener('scroll', onScroll)
  }, [])

  useEffect(() => {
    document.body.style.overflow = open ? 'hidden' : ''
    return () => {
      document.body.style.overflow = ''
    }
  }, [open])

  return (
    <>
      <header className={`nav${stuck ? ' stuck' : ''}`}>
        <a href="#top" className="brand" aria-label="Agent Java 首页">
          <BrandMark />
          <span className="brand-tx">
            <b>AGENT JAVA</b>
            <i>多智能体运行时</i>
          </span>
        </a>

        <nav className="nav-links" aria-label="主导航">
          {links.map((l) => (
            <a key={l.href} href={l.href} className="nav-link">
              <span>{l.label}</span>
              <span className="alt">{l.alt}</span>
            </a>
          ))}
        </nav>

        <a className="nav-cta" href="#cta">
          获取源码
        </a>

        <button
          className="nav-burger"
          aria-label={open ? '关闭菜单' : '打开菜单'}
          aria-expanded={open}
          onClick={() => setOpen((v) => !v)}
        >
          <i />
          <i />
        </button>
      </header>

      <div className={`nav-sheet${open ? ' open' : ''}`}>
        {links.map((l) => (
          <a key={l.href} href={l.href} onClick={() => setOpen(false)}>
            {l.label}
          </a>
        ))}
        <a href="#cta" onClick={() => setOpen(false)}>
          获取源码
        </a>
      </div>
    </>
  )
}
