interface SparklineProps { data: number[]; width?: number; height?: number }

export default function Sparkline({ data, width = 60, height = 24 }: SparklineProps) {
  if (data.length < 2) return <div style={{ width, height }} className="bg-slate-50 rounded" />
  const min = Math.min(...data)
  const max = Math.max(...data)
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
    <svg width={width} height={height} className="shrink-0">
      <polyline points={points} fill="none" stroke={stroke} strokeWidth={1.3} strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}
