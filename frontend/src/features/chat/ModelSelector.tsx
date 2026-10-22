import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Link } from 'react-router-dom'
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion'
import { Check, ChevronDown, Loader2, Settings2 } from 'lucide-react'
import { useModels } from '@/hooks/use-models'
import InfoTip from '@/components/InfoTip'

export default function ModelSelector() {
  const { settings, select, loading, busy, error: loadError } = useModels()
  const current = settings.models.find(model => model.id === settings.activeModelId)
  const [open, setOpen] = useState(false)
  const [error, setError] = useState('')
  const [position, setPosition] = useState({ left: 16, bottom: 80 })
  const trigger = useRef<HTMLButtonElement>(null)
  const menu = useRef<HTMLDivElement>(null)
  const id = useId()
  const reduceMotion = useReducedMotion()
  useLayoutEffect(() => {
    if (!open) return
    const rect = trigger.current?.getBoundingClientRect()
    if (rect) setPosition({ left: Math.max(12, Math.min(rect.right - 288, window.innerWidth - 300)), bottom: window.innerHeight - rect.top + 8 })
  }, [open])
  useEffect(() => {
    if (!open) return
    function outside(event: PointerEvent) {
      if (event.target instanceof Node && !menu.current?.contains(event.target) && !trigger.current?.contains(event.target)) setOpen(false)
    }
    function close() { setOpen(false) }
    document.addEventListener('pointerdown', outside)
    window.addEventListener('resize', close)
    const frame = requestAnimationFrame(() => {
      const buttons = menu.current?.querySelectorAll<HTMLButtonElement>('button[role="menuitemradio"]:not(:disabled)')
      const selected = menu.current?.querySelector<HTMLButtonElement>('button[aria-checked="true"]:not(:disabled)')
      ;(selected ?? buttons?.[0])?.focus()
    })
    return () => { document.removeEventListener('pointerdown', outside); window.removeEventListener('resize', close); cancelAnimationFrame(frame) }
  }, [open])
  async function choose(modelId: number) {
    if (busy) return
    setError('')
    try { await select(modelId); setOpen(false); trigger.current?.focus() }
    catch { setError('切换失败，请重试') }
  }
  return <>
    <motion.button ref={trigger} type="button" layout={!reduceMotion} aria-label={`切换模型，当前${current?.model ?? '未配置'}`}
      aria-haspopup="menu" aria-expanded={open} aria-controls={open ? id : undefined}
      onClick={() => setOpen(value => !value)} onKeyDown={event => { if (event.key === 'ArrowDown' || event.key === 'ArrowUp') { event.preventDefault(); setOpen(true) } }}
      className="inline-flex items-center gap-1.5 min-h-11 min-w-0 max-w-[min(50vw,220px)] px-2.5 rounded-xl text-slate-700 dark:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-slate-500 cursor-pointer"
      transition={reduceMotion ? { duration: 0 } : { type: 'spring', stiffness: 440, damping: 34 }}>
      {loading || busy ? <Loader2 className="w-3.5 h-3.5 animate-spin motion-reduce:animate-none shrink-0" /> : null}
      <AnimatePresence mode="popLayout" initial={false}>
        <motion.span key={current?.id ?? 'empty'} initial={{ opacity: 0, y: reduceMotion ? 0 : 5 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: reduceMotion ? 0 : -5 }} transition={{ duration: reduceMotion ? 0 : 0.15 }} className="truncate text-sm font-medium">{loading ? '读取模型' : current?.model ?? '配置模型'}</motion.span>
      </AnimatePresence>
      <motion.span animate={{ rotate: open ? 180 : 0 }} transition={{ duration: reduceMotion ? 0 : 0.18 }} className="shrink-0"><ChevronDown className="w-3.5 h-3.5 text-slate-400" /></motion.span>
    </motion.button>
    {createPortal(<AnimatePresence>{open && <motion.div ref={menu} id={id} role="menu" aria-label="切换模型"
      initial={{ opacity: 0, y: reduceMotion ? 0 : 7, scale: reduceMotion ? 1 : 0.97 }} animate={{ opacity: 1, y: 0, scale: 1 }} exit={{ opacity: 0, y: reduceMotion ? 0 : 5, scale: reduceMotion ? 1 : 0.98 }}
      transition={reduceMotion ? { duration: 0 } : { type: 'spring', stiffness: 480, damping: 36, mass: 0.65 }}
      style={{ left: position.left, bottom: position.bottom, transformOrigin: 'bottom right', maxHeight: 'min(440px, 65vh)' }}
      className="fixed z-[90] w-72 max-w-[calc(100vw-24px)] overflow-y-auto rounded-2xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900 p-1.5 shadow-xl"
      onKeyDown={event => {
        if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); setOpen(false); trigger.current?.focus(); return }
        if (event.key === 'Tab') { setOpen(false); return }
        if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
        event.preventDefault()
        const buttons = Array.from(menu.current?.querySelectorAll<HTMLButtonElement>('[role="menuitemradio"]:not(:disabled)') ?? [])
        const index = buttons.findIndex(button => button === document.activeElement)
        const next = event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1 : (index + (event.key === 'ArrowDown' ? 1 : -1) + buttons.length) % buttons.length
        buttons[next]?.focus()
      }}>
      <div className="flex items-center px-2.5 pb-1 text-xs text-slate-500 dark:text-slate-400"><span>切换模型</span><InfoTip label="模型切换">切换立即作用于下一条消息。正在生成的回复继续使用发送时的模型和上下文配置。</InfoTip><span className="ml-auto tabular-nums">{settings.models.length} / 3</span></div>
      {settings.models.map(model => <button key={model.id} type="button" role="menuitemradio" aria-checked={model.id === settings.activeModelId} disabled={busy || !model.configured}
        onClick={() => choose(model.id)} className="relative w-full min-h-14 flex items-center gap-3 rounded-xl px-3 py-2 text-left hover:bg-slate-50 dark:hover:bg-slate-800 disabled:opacity-45 focus-visible:outline-2 focus-visible:outline-slate-400 cursor-pointer">
        <span className="flex-1 min-w-0"><span className="block truncate text-sm font-medium text-slate-800 dark:text-slate-200">{model.model}</span><span className="block mt-0.5 text-[11px] text-slate-500 dark:text-slate-400">{model.configured ? `${model.provider === 'anthropic' ? 'Anthropic' : 'OpenAI 兼容'}，${(model.contextWindow ?? 0).toLocaleString()} tokens` : '需在设置中完善配置'}</span></span>
        {model.id === settings.activeModelId && <motion.span layoutId={`${id}-selected`} transition={reduceMotion ? { duration: 0 } : { type: 'spring', stiffness: 500, damping: 34 }} className="flex items-center justify-center w-6 h-6 rounded-full bg-slate-100 dark:bg-slate-700"><Check className="w-3.5 h-3.5 text-slate-700 dark:text-slate-200" /></motion.span>}
      </button>)}
      {(error || loadError) && <p className="px-3 py-2 text-xs text-red-600 dark:text-red-400" role="alert">{error || loadError}</p>}
      <Link to="/settings" role="menuitem" onClick={() => setOpen(false)} className="mt-1 flex min-h-11 items-center gap-2 border-t border-slate-100 dark:border-slate-800 px-3 text-xs text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-800 rounded-b-xl"><Settings2 className="w-3.5 h-3.5" />管理模型</Link>
    </motion.div>}</AnimatePresence>, document.body)}
  </>
}
