import { Suspense } from 'react'
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion'
import { useLocation, useOutlet } from 'react-router-dom'
import PageLoading from '@/components/PageLoading'

/** A shared circular reveal; query changes and data refreshes stay in place. */
export default function PageTransition() {
  const { pathname } = useLocation()
  const outlet = useOutlet()
  const reducedMotion = useReducedMotion()
  return (
    <AnimatePresence mode="wait">
      <motion.div
        key={pathname}
        className="page-transition h-full"
        initial={false}
        animate={{ opacity: 1 }}
        exit={{ opacity: 0 }}
        transition={{ duration: reducedMotion ? 0 : 0.1, ease: 'easeOut' }}
      >
        <Suspense fallback={<PageLoading />}>
          <div className="page-reveal h-full">{outlet}</div>
        </Suspense>
      </motion.div>
    </AnimatePresence>
  )
}
