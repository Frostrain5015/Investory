import { useEffect, useState, useCallback } from 'react'
import { useCountUp } from '@/hooks/use-count-up'
import { useTheme } from '@/hooks/use-theme'
import { Link } from 'react-router-dom'
import { useAuth } from '@/hooks/use-auth'
import { useTimedRefresh, timeAgo } from '@/hooks/use-timed-refresh'
import { useToast } from '@/components/Toast'
import { useSettings } from '@/hooks/use-settings'
import { chartAPI } from '@/services/api'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import type { AllocationItem, CumulativeReturnItem } from '@/types'
import { ArrowLeftRight } from 'lucide-react'
import {
  PieChart, Pie, Cell, XAxis, YAxis, CartesianGrid,
  AreaChart, Area, Tooltip, ResponsiveContainer
} from 'recharts'
import { getDashboard, getPortfolios } from '@/services/api'
import { displaySymbol, fmtPriceTs } from '@/lib/format'
import CloudChart from '@/components/CloudChart'
import ClosedPositions from '@/components/ClosedPositions'
import { useT } from '@/i18n/I18nContext'
import { BASE } from '@/services/api'

interface Snapshot {
  stockId: number; stockSymbol: string; stockName: string; market: string; currency: string
  totalShares: number; avgCost: number; dilutedCost: number
  totalInvested: number; totalDividends: number
  currentPrice: number; marketValue: number; unrealizedPnl: number; unrealizedPnlPct: number
  changeToday: number; changePctToday: number
  nativePrice: number; nativeAvgCost: number; nativeInvested: number; nativeMarketValue: number; nativeUnrealizedPnl: number
  priceTimestamp?: string
}

const COLORS = [
  '#1e3a5f', '#e07a5f', '#2a9d8f', '#e9c46a', '#7c6fae',
  '#d67ba8', '#6b7b8c', '#52b788', '#f4a261', '#457b9d',
  '#e76f51', '#2ec4b6', '#9b5de5', '#00bbf9',
]

export default function Dashboard() {
  const { portfolioId, portfolioName, setPortfolioName } = useAuth()
  const { positiveClass, negativeClass, positiveHex, negativeHex, formatCurrency, convertCurrency } = useSettings()
  const { isDark } = useTheme()
  const toast = useToast()
  const { t, lang } = useT()
  const [snapshots, setSnapshots] = useState<Snapshot[]>([])
  const [totals, setTotals] = useState({ totalMarketValue: 0, totalInvested: 0, totalPnl: 0, realizedPnl: 0, cumulativePnl: 0, totalReturnPct: 0, cumulativeReturnPct: 0, todayPnl: 0, todayPnlPct: 0, cashBalance: 0 })
  const [allocation, setAllocation] = useState<AllocationItem[]>([])
  type Period = '1M' | '6M' | '1Y' | 'all' | 'custom'
  interface ChartParams { days: number; start?: string; end?: string }
  const [cumulative, setCumulative] = useState<CumulativeReturnItem[]>([])
  const [cumulativePeriod, setCumulativePeriod] = useState<Period>('1Y')
  const [cumParams, setCumParams] = useState<ChartParams>({ days: 365 })
  const [cumCustomStart, setCumCustomStart] = useState('')
  const [cumCustomEnd, setCumCustomEnd] = useState('')
  const [rankMode, setRankMode] = useState<'cumulative' | 'today'>('cumulative')
  const [allocChart, setAllocChart] = useState<'pie' | 'cloud'>('pie')
  const [priceMode, setPriceMode] = useState<'base' | 'native'>('base')
  const [pnlCardMode, setPnlCardMode] = useState<'today' | 'holding'>('today')
  const [showClosed, setShowClosed] = useState(false)
  const [cashCardMode, setCashCardMode] = useState<'mv' | 'cash'>('mv')
  const [cashByCurrency, setCashByCurrency] = useState<{ currency: string; amount: number }[]>([])
  const [refreshing, setRefreshing] = useState(false)
  const [loading, setLoading] = useState(true)

  // Clear data when switching portfolios to avoid stale data flash
  useEffect(() => {
    setSnapshots([])
    setAllocation([])
    setCumulative([])
    setTotals({ totalMarketValue: 0, totalInvested: 0, totalPnl: 0, realizedPnl: 0, cumulativePnl: 0, totalReturnPct: 0, cumulativeReturnPct: 0, todayPnl: 0, todayPnlPct: 0, cashBalance: 0 })
    setLoading(true)
  }, [portfolioId])

  const loadDashboard = useCallback(() => {
    if (!portfolioId) return
    Promise.all([
      getDashboard(),
      chartAPI.cumulativeReturn(portfolioId, cumParams.days, cumParams.start, cumParams.end),
    ]).then(([dash, cum]) => {
      setSnapshots(dash.snapshots || [])
      setTotals({
        totalMarketValue: dash.totalMarketValue || 0,
        totalInvested: dash.totalInvested || 0,
        totalPnl: dash.totalPnl || 0,
        realizedPnl: dash.realizedPnl || 0,
        cumulativePnl: dash.cumulativePnl || 0,
        totalReturnPct: dash.totalReturnPct || 0,
        cumulativeReturnPct: dash.cumulativeReturnPct || 0,
        todayPnl: dash.todayPnl || 0,
        todayPnlPct: dash.todayPnlPct || 0,
        cashBalance: dash.cashBalance || 0,
      })
      setCashByCurrency((dash as any).cashByCurrency || [])
      setAllocation((dash as any).allocation || [])
      setCumulative(cum || [])
    }).catch((e) => console.error('Dashboard load error:', e))
    .finally(() => setLoading(false))
  }, [portfolioId, cumParams])

  useEffect(() => {
    if (!portfolioId) return
    getPortfolios().then((list) => {
      const p = list.find(p => p.id === portfolioId)
      if (p) setPortfolioName(p.name)
    }).catch(() => {})
  }, [portfolioId])

  useEffect(() => { loadDashboard() }, [loadDashboard])

  const [lastRefresh, markRefreshed] = useTimedRefresh(() => {
    fetch(`${BASE}/api/portfolio/refresh`, { method: 'POST', credentials: 'include' })
    loadDashboard()
  })

  const animTotalAsset    = useCountUp(totals.totalMarketValue + totals.cashBalance)
  const animMarketValue   = useCountUp(totals.totalMarketValue)
  const animTodayPnl      = useCountUp(totals.todayPnl)
  const animHoldingPnl    = useCountUp(totals.totalPnl)
  const animCumulativePnl = useCountUp(totals.cumulativePnl)

  if (loading) {
    return (
      <div className="flex flex-col items-center justify-center gap-3 h-96">
        <div className="w-8 h-8 border-2 border-slate-300 border-t-slate-900 rounded-full animate-spin" />
        <span className="text-sm text-slate-400">{t.common.loading}</span>
      </div>
    )
  }

  const cumUp = cumulative.length > 1 ? Number(cumulative[cumulative.length - 1].value) >= Number(cumulative[0].value) : totals.todayPnl >= 0

  return (
    <div className="p-6 space-y-6">
      <div className="flex items-center justify-between">
        <Link to="/portfolio" className="text-xl font-bold text-slate-900 hover:text-blue-600 transition-colors tracking-tight inline-flex items-center gap-1.5">{portfolioName || t.dashboard.title}<ArrowLeftRight className="w-4 h-4 text-slate-300" /></Link>
        <div className="flex items-center gap-2">
          {lastRefresh && (
            <span className="text-[10px] text-slate-400">{timeAgo(lastRefresh)}</span>
          )}
          {snapshots.length > 0 && (
            <button disabled={refreshing}
              onClick={async () => { setRefreshing(true); try { await fetch(`${BASE}/api/portfolio/refresh`, { method: 'POST', credentials: 'include' }); toast(t.dashboard.toastRefreshSuccess, true); loadDashboard() } catch { toast(t.dashboard.toastRefreshFail, false) } setRefreshing(false); markRefreshed() }}
              className="h-8 px-3 rounded-lg border border-slate-200 text-xs text-slate-500 hover:bg-slate-50 transition-colors disabled:opacity-50">
              {refreshing ? t.common.loading : t.common.refresh}
            </button>
          )}
          <Link to="/transactions/add"
            className="inline-flex items-center gap-1.5 h-8 px-3 rounded-lg bg-slate-900 text-white text-xs font-medium hover:bg-slate-800 transition-colors">
            {t.common.add}
          </Link>
        </div>
      </div>

      {snapshots.length === 0 ? (
        <Card>
          <CardContent className="py-16 text-center">
            <div className="w-16 h-16 rounded-2xl bg-slate-100 flex items-center justify-center mx-auto mb-4">
              <svg className="w-8 h-8 text-slate-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M12 6v6m0 0v6m0-6h6m-6 0H6" />
              </svg>
            </div>
            <h3 className="text-lg font-semibold text-slate-900 mb-2">{t.dashboard.noTransactions}</h3>
            <p className="text-sm text-slate-500 mb-6">{t.dashboard.emptyHint}</p>
            <Link to="/transactions/add"
              className="inline-flex items-center gap-2 h-10 px-6 rounded-xl bg-slate-900 text-white text-sm font-medium hover:bg-slate-800 transition-colors">
              {t.dashboard.addFirstTransaction}
            </Link>
          </CardContent>
        </Card>
      ) : (<>

      {/* Summary cards */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <Card>
          <CardContent className="pt-6">
            <p className="text-xs text-slate-500 font-medium">{t.dashboard.totalAsset}</p>
            <p className="text-2xl font-bold text-slate-900 mt-1 tabular-nums">
              {formatCurrency(animTotalAsset)}
            </p>
          </CardContent>
        </Card>
        <Card className="cursor-pointer select-none" onClick={() => setCashCardMode(cashCardMode === 'mv' ? 'cash' : 'mv')}>
          <CardContent className="pt-6">
            <div className="flex items-center justify-between">
              <p className="text-xs text-slate-500 font-medium">{cashCardMode === 'mv' ? t.dashboard.totalValue : t.dashboard.cashBalance}</p>
              <ArrowLeftRight className="w-3 h-3 text-slate-300" />
            </div>
            {cashCardMode === 'mv' ? (<>
            <p className="text-2xl font-bold text-slate-900 mt-1 tabular-nums">
              {formatCurrency(animMarketValue)}
            </p>
            {(totals.totalMarketValue + totals.cashBalance) > 0 && (
              <p className="text-xs font-medium text-slate-400 mt-0.5">
                {t.dashboard.positionRatio}{(totals.totalMarketValue / (totals.totalMarketValue + totals.cashBalance) * 100).toFixed(0)}%
              </p>
            )}
            </>) : (
            <div className="space-y-1 mt-1">
              {cashByCurrency.map(c => {
                const flag = c.currency === 'CNY' ? 'cn' : c.currency === 'HKD' ? 'hk' : 'us'
                return (
                  <div key={c.currency} className="flex items-center gap-2">
                    <img src={`https://flagcdn.com/${flag}.svg`} className="w-4 h-3 rounded-sm" alt={c.currency} />
                    <span className="text-base font-semibold text-slate-700 tabular-nums">{Number(c.amount).toLocaleString('zh-CN', {minimumFractionDigits:2})}</span>
                    <span className="text-xs text-slate-400">{c.currency}</span>
                  </div>
                )
              })}
              {cashByCurrency.length === 0 && <span className="text-sm text-slate-400">{t.common.none}</span>}
            </div>
            )}
          </CardContent>
        </Card>
        <Card className="cursor-pointer select-none" onClick={() => setPnlCardMode(pnlCardMode === 'today' ? 'holding' : 'today')}>
          <CardContent className="pt-6">
            <div className="flex items-center justify-between">
              <p className="text-xs text-slate-500 font-medium">{pnlCardMode === 'today' ? t.dashboard.todayPnl : t.dashboard.holdingPnl}</p>
              <ArrowLeftRight className="w-3 h-3 text-slate-300" />
            </div>
            {pnlCardMode === 'today' ? (<>
            <p className={`text-2xl font-bold mt-1 tabular-nums ${totals.todayPnl >= 0 ? positiveClass : negativeClass}`}>
              {animTodayPnl >= 0 ? '+' : '-'}{formatCurrency(Math.abs(animTodayPnl))}
            </p>
            <p className={`text-xs font-medium mt-0.5 ${totals.todayPnl >= 0 ? positiveClass : negativeClass}`}>
              {totals.todayPnlPct >= 0 ? '+' : ''}{totals.todayPnlPct}%
            </p>
            </>) : (<>
            <p className={`text-2xl font-bold mt-1 tabular-nums ${totals.totalPnl >= 0 ? positiveClass : negativeClass}`}>
              {animHoldingPnl >= 0 ? '+' : '-'}{formatCurrency(Math.abs(animHoldingPnl))}
            </p>
            <p className={`text-xs font-medium mt-0.5 ${totals.totalPnl >= 0 ? positiveClass : negativeClass}`}>
              {totals.totalPnl >= 0 ? '+' : '-'}{Math.abs(totals.totalReturnPct).toFixed(2)}%
            </p>
            </>)}
          </CardContent>
        </Card>
        <Card>
          <CardContent className="pt-6">
            <p className="text-xs text-slate-500 font-medium">{t.pnl.cumulativePnl}</p>
            <p className={`text-2xl font-bold mt-1 tabular-nums ${totals.cumulativePnl >= 0 ? positiveClass : negativeClass}`}>
              {animCumulativePnl >= 0 ? '+' : '-'}{formatCurrency(Math.abs(animCumulativePnl))}
            </p>
            <p className={`text-xs font-medium mt-0.5 ${totals.cumulativeReturnPct >= 0 ? positiveClass : negativeClass}`}>
              {totals.cumulativeReturnPct >= 0 ? '+' : '-'}{Math.abs(totals.cumulativeReturnPct).toFixed(2)}%
            </p>
          </CardContent>
        </Card>
      </div>

      {/* Total asset curve */}
      <Card>
        <CardHeader className="flex-row items-baseline justify-between flex-wrap gap-y-2">
          <div className="flex items-center gap-3 flex-wrap gap-y-2">
            <CardTitle className="text-base">{t.dashboard.totalAssetCurve}</CardTitle>
            <div className="flex bg-slate-100 rounded-lg p-0.5">
              {([
                { period: '1M' as Period, label: '1M' },
                { period: '6M' as Period, label: '6M' },
                { period: '1Y' as Period, label: '1Y' },
                { period: 'all' as Period, label: t.stockDetail.periodAll },
                { period: 'custom' as Period, label: t.stockDetail.periodCustom },
              ]).map(({ period, label }) => (
                <button key={period} onClick={() => {
                  setCumulativePeriod(period)
                  if (period !== 'custom') {
                    const daysMap: Record<string, number> = { '1M': 30, '6M': 180, '1Y': 365, 'all': 0 }
                    setCumParams({ days: daysMap[period] ?? 365 })
                  }
                }}
                  className={`px-2.5 py-1 rounded-md text-xs font-medium transition-colors ${cumulativePeriod === period ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'}`}>
                  {label}
                </button>
              ))}
            </div>
            {cumulativePeriod === 'custom' && (
              <div className="flex items-center gap-1.5">
                <input type="date" value={cumCustomStart} onChange={e => setCumCustomStart(e.target.value)}
                  className="h-7 px-2 rounded-md border border-slate-200 text-xs text-slate-700 bg-white focus:outline-none focus:ring-1 focus:ring-slate-400" />
                <span className="text-xs text-slate-400">—</span>
                <input type="date" value={cumCustomEnd} onChange={e => setCumCustomEnd(e.target.value)}
                  className="h-7 px-2 rounded-md border border-slate-200 text-xs text-slate-700 bg-white focus:outline-none focus:ring-1 focus:ring-slate-400" />
                <button onClick={() => { if (cumCustomStart && cumCustomEnd) setCumParams({ days: 0, start: cumCustomStart, end: cumCustomEnd }) }}
                  disabled={!cumCustomStart || !cumCustomEnd}
                  className="h-7 px-2.5 rounded-md bg-slate-900 text-white text-xs font-medium disabled:opacity-40 hover:bg-slate-700 transition-colors">
                  {t.stockDetail.query}
                </button>
              </div>
            )}
          </div>
          {cumulative.length > 1 && (() => { const useExTransfer = cumulative[0].valueExTransfer != null; const s = useExTransfer ? Number(cumulative[0].valueExTransfer) : Number(cumulative[0].value); const e = useExTransfer ? Number(cumulative[cumulative.length - 1].valueExTransfer) : Number(cumulative[cumulative.length - 1].value); const chg = e - s; return (
            <span className={`text-lg font-bold tabular-nums tracking-tight ${chg >= 0 ? positiveClass : negativeClass}`}>
              {chg >= 0 ? '+' : '-'}{formatCurrency(Math.abs(chg))}
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
              <CartesianGrid strokeDasharray="3 3" stroke={isDark ? '#334155' : '#f1f5f9'} />
              <XAxis dataKey="date" tick={{ fontSize: 11, fill: isDark ? '#64748b' : '#94a3b8' }} stroke={isDark ? '#334155' : '#94a3b8'}
                tickFormatter={(v: string) => cumulativePeriod === '1M' ? v.substring(5) : v.substring(0, 7)} />
              <YAxis tick={{ fontSize: 11, fill: isDark ? '#64748b' : '#94a3b8' }} stroke={isDark ? '#334155' : '#94a3b8'} domain={['auto', 'auto']} tickFormatter={(v: number) => {
                const cv = convertCurrency(Number(v))
                if (lang === 'zh' && Math.abs(cv) >= 10000) return (cv / 10000).toFixed(0) + t.dashboard.chartUnitLarge
                if (lang === 'en' && Math.abs(cv) >= 1000) return (cv / 1000).toFixed(0) + t.dashboard.chartUnitLarge
                return String(Math.round(cv))
              }} />
              <Tooltip formatter={(value: unknown) => formatCurrency(Number(value))}
                contentStyle={{ backgroundColor: isDark ? '#1e293b' : '#fff', borderColor: isDark ? '#334155' : '#e2e8f0', color: isDark ? '#f8fafc' : '#0f172a' }} />
              <Area type="monotone" dataKey="value" stroke={cumUp ? positiveHex : negativeHex} fill="url(#colorValue)" strokeWidth={2} />
            </AreaChart>
          </ResponsiveContainer>
        </CardContent>
      </Card>

      {/* Charts row */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <Card>
          <CardHeader className="flex-row items-center justify-between">
            <CardTitle className="text-base">{t.dashboard.allocation}</CardTitle>
            <div className="flex bg-slate-100 rounded-lg p-0.5">
              <button onClick={() => setAllocChart('pie')}
                className={`px-2.5 py-1 rounded-md text-xs font-medium transition-colors ${allocChart === 'pie' ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'}`}>
                {t.dashboard.pieChart}
              </button>
              <button onClick={() => setAllocChart('cloud')}
                className={`px-2.5 py-1 rounded-md text-xs font-medium transition-colors ${allocChart === 'cloud' ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'}`}>
                {t.dashboard.cloudChart}
              </button>
            </div>
          </CardHeader>
          <CardContent>
            {allocChart === 'pie' ? (
              <ResponsiveContainer width="100%" height={280}>
                <PieChart>
                  <Pie data={[...allocation].sort((a, b) => (b.value || 0) - (a.value || 0))} dataKey="value" nameKey="name" cx="50%" cy="50%" outerRadius={85} innerRadius={45}
                    labelLine={{ stroke: '#94a3b8', strokeWidth: 1 }}
                    label={({ name, percent }) => {
                      if ((percent ?? 0) < 0.03) return null
                      return name ? `${name} ${((percent ?? 0) * 100).toFixed(0)}%` : ''
                    }}>
                    {[...allocation].sort((a, b) => (b.value || 0) - (a.value || 0)).map((_, i) => (
                      <Cell key={i} fill={COLORS[i % COLORS.length]} />
                    ))}
                  </Pie>
                  <Tooltip formatter={(value: unknown) => formatCurrency(Number(value))}
                    contentStyle={{ backgroundColor: isDark ? '#1e293b' : '#fff', borderColor: isDark ? '#334155' : '#e2e8f0', color: isDark ? '#f8fafc' : '#0f172a' }} />
                </PieChart>
              </ResponsiveContainer>
            ) : (
              <CloudChart data={allocation} colors={COLORS} />
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="flex-row items-center justify-between">
            <CardTitle className="text-base">{t.dashboard.pnlRankTitle}</CardTitle>
            <div className="flex bg-slate-100 rounded-lg p-0.5">
              <button onClick={() => setRankMode('cumulative')}
                className={`px-2.5 py-1 rounded-md text-xs font-medium ${rankMode === 'cumulative' ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'}`}>{t.common.cumulative}</button>
              <button onClick={() => setRankMode('today')}
                className={`px-2.5 py-1 rounded-md text-xs font-medium ${rankMode === 'today' ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'}`}>{t.common.today}</button>
            </div>
          </CardHeader>
          <CardContent>
            {snapshots.length === 0 ? (
              <div className="h-[280px] flex items-center justify-center text-slate-400 text-sm">{t.pnl.noData}</div>
            ) : (
              <div className="space-y-0.5 h-[280px] overflow-auto">
                {[...snapshots].sort((a, b) => {
                  const va = rankMode === 'today' ? (a.changeToday || 0) : a.unrealizedPnl
                  const vb = rankMode === 'today' ? (b.changeToday || 0) : b.unrealizedPnl
                  return vb - va
                }).map(item => {
                  const val = rankMode === 'today' ? (item.changeToday || 0) : item.unrealizedPnl
                  const pctVal = rankMode === 'today' ? (item.changePctToday || 0) : item.unrealizedPnlPct
                  const absMax = Math.max(...snapshots.map(s => Math.abs(rankMode === 'today' ? (s.changeToday || 0) : s.unrealizedPnl)), 1)
                  const barPct = val / absMax
                  return (
                    <div key={item.stockId} className="flex items-center gap-3 py-2 px-3 rounded-lg hover:bg-slate-50 transition-colors">
                      <span className="text-sm font-medium text-slate-700 w-24 truncate">{item.stockName}</span>
                      <div className="flex-1 flex items-center gap-2">
                        <div className="h-2 rounded-full flex-1 bg-slate-100 relative overflow-hidden">
                          <div className="absolute top-0 h-full rounded-full transition-all"
                            style={{ width: `${Math.abs(barPct) * 100}%`, left: 0, backgroundColor: val >= 0 ? positiveHex : negativeHex }} />
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
            <CardTitle className="text-base">{t.holdings.title}</CardTitle>
            <div className="flex items-center gap-2">
            <div className="flex bg-slate-100 rounded-lg p-0.5">
              <button onClick={() => setPriceMode('base')}
                className={`px-2.5 py-1 rounded-md text-xs font-medium ${priceMode === 'base' ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'}`}>{t.dashboard.baseCurrency}</button>
              <button onClick={() => setPriceMode('native')}
                className={`px-2.5 py-1 rounded-md text-xs font-medium ${priceMode === 'native' ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'}`}>{t.dashboard.nativeCurrency}</button>
            </div>
            <button onClick={() => setShowClosed(true)} className="text-xs text-slate-500 hover:text-slate-700 border border-slate-200 px-2.5 py-1 rounded-lg hover:bg-slate-50 transition-colors">{t.dashboard.viewClosed}</button>
            </div>
          </CardHeader>
          <CardContent className="p-0">
            {/* Desktop table */}
            <div className="hidden lg:block overflow-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-slate-100">
                    <th className="text-left text-xs font-medium text-slate-500 px-6 py-3">{t.holdings.stock}</th>
                    <th className="text-left text-xs font-medium text-slate-500 px-3 py-3">{t.dashboard.marketColumn}</th>
                    <th className="text-right text-xs font-medium text-slate-500 px-3 py-3">{t.holdings.title}</th>
                    <th className="text-right text-xs font-medium text-slate-500 px-3 py-3">{t.market.price}</th>
                    <th className="text-right text-xs font-medium text-slate-500 px-3 py-3">{t.holdings.avgCost}</th>
                    <th className="text-right text-xs font-medium text-slate-500 px-3 py-3">{t.dashboard.dilutedCost}</th>
                    <th className="text-right text-xs font-medium text-slate-500 px-3 py-3">{t.holdings.marketValue}</th>
                    <th className="text-right text-xs font-medium text-slate-500 px-3 py-3">{t.holdings.pnl}</th>
                    <th className="text-right text-xs font-medium text-slate-500 px-6 py-3">{t.holdings.pnlPct}</th>
                  </tr>
                </thead>
                <tbody>
                  {[...snapshots].sort((a, b) => (b.marketValue || 0) - (a.marketValue || 0)).map(s => {
                    const native = priceMode === 'native' && s.currency !== 'CNY'
                    const price = native ? (s.nativePrice || s.currentPrice) : s.currentPrice
                    const cost = native ? (s.nativeAvgCost || s.avgCost) : s.avgCost
                    const diluted = native ? s.dilutedCost : s.dilutedCost
                    const mv = native ? (s.nativeMarketValue || s.marketValue) : s.marketValue
                    const pnl = native ? (s.nativeUnrealizedPnl || s.unrealizedPnl) : s.unrealizedPnl
                    const fmtNative = (v: number) => v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
                    const fmtVal = (v: number) => native ? fmtNative(v) : fmtNative(convertCurrency(v))
                    return (
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
                      <td className="px-3 py-3 text-right tabular-nums">
                        <div>{fmtVal(price)}</div>
                        {s.priceTimestamp && <div className="text-[10px] text-slate-400">{fmtPriceTs(s.priceTimestamp)}</div>}
                      </td>
                      <td className="px-3 py-3 text-right tabular-nums">{fmtVal(cost)}</td>
                      <td className="px-3 py-3 text-right tabular-nums">{fmtVal(diluted)}</td>
                      <td className="px-3 py-3 text-right font-medium tabular-nums">{fmtVal(mv)}</td>
                      <td className={`px-3 py-3 text-right font-medium tabular-nums ${pnl >= 0 ? positiveClass : negativeClass}`}>
                        {pnl >= 0 ? '+' : ''}{fmtVal(Math.abs(pnl))}
                      </td>
                      <td className={`px-6 py-3 text-right font-medium tabular-nums ${s.unrealizedPnlPct >= 0 ? positiveClass : negativeClass}`}>
                        {s.unrealizedPnlPct >= 0 ? '+' : ''}{s.unrealizedPnlPct}%
                      </td>
                    </tr>
                  )})}
                </tbody>
              </table>
            </div>
            {/* Mobile cards */}
            <div className="lg:hidden divide-y divide-slate-50">
              {[...snapshots].sort((a, b) => (b.marketValue || 0) - (a.marketValue || 0)).map(s => {
                const native = priceMode === 'native' && s.currency !== 'CNY'
                const mv = native ? (s.nativeMarketValue || s.marketValue) : s.marketValue
                const pnl = native ? (s.nativeUnrealizedPnl || s.unrealizedPnl) : s.unrealizedPnl
                const cost = native ? (s.nativeAvgCost || s.avgCost) : s.avgCost
                const diluted = native ? s.dilutedCost : s.dilutedCost
                const invested = s.totalInvested
                const fmtNative = (v: number) => v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
                const fmtVal = (v: number) => native ? fmtNative(v) : fmtNative(convertCurrency(v))
                return (
                  <div key={s.stockId} className="px-4 py-3">
                    <div className="flex items-center justify-between mb-1.5">
                      <div className="flex items-center gap-2">
                        <Link to={`/stock?symbol=${encodeURIComponent(s.stockSymbol)}`}
                          className="font-medium text-slate-900 hover:text-blue-600 transition-colors">{s.stockName}</Link>
                        <span className="text-[10px] px-1.5 py-0.5 rounded bg-slate-100 text-slate-500">{s.market}</span>
                      </div>
                      <span className={`text-sm font-semibold tabular-nums ${s.unrealizedPnlPct >= 0 ? positiveClass : negativeClass}`}>
                        {s.unrealizedPnlPct >= 0 ? '+' : ''}{s.unrealizedPnlPct}%
                      </span>
                    </div>
                    <div className="flex justify-between text-sm text-slate-600 mb-1">
                      <span className="text-xs text-slate-400">{s.totalShares} {t.dashboard.sharesUnit}</span>
                      <span className="tabular-nums font-medium text-slate-800">{fmtVal(mv)}</span>
                    </div>
                    <details className="mt-1">
                      <summary className="text-xs text-slate-400 cursor-pointer select-none">{t.common.more}</summary>
                      <div className="mt-2 space-y-1 text-xs text-slate-500">
                        <div className="flex justify-between"><span>{t.holdings.pnl}</span><span className={`tabular-nums ${pnl >= 0 ? positiveClass : negativeClass}`}>{pnl >= 0 ? '+' : ''}{fmtVal(Math.abs(pnl))}</span></div>
                        <div className="flex justify-between"><span>{t.holdings.avgCost}</span><span className="tabular-nums">{fmtVal(cost)}</span></div>
                        <div className="flex justify-between"><span>{t.dashboard.dilutedCost}</span><span className="tabular-nums">{fmtVal(diluted)}</span></div>
                        <div className="flex justify-between"><span>{t.holdings.invested}</span><span className="tabular-nums">{fmtVal(invested)}</span></div>
                        <div className="flex justify-between"><span>{t.dashboard.totalDividends}</span><span className="tabular-nums text-sky-600">{fmtVal(s.totalDividends)}</span></div>
                      </div>
                    </details>
                  </div>
                )
              })}
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
            <p className="text-slate-500 text-sm">{t.dashboard.noHoldings}</p>
            <Link to="/transactions/add" className="inline-flex mt-2 text-sm text-slate-900 font-medium hover:underline">
              {t.dashboard.addFirstTransaction} &rarr;
            </Link>
          </CardContent>
        </Card>
      )}
      </>)}
      <ClosedPositions open={showClosed} onClose={() => setShowClosed(false)} />
    </div>
  )
}
