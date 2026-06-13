import { ResponsiveContainer, RadarChart, Radar, PolarGrid, PolarAngleAxis, Tooltip } from 'recharts'
import type { FactorDetail } from '@/types'

interface Props {
  factors: FactorDetail[]
  size?: number
}

const GROUP_LABELS: Record<string, string> = {
  value: '价值', growth: '成长', momentum: '动量', quality: '质量',
  technical: '技术', event: '事件', social: '情绪', other: '其他',
}

export default function FactorRadarChart({ factors, size = 200 }: Props) {
  const grouped = new Map<string, { buy: number; sell: number }>()
  for (const f of factors) {
    const g = f.group || 'other'
    if (!grouped.has(g)) grouped.set(g, { buy: 0, sell: 0 })
    const cur = grouped.get(g)!
    cur.buy += f.buyScore
    cur.sell += f.sellScore
  }

  const data = Array.from(grouped.entries()).map(([group, scores]) => ({
    group: GROUP_LABELS[group] || group,
    buyScore: Math.min(scores.buy, 10),
    sellScore: Math.min(scores.sell, 10),
  }))

  if (data.length === 0) return null

  return (
    <ResponsiveContainer width={size} height={size}>
      <RadarChart data={data}>
        <PolarGrid stroke="#e2e8f0" />
        <PolarAngleAxis dataKey="group" tick={{ fontSize: 11, fill: '#64748b' }} />
        <Tooltip formatter={(v) => (typeof v === 'number' ? v.toFixed(1) : String(v ?? ''))} />
        <Radar name="买入分" dataKey="buyScore" stroke="#22c55e" fill="#22c55e" fillOpacity={0.2} />
        <Radar name="卖出分" dataKey="sellScore" stroke="#ef4444" fill="#ef4444" fillOpacity={0.2} />
      </RadarChart>
    </ResponsiveContainer>
  )
}
