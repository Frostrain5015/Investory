import { useMemo } from 'react'
import { useT } from '@/i18n/I18nContext'
import type { AllocationItem } from '@/types'

interface Props { data: AllocationItem[]; colors: string[] }

export default function CloudChart({ data, colors }: Props) {
  const { t } = useT()
  const total = useMemo(() => data.reduce((s, d) => s + d.value, 0), [data])

  const bubbles = useMemo(() => {
    if (data.length === 0) return []
    const sorted = [...data].sort((a, b) => b.value - a.value)
    return sorted.map((item, i) => {
      const pct = total > 0 ? item.value / total : 0
      const dia = Math.max(48, Math.min(170, 44 + pct * 160))
      return { name: item.name, dia, color: colors[i % colors.length], pct }
    })
  }, [data, colors, total])

  if (bubbles.length === 0) {
    return <div className="h-[280px] flex items-center justify-center text-slate-400 text-sm">{t.dashboard.noHoldings}</div>
  }

  return (
    <div className="h-[280px] flex flex-wrap items-center justify-center gap-3 content-center px-2">
      {bubbles.map((b, i) => (
        <div
          key={i}
          className="rounded-full shrink-0 flex flex-col items-center justify-center text-white shadow-md hover:scale-110 transition-transform duration-200 cursor-default select-none"
          style={{ width: b.dia, height: b.dia, backgroundColor: b.color }}
          title={`${b.name} ${(b.pct * 100).toFixed(1)}%`}
        >
          <span className="font-bold leading-tight text-center px-2"
            style={{ fontSize: Math.max(10, b.dia * 0.16) }}>
            {b.name.length > 4 ? b.name.substring(0, 4) : b.name}
          </span>
          {b.dia > 60 && (
            <span className="opacity-80 font-medium mt-0.5"
              style={{ fontSize: Math.max(8, b.dia * 0.11) }}>
              {(b.pct * 100).toFixed(0)}%
            </span>
          )}
        </div>
      ))}
    </div>
  )
}
