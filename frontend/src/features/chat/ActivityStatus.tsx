import { AnimatePresence, motion, useReducedMotion } from 'framer-motion'

/** A transient, noninteractive description of the current operation. */
export default function ActivityStatus({ message }: { message: string }) {
  const reducedMotion = useReducedMotion()
  return (
    <div role="status" aria-live="polite" aria-atomic="true" className="min-h-6 py-1 text-[13px] leading-6 text-slate-600 dark:text-slate-400">
      <AnimatePresence initial={false} mode="wait">
        <motion.span key={message} className="chat-activity-text block"
          initial={reducedMotion ? false : { opacity: 0, y: 2 }}
          animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }}
          transition={{ duration: reducedMotion ? 0 : 0.16, ease: 'easeOut' }}>
          {message}
        </motion.span>
      </AnimatePresence>
    </div>
  )
}
