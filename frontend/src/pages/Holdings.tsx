import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '@/hooks/use-auth'
import { Card, CardContent } from '@/components/ui/card'

interface Snapshot {
  stockId: number; stockSymbol: string; stockName: string; market: string
  totalShares: number; avgCost: number; dilutedCost: number
  totalInvested: number; currentPrice: number; marketValue: number
  unrealizedPnl: number; unrealizedPnlPct: number
}

export default function Holdings() {
  const { portfolioId } = useAuth()
  const [snapshots, setSnapshots] = useState<Snapshot[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    fetch(`/investory/api/holdings`, { credentials: 'include' })
      .then(r => r.json()).then(d => setSnapshots(d.snapshots || []))
      .finally(() => setLoading(false))
  }, [portfolioId])

  if (loading) {
    return <div className="flex items-center justify-center h-96">
      <div className="w-8 h-8 border-2 border-slate-300 border-t-slate-900 rounded-full animate-spin" />
    </div>
  }

  return (
    <div className="p-6 space-y-6">
      <h2 className="text-xl font-bold text-slate-900 tracking-tight">持仓明细</h2>
      <Card>
        <CardContent className="p-0">
          {snapshots.length === 0 ? (
            <div className="py-12 text-center text-slate-500 text-sm">暂无持仓数据</div>
          ) : (
            <div className="overflow-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-slate-100">
                    <th className="text-left text-xs font-medium text-slate-500 px-6 py-3">股票</th>
                    <th className="text-right text-xs font-medium text-slate-500 px-3 py-3">持仓</th>
                    <th className="text-right text-xs font-medium text-slate-500 px-3 py-3">现价</th>
                    <th className="text-right text-xs font-medium text-slate-500 px-3 py-3">平均成本</th>
                    <th className="text-right text-xs font-medium text-slate-500 px-3 py-3">市值</th>
                    <th className="text-right text-xs font-medium text-slate-500 px-3 py-3">浮盈</th>
                    <th className="text-right text-xs font-medium text-slate-500 px-6 py-3">收益率</th>
                  </tr>
                </thead>
                <tbody>
                  {snapshots.map(s => (
                    <tr key={s.stockId} className="border-b border-slate-50 hover:bg-slate-50/50 transition-colors">
                      <td className="px-6 py-3">
                        <Link to={`/stock?symbol=${encodeURIComponent(s.stockSymbol)}`}
                          className="font-medium text-slate-900 hover:text-blue-600">{s.stockName}</Link>
                        <div className="text-xs text-slate-400">{s.stockSymbol}</div>
                      </td>
                      <td className="px-3 py-3 text-right tabular-nums">{s.totalShares}</td>
                      <td className="px-3 py-3 text-right tabular-nums">{s.currentPrice?.toFixed(2)}</td>
                      <td className="px-3 py-3 text-right tabular-nums">{s.avgCost?.toFixed(2)}</td>
                      <td className="px-3 py-3 text-right font-medium tabular-nums">{s.marketValue?.toFixed(2)}</td>
                      <td className={`px-3 py-3 text-right font-medium tabular-nums ${s.unrealizedPnl >= 0 ? 'text-emerald-600' : 'text-red-500'}`}>
                        {s.unrealizedPnl >= 0 ? '+' : ''}{s.unrealizedPnl?.toFixed(2)}
                      </td>
                      <td className={`px-6 py-3 text-right font-medium tabular-nums ${s.unrealizedPnlPct >= 0 ? 'text-emerald-600' : 'text-red-500'}`}>
                        {s.unrealizedPnlPct >= 0 ? '+' : ''}{s.unrealizedPnlPct}%
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
