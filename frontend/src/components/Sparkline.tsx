import { AnimatePresence, motion, useReducedMotion } from 'framer-motion'
import DataReveal from '@/components/DataReveal'
import { DATA_MOTION } from '@/lib/dataMotion'

interface SparklineProps { data: number[]; width?: number; height?: number }

export default function Sparkline({ data, width = 60, height = 24 }: SparklineProps) {
  const reduced = useReducedMotion()
  const available = data.length >= 2 && data.every(Number.isFinite)
  const min = available ? Math.min(...data) : 0
  const max = available ? Math.max(...data) : 0
  const range = max - min || 1
  const padX = 1
  const padY = 2
  const w = width - padX * 2
  const h = height - padY * 2
  const points = data.map((v, i) => {
    const x = padX + (i / (data.length - 1)) * w
    const y = padY + h - ((v - min) / range) * h
    return `${x.toFixed(1)},${y.toFixed(1)}`
  }).join(' ')

  const up = data[data.length - 1] >= data[0]
  const stroke = up ? '#ef4444' : '#10b981'

  return (
    <DataReveal contentKey={available ? 'available' : 'missing'} className="shrink-0" >
      {available ? <svg width={width} height={height} role="img" aria-label="价格走势" className="block">
        <AnimatePresence>
          {/* Equal-length histories morph; added/removed dates dissolve without resampling prices. */}
          <motion.polyline key={data.length} initial={{ points, stroke, opacity: 0 }}
            animate={{ points, stroke, opacity: 1 }} exit={{ opacity: 0 }}
            transition={reduced ? { duration: 0 } : DATA_MOTION}
            fill="none" strokeWidth={1.3} strokeLinecap="round" strokeLinejoin="round" />
        </AnimatePresence>
      </svg> : <span style={{ width, height }} className="block bg-slate-50 dark:bg-slate-800/40 rounded" aria-hidden="true" />}
    </DataReveal>
  )
}
