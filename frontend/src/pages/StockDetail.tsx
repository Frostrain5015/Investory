import { useEffect, useState } from 'react'
import { useSearchParams, Link } from 'react-router-dom'
import { useAuth } from '@/hooks/use-auth'
import { chartAPI, getStockDetail } from '@/services/api'
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
  const [days, setDays] = useState(180)
  const [loading, setLoading] = useState(true)

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
  }, [symbol, portfolioId, days])

  if (loading) {
    return <div className="flex items-center justify-center h-96"><div className="w-8 h-8 border-2 border-slate-300 border-t-slate-900 rounded-full animate-spin" /></div>
  }

  const stock = data?.stock
  const holding = data?.holding
  const currentPrice = priceData.length > 0 ? Number(priceData[priceData.length - 1].close) : null
  const dilutedCost = holding ? Number(holding.dilutedCost) : 0
  const inProfit = currentPrice != null && dilutedCost > 0 ? currentPrice >= dilutedCost : true
  const chartColor = inProfit ? positiveHex : negativeHex
  const transactions = data?.transactions || []
  const dividends = data?.dividends || []

  return (
    <div className="p-6 space-y-6">
      {/* Header */}
      <div className="flex items-center gap-4">
        <div>
          <div className="flex items-baseline gap-3">
            <h2 className="text-xl font-bold text-slate-900">
              <span className="mr-2 text-2xl leading-none align-middle">
                {stock?.market === 'SH' || stock?.market === 'SZ' ? '🇨🇳' : stock?.market === 'HK' ? '🇭🇰' : stock?.market === 'US' ? '🇺🇸' : ''}
              </span>
              {stock?.name}
            </h2>
            {currentPrice != null && (
              <span className={`text-3xl font-bold tabular-nums tracking-tight ${inProfit ? positiveClass : negativeClass}`}>
                {stock?.currency === 'CNY' ? '¥' : stock?.currency === 'HKD' ? 'HK$' : '$'}{currentPrice.toFixed(2)}
              </span>
            )}
          </div>
          <p className="text-sm text-slate-500">{stock && displaySymbol(stock.symbol, stock.market)}</p>
        </div>
        <Link to="/transactions/add"
          className="ml-auto inline-flex items-center gap-1.5 h-9 px-4 rounded-xl bg-slate-900 text-white text-xs font-medium hover:bg-slate-800 transition-colors">
          买入/卖出
        </Link>
      </div>

      {/* Cost cards */}
      {holding && (
        <div className="grid grid-cols-2 lg:grid-cols-5 gap-4">
          {[
            { label: '持仓数量', value: holding.totalShares },
            { label: '平均成本', value: holding.avgCost?.toFixed(2), color: 'text-amber-600' },
            { label: '摊薄成本', value: holding.dilutedCost?.toFixed(2), color: 'text-sky-600' },
            { label: '总投入', value: holding.totalInvested?.toFixed(2) },
            { label: '累计分红', value: holding.totalDividends?.toFixed(2), color: positiveClass },
          ].map((c, i) => (
            <Card key={i}>
              <CardContent className="pt-6">
                <p className="text-xs text-slate-500 font-medium">{c.label}</p>
                <p className={`text-lg font-bold mt-1 tabular-nums ${c.color || 'text-slate-900'}`}>{c.value}</p>
              </CardContent>
            </Card>
          ))}
        </div>
      )}

      {/* Price chart */}
      <Card>
        <CardHeader className="flex-row items-center justify-between">
          <CardTitle className="text-base">股价走势</CardTitle>
          <div className="flex gap-1">
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
              <XAxis dataKey="date" tick={{ fontSize: 11 }} stroke="#94a3b8" />
              <YAxis tick={{ fontSize: 11 }} stroke="#94a3b8" domain={['auto', 'auto']} />
              {holding?.dilutedCost && Number(holding.dilutedCost) > 0 && (
                <ReferenceLine y={Number(holding.dilutedCost)} stroke="#0ea5e9" strokeDasharray="6 4" strokeWidth={1.5}
                  label={{ value: `摊薄 ${Number(holding.dilutedCost).toFixed(2)}`, position: 'insideTopRight', fontSize: 11, fill: '#0ea5e9' }} />
              )}
              <Tooltip />
              <Area type="monotone" dataKey="close" stroke={chartColor} fill="url(#colorPrice)" strokeWidth={2} />
              {/* B/S markers */}
              {transactions.map(t => (
                <ReferenceDot key={`tx-${t.id}`} x={t.tradeDate} y={Number(t.price)}
                  r={5} fill={t.type === 'BUY' ? '#ef4444' : '#10b981'} stroke="#fff" strokeWidth={2} />
              ))}
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
                  {transactions.map((t: Transaction) => (
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
