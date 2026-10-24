import type { ReactNode } from 'react'
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion'
import { DATA_MOTION } from '@/lib/dataMotion'

/** Keep the slot in place while missing/available content dissolves. */
export default function DataReveal({ children, contentKey, className = '' }: {
  children: ReactNode
  contentKey: string
  className?: string
}) {
  const reduced = useReducedMotion()
  return <span className={`inline-grid align-baseline ${className}`} data-data-reveal>
    <AnimatePresence>
      <motion.span key={contentKey} className="col-start-1 row-start-1 min-w-0"
        initial={{ opacity: reduced ? 1 : 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
        transition={reduced ? { duration: 0 } : DATA_MOTION}>
        {children}
      </motion.span>
    </AnimatePresence>
  </span>
}
