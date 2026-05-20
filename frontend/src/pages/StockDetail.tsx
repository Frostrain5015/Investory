import { useEffect, useState } from 'react'
import { useSearchParams, Link } from 'react-router-dom'
import { useAuth } from '@/hooks/use-auth'
import { chartAPI, getStockDetail, searchStocks } from '@/services/api'
import { useSettings } from '@/hooks/use-settings'
import type { StockDetailResponse, Transaction, Dividend, PriceData } from '@/types'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, ReferenceLine, ReferenceDot } from 'recharts'
import { displaySymbol } from '@/lib/format'

export default function StockDetail() {
  const [params] = useSearchParams()
  const symbol = params.get('symbol') || ''
  const { portfolioId } = useAuth()
  const { positiveClass, negativeClass, positiveHex, negativeHex } = useSettings()
  const [data, setData] = useState<StockDetailResponse | null>(null)
  const [priceData, setPriceData] = useState<PriceData[]>([])
  const [days, setDays] = useState(730)
  const [loading, setLoading] = useState(true)
  const [watching, setWatching] = useState(false)
  const [watchId, setWatchId] = useState<number | null>(null)

  useEffect(() => {
    if (!symbol || !portfolioId) return
    setLoading(true)
    Promise.all([
      getStockDetail(symbol),
      chartAPI.price(symbol, days),
    ]).then(([detail, prices]) => {
      setData(detail)
      setPriceData(prices)
    }).finally(() => setLoading(false))
    // Check watchlist status
    fetch('/investory/api/watchlist', { credentials: 'include' })
      .then(r => r.json()).then((list: any[]) => {
        const found = list.find((w: any) => w.symbol === symbol)
        if (found) { setWatching(true); setWatchId(found.id) }
      }).catch(() => {})
  }, [symbol, portfolioId, days])

  if (loading) {
    return <div className="flex items-center justify-center h-96"><div className="w-8 h-8 border-2 border-slate-300 border-t-slate-900 rounded-full animate-spin" /></div>
  }

  const stock = data?.stock
  const holding = data?.holding
  const currentPrice = priceData.length > 0 ? Number(priceData[priceData.length - 1].close) : null
  const prevClose = priceData.length > 1 ? Number(priceData[priceData.length - 2].close) : null
  const changePct = currentPrice != null && prevClose != null && prevClose > 0
    ? ((currentPrice - prevClose) / prevClose * 100) : null
  const priceUp = changePct != null ? changePct >= 0 : true
  const chartColor = priceUp ? positiveHex : negativeHex
  const transactions = data?.transactions || []
  const dividends = data?.dividends || []

  return (
    <div className="p-6 space-y-6">
      {/* Header */}
      <div className="flex items-center gap-4">
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
              </>
            )}
          </div>
          <p className="text-sm text-slate-500">{stock && displaySymbol(stock.symbol, stock.market)}</p>
        </div>
        <button onClick={() => {
          const sym = params.get('symbol')
          if (sym) fetch(`/investory/api/stocks/${encodeURIComponent(sym)}/refresh`, { method: 'POST', credentials: 'include' }).then(() => window.location.reload())
        }}
          className="ml-auto inline-flex items-center gap-1.5 h-9 px-4 rounded-xl border border-slate-200 text-slate-600 text-xs font-medium hover:bg-slate-50 transition-colors">
          刷新数据
        </button>
        <button onClick={async () => {
          const sym = params.get('symbol')
          if (!sym) return
          if (watching && watchId) {
            await fetch(`/investory/api/watchlist/${stock?.id}`, { method: 'DELETE', credentials: 'include' })
            setWatching(false); setWatchId(null)
          } else {
            const stocks = await searchStocks(sym)
            if (stocks.length > 0) {
              const form = new URLSearchParams({ stockId: stocks[0].id })
              await fetch('/investory/api/watchlist', { method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: form })
              setWatching(true)
            }
          }
        }}
          className={`inline-flex items-center gap-1.5 h-9 px-4 rounded-xl border text-xs font-medium transition-colors ${watching ? 'border-red-200 text-red-600 hover:bg-red-50' : 'border-slate-200 text-slate-600 hover:bg-slate-50'}`}>
          {watching ? '删除自选' : '添加自选'}
        </button>
        <Link to="/transactions/add"
          className="inline-flex items-center gap-1.5 h-9 px-4 rounded-xl bg-slate-900 text-white text-xs font-medium hover:bg-slate-800 transition-colors">
          添加交易
        </Link>
      </div>

      {/* Cost cards */}
      {holding && (
        <div className="grid grid-cols-2 lg:grid-cols-5 gap-4">
          {([
            { label: '持仓数量' as const, value: holding.totalShares, sub: '' as string | undefined, color: '' },
            { label: '持仓市值' as const, value: currentPrice != null && holding?.totalShares != null ? (currentPrice * holding.totalShares).toFixed(2) : '—', sub: undefined, color: '' },
            { label: '平均成本' as const, value: holding.avgCost?.toFixed(2), sub: undefined, color: 'text-amber-600' },
            { label: '摊薄成本' as const, value: holding.dilutedCost?.toFixed(2), sub: undefined, color: 'text-sky-600' },
            { label: '持仓盈亏' as const, value: (() => {
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
        <CardHeader className="flex-row items-center justify-between">
          <CardTitle className="text-base">股价走势</CardTitle>
          <div className="flex items-center gap-1">
            {[30, 180, 365, 730].map(d => (
              <button key={d} onClick={() => setDays(d)}
                className={`px-3 py-1 rounded-lg text-xs font-medium transition-colors ${days === d ? 'bg-slate-900 text-white' : 'bg-slate-100 text-slate-600 hover:bg-slate-200'}`}>
                {d === 30 ? '1M' : d === 180 ? '6M' : d === 365 ? '1Y' : '2Y'}
              </button>
            ))}
          </div>
        </CardHeader>
        <CardContent>
          <ResponsiveContainer width="100%" height={300}>
            <AreaChart data={priceData}>
              <defs>
                <linearGradient id="colorPrice" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%" stopColor={chartColor} stopOpacity={0.1} />
                  <stop offset="95%" stopColor={chartColor} stopOpacity={0} />
                </linearGradient>
              </defs>
              <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9" />
              <XAxis dataKey="date" tick={{ fontSize: 11 }} stroke="#94a3b8"
                tickFormatter={(v: string) => days <= 180 ? v.substring(5) : v.substring(0, 7)}
                interval={days <= 60 ? 0 : 'preserveStartEnd'} />
              <YAxis tick={{ fontSize: 11 }} stroke="#94a3b8" domain={['auto', 'auto']} />
              {holding?.dilutedCost && Number(holding.dilutedCost) > 0 && (
                <ReferenceLine y={Number(holding.dilutedCost)} stroke="#0ea5e9" strokeDasharray="6 4" strokeWidth={1.5}
                  label={{ value: `摊薄 ${Number(holding.dilutedCost).toFixed(2)}`, position: 'insideTopRight', fontSize: 11, fill: '#0ea5e9' }} />
              )}
              <Tooltip />
              <Area type="monotone" dataKey="close" stroke={chartColor} fill="url(#colorPrice)" strokeWidth={2} />
              {transactions.map(t => {
                const match = priceData.find(p => p.date === t.tradeDate)
                const y = match ? Number(match.close) : Number(t.price)
                return (
                <ReferenceDot key={`tx-${t.id}`} x={t.tradeDate} y={y}
                  r={5} fill={t.type === 'BUY' ? '#ef4444' : '#10b981'} stroke="#fff" strokeWidth={2}
                  label={{ value: t.type === 'BUY' ? 'B' : 'S', position: 'top', fontSize: 11, fill: t.type === 'BUY' ? '#ef4444' : '#10b981', fontWeight: 'bold' }} />
              )})}
              {dividends.map(d => {
                const match = priceData.find(p => p.date === d.recordDate)
                return match ? (
                  <ReferenceDot key={`div-${d.id}`} x={d.recordDate} y={match.close}
                    r={5} fill="#0ea5e9" stroke="#fff" strokeWidth={2}
                    label={{ value: 'D', position: 'top', fontSize: 11, fill: '#0ea5e9', fontWeight: 'bold' }} />
                ) : null
              })}
            </AreaChart>
          </ResponsiveContainer>
        </CardContent>
      </Card>

      {/* Transactions + Dividends */}
      <div className="grid grid-cols-1 lg:grid-cols-7 gap-6">
        <Card className="lg:col-span-4">
          <CardHeader><CardTitle className="text-base">交易记录</CardTitle></CardHeader>
          <CardContent className="p-0">
            {transactions.length === 0 ? (
              <p className="text-center text-slate-400 text-sm py-6">暂无交易</p>
            ) : (
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-slate-100">
                    <th className="text-left text-xs font-medium text-slate-500 px-4 py-2">日期</th>
                    <th className="text-left text-xs font-medium text-slate-500 px-4 py-2">操作</th>
                    <th className="text-right text-xs font-medium text-slate-500 px-4 py-2">股数</th>
                    <th className="text-right text-xs font-medium text-slate-500 px-4 py-2">价格</th>
                    <th className="text-right text-xs font-medium text-slate-500 px-4 py-2">手续费</th>
                  </tr>
                </thead>
                <tbody>
                  {[...transactions].sort((a, b) => b.tradeDate.localeCompare(a.tradeDate)).map((t: Transaction) => (
                    <tr key={t.id} className="border-b border-slate-50">
                      <td className="px-4 py-2">{t.tradeDate}</td>
                      <td className="px-4 py-2">
                        <span className={`inline-flex rounded-lg px-2 py-0.5 text-xs font-medium ${t.type === 'BUY' ? 'bg-red-50 text-red-600' : 'bg-emerald-50 text-emerald-600'}`}>
                          {t.type === 'BUY' ? '买入' : '卖出'}
                        </span>
                      </td>
                      <td className="px-4 py-2 text-right">{t.shares}</td>
                      <td className="px-4 py-2 text-right">{t.price?.toFixed(2)}</td>
                      <td className="px-4 py-2 text-right">{t.fee?.toFixed(2)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </CardContent>
        </Card>

        <Card className="lg:col-span-3">
          <CardHeader><CardTitle className="text-base">分红记录</CardTitle></CardHeader>
          <CardContent className="p-0">
            {dividends.length === 0 ? (
              <p className="text-center text-slate-400 text-sm py-6">暂无分红</p>
            ) : (
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-slate-100">
                    <th className="text-left text-xs font-medium text-slate-500 px-4 py-2">记录日</th>
                    <th className="text-right text-xs font-medium text-slate-500 px-4 py-2">每股</th>
                    <th className="text-right text-xs font-medium text-slate-500 px-4 py-2">总额</th>
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
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
