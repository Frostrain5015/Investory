import { motion } from 'framer-motion'
import type { RegimeStatus } from '@/types'

interface Props {
  regime: RegimeStatus
  className?: string
}

const SEGMENTS = [
  { min: 0, max: 2, color: '#ef4444', label: 'BEAR' },
  { min: 2, max: 4, color: '#f97316', label: 'CRISIS' },
  { min: 4, max: 6, color: '#eab308', label: 'CAUTION' },
  { min: 6, max: 7.5, color: '#64748b', label: 'NORMAL' },
  { min: 7.5, max: 9, color: '#22c55e', label: 'BULL' },
  { min: 9, max: 10, color: '#16a34a', label: 'EXTREME' },
]

export default function MarketThermometer({ regime, className }: Props) {
  const score = Math.min(Math.max(regime.score ?? 5, 0), 10)
  const pct = (score / 10) * 100

  const currentSegment = SEGMENTS.find(s => score >= s.min && score < s.max) || SEGMENTS[3]

  return (
    <div className={`flex items-center gap-2 ${className || ''}`}>
      {/* Thermometer bar */}
      <div className="relative w-20 h-2 rounded-full overflow-hidden bg-slate-200">
        {/* Gradient segments */}
        <div className="absolute inset-0 flex">
          {SEGMENTS.map(s => (
            <div
              key={s.label}
              className="h-full"
              style={{
                width: `${((s.max - s.min) / 10) * 100}%`,
                backgroundColor: s.color,
              }}
            />
          ))}
        </div>
        {/* Score marker */}
        <motion.div
          className="absolute top-1/2 -translate-y-1/2 w-2.5 h-2.5 rounded-full border-2 border-white shadow-sm"
          style={{ backgroundColor: currentSegment.color, left: `${pct}%`, marginLeft: -5 }}
          animate={{ left: `${pct}%` }}
          transition={{ type: 'spring', stiffness: 200, damping: 20 }}
        />
      </div>
      {/* Label + score */}
      <div className="flex flex-col leading-tight">
        <span className="text-[11px] font-bold text-slate-700">{regime.signal || currentSegment.label}</span>
        <span className="text-[10px] text-slate-400">{score.toFixed(1)} / 10</span>
      </div>
    </div>
  )
}
