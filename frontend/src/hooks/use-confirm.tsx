import { useState, useCallback, createContext, useContext, useRef, type ReactNode } from 'react'
import { motion, AnimatePresence } from 'framer-motion'

interface ConfirmItem { id: number; msg: string; resolve: (ok: boolean) => void }

const ConfirmCtx = createContext<(msg: string) => Promise<boolean>>(() => Promise.resolve(false))

export function useConfirm() {
  return useContext(ConfirmCtx)
}

export function ConfirmProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ConfirmItem[]>([])
  const idRef = useRef(0)

  const confirm = useCallback((msg: string): Promise<boolean> => {
    return new Promise(resolve => {
      idRef.current += 1
      setItems(prev => [...prev, { id: idRef.current, msg, resolve }])
    })
  }, [])

  const handle = useCallback((id: number, ok: boolean) => {
    setItems(prev => {
      const item = prev.find(i => i.id === id)
      if (item) item.resolve(ok)
      return prev.filter(i => i.id !== id)
    })
  }, [])

  return (
    <ConfirmCtx.Provider value={confirm}>
      {children}
      <AnimatePresence>
        {items.length > 0 && (
          <motion.div
            initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
            className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 backdrop-blur-sm"
            onClick={() => handle(items[0].id, false)}>
            <motion.div
              initial={{ opacity: 0, scale: 0.95, y: 8 }}
              animate={{ opacity: 1, scale: 1, y: 0 }}
              exit={{ opacity: 0, scale: 0.95, y: 8 }}
              onClick={e => e.stopPropagation()}
              className="bg-white dark:bg-slate-800 rounded-2xl shadow-2xl p-6 max-w-sm w-[90vw]">
              <p className="text-sm text-slate-800 dark:text-slate-200 leading-relaxed">{items[0].msg}</p>
              <div className="flex items-center gap-2 mt-5">
                <button onClick={() => handle(items[0].id, false)}
                  className="flex-1 h-9 rounded-xl border border-slate-200 dark:border-slate-600 text-xs font-medium text-slate-500 dark:text-slate-400 hover:bg-slate-50 dark:hover:bg-slate-700 transition-colors">
                  取消
                </button>
                <button onClick={() => handle(items[0].id, true)}
                  className="flex-1 h-9 rounded-xl bg-red-600 text-white text-xs font-medium hover:bg-red-700 transition-colors">
                  确认
                </button>
              </div>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>
    </ConfirmCtx.Provider>
  )
}
