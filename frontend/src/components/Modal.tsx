import { type ReactNode, useEffect, useRef } from 'react'
import { motion, AnimatePresence, type Variants } from 'framer-motion'
import { X } from 'lucide-react'

interface Props {
  open: boolean
  onClose: () => void
  title: string
  children: ReactNode
}

const panel: Variants = {
  hidden: { opacity: 0, scale: 0.96, y: 20 },
  visible: { opacity: 1, scale: 1, y: 0, transition: { type: 'spring', stiffness: 400, damping: 32 } },
  exit:    { opacity: 0, scale: 0.96, y: 20, transition: { duration: 0.15 } },
}

const sheet: Variants = {
  hidden: { y: '100%' },
  visible: { y: 0, transition: { type: 'spring', stiffness: 400, damping: 36 } },
  exit:    { y: '100%', transition: { duration: 0.2 } },
}

export default function Modal({ open, onClose, title, children }: Props) {
  const ref = useRef<HTMLDivElement>(null)
  const isMobile = typeof window !== 'undefined' && window.innerWidth < 640

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    document.addEventListener('keydown', onKey)
    document.body.style.overflow = 'hidden'
    return () => {
      document.removeEventListener('keydown', onKey)
      document.body.style.overflow = ''
    }
  }, [open, onClose])

  return (
    <AnimatePresence>
      {open && (
        <>
          {/* Backdrop — independent fade only, exactly like Guanlan expanded mode */}
          <motion.div
            key="modal-backdrop"
            initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
            transition={{ duration: 0.2, ease: 'easeOut' }}
            onClick={onClose}
            className="fixed inset-0 z-50 bg-slate-950/30 dark:bg-black/40 cursor-pointer"
            style={{ backdropFilter: 'blur(6px)', WebkitBackdropFilter: 'blur(6px)' }}
          />

          {/* Panel / Sheet — independent scale+slide, never touches the blur */}
          <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center pointer-events-none">
            <motion.div
              ref={ref}
              variants={isMobile ? sheet : panel}
              initial="hidden" animate="visible" exit="exit"
              className={[
                'pointer-events-auto w-full sm:max-w-md bg-white dark:bg-slate-900',
                'sm:rounded-2xl rounded-t-2xl shadow-2xl',
                'border border-slate-200 dark:border-slate-800',
                'max-h-[85vh] overflow-y-auto',
                'focus:outline-none',
              ].join(' ')}
              role="dialog" aria-modal="true" aria-label={title}
            >
              {/* Header */}
              <div className="sticky top-0 z-10 flex items-center justify-between px-5 py-4
                              bg-white/90 dark:bg-slate-900/90 backdrop-blur-sm
                              border-b border-slate-100 dark:border-slate-800">
                <h3 className="text-sm font-semibold text-slate-800 dark:text-slate-200 tracking-tight">
                  {title}
                </h3>
                <button
                  onClick={onClose}
                  className="flex items-center justify-center w-8 h-8 rounded-lg
                             text-slate-400 hover:text-slate-600 dark:hover:text-slate-300
                             hover:bg-slate-100 dark:hover:bg-slate-800
                             transition-colors cursor-pointer"
                  aria-label="关闭"
                >
                  <X className="w-4 h-4" />
                </button>
              </div>

              {/* Body */}
              <div className="px-5 py-4">{children}</div>
            </motion.div>
          </div>
        </>
      )}
    </AnimatePresence>
  )
}

/** Inline form row used inside modal body */
export function ModalRow({ label, desc, children }: { label: string; desc?: string; children: ReactNode }) {
  return (
    <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between
                    py-3 border-b border-slate-100 dark:border-slate-800 last:border-0 gap-2">
      <div className="min-w-0">
        <p className="text-sm font-medium text-slate-700 dark:text-slate-300">{label}</p>
        {desc && <p className="text-xs text-slate-400 dark:text-slate-500 mt-0.5">{desc}</p>}
      </div>
      <div className="shrink-0">{children}</div>
    </div>
  )
}
