import { useState, useCallback, createContext, useContext, useRef, type ReactNode } from 'react'
import { motion, AnimatePresence } from 'framer-motion'

interface PromptOptions {
  message: string
  defaultValue?: string
  placeholder?: string
  confirmText?: string
  cancelText?: string
}
interface PromptItem extends PromptOptions { id: number; resolve: (value: string | null) => void }

const PromptCtx = createContext<(opts: PromptOptions) => Promise<string | null>>(() => Promise.resolve(null))

/** Unified text-input modal — the input-box counterpart to useConfirm(), so we
 *  never fall back to the browser-native prompt(). Resolves to the trimmed input
 *  on 确认, or null on 取消 / backdrop / Esc. */
export function usePrompt() {
  return useContext(PromptCtx)
}

export function PromptProvider({ children }: { children: ReactNode }) {
  const [item, setItem] = useState<PromptItem | null>(null)
  const [value, setValue] = useState('')
  const idRef = useRef(0)

  const prompt = useCallback((opts: PromptOptions): Promise<string | null> => {
    return new Promise(resolve => {
      idRef.current += 1
      setValue(opts.defaultValue ?? '')
      setItem({ ...opts, id: idRef.current, resolve })
    })
  }, [])

  const close = useCallback((result: string | null) => {
    setItem(prev => { if (prev) prev.resolve(result); return null })
  }, [])

  return (
    <PromptCtx.Provider value={prompt}>
      {children}
      <AnimatePresence>
        {item && (
          <motion.div
            initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
            className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 backdrop-blur-sm"
            onClick={() => close(null)}>
            <motion.div
              initial={{ opacity: 0, scale: 0.95, y: 8 }}
              animate={{ opacity: 1, scale: 1, y: 0 }}
              exit={{ opacity: 0, scale: 0.95, y: 8 }}
              onClick={e => e.stopPropagation()}
              className="bg-white dark:bg-slate-800 rounded-2xl shadow-2xl p-6 max-w-sm w-[90vw]">
              <p className="text-sm text-slate-800 dark:text-slate-200 leading-relaxed mb-3">{item.message}</p>
              <input
                autoFocus
                value={value}
                placeholder={item.placeholder}
                onChange={e => setValue(e.target.value)}
                onKeyDown={e => {
                  if (e.key === 'Enter' && value.trim()) close(value.trim())
                  else if (e.key === 'Escape') close(null)
                }}
                className="w-full h-9 rounded-xl border border-slate-200 dark:border-slate-600 bg-transparent px-3 text-sm text-slate-800 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-purple-300" />
              <div className="flex items-center gap-2 mt-5">
                <button onClick={() => close(null)}
                  className="flex-1 h-9 rounded-xl border border-slate-200 dark:border-slate-600 text-xs font-medium text-slate-500 dark:text-slate-400 hover:bg-slate-50 dark:hover:bg-slate-700 transition-colors">
                  {item.cancelText ?? '取消'}
                </button>
                <button onClick={() => value.trim() && close(value.trim())} disabled={!value.trim()}
                  className="flex-1 h-9 rounded-xl bg-purple-600 text-white text-xs font-medium hover:bg-purple-700 transition-colors disabled:opacity-50">
                  {item.confirmText ?? '确认'}
                </button>
              </div>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>
    </PromptCtx.Provider>
  )
}
