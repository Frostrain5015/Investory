import { useEffect, useState, useCallback, useMemo } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '@/hooks/use-auth'
import { useSettings } from '@/hooks/use-settings'
import { useTimedRefresh, timeAgo } from '@/hooks/use-timed-refresh'
import { searchStocks, chartAPI, getHoldingsMetrics } from '@/services/api'
import { Card, CardContent } from '@/components/ui/card'
import { displaySymbol, fmtPriceTs } from '@/lib/format'
import Sparkline from '@/components/Sparkline'
import type { StockSearchItem, PriceData, StockMetrics } from '@/types'
import { Search, X, Plus, GripVertical } from 'lucide-react'

interface WatchItem { id: number; stock_id: number; symbol: string; name: string; market: string; currency: string; price: number; changeToday?: number; changePctToday?: number; priceTimestamp?: string }

interface MarketGroup { key: string; label: string; flag: string; items: WatchItem[] }

function marketToGroup(market: string): string {
  if (market === 'SH' || market === 'SZ') return 'A'
  return market
}

const GROUP_DEFS: Omit<MarketGroup, 'items'>[] = [
  { key: 'A',  label: '中国A股', flag: 'https://flagcdn.com/cn.svg' },
  { key: 'HK', label: '香港股市', flag: 'https://flagcdn.com/hk.svg' },
  { key: 'US', label: '美国股市', flag: 'https://flagcdn.com/us.svg' },
]

export default function Holdings() {
  const { portfolioId } = useAuth()
  const { positiveClass, negativeClass, showRiskMetrics } = useSettings()
  const [items, setItems] = useState<WatchItem[]>([])
  const [loading, setLoading] = useState(true)
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<StockSearchItem[]>([])
  const [showAdd, setShowAdd] = useState(false)
  const [managing, setManaging] = useState(false)
  const [sparkData, setSparkData] = useState<Record<string, number[]>>({})
  const [dragKey, setDragKey] = useState<string | null>(null)
  const [dragIdx, setDragIdx] = useState<number | null>(null)
  const [dropIdx, setDropIdx] = useState<number | null>(null)
  const [metrics, setMetrics] = useState<Record<string, StockMetrics>>({})

  useEffect(() => {
    if (items.length === 0) return
    items.forEach(item => {
      if (sparkData[item.symbol]) return
      chartAPI.price(item.symbol, 30).then((raw: any) => {
        const prices: PriceData[] = Array.isArray(raw) ? raw : raw.prices
        setSparkData(prev => ({ ...prev, [item.symbol]: prices.map(p => p.close) }))
      }).catch(() => {})
    })
  }, [items])

  useEffect(() => {
    if (!showRiskMetrics || items.length === 0) return
    getHoldingsMetrics().then(r => setMetrics(r.metrics)).catch(() => {})
  }, [showRiskMetrics, items.length])

  function handleDragStart(groupKey: string, idx: number) {
    setDragKey(groupKey)
    setDragIdx(idx)
  }
  function handleDragOver(e: React.DragEvent, groupKey: string, idx: number) {
    e.preventDefault()
    if (dragKey !== groupKey) return // block cross-market drop
    setDropIdx(idx)
  }
  function handleDrop(group: MarketGroup, idx: number) {
    if (dragIdx == null || dragIdx === idx || dragKey !== group.key) {
      setDragKey(null); setDragIdx(null); setDropIdx(null); return
    }
    const next = [...items]
    // Find global indices within the full items array
    const groupStart = groups.findIndex(g => g.key === group.key)
    const globalStart = groups.slice(0, groupStart).reduce((s, g) => s + g.items.length, 0)
    const from = globalStart + dragIdx
    const to = globalStart + idx
    const [moved] = next.splice(from, 1)
    next.splice(to, 0, moved)
    setItems(next)
    setDragKey(null)
    setDragIdx(null)
    setDropIdx(null)
    const body = next.map((item, i) => ({ id: item.id, sortOrder: i }))
    fetch('/investory/api/watchlist/reorder', {
      method: 'PUT', credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }).catch(() => {})
  }

  useEffect(() => { setItems([]); setSparkData({}); setLoading(true) }, [portfolioId])

  const load = useCallback(() => {
    if (!portfolioId) return
    Promise.all([
      fetch('/investory/api/holdings', { credentials: 'include' }).then(r => r.json()),
      fetch('/investory/api/watchlist', { credentials: 'include' }).then(r => r.json()),
    ]).then(([hData, wData]) => {
      const held = (hData.snapshots || []) as any[]
      const heldById = new Map(held.map(s => [s.stockId, s]))
      const watchItems = (wData as WatchItem[]) || []

      const merged: WatchItem[] = []

      // Holdings first
      for (const s of held) {
        merged.push({
          id: -(s.stockId), stock_id: s.stockId, symbol: s.stockSymbol, name: s.stockName,
          market: s.market, currency: s.currency || '', price: s.currentPrice || 0,
          changeToday: s.changeToday ?? 0, changePctToday: s.changePctToday ?? 0,
          priceTimestamp: s.priceTimestamp,
        })
      }

      // Watched but not held
      for (const w of watchItems) {
        if (!heldById.has(w.stock_id)) merged.push(w)
      }

      setItems(merged)
    }).finally(() => setLoading(false))
  }, [portfolioId])

  useEffect(() => { load() }, [load])
  const [lastRefresh] = useTimedRefresh(() => {
    fetch('/investory/api/portfolio/refresh', { method: 'POST', credentials: 'include' })
    load()
  })

  useEffect(() => { if (query.length >= 1) searchStocks(query).then(setResults) }, [query])

  async function addToWatch(s: StockSearchItem) {
    const form = new URLSearchParams({ stockId: s.id })
    await fetch('/investory/api/watchlist', { method: 'POST', body: form, credentials: 'include' })
    setShowAdd(false)
    setQuery('')
    setResults([])
    load()
  }

  async function removeWatch(stockId: number) {
    await fetch(`/investory/api/watchlist/${stockId}`, { method: 'DELETE', credentials: 'include' })
    load()
  }

  const groups = useMemo<MarketGroup[]>(() => {
    return GROUP_DEFS.map(def => ({
      ...def,
      items: items.filter(item => marketToGroup(item.market) === def.key),
    })).filter(g => g.items.length > 0)
  }, [items])

  if (loading) {
    return <div className="flex flex-col items-center justify-center gap-3 h-96">
      <div className="w-8 h-8 border-2 border-slate-300 border-t-slate-900 rounded-full animate-spin" />
      <span className="text-sm text-slate-400">正在加载持仓...</span>
    </div>
  }

  return (
    <div className="p-6 space-y-6">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <h2 className="text-xl font-bold text-slate-900 tracking-tight">自选</h2>
          {lastRefresh && <span className="text-[10px] text-slate-400">{timeAgo(lastRefresh)}</span>}
        </div>
        <div className="relative flex items-center gap-2">
          <button onClick={() => { setManaging(!managing); setShowAdd(false) }}
            className={`h-9 px-4 rounded-xl text-xs font-medium transition-colors border ${managing ? 'bg-slate-900 text-white border-slate-900' : 'bg-white text-slate-600 border-slate-200 hover:bg-slate-50'}`}>
            管理
          </button>
          <button onClick={() => setShowAdd(!showAdd)}
            className="inline-flex items-center gap-1.5 h-9 px-4 rounded-xl bg-slate-900 text-white text-xs font-medium hover:bg-slate-800 transition-colors">
            <Plus className="w-3.5 h-3.5" />添加自选
          </button>
          {showAdd && (
            <div className="absolute right-0 top-full mt-1 w-72 bg-white rounded-xl border border-slate-200 shadow-lg overflow-hidden z-50">
              <div className="p-2">
                <div className="relative">
                  <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-slate-400" />
                  <input type="text" placeholder="搜索股票..." value={query}
                    onChange={e => setQuery(e.target.value)}
                    className="w-full h-9 pl-8 pr-3 rounded-lg border border-slate-200 text-sm focus:outline-none focus:ring-2 focus:ring-slate-900/5" autoFocus />
                </div>
              </div>
              {results.length > 0 && (
                <div className="max-h-60 overflow-auto">
                  {results.map(s => (
                    <button key={s.id} onClick={() => addToWatch(s)}
                      className="w-full flex items-center justify-between px-4 py-2.5 hover:bg-slate-50 text-left">
                      <span className="text-sm font-medium text-slate-900">{s.name}</span>
                      <span className="text-xs text-slate-400">{displaySymbol(s.symbol, s.market)}</span>
                    </button>
                  ))}
                </div>
              )}
              {query && results.length === 0 && (
                <div className="px-4 py-3 text-xs text-slate-400">未找到匹配的股票</div>
              )}
            </div>
          )}
        </div>
      </div>

      {items.length === 0 ? (
        <div className="py-12 text-center text-slate-500 text-sm">暂无数据</div>
      ) : (
        <div className="space-y-6">
          {groups.map(group => (
            <Card key={group.key}>
              <div className="flex items-center gap-2 px-6 pt-4 pb-2">
                <img src={group.flag} alt="" className="w-5 h-3.5 rounded-sm shadow-sm" />
                <h3 className="text-sm font-bold text-slate-700">{group.label}</h3>
                <span className="text-xs text-slate-400">{group.items.length}只</span>
              </div>
              <CardContent className="p-0">
                <div className="overflow-auto">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="border-b border-slate-100">
                        <th className="text-left text-xs font-medium text-slate-500 px-6 py-2">股票</th>
                        <th className="text-center text-xs font-medium text-slate-500 px-1 py-2 w-[68px]">1M</th>
                        <th className="text-right text-xs font-medium text-slate-500 px-3 py-2">现价</th>
                        <th className="text-right text-xs font-medium text-slate-500 px-3 py-2">今日涨跌</th>
                        <th className="text-right text-xs font-medium text-slate-500 px-3 py-2">涨跌幅</th>
                        {showRiskMetrics && <>
                          <th className="text-center text-xs font-medium text-slate-500 px-3 py-2">分位数</th>
                          <th className="text-right text-xs font-medium text-slate-500 px-3 py-2">Beta</th>
                          <th className="text-right text-xs font-medium text-slate-500 px-3 py-2">波动率</th>
                        </>}
                        <th className="text-right text-xs font-medium text-slate-500 px-3 py-2 w-10"></th>
                      </tr>
                    </thead>
                    <tbody>
                      {group.items.map((item, idx) => {
                        const valid = item.price != null && Number(item.price) !== 0
                        const chg = Number(item.changeToday ?? 0)
                        const chgPct = Number(item.changePctToday ?? 0)
                        const up = chg >= 0
                        return (
                          <tr key={`${item.stock_id}-${item.id}`}
                            draggable={managing}
                            onDragStart={() => handleDragStart(group.key, idx)}
                            onDragOver={(e) => handleDragOver(e, group.key, idx)}
                            onDrop={() => handleDrop(group, idx)}
                            onDragEnd={() => { setDragKey(null); setDragIdx(null); setDropIdx(null) }}
                            className={`border-b border-slate-50 hover:bg-slate-50/50 transition-colors ${managing ? 'cursor-grab active:cursor-grabbing' : ''} ${dropIdx === idx && dragIdx !== idx && dragKey === group.key ? 'border-t-2 border-t-slate-900' : ''}`}>
                            <td className="px-6 py-3">
                              <div className="flex items-center gap-2">
                                {managing && <GripVertical className="w-3.5 h-3.5 text-slate-300 shrink-0" />}
                                <Link to={`/stock?symbol=${encodeURIComponent(item.symbol)}`}
                                  className="font-medium text-slate-900 hover:text-blue-600">{item.name}</Link>
                              </div>
                              <div className="text-xs text-slate-400">{displaySymbol(item.symbol, item.market)}</div>
                            </td>
                            <td className="px-1 py-3 flex justify-center">
                              {sparkData[item.symbol]?.length > 0
                                ? <Sparkline data={sparkData[item.symbol]} />
                                : <div className="w-[60px] h-6 bg-slate-50 rounded" />}
                            </td>
                            <td className="px-3 py-3 text-right tabular-nums">
                              <div>{valid ? Number(item.price).toFixed(2) : '—'}</div>
                              {item.priceTimestamp && <div className="text-[10px] text-slate-400">{fmtPriceTs(item.priceTimestamp)}</div>}
                            </td>
                            <td className={`px-3 py-3 text-right font-medium tabular-nums ${valid ? (up ? positiveClass : negativeClass) : 'text-slate-400'}`}>
                              {valid ? `${up ? '+' : ''}${chg.toFixed(2)}` : '—'}
                            </td>
                            <td className={`px-3 py-3 text-right font-medium tabular-nums ${valid ? (up ? positiveClass : negativeClass) : 'text-slate-400'}`}>
                              {valid ? `${up ? '+' : ''}${chgPct.toFixed(2)}%` : '—'}
                            </td>
                            {showRiskMetrics && (() => {
                              const m = metrics[String(item.stock_id)]
                              const pct = m?.percentile_5y ?? null
                              const badgeColor = pct == null
                                ? 'bg-slate-100 text-slate-400'
                                : pct < 30 ? 'bg-blue-100 text-blue-700'
                                : pct > 70 ? 'bg-red-100 text-red-600'
                                : 'bg-slate-100 text-slate-600'
                              return <>
                                <td className="px-3 py-3 text-center">
                                  {pct != null
                                    ? <span className={`inline-block px-2 py-0.5 rounded-full text-xs font-medium ${badgeColor}`}>{pct.toFixed(0)}%</span>
                                    : <span className="text-slate-300 text-xs">—</span>}
                                </td>
                                <td className="px-3 py-3 text-right tabular-nums text-xs text-slate-700">
                                  {m?.beta_1y != null ? m.beta_1y.toFixed(2) : <span className="text-slate-300">—</span>}
                                </td>
                                <td className="px-3 py-3 text-right tabular-nums text-xs text-slate-700">
                                  {m?.volatility_1y != null ? `${m.volatility_1y.toFixed(1)}%` : <span className="text-slate-300">—</span>}
                                </td>
                              </>
                            })()}
                            <td className="px-3 py-3 text-right">
                              {managing && (
                                <button onClick={() => removeWatch(item.stock_id)}
                                  className="text-slate-400 hover:text-red-500 transition-colors"><X className="w-3.5 h-3.5" /></button>
                              )}
                            </td>
                          </tr>
                        )
                      })}
                    </tbody>
                  </table>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      )}
    </div>
  )
}
