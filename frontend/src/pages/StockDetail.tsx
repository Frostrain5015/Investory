import { useEffect, useState } from 'react'
import { useSearchParams, Link } from 'react-router-dom'
import { useAuth } from '@/hooks/use-auth'
import { BASE, chartAPI, getStockDetail, searchStocks, getHoldingsCorrelation } from '@/services/api'
import { useSettings } from '@/hooks/use-settings'
import { useT } from '@/i18n/I18nContext'
import type { StockDetailResponse, Transaction, Dividend, PriceData, HoldingCorrelation } from '@/types'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, ReferenceLine, ReferenceDot, Legend, Line } from 'recharts'
import type { ValueType, NameType } from 'recharts/types/component/DefaultTooltipContent'
import { displaySymbol, fmtPriceTs } from '@/lib/format'

export default function StockDetail() {
  const [params] = useSearchParams()
  const symbol = params.get('symbol') || ''
  const { portfolioId } = useAuth()
  const { positiveClass, negativeClass, positiveHex, negativeHex } = useSettings()
  const { t } = useT()
  const [data, setData] = useState<StockDetailResponse | null>(null)
  type Period = '1M' | '6M' | '1Y' | 'all' | 'custom'
  interface ChartParams { days: number; start?: string; end?: string }
  const [priceData, setPriceData] = useState<PriceData[]>([])
  const [period, setPeriod] = useState<Period>('1Y')
  const [chartParams, setChartParams] = useState<ChartParams>({ days: 365 })
  const [customStart, setCustomStart] = useState('')
  const [customEnd, setCustomEnd] = useState('')
  const [loading, setLoading] = useState(true)
  const [watching, setWatching] = useState(false)
  const [watchId, setWatchId] = useState<number | null>(null)
  const [benchmark, setBenchmark] = useState('')
  const [bmData, setBmData] = useState<{ date: string; base100: number; bmBase100: number; bmName: string }[] | null>(null)
  const [correlations, setCorrelations] = useState<HoldingCorrelation[]>([])

  // Benchmarks per market (proper names, not translated)
  const BENCHMARKS: Record<string, { symbol: string; name: string }[]> = {
    SH: [{ symbol: '000001.SH', name: '上证指数' }],
    SZ: [{ symbol: '399001.SZ', name: '深证成指' }],
    HK: [{ symbol: 'HSI.HK', name: '恒生指数' }, { symbol: 'HSTECH.HK', name: '恒生科技' }],
    US: [{ symbol: 'GSPC.US', name: '标普500' }, { symbol: 'IXIC.US', name: '纳斯达克' }],
  }

  useEffect(() => {
    if (!symbol || !portfolioId) return
    setLoading(true)
    Promise.all([
      getStockDetail(symbol),
      chartAPI.price(symbol, chartParams.days, chartParams.start, chartParams.end, benchmark || undefined),
    ]).then(([detail, rawPrices]) => {
      setData(detail)
      // rawPrices is array when no benchmark, { prices, benchmark } when benchmark set
      if (Array.isArray(rawPrices)) {
        setPriceData(rawPrices)
        setBmData(null)
      } else {
        setPriceData(rawPrices.prices)
        const bm = rawPrices.benchmark
        if (bm && bm.length > 0) {
          const bmName = BENCHMARKS[stock?.market || '']?.find(b => b.symbol === benchmark)?.name || benchmark
          setBmData(bm.map((d) => ({ date: d.date, base100: d.base100, bmBase100: d.bmBase100, bmName })))
        } else {
          setBmData(null)
        }
      }
    }).finally(() => setLoading(false))
    // Check watchlist status
    fetch(`${BASE}/api/watchlist`, { credentials: 'include' })
      .then(r => r.json() as Promise<{ id: number; symbol: string }[]>).then((list) => {
        const found = list.find((w) => w.symbol === symbol)
        if (found) { setWatching(true); setWatchId(found.id) }
      }).catch(() => {})
    // Holdings correlation
    getHoldingsCorrelation(symbol).then(setCorrelations).catch(() => {})
  }, [symbol, portfolioId, chartParams, benchmark])

  if (loading) {
    return (
      <div className="flex flex-col items-center justify-center gap-3 h-96">
        <div className="w-8 h-8 border-2 border-slate-300 border-t-slate-900 rounded-full animate-spin" />
        <span className="text-sm text-slate-400">{t.stockDetail.loading}</span>
      </div>
    )
  }

  const stock = data?.stock
  const holding = data?.holding
  const livePrice = data?.livePrice != null ? Number(data.livePrice) : null
  const lastClose = priceData.length > 0 ? Number(priceData[priceData.length - 1].close) : null
  const currentPrice = livePrice ?? lastClose
  // prevClose: when live price available, compare against last historical close (yesterday);
  // otherwise fall back to second-to-last so the chart-only case still shows a change %.
  const prevClose = livePrice != null
    ? lastClose
    : priceData.length > 1 ? Number(priceData[priceData.length - 2].close) : null
  const changePct = currentPrice != null && prevClose != null && prevClose > 0
    ? ((currentPrice - prevClose) / prevClose * 100) : null
  const priceUp = changePct != null ? changePct >= 0 : true
  const chartColor = priceUp ? positiveHex : negativeHex
  const transactions = data?.transactions || []
  const dividends = data?.dividends || []

  return (
    <div className="p-6 space-y-6">
      {/* Header */}
      <div className="flex items-center gap-3 flex-wrap">
        <div>
          <div className="flex items-baseline gap-3">
            <h2 className="text-xl font-bold text-slate-900">
              {(() => { const m: string = stock?.market ?? ''; if (!m || m === 'IDX' || m === 'CMD' || m === 'CCY') return null;
                const flag = m === 'SH' || m === 'SZ' ? 'cn' : m.toLowerCase()
                return (
                <img src={`https://flagcdn.com/${flag}.svg`}
                  alt={m} className="w-6 h-4 inline-block align-middle mr-2 rounded-sm" />
              )})()}
              {stock?.name}
            </h2>
            {currentPrice != null && (
              <>
                <span className={`text-3xl font-bold tabular-nums tracking-tight ${priceUp ? positiveClass : negativeClass}`}>
                  {(() => {
                    const sym = stock?.symbol || ''
                    const mkt = stock?.market || ''
                    // Commodities / crypto — show USD
                    if (mkt === 'CMD' || mkt === 'CCY') return '$'
                    // Markets that contain only indices (no individual stocks)
                    if (mkt === 'JP' || mkt === 'KR' || mkt === 'GB' || mkt === 'DE' || mkt === 'FR'
                        || mkt === 'TW' || mkt === 'SG' || mkt === 'IN' || mkt === 'AU' || mkt === 'CA'
                        || mkt === 'BR' || mkt === 'IDX') return ''
                    // The 9 index symbols inside SH/SZ/HK/US markets
                    if (sym === '000001.SH' || sym === '399001.SZ' || sym === '399006.SZ'
                        || sym === 'HSI.HK' || sym === 'HSCE.HK' || sym === 'HSTECH.HK'
                        || sym === 'GSPC.US' || sym === 'DJI.US' || sym === 'IXIC.US') return ''
                    return stock?.currency === 'CNY' ? '¥' : stock?.currency === 'HKD' ? 'HK$' : '$'
                  })()}{currentPrice.toFixed(2)}
                </span>
                {changePct != null && (
                  <span className={`text-lg font-semibold tabular-nums ${changePct >= 0 ? positiveClass : negativeClass}`}>
                    {changePct >= 0 ? '+' : ''}{changePct.toFixed(2)}%
                  </span>
                )}
                {data?.livePriceTs && (
                  <span className="text-xs text-slate-400 self-end mb-0.5">
                    {fmtPriceTs(data.livePriceTs)}
                  </span>
                )}
              </>
            )}
          </div>
          <p className="text-sm text-slate-500">{stock && displaySymbol(stock.symbol, stock.market)}</p>
        </div>
        <button onClick={() => {
          const sym = params.get('symbol')
          if (sym) fetch(`${BASE}/api/stocks/${encodeURIComponent(sym)}/refresh`, { method: 'POST', credentials: 'include' }).then(() => window.location.reload())
        }}
          className="ml-auto inline-flex items-center gap-1.5 h-9 px-4 rounded-xl border border-slate-200 text-slate-600 text-xs font-medium hover:bg-slate-50 transition-colors">
          {t.stockDetail.refreshData}
        </button>
        <button onClick={async () => {
          const sym = params.get('symbol')
          if (!sym) return
          if (watching && watchId) {
            await fetch(`${BASE}/api/watchlist/${stock?.id}`, { method: 'DELETE', credentials: 'include' })
            setWatching(false); setWatchId(null)
          } else {
            const stocks = await searchStocks(sym)
            if (stocks.length > 0) {
              const form = new URLSearchParams({ stockId: stocks[0].id })
              await fetch(`${BASE}/api/watchlist`, { method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: form })
              setWatching(true)
            }
          }
        }}
          className={`inline-flex items-center gap-1.5 h-9 px-4 rounded-xl border text-xs font-medium transition-colors ${watching ? 'border-red-200 text-red-600 hover:bg-red-50' : 'border-slate-200 text-slate-600 hover:bg-slate-50'}`}>
          {watching ? t.stockDetail.removeFromWatchlist : t.stockDetail.addToWatchlist}
        </button>
        <Link to="/transactions/add"
          className="inline-flex items-center gap-1.5 h-9 px-4 rounded-xl bg-slate-900 text-white text-xs font-medium hover:bg-slate-800 transition-colors">
          {t.stockDetail.addTransaction}
        </Link>
      </div>

      {/* Cost cards */}
      {holding && (
        <div className="grid grid-cols-2 lg:grid-cols-5 gap-4">
          {([
            { label: t.stockDetail.sharesHeld, value: holding.totalShares, sub: '' as string | undefined, color: '' },
            { label: t.stockDetail.marketValueHeld, value: currentPrice != null && holding?.totalShares != null ? (currentPrice * holding.totalShares).toFixed(2) : '—', sub: undefined, color: '' },
            { label: t.stockDetail.avgCostHeld, value: holding.avgCost?.toFixed(2), sub: undefined, color: 'text-amber-600' },
            { label: t.stockDetail.dilutedCostHeld, value: holding.dilutedCost?.toFixed(2), sub: undefined, color: 'text-sky-600' },
            { label: t.stockDetail.holdingPnl, value: (() => {
                if (currentPrice == null || !holding?.totalShares || !holding?.totalInvested) return '—'
                const mv = currentPrice * holding.totalShares
                const pnl = mv - holding.totalInvested + (holding.totalDividends || 0)
                const sign = pnl >= 0 ? '+' : ''
                return `${sign}${pnl.toFixed(2)}`
              })(), sub: (() => {
                if (currentPrice == null || !holding?.totalShares || !holding?.totalInvested || holding.totalInvested === 0) return undefined
                const mv = currentPrice * holding.totalShares
                const pnl = mv - holding.totalInvested + (holding.totalDividends || 0)
                const pct = (pnl / holding.totalInvested * 100)
                return `${pct >= 0 ? '+' : ''}${pct.toFixed(2)}%`
              })(), color: (() => {
                if (currentPrice == null || !holding?.totalShares || !holding?.totalInvested) return 'text-slate-900'
                const pnl = (currentPrice * holding.totalShares) - holding.totalInvested + (holding.totalDividends || 0)
                return pnl >= 0 ? positiveClass : negativeClass
              })() },
          ] as const).map((c, i) => (
            <Card key={i}>
              <CardContent className="pt-6">
                <p className="text-xs text-slate-500 font-medium">{c.label}</p>
                <p className={`text-lg font-bold mt-1 tabular-nums ${c.color || 'text-slate-900'}`}>{c.value}</p>
                {c.sub && <p className={`text-xs font-medium mt-0.5 ${c.color || 'text-slate-400'}`}>{c.sub}</p>}
              </CardContent>
            </Card>
          ))}
        </div>
      )}

      {/* Price chart */}
      <Card>
        <CardHeader className="flex-row items-center justify-between flex-wrap gap-y-2">
          <CardTitle className="text-base">{t.stockDetail.priceChart}</CardTitle>
          <div className="flex items-center gap-2 flex-wrap gap-y-1.5">
            <div className="flex items-center gap-1">
              {([
                { label: '1M', period: '1M' as Period },
                { label: '6M', period: '6M' as Period },
                { label: '1Y', period: '1Y' as Period },
                { label: t.stockDetail.periodAll, period: 'all' as Period },
                { label: t.stockDetail.periodCustom, period: 'custom' as Period },
              ]).map(({ label, period: p }) => (
                  <button key={label} onClick={() => {
                    setPeriod(p)
                    if (p !== 'custom') {
                      const daysMap: Record<string, number> = { '1M': 30, '6M': 180, '1Y': 365, 'all': 0 }
                      setChartParams({ days: daysMap[p] ?? 365 })
                    }
                  }}
                    className={`px-3 py-1 rounded-lg text-xs font-medium transition-colors ${period === p ? 'bg-slate-900 text-white' : 'bg-slate-100 text-slate-600 hover:bg-slate-200'}`}>
                    {label}
                  </button>
                )
              )}
            </div>
            {/* Benchmark selector */}
            {stock && BENCHMARKS[stock.market] && (
              <select value={benchmark} onChange={e => setBenchmark(e.target.value)}
                className="h-7 px-2 rounded-md border border-slate-200 text-xs text-slate-600 bg-white focus:outline-none focus:ring-1 focus:ring-amber-400">
                <option value="">{t.stockDetail.noBenchmark}</option>
                {BENCHMARKS[stock.market].map(b => (
                  <option key={b.symbol} value={b.symbol}>vs {b.name}</option>
                ))}
              </select>
            )}
            {period === 'custom' && (
              <div className="flex items-center gap-1.5">
                <input type="date" value={customStart} onChange={e => setCustomStart(e.target.value)}
                  className="h-7 px-2 rounded-md border border-slate-200 text-xs text-slate-700 bg-white focus:outline-none focus:ring-1 focus:ring-slate-400" />
                <span className="text-xs text-slate-400">—</span>
                <input type="date" value={customEnd} onChange={e => setCustomEnd(e.target.value)}
                  className="h-7 px-2 rounded-md border border-slate-200 text-xs text-slate-700 bg-white focus:outline-none focus:ring-1 focus:ring-slate-400" />
                <button onClick={() => { if (customStart && customEnd) setChartParams({ days: 0, start: customStart, end: customEnd }) }}
                  disabled={!customStart || !customEnd}
                  className="h-7 px-2.5 rounded-md bg-slate-900 text-white text-xs font-medium disabled:opacity-40 hover:bg-slate-700 transition-colors">
                  {t.stockDetail.query}
                </button>
              </div>
            )}
          </div>
        </CardHeader>
        <CardContent>
          <ResponsiveContainer width="100%" height={300}>
            <AreaChart data={(bmData || priceData) as Record<string, unknown>[]}>
              {!bmData ? (
                <>
                  <defs>
                    <linearGradient id="colorPrice" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%" stopColor={chartColor} stopOpacity={0.1} />
                      <stop offset="95%" stopColor={chartColor} stopOpacity={0} />
                    </linearGradient>
                  </defs>
                  <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9" />
                  <XAxis dataKey="date" tick={{ fontSize: 11 }} stroke="#94a3b8"
                    tickFormatter={(v: string) => period === '1M' ? v.substring(5) : v.substring(0, 7)}
                    interval="preserveStartEnd" />
                  <YAxis tick={{ fontSize: 11 }} stroke="#94a3b8" domain={['auto', 'auto']} />
                  {holding?.dilutedCost && Number(holding.dilutedCost) > 0 && (
                    <ReferenceLine y={Number(holding.dilutedCost)} stroke="#0ea5e9" strokeDasharray="6 4" strokeWidth={1.5}
                      label={{ value: `${t.stockDetail.dilutedLabel} ${Number(holding.dilutedCost).toFixed(2)}`, position: 'insideTopRight', fontSize: 11, fill: '#0ea5e9' }} />
                  )}
                  <Tooltip formatter={(value: ValueType | undefined) => [
                    Number(value).toFixed(2), stock?.name || ''
                  ]} />
                  <Area type="monotone" dataKey="close" stroke={chartColor} fill="url(#colorPrice)" strokeWidth={2} />
                  {transactions.map(tran => {
                    const match = priceData.find(p => p.date === tran.tradeDate)
                    const y = match ? Number(match.close) : Number(tran.price)
                    return (
                    <ReferenceDot key={`tx-${tran.id}`} x={tran.tradeDate} y={y}
                      r={5} fill={tran.type === 'BUY' ? '#ef4444' : '#10b981'} stroke="#fff" strokeWidth={2}
                      label={{ value: tran.type === 'BUY' ? 'B' : 'S', position: 'top', fontSize: 11, fill: tran.type === 'BUY' ? '#ef4444' : '#10b981', fontWeight: 'bold' }} />
                  )})}
                  {dividends.map(d => {
                    const match = priceData.find(p => p.date === d.recordDate)
                    return match ? (
                      <ReferenceDot key={`div-${d.id}`} x={d.recordDate} y={match.close}
                        r={5} fill="#0ea5e9" stroke="#fff" strokeWidth={2}
                        label={{ value: 'D', position: 'top', fontSize: 11, fill: '#0ea5e9', fontWeight: 'bold' }} />
                    ) : null
                  })}
                </>
              ) : (
                <>
                  <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9" />
                  <XAxis dataKey="date" tick={{ fontSize: 11 }} stroke="#94a3b8"
                    tickFormatter={(v: string) => period === '1M' ? v.substring(5) : v.substring(0, 7)}
                    interval="preserveStartEnd" />
                  <YAxis tick={{ fontSize: 11 }} stroke="#94a3b8"
                    label={{ value: t.stockDetail.base100, angle: -90, position: 'insideLeft', fontSize: 10, fill: '#94a3b8' }}
                    domain={['auto', 'auto']} />
                  <Tooltip formatter={(value: ValueType | undefined, name: NameType | undefined) => {
                    const v = Number(value)
                    if (name === 'base100') return [v.toFixed(2), stock?.name || 'Stock']
                    if (name === 'bmBase100') return [v.toFixed(2), bmData?.[0]?.bmName || 'BM']
                    return [String(v), name ?? '']
                  }} />
                  <Legend />
                  <defs>
                    <linearGradient id="colorPrice" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%" stopColor={chartColor} stopOpacity={0.1} />
                      <stop offset="95%" stopColor={chartColor} stopOpacity={0} />
                    </linearGradient>
                  </defs>
                  <Line name={bmData?.[0]?.bmName || 'Benchmark'} type="monotone" dataKey="bmBase100" stroke="#f59e0b" strokeWidth={2} dot={false}
                    strokeDasharray="5 3" />
                  <Area name={stock?.name || 'Stock'} type="monotone" dataKey="base100" stroke={chartColor} fill="url(#colorPrice)" strokeWidth={2} />
                  {transactions.map(tran => {
                    const match = bmData!.find(p => p.date === tran.tradeDate)
                    const y = match ? match.base100 : undefined
                    return y != null ? (
                    <ReferenceDot key={`tx-${tran.id}`} x={tran.tradeDate} y={y}
                      r={5} fill={tran.type === 'BUY' ? '#ef4444' : '#10b981'} stroke="#fff" strokeWidth={2}
                      label={{ value: tran.type === 'BUY' ? 'B' : 'S', position: 'top', fontSize: 11, fill: tran.type === 'BUY' ? '#ef4444' : '#10b981', fontWeight: 'bold' }} />
                    ) : null
                  })}
                </>
              )}
            </AreaChart>
          </ResponsiveContainer>
        </CardContent>
      </Card>

      {/* Transactions + Dividends */}
      <div className="grid grid-cols-1 lg:grid-cols-7 gap-6">
        <Card className="lg:col-span-4">
          <CardHeader><CardTitle className="text-base">{t.stockDetail.transactions}</CardTitle></CardHeader>
          <CardContent className="p-0">
            {transactions.length === 0 ? (
              <p className="text-center text-slate-400 text-sm py-6">{t.stockDetail.noTransactions}</p>
            ) : (
              <>
                <div className="hidden lg:block overflow-auto">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="border-b border-slate-100">
                        <th className="text-left text-xs font-medium text-slate-500 px-4 py-2">{t.transactions.date}</th>
                        <th className="text-left text-xs font-medium text-slate-500 px-4 py-2">{t.transactions.type}</th>
                        <th className="text-right text-xs font-medium text-slate-500 px-4 py-2">{t.transactions.shares}</th>
                        <th className="text-right text-xs font-medium text-slate-500 px-4 py-2">{t.transactions.price}</th>
                        <th className="text-right text-xs font-medium text-slate-500 px-4 py-2">{t.stockDetail.fee}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {[...transactions].sort((a, b) => b.tradeDate.localeCompare(a.tradeDate)).map((tran: Transaction) => (
                        <tr key={tran.id} className="border-b border-slate-50">
                          <td className="px-4 py-2">{tran.tradeDate}</td>
                          <td className="px-4 py-2">
                            <span className={`inline-flex rounded-lg px-2 py-0.5 text-xs font-medium ${tran.type === 'BUY' ? 'bg-red-50 text-red-600' : 'bg-emerald-50 text-emerald-600'}`}>
                              {tran.type === 'BUY' ? t.transactions.buy : t.transactions.sell}
                            </span>
                          </td>
                          <td className="px-4 py-2 text-right">{tran.shares}</td>
                          <td className="px-4 py-2 text-right">{tran.price?.toFixed(2)}</td>
                          <td className="px-4 py-2 text-right">{tran.fee?.toFixed(2)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                {/* Mobile transaction cards */}
                <div className="lg:hidden divide-y divide-slate-50">
                  {[...transactions].sort((a, b) => b.tradeDate.localeCompare(a.tradeDate)).map((tran: Transaction) => (
                    <div key={tran.id} className="px-4 py-3">
                      <div className="flex items-center justify-between mb-1.5">
                        <span className="text-sm text-slate-500">{tran.tradeDate}</span>
                        <span className={`inline-flex rounded-lg px-2 py-0.5 text-xs font-medium ${tran.type === 'BUY' ? 'bg-red-50 text-red-600' : 'bg-emerald-50 text-emerald-600'}`}>
                          {tran.type === 'BUY' ? t.transactions.buy : t.transactions.sell}
                        </span>
                      </div>
                      <div className="flex items-center justify-between text-sm">
                        <span className="font-medium text-slate-900">{stock?.name}</span>
                        <span className="tabular-nums font-semibold text-slate-800">{((tran.shares ?? 0) * (tran.price ?? 0)).toFixed(2)}</span>
                      </div>
                      <details className="mt-1">
                        <summary className="text-xs text-slate-400 cursor-pointer select-none">{t.common.more}</summary>
                        <div className="mt-2 space-y-1 text-xs text-slate-500">
                          <div className="flex justify-between"><span>{t.transactions.shares}</span><span className="tabular-nums">{tran.shares}</span></div>
                          <div className="flex justify-between"><span>{t.transactions.price}</span><span className="tabular-nums">{tran.price?.toFixed(2)}</span></div>
                          <div className="flex justify-between"><span>{t.stockDetail.fee}</span><span className="tabular-nums">{tran.fee?.toFixed(2)}</span></div>
                        </div>
                      </details>
                    </div>
                  ))}
                </div>
              </>
            )}
          </CardContent>
        </Card>

        <Card className="lg:col-span-3">
          <CardHeader><CardTitle className="text-base">{t.stockDetail.dividends}</CardTitle></CardHeader>
          <CardContent className="p-0">
            {dividends.length === 0 ? (
              <p className="text-center text-slate-400 text-sm py-6">{t.stockDetail.noDividends}</p>
            ) : (
              <>
                <div className="hidden lg:block overflow-auto">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="border-b border-slate-100">
                        <th className="text-left text-xs font-medium text-slate-500 px-4 py-2">{t.stockDetail.recordDate}</th>
                        <th className="text-right text-xs font-medium text-slate-500 px-4 py-2">{t.stockDetail.perShare}</th>
                        <th className="text-right text-xs font-medium text-slate-500 px-4 py-2">{t.stockDetail.totalAmount}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {dividends.map((d: Dividend) => (
                        <tr key={d.id} className="border-b border-slate-50">
                          <td className="px-4 py-2">{d.recordDate}</td>
                          <td className="px-4 py-2 text-right">{d.amountPerShare}</td>
                          <td className={`px-4 py-2 text-right font-semibold ${positiveClass}`}>{d.totalAmount}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                {/* Mobile dividend cards */}
                <div className="lg:hidden divide-y divide-slate-50">
                  {dividends.map((d: Dividend) => (
                    <div key={d.id} className="px-4 py-3">
                      <div className="flex items-center justify-between mb-1.5">
                        <span className="text-sm text-slate-500">{t.stockDetail.recordDate}</span>
                        <span className="text-sm tabular-nums">{d.recordDate}</span>
                      </div>
                      <div className="flex items-center justify-between text-sm mb-1">
                        <span className="text-slate-500">{t.stockDetail.perShare} <span className="tabular-nums">{d.amountPerShare}</span></span>
                      </div>
                      <div className={`text-base font-bold tabular-nums ${positiveClass}`}>{d.totalAmount}</div>
                    </div>
                  ))}
                </div>
              </>
            )}
          </CardContent>
        </Card>
      </div>

      {/* Holdings correlation */}
      {correlations.length > 0 && (
        <Card>
          <CardHeader><CardTitle className="text-base">相关持仓 (30日)</CardTitle></CardHeader>
          <CardContent>
            <div className="space-y-2.5">
              {correlations.slice(0, 6).map(c => (
                <div key={c.symbol} className="flex items-center gap-3">
                  <Link to={`/stock?symbol=${encodeURIComponent(c.symbol)}`}
                    className="text-sm font-medium text-slate-700 hover:text-blue-600 transition-colors w-24 shrink-0 truncate">
                    {c.name}
                  </Link>
                  <div className="flex-1 bg-slate-100 rounded-full h-1.5 relative overflow-hidden">
                    <div className="absolute top-0 left-0 h-full rounded-full transition-all"
                      style={{
                        width: `${Math.abs(c.correlation_30d) * 100}%`,
                        backgroundColor: c.correlation_30d >= 0 ? positiveHex : negativeHex,
                      }} />
                  </div>
                  <span className="text-xs tabular-nums text-slate-500 w-12 text-right shrink-0">
                    {c.correlation_30d >= 0 ? '+' : ''}{c.correlation_30d.toFixed(2)}
                  </span>
                </div>
              ))}
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  )
}
