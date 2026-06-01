import { useState, useCallback, createContext, useContext, type ReactNode } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import { Sparkles, X, ChevronRight } from 'lucide-react'

interface Bubble {
  id: number
  title: string
  message: string
  actionLabel?: string
  actionHref?: string
  onAction?: () => void
}

interface Ctx {
  show: (b: Omit<Bubble, 'id'>) => void
  dismiss: (id: number) => void
}

const ctx = createContext<Ctx>({ show: () => {}, dismiss: () => {} })
export const useNotificationBubble = () => useContext(ctx)

export function NotificationBubbleProvider({ children }: { children: ReactNode }) {
  const [bubbles, setBubbles] = useState<Bubble[]>([])

  const show = useCallback((b: Omit<Bubble, 'id'>) => {
    const id = Date.now()
    setBubbles(prev => [...prev.slice(-1), { ...b, id }])
    // Auto-dismiss after 12 seconds
    setTimeout(() => setBubbles(prev => prev.filter(p => p.id !== id)), 12000)
  }, [])

  const dismiss = useCallback((id: number) => {
    setBubbles(prev => prev.filter(p => p.id !== id))
  }, [])

  return (
    <ctx.Provider value={{ show, dismiss }}>
      {children}
      <AnimatePresence>
        {bubbles.map(b => (
          <motion.div key={b.id}
            initial={{ opacity: 0, y: 20, scale: 0.95 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, x: 100, scale: 0.95 }}
            transition={{ type: 'spring', stiffness: 400, damping: 25 }}
            className="fixed bottom-20 right-6 z-50 max-w-[320px] bg-white rounded-2xl shadow-xl shadow-purple-500/10 border border-purple-100 p-4 space-y-3 pointer-events-auto">
            {/* Header */}
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <span className="w-5 h-5 rounded-md flex items-center justify-center"
                  style={{ background: 'linear-gradient(135deg, #863bff, #47bfff)' }}>
                  <Sparkles className="w-3 h-3 text-white" />
                </span>
                <span className="text-xs font-semibold text-slate-700">{b.title}</span>
              </div>
              <button onClick={() => dismiss(b.id)} className="text-slate-300 hover:text-slate-500 transition-colors">
                <X className="w-3.5 h-3.5" />
              </button>
            </div>
            {/* Body */}
            <p className="text-xs text-slate-500 leading-relaxed">{b.message}</p>
            {/* Action */}
            {b.actionLabel && (
              <a href={b.actionHref || '#'} onClick={b.onAction}
                className="flex items-center justify-between w-full mt-1 px-3 py-2 rounded-xl text-xs font-medium text-white transition-colors hover:opacity-90"
                style={{ background: 'linear-gradient(135deg, #863bff, #47bfff)' }}>
                {b.actionLabel}
                <ChevronRight className="w-3.5 h-3.5" />
              </a>
            )}
          </motion.div>
        ))}
      </AnimatePresence>
    </ctx.Provider>
  )
}
