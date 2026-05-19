import { useMemo, useState } from 'react'
import type { AllocationItem } from '@/types'

interface Props {
  data: AllocationItem[]
  colors: string[]
}

function logoUrl(symbol: string): string {
  return `https://webquoteklinepic.eastmoney.com/GetPic.aspx?id=${symbol}&imageType=r`
}

function Bubble({ item, dia, color, pct }: {
  item: AllocationItem; dia: number; color: string; pct: number
}) {
  const [imgError, setImgError] = useState(false)
  const url = logoUrl(item.symbol)

  return (
    <div
      className="flex flex-col items-center justify-center rounded-full shrink-0 font-bold shadow-md hover:scale-110 transition-transform duration-200 cursor-default relative overflow-hidden"
      style={{
        width: dia,
        height: dia,
        backgroundColor: imgError ? color : '#f1f5f9',
        fontSize: Math.max(10, dia * 0.3),
      }}
      title={`${item.name}\n${(pct * 100).toFixed(1)}%`}
    >
      {url && !imgError ? (
        <img
          src={url}
          alt={item.name.charAt(0)}
          onError={() => setImgError(true)}
          className="w-full h-full object-cover rounded-full"
        />
      ) : (
        <>
          <span className="leading-none text-white">{item.name.charAt(0)}</span>
          {dia > 55 && (
            <span className="text-[0.55em] leading-none mt-0.5 text-white/80">
              {(pct * 100).toFixed(0)}%
            </span>
          )}
        </>
      )}
    </div>
  )
}

export default function CloudChart({ data, colors }: Props) {
  const total = useMemo(() => data.reduce((s, d) => s + d.value, 0), [data])

  const bubbles = useMemo(() => {
    if (data.length === 0) return []
    const sorted = [...data].sort((a, b) => b.value - a.value)
    return sorted.map((item, i) => {
      const pct = total > 0 ? item.value / total : 0.25
      const dia = Math.max(36, Math.min(90, 28 + pct * 200))
      return { item, dia, color: colors[i % colors.length], pct }
    })
  }, [data, colors, total])

  if (bubbles.length === 0) {
    return <div className="h-[280px] flex items-center justify-center text-slate-400 text-sm">暂无持仓</div>
  }

  return (
    <div className="h-[280px] flex flex-wrap items-center justify-center gap-2 content-center px-2">
      {bubbles.map(b => (
        <Bubble key={b.item.symbol} {...b} />
      ))}
    </div>
  )
}
