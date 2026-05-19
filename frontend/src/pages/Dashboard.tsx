import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '@/hooks/use-auth'
import { useSettings } from '@/hooks/use-settings'
import { chartAPI } from '@/services/api'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import type { AllocationItem, PnlRankItem, CumulativeReturnItem } from '@/types'
import {
  PieChart, Pie, Cell, BarChart, Bar, XAxis, YAxis, CartesianGrid,
  AreaChart, Area, Tooltip, ResponsiveContainer
} from 'recharts'
import { getDashboard } from '@/services/api'
import { displaySymbol } from '@/lib/format'
import CloudChart from '@/components/CloudChart'

interface Snapshot {
  stockId: number; stockSymbol: string; stockName: string; market: string
  totalShares: number; avgCost: number; dilutedCost: number
  totalInvested: number; totalDividends: number
  currentPrice: number; marketValue: number; unrealizedPnl: number; unrealizedPnlPct: number
}

const COLORS = ['#0f172a', '#1e293b', '#334155', '#475569', '#64748b', '#94a3b8', '#cbd5e1',
  '#0369a1', '#0284c7', '#0ea5e9', '#38bdf8', '#7dd3fc', '#06b6d4', '#0891b2']

export default function Dashboard() {
  const { portfolioId } = useAuth()
  const { positiveClass, negativeClass, positiveHex, negativeHex } = useSettings()
  const [snapshots, setSnapshots] = useState<Snapshot[]>([])
  const [totals, setTotals] = useState({ totalMarketValue: 0, totalInvested: 0, totalPnl: 0, totalReturnPct: 0 })
  const [allocation, setAllocation] = useState<AllocationItem[]>([])
  const [pnlRank, setPnlRank] = useState<PnlRankItem[]>([])
  const [cumulative, setCumulative] = useState<CumulativeReturnItem[]>([])
  const [allocChart, setAllocChart] = useState<'pie' | 'cloud'>('pie')
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    if (!portfolioId) return
    Promise.all([
      getDashboard(),
      chartAPI.allocation(portfolioId),
      chartAPI.pnlRank(portfolioId),
      chartAPI.cumulativeReturn(portfolioId, 365),
    ]).then(([dash, alloc, rank, cum]) => {
      setSnapshots(dash.snapshots || [])
      setTotals({
        totalMarketValue: dash.totalMarketValue || 0,
        totalInvested: dash.totalInvested || 0,
        totalPnl: dash.totalPnl || 0,
        totalReturnPct: dash.totalReturnPct || 0,
      })
      setAllocation(alloc || [])
      setPnlRank(rank || [])
      setCumulative(cum || [])
    }).catch((e) => console.error('Dashboard load error:', e))
    .finally(() => setLoading(false))
  }, [portfolioId])

  if (loading) {
    return (
      <div className="flex items-center justify-center h-96">
        <div className="w-8 h-8 border-2 border-slate-300 border-t-slate-900 rounded-full animate-spin" />
      </div>
    )
  }

  const isPositive = totals.totalPnl >= 0

  return (
    <div className="p-6 space-y-6">
      <h2 className="text-xl font-bold text-slate-900 tracking-tight">总览</h2>

      {/* Summary cards */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <Card>
          <CardContent className="pt-6">
            <p className="text-xs text-slate-500 font-medium">总市值</p>
            <p className="text-2xl font-bold text-slate-900 mt-1 tabular-nums">
              &yen;{totals.totalMarketValue.toLocaleString('zh-CN', { minimumFractionDigits: 2 })}
            </p>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="pt-6">
            <p className="text-xs text-slate-500 font-medium">总成本</p>
            <p className="text-2xl font-bold text-slate-900 mt-1 tabular-nums">
              &yen;{totals.totalInvested.toLocaleString('zh-CN', { minimumFractionDigits: 2 })}
            </p>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="pt-6">
            <p className="text-xs text-slate-500 font-medium">浮动盈亏</p>
            <p className={`text-2xl font-bold mt-1 tabular-nums ${isPositive ? positiveClass : negativeClass}`}>
              {isPositive ? '+' : ''}&yen;{totals.totalPnl.toLocaleString('zh-CN', { minimumFractionDigits: 2 })}
            </p>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="pt-6">
            <p className="text-xs text-slate-500 font-medium">总收益率</p>
            <p className={`text-2xl font-bold mt-1 tabular-nums ${isPositive ? positiveClass : negativeClass}`}>
              {isPositive ? '+' : ''}{totals.totalReturnPct}%
            </p>
          </CardContent>
        </Card>
      </div>

      {/* Charts row 1 */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <Card>
          <CardHeader className="flex-row items-center justify-between">
            <CardTitle className="text-base">持仓占比</CardTitle>
            <div className="flex bg-slate-100 rounded-lg p-0.5">
              <button onClick={() => setAllocChart('pie')}
                className={`px-2.5 py-1 rounded-md text-xs font-medium transition-colors ${allocChart === 'pie' ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'}`}>
                扇形
              </button>
              <button onClick={() => setAllocChart('cloud')}
                className={`px-2.5 py-1 rounded-md text-xs font-medium transition-colors ${allocChart === 'cloud' ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'}`}>
                云图
              </button>
            </div>
          </CardHeader>
          <CardContent>
            {allocChart === 'pie' ? (
              <ResponsiveContainer width="100%" height={280}>
                <PieChart>
                  <Pie data={allocation} dataKey="value" nameKey="name" cx="50%" cy="50%" outerRadius={100} innerRadius={55}
                    label={({ cx, cy, midAngle, innerRadius, outerRadius, name }) => {
                      if (midAngle == null) return null
                      const RADIAN = Math.PI / 180
                      const radius = (innerRadius ?? 0) + ((outerRadius ?? 0) - (innerRadius ?? 0)) * 0.6
                      const x = (cx ?? 0) + radius * Math.cos(-midAngle * RADIAN)
                      const y = (cy ?? 0) + radius * Math.sin(-midAngle * RADIAN)
                      return name ? (
                        <text x={x} y={y} textAnchor="middle" dominantBaseline="central"
                          className="text-[10px] font-bold fill-white pointer-events-none select-none">
                          {name.charAt(0)}
                        </text>
                      ) : null
                    }}>
                    {allocation.map((_, i) => (
                      <Cell key={i} fill={COLORS[i % COLORS.length]} />
                    ))}
                  </Pie>
                  <Tooltip formatter={(value: unknown) => `¥${Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2 })}`} />
                </PieChart>
              </ResponsiveContainer>
            ) : (
              <CloudChart data={allocation} colors={COLORS} />
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader><CardTitle className="text-base">盈亏排行榜</CardTitle></CardHeader>
          <CardContent>
            <ResponsiveContainer width="100%" height={280}>
              <BarChart data={pnlRank} layout="vertical" margin={{ left: 40 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9" />
                <XAxis type="number" tick={{ fontSize: 11 }} stroke="#94a3b8" />
                <YAxis type="category" dataKey="name" tick={{ fontSize: 11 }} stroke="#94a3b8" width={60} />
                <Tooltip formatter={(value: unknown) => `¥${Number(value).toLocaleString()}`} />
                <Bar dataKey="pnl" radius={[0, 4, 4, 0]}>
                  {pnlRank.map((_, i) => (
                    <Cell key={i} fill={pnlRank[i].pnl >= 0 ? positiveHex : negativeHex} />
                  ))}
                </Bar>
              </BarChart>
            </ResponsiveContainer>
          </CardContent>
        </Card>
      </div>

      {/* Cumulative return */}
      <Card>
        <CardHeader><CardTitle className="text-base">累计收益曲线</CardTitle></CardHeader>
        <CardContent>
          <ResponsiveContainer width="100%" height={240}>
            <AreaChart data={cumulative}>
              <defs>
                <linearGradient id="colorReturn" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%" stopColor={positiveHex} stopOpacity={0.15} />
                  <stop offset="95%" stopColor={positiveHex} stopOpacity={0} />
                </linearGradient>
              </defs>
              <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9" />
              <XAxis dataKey="date" tick={{ fontSize: 11 }} stroke="#94a3b8" />
              <YAxis tick={{ fontSize: 11 }} stroke="#94a3b8" tickFormatter={(v: number) => `${v}%`} />
              <Tooltip formatter={(value: unknown) => `${Number(value).toFixed(2)}%`} />
              <Area type="monotone" dataKey="return" stroke={positiveHex} fill="url(#colorReturn)" strokeWidth={2} />
            </AreaChart>
          </ResponsiveContainer>
        </CardContent>
      </Card>

      {/* Holdings table */}
      {snapshots.length > 0 ? (
        <Card>
          <CardHeader className="flex-row items-center justify-between">
            <CardTitle className="text-base">持仓明细</CardTitle>
            <Link to="/transactions/add"
              className="inline-flex items-center gap-1.5 h-9 px-4 rounded-xl bg-slate-900 text-white text-xs font-medium hover:bg-slate-800 transition-colors">
              买入
            </Link>
          </CardHeader>
          <CardContent className="p-0">
            <div className="overflow-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-slate-100">
                    <th className="text-left text-xs font-medium text-slate-500 px-6 py-3">股票</th>
                    <th className="text-left text-xs font-medium text-slate-500 px-3 py-3">市场</th>
                    <th className="text-right text-xs font-medium text-slate-500 px-3 py-3">持仓</th>
                    <th className="text-right text-xs font-medium text-slate-500 px-3 py-3">现价</th>
                    <th className="text-right text-xs font-medium text-slate-500 px-3 py-3">平均成本</th>
                    <th className="text-right text-xs font-medium text-slate-500 px-3 py-3">摊薄成本</th>
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
                          className="font-medium text-slate-900 hover:text-blue-600 transition-colors">
                          {s.stockName}
                        </Link>
                        <div className="text-xs text-slate-400">{displaySymbol(s.stockSymbol, s.market)}</div>
                      </td>
                      <td className="px-3 py-3">
                        <span className="inline-flex items-center rounded-lg bg-slate-100 px-2 py-0.5 text-xs text-slate-600">{s.market}</span>
                      </td>
                      <td className="px-3 py-3 text-right tabular-nums">{s.totalShares}</td>
                      <td className="px-3 py-3 text-right tabular-nums">{s.currentPrice?.toFixed(2)}</td>
                      <td className="px-3 py-3 text-right tabular-nums">{s.avgCost?.toFixed(2)}</td>
                      <td className="px-3 py-3 text-right tabular-nums">{s.dilutedCost?.toFixed(2)}</td>
                      <td className="px-3 py-3 text-right font-medium tabular-nums">{s.marketValue?.toFixed(2)}</td>
                      <td className={`px-3 py-3 text-right font-medium tabular-nums ${s.unrealizedPnl >= 0 ? positiveClass : negativeClass}`}>
                        {s.unrealizedPnl >= 0 ? '+' : ''}{s.unrealizedPnl?.toFixed(2)}
                      </td>
                      <td className={`px-6 py-3 text-right font-medium tabular-nums ${s.unrealizedPnlPct >= 0 ? positiveClass : negativeClass}`}>
                        {s.unrealizedPnlPct >= 0 ? '+' : ''}{s.unrealizedPnlPct}%
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </CardContent>
        </Card>
      ) : (
        <Card>
          <CardContent className="py-12 text-center">
            <div className="w-12 h-12 rounded-full bg-slate-100 flex items-center justify-center mx-auto mb-3">
              <svg className="w-6 h-6 text-slate-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M20 13V6a2 2 0 00-2-2H6a2 2 0 00-2 2v7m16 0v5a2 2 0 01-2 2H6a2 2 0 01-2-2v-5m16 0h-2.586a1 1 0 00-.707.293l-2.414 2.414a1 1 0 01-.707.293h-3.172a1 1 0 01-.707-.293l-2.414-2.414A1 1 0 006.586 13H4" />
              </svg>
            </div>
            <p className="text-slate-500 text-sm">暂无持仓</p>
            <Link to="/transactions/add" className="inline-flex mt-2 text-sm text-slate-900 font-medium hover:underline">
              添加第一笔交易 &rarr;
            </Link>
          </CardContent>
        </Card>
      )}
    </div>
  )
}
