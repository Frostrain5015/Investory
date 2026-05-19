import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '@/hooks/use-auth'
import { useSettings } from '@/hooks/use-settings'
import { chartAPI } from '@/services/api'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import type { AllocationItem, CumulativeReturnItem } from '@/types'
import {
  PieChart, Pie, Cell, XAxis, YAxis, CartesianGrid,
  AreaChart, Area, Tooltip, ResponsiveContainer
} from 'recharts'
import { getDashboard, getPortfolios } from '@/services/api'
import { displaySymbol } from '@/lib/format'
import CloudChart from '@/components/CloudChart'

interface Snapshot {
  stockId: number; stockSymbol: string; stockName: string; market: string
  totalShares: number; avgCost: number; dilutedCost: number
  totalInvested: number; totalDividends: number
  currentPrice: number; marketValue: number; unrealizedPnl: number; unrealizedPnlPct: number
  changeToday: number; changePctToday: number
}

const COLORS = ['#0f172a', '#1e293b', '#334155', '#475569', '#64748b', '#94a3b8', '#cbd5e1',
  '#0369a1', '#0284c7', '#0ea5e9', '#38bdf8', '#7dd3fc', '#06b6d4', '#0891b2']

export default function Dashboard() {
  const { portfolioId } = useAuth()
  const { positiveClass, negativeClass, positiveHex, negativeHex, formatCurrency, convertCurrency } = useSettings()
  const [snapshots, setSnapshots] = useState<Snapshot[]>([])
  const [totals, setTotals] = useState({ totalMarketValue: 0, totalInvested: 0, totalPnl: 0, totalReturnPct: 0, todayPnl: 0, todayPnlPct: 0 })
  const [allocation, setAllocation] = useState<AllocationItem[]>([])
  const [cumulative, setCumulative] = useState<CumulativeReturnItem[]>([])
  const [cumulativeDays, setCumulativeDays] = useState(365)
  const [rankMode, setRankMode] = useState<'cumulative' | 'today'>('cumulative')
  const [allocChart, setAllocChart] = useState<'pie' | 'cloud'>('pie')
  const [portfolioName, setPortfolioName] = useState('')
  const [refreshing, setRefreshing] = useState(false)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    if (!portfolioId) return
    Promise.all([
      getDashboard(),
      chartAPI.allocation(portfolioId),
      chartAPI.cumulativeReturn(portfolioId, cumulativeDays),
    ]).then(([dash, alloc, cum]) => {
      setSnapshots(dash.snapshots || [])
      setTotals({
        totalMarketValue: dash.totalMarketValue || 0,
        totalInvested: dash.totalInvested || 0,
        totalPnl: dash.totalPnl || 0,
        totalReturnPct: dash.totalReturnPct || 0,
        todayPnl: dash.todayPnl || 0,
        todayPnlPct: dash.todayPnlPct || 0,
      })
      setAllocation(alloc || [])
      setCumulative(cum || [])
    }).catch((e) => console.error('Dashboard load error:', e))
    .finally(() => setLoading(false))
  }, [portfolioId, cumulativeDays])

  useEffect(() => {
    if (!portfolioId) return
    getPortfolios().then((list) => {
      const p = list.find((p) => p.id === portfolioId)
      if (p) setPortfolioName(p.name)
    }).catch(() => {})
  }, [portfolioId])

  if (loading) {
    return (
      <div className="flex items-center justify-center h-96">
        <div className="w-8 h-8 border-2 border-slate-300 border-t-slate-900 rounded-full animate-spin" />
      </div>
    )
  }

  const isPositive = totals.totalPnl >= 0
  const cumUp = totals.todayPnl >= 0

  return (
    <div className="p-6 space-y-6">
      <div className="flex items-center justify-between">
        <h2 className="text-xl font-bold text-slate-900 tracking-tight">{portfolioName || '总览'}</h2>
        {snapshots.length > 0 && (
          <button disabled={refreshing}
            onClick={async () => { setRefreshing(true); await fetch('/investory/api/portfolio/refresh', { method: 'POST', credentials: 'include' }); setTimeout(() => setRefreshing(false), 5000) }}
            className="h-8 px-3 rounded-lg border border-slate-200 text-xs text-slate-500 hover:bg-slate-50 transition-colors disabled:opacity-50">
            {refreshing ? '刷新中...' : '刷新行情'}
          </button>
        )}
      </div>

      {snapshots.length === 0 ? (
        <Card>
          <CardContent className="py-16 text-center">
            <div className="w-16 h-16 rounded-2xl bg-slate-100 flex items-center justify-center mx-auto mb-4">
              <svg className="w-8 h-8 text-slate-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M12 6v6m0 0v6m0-6h6m-6 0H6" />
              </svg>
            </div>
            <h3 className="text-lg font-semibold text-slate-900 mb-2">开始你的投资之旅</h3>
            <p className="text-sm text-slate-500 mb-6">添加第一笔交易记录，盈亏鉴将为你追踪持仓、计算盈亏。</p>
            <Link to="/transactions/add"
              className="inline-flex items-center gap-2 h-10 px-6 rounded-xl bg-slate-900 text-white text-sm font-medium hover:bg-slate-800 transition-colors">
              添加第一笔交易
            </Link>
          </CardContent>
        </Card>
      ) : (<>

      {/* Summary cards */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <Card>
          <CardContent className="pt-6">
            <p className="text-xs text-slate-500 font-medium">总市值</p>
            <p className="text-2xl font-bold text-slate-900 mt-1 tabular-nums">
              {formatCurrency(totals.totalMarketValue)}
              <span className={`text-xs font-medium ml-1.5 align-middle ${isPositive ? positiveClass : negativeClass}`}>
                {isPositive ? '+' : ''}{(totals.totalMarketValue > 0 ? (totals.totalPnl / totals.totalMarketValue * 100) : 0).toFixed(1)}%
              </span>
            </p>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="pt-6">
            <p className="text-xs text-slate-500 font-medium">总成本</p>
            <p className="text-2xl font-bold text-slate-900 mt-1 tabular-nums">
              {formatCurrency(totals.totalInvested)}
            </p>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="pt-6">
            <p className="text-xs text-slate-500 font-medium">今日盈亏</p>
            <p className={`text-2xl font-bold mt-1 tabular-nums ${totals.todayPnl >= 0 ? positiveClass : negativeClass}`}>
              {totals.todayPnl >= 0 ? '+' : '-'}{formatCurrency(Math.abs(totals.todayPnl))}
            </p>
            <p className={`text-xs font-medium mt-0.5 ${totals.todayPnl >= 0 ? positiveClass : negativeClass}`}>
              {totals.todayPnlPct >= 0 ? '+' : ''}{totals.todayPnlPct}%
            </p>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="pt-6">
            <p className="text-xs text-slate-500 font-medium">总收益率</p>
            <p className={`text-2xl font-bold mt-1 tabular-nums ${isPositive ? positiveClass : negativeClass}`}>
              {isPositive ? '+' : ''}{totals.totalReturnPct}%
            </p>
            <p className="text-[10px] text-slate-400 mt-0.5">现金加权</p>
          </CardContent>
        </Card>
      </div>

      {/* Total asset curve */}
      <Card>
        <CardHeader className="flex-row items-baseline justify-between">
          <div className="flex items-center gap-3">
            <CardTitle className="text-base">总资产曲线</CardTitle>
            <div className="flex bg-slate-100 rounded-lg p-0.5">
              {([30, 180, 365, 730] as const).map(days => (
                <button key={days} onClick={() => setCumulativeDays(days)}
                  className={`px-2.5 py-1 rounded-md text-xs font-medium transition-colors ${cumulativeDays === days ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'}`}>
                  {days === 30 ? '1M' : days === 180 ? '6M' : days === 365 ? '1Y' : '2Y'}
                </button>
              ))}
            </div>
          </div>
          {cumulative.length > 1 && (() => { const s = Number(cumulative[0].value); const e = Number(cumulative[cumulative.length - 1].value); const chg = e - s; const pct = s > 0 ? (chg / s * 100) : 0; return (
            <span className={`text-lg font-bold tabular-nums tracking-tight ${chg >= 0 ? positiveClass : negativeClass}`}>
              {chg >= 0 ? '+' : ''}{formatCurrency(chg)} ({pct >= 0 ? '+' : ''}{pct.toFixed(2)}%)
            </span>
          )})()}
        </CardHeader>
        <CardContent>
          <ResponsiveContainer width="100%" height={240}>
            <AreaChart data={cumulative}>
              <defs>
                <linearGradient id="colorValue" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%" stopColor={cumUp ? positiveHex : negativeHex} stopOpacity={0.15} />
                  <stop offset="95%" stopColor={cumUp ? positiveHex : negativeHex} stopOpacity={0} />
                </linearGradient>
              </defs>
              <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9" />
              <XAxis dataKey="date" tick={{ fontSize: 11 }} stroke="#94a3b8"
                tickFormatter={(v: string) => cumulativeDays <= 180 ? v.substring(5) : v} />
              <YAxis tick={{ fontSize: 11 }} stroke="#94a3b8" tickFormatter={(v: number) => {
                const cv = convertCurrency(Number(v))
                if (Math.abs(cv) >= 10000) return (cv / 10000).toFixed(0) + '万'
                return String(Math.round(cv))
              }} />
              <Tooltip formatter={(value: unknown) => formatCurrency(Number(value))} />
              <Area type="monotone" dataKey="value" stroke={cumUp ? positiveHex : negativeHex} fill="url(#colorValue)" strokeWidth={2} isAnimationActive={false} />
            </AreaChart>
          </ResponsiveContainer>
        </CardContent>
      </Card>

      {/* Charts row */}
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
                  <Pie data={allocation} dataKey="value" nameKey="name" cx="50%" cy="50%" outerRadius={85} innerRadius={45}
                    labelLine={{ stroke: '#94a3b8', strokeWidth: 1 }}
                    label={({ name, percent }) => name ? `${name} ${((percent ?? 0) * 100).toFixed(0)}%` : ''}>
                    {allocation.map((_, i) => (
                      <Cell key={i} fill={COLORS[i % COLORS.length]} />
                    ))}
                  </Pie>
                  <Tooltip formatter={(value: unknown) => formatCurrency(Number(value))} />
                </PieChart>
              </ResponsiveContainer>
            ) : (
              <CloudChart data={allocation} colors={COLORS} />
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex-row items-center justify-between">
            <CardTitle className="text-base">盈亏排行榜</CardTitle>
            <div className="flex bg-slate-100 rounded-lg p-0.5">
              <button onClick={() => setRankMode('cumulative')}
                className={`px-2.5 py-1 rounded-md text-xs font-medium ${rankMode === 'cumulative' ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'}`}>累计</button>
              <button onClick={() => setRankMode('today')}
                className={`px-2.5 py-1 rounded-md text-xs font-medium ${rankMode === 'today' ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'}`}>今日</button>
            </div>
          </CardHeader>
          <CardContent>
            {snapshots.length === 0 ? (
              <div className="h-[280px] flex items-center justify-center text-slate-400 text-sm">暂无数据</div>
            ) : (
              <div className="space-y-0.5 h-[280px] overflow-auto">
                {[...snapshots].sort((a, b) => {
                  const va = rankMode === 'today' ? (a.changeToday || 0) : a.unrealizedPnl
                  const vb = rankMode === 'today' ? (b.changeToday || 0) : b.unrealizedPnl
                  return va - vb
                }).map((item, i) => {
                  const val = rankMode === 'today' ? (item.changeToday || 0) : item.unrealizedPnl
                  const pctVal = rankMode === 'today' ? (item.changePctToday || 0) : item.unrealizedPnlPct
                  const absMax = Math.max(...snapshots.map(s => Math.abs(rankMode === 'today' ? (s.changeToday || 0) : s.unrealizedPnl)), 1)
                  const barPct = val / absMax
                  return (
                    <div key={item.stockId} className="flex items-center gap-3 py-2 px-3 rounded-lg hover:bg-slate-50 transition-colors">
                      <span className="text-xs text-slate-500 w-5 tabular-nums">{snapshots.length - i}</span>
                      <span className="text-sm font-medium text-slate-700 w-20 truncate">{item.stockName}</span>
                      <div className="flex-1 flex items-center gap-2">
                        <div className="h-2 rounded-full flex-1 bg-slate-100 relative overflow-hidden">
                          <div className="absolute top-0 h-full rounded-full transition-all"
                            style={{ width: `${Math.abs(barPct) * 100}%`, left: val >= 0 ? 'auto' : 0, right: val >= 0 ? 0 : 'auto', backgroundColor: val >= 0 ? positiveHex : negativeHex }} />
                        </div>
                        <span className={`text-sm font-semibold tabular-nums whitespace-nowrap ${val >= 0 ? positiveClass : negativeClass}`}>
                          {val >= 0 ? '+' : ''}{formatCurrency(val)} ({pctVal >= 0 ? '+' : ''}{pctVal.toFixed(2)}%)
                        </span>
                      </div>
                    </div>
                  )
                })}
              </div>
            )}
          </CardContent>
        </Card>
      </div>

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
      </>)}
    </div>
  )
}
