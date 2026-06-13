import { useState, useCallback, createContext, useContext, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { motion, AnimatePresence } from 'framer-motion'
import { Sparkles, Sun, X, ChevronRight } from 'lucide-react'

type Variant = 'default' | 'morning'

interface Bubble {
  id: number
  title: string
  message: string
  actionLabel?: string
  actionHref?: string
  variant?: Variant
  onAction?: () => void
}

interface Ctx {
  show: (b: Omit<Bubble, 'id'>) => void
  dismiss: (id: number) => void
}

const ctx = createContext<Ctx>({ show: () => {}, dismiss: () => {} })
export const useNotificationBubble = () => useContext(ctx)

const STYLES: Record<Variant, {
  shadow: string; border: string; iconBg: string; iconNode: ReactNode; actionBg: string;
}> = {
  default: {
    shadow: 'shadow-purple-500/10',
    border: 'border-purple-100',
    iconBg: 'linear-gradient(135deg, #863bff, #47bfff)',
    iconNode: <Sparkles className="w-3 h-3 text-white" />,
    actionBg: 'linear-gradient(135deg, #863bff, #47bfff)',
  },
  morning: {
    shadow: 'shadow-amber-500/10',
    border: 'border-amber-100',
    iconBg: 'linear-gradient(135deg, #f59e0b, #fb923c)',
    iconNode: <Sun className="w-3 h-3 text-white" />,
    actionBg: 'linear-gradient(135deg, #f59e0b, #fb923c)',
  },
}

export function NotificationBubbleProvider({ children }: { children: ReactNode }) {
  const [bubbles, setBubbles] = useState<Bubble[]>([])

  const show = useCallback((b: Omit<Bubble, 'id'>) => {
    const id = Date.now() + Math.random()
    // Replace any existing bubble of the same variant; keep different variants stacked.
    setBubbles(prev => {
      const variant = b.variant || 'default'
      const filtered = prev.filter(p => (p.variant || 'default') !== variant)
      return [...filtered, { ...b, id }].slice(-3)  // hard cap: 3 bubbles
    })
    // Auto-dismiss after 15 seconds
    setTimeout(() => setBubbles(prev => prev.filter(p => p.id !== id)), 15000)
  }, [])

  const dismiss = useCallback((id: number) => {
    setBubbles(prev => prev.filter(p => p.id !== id))
  }, [])

  return (
    <ctx.Provider value={{ show, dismiss }}>
      {children}
      <div className="fixed bottom-20 right-6 z-50 flex flex-col gap-3 items-end pointer-events-none">
        <AnimatePresence>
          {bubbles.map(b => {
            const style = STYLES[b.variant || 'default']
            return (
              <motion.div key={b.id}
                initial={{ opacity: 0, y: 20, scale: 0.95 }}
                animate={{ opacity: 1, y: 0, scale: 1 }}
                exit={{ opacity: 0, x: 100, scale: 0.95 }}
                transition={{ type: 'spring', stiffness: 400, damping: 25 }}
                className={`max-w-[320px] bg-white rounded-2xl shadow-xl ${style.shadow} border ${style.border} p-4 space-y-3 pointer-events-auto`}>
                {/* Header */}
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <span className="w-5 h-5 rounded-md flex items-center justify-center"
                      style={{ background: style.iconBg }}>
                      {style.iconNode}
                    </span>
                    <span className="text-xs font-semibold text-slate-700">{b.title}</span>
                  </div>
                  <button onClick={() => dismiss(b.id)} className="text-slate-300 hover:text-slate-500 transition-colors">
                    <X className="w-3.5 h-3.5" />
                  </button>
                </div>
                {/* Body */}
                <p className="text-xs text-slate-500 leading-relaxed whitespace-pre-wrap">{b.message}</p>
                {/* Action */}
                {b.actionLabel && (
                  <Link to={b.actionHref || '#'} onClick={() => { b.onAction?.(); dismiss(b.id) }}
                    className="flex items-center justify-between w-full mt-1 px-3 py-2 rounded-xl text-xs font-medium text-white transition-colors hover:opacity-90"
                    style={{ background: style.actionBg }}>
                    {b.actionLabel}
                    <ChevronRight className="w-3.5 h-3.5" />
                  </Link>
                )}
              </motion.div>
            )
          })}
        </AnimatePresence>
      </div>
    </ctx.Provider>
  )
}
