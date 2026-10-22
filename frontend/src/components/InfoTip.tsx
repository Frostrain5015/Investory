import { useEffect, useId, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Info } from 'lucide-react'

/** Field help: hover, keyboard focus, or tap. Portalled outside scrolling dialogs. */
export default function InfoTip({ label, children }: { label: string; children: string }) {
  const id = useId()
  const trigger = useRef<HTMLButtonElement>(null)
  const dismissTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const [open, setOpen] = useState(false)
  const [position, setPosition] = useState({ top: 0, left: 16, above: false })
  function show() { clearTimeout(dismissTimer.current); setOpen(true) }
  function hide() { dismissTimer.current = setTimeout(() => setOpen(false), 150) }
  useEffect(() => () => clearTimeout(dismissTimer.current), [])
  useEffect(() => {
    if (!open) return
    function place() {
      const rect = trigger.current?.getBoundingClientRect()
      if (!rect) return
      const width = Math.min(288, window.innerWidth - 32)
      const above = window.innerHeight - rect.bottom < 200
      setPosition({ left: Math.max(16, Math.min(rect.left, window.innerWidth - width - 16)), top: above ? rect.top - 8 : rect.bottom + 8, above })
    }
    place()
    window.addEventListener('resize', place)
    window.addEventListener('scroll', place, true)
    return () => { window.removeEventListener('resize', place); window.removeEventListener('scroll', place, true) }
  }, [open])
  return <span className="inline-flex align-middle" onMouseEnter={show} onMouseLeave={hide}>
    <button ref={trigger} type="button" aria-label={`${label}说明`} aria-describedby={open ? id : undefined} aria-expanded={open}
      onFocus={show} onBlur={hide} onClick={() => setOpen(true)}
      onKeyDown={event => { if (event.key === 'Escape' && open) { event.stopPropagation(); setOpen(false) } }}
      className="inline-flex w-7 h-7 items-center justify-center rounded-full text-slate-500 dark:text-slate-400 hover:text-slate-800 dark:hover:text-slate-200 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-slate-500 cursor-help">
      <Info aria-hidden="true" className="w-3.5 h-3.5" />
    </button>
    {open && createPortal(<span id={id} role="tooltip" onMouseEnter={show} onMouseLeave={hide}
      className="fixed z-[100] w-72 max-w-[calc(100vw-32px)] rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 px-3 py-2.5 text-xs font-normal leading-relaxed text-slate-700 dark:text-slate-200 shadow-lg"
      style={{ left: position.left, top: position.top, transform: position.above ? 'translateY(-100%)' : undefined }}>
      {children}
    </span>, document.body)}
  </span>
}
