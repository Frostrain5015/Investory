import { useMemo } from 'react'
import type { PriceData } from '@/types'

interface Props { data: PriceData[]; holding?: { dilutedCost?: number } | null; transactions?: { id: number; tradeDate: string; price: number; type: string }[] }

export default function CandlestickChart({ data }: Props) {
  const chart = useMemo(() => {
    if (data.length === 0) return null
    let lo = Infinity, hi = -Infinity
    for (const d of data) { if (d.low < lo) lo = d.low; if (d.high > hi) hi = d.high }
    const pad = (hi - lo) * 0.08 || 1
    const minV = lo - pad; const maxV = hi + pad
    const raw = (maxV - minV) / 5
    const mag = Math.pow(10, Math.floor(Math.log10(raw)))
    const norm = raw / mag
    const step = (norm <= 1.5 ? 1 : norm <= 3 ? 2 : 5) * mag
    const ticks: number[] = []
    for (let v = Math.ceil(minV / step) * step; v <= maxV; v += step) ticks.push(Math.round(v * 100) / 100)

    const W = 800; const H = 300; const R = 62; const B = 22
    const cw = W - R - 12; const ch = H - 20 - B
    const toX = (i: number) => R + (i / Math.max(data.length - 1, 1)) * cw
    const toY = (v: number) => 20 + ((maxV - v) / (maxV - minV)) * ch
    const barW = Math.max(1.5, Math.min(9, cw / Math.max(data.length, 1) * 0.55))
    return { W, H, R, B, data, ticks, toX, toY, barW }
  }, [data])

  if (!chart) return null
  const { W, H, R, data: d, ticks, toX, toY, barW } = chart

  return (
    <div className="w-full h-[300px]">
      <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="xMidYMid meet" className="w-full h-full">
        <style>{`
          @keyframes candleIn { from { opacity:0; transform:scaleY(0) } to { opacity:1; transform:scaleY(1) } }
          .candle { animation:candleIn .35s ease-out both; transform-origin:center }
        `}</style>
        {ticks.map((v: number) => (
          <g key={v}>
            <line x1={R} y1={toY(v)} x2={W - 10} y2={toY(v)} stroke="#f1f5f9" strokeDasharray="3 3" />
            <text x={R - 8} y={toY(v) + 4} textAnchor="end" className="text-[10px] fill-slate-400 select-none">{v}</text>
          </g>
        ))}
        {d.map((p, i) => {
          const isUp = p.close >= p.open
          const color = isUp ? '#ef4444' : '#10b981'
          const cx = toX(i)
          return (
            <g key={i} className="candle">
              <line x1={cx} y1={toY(p.high)} x2={cx} y2={toY(p.low)} stroke={color} strokeWidth={1} />
              <rect x={cx - barW / 2} y={toY(Math.max(p.open, p.close))}
                width={barW} height={Math.max(1, Math.abs(toY(p.open) - toY(p.close)))}
                fill={isUp ? color : 'transparent'} stroke={color} strokeWidth={1} />
            </g>
          )
        })}
        {d.map((p, i) => {
          if (i % Math.ceil(d.length / 8) !== 0) return null
          return <text key={i} x={toX(i)} y={H - 4} textAnchor="middle" className="text-[9px] fill-slate-400 select-none">{p.date.substring(5)}</text>
        })}
      </svg>
    </div>
  )
}
