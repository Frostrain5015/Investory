import AsyncState from '@/components/AsyncState'
import { useAsyncResource } from '@/hooks/use-async-resource'
import QuoteNumber from '@/components/QuoteNumber'
import { useEffect, useState, useCallback, useRef, useMemo } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '@/hooks/use-auth'
import { useSettings } from '@/hooks/use-settings'
import { useTimedRefresh, timeAgo } from '@/hooks/use-timed-refresh'
import { BASE, searchStocks, chartAPI } from '@/services/api'
import { Card, CardContent } from '@/components/ui/card'
import { displaySymbol } from '@/lib/format'
import QuoteWarning from '@/components/QuoteWarning'
import Sparkline from '@/components/Sparkline'
import { useT } from '@/i18n/I18nContext'
import type { StockSearchItem, PriceData, HoldingsResponse, HoldingSnapshot, QuoteMetrics } from '@/types'
import { Search, X, Plus, GripVertical } from 'lucide-react'

interface WatchItem { id: number; stock_id: number; symbol: string; name: string; market: string; currency: string; price: number | null; changeToday?: number | null; changePctToday?: number | null; priceTimestamp?: string | null; priceLive?: boolean; priceSource?: string | null; quoteMetrics?: QuoteMetrics | null }

interface MarketGroup { key: string; label: string; flag: string; items: WatchItem[] }

function marketToGroup(market: string): string {
  if (market === 'SH' || market === 'SZ' || market === 'BJ') return 'A'
  return market
}

const GROUP_META: Record<string, { flag: string }> = {
  A:  { flag: 'https://flagcdn.com/cn.svg' },
  HK: { flag: 'https://flagcdn.com/hk.svg' },
  US: { flag: 'https://flagcdn.com/us.svg' },
}

const GROUP_KEYS = ['A', 'HK', 'US'] as const

function compactMetric(value: number, suffix = '', lang = 'zh'): string {
  if (lang === 'en') return new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 2 }).format(value) + suffix
  if (Math.abs(value) >= 100_000_000) return `${(value / 100_000_000).toFixed(2)}${lang === 'hk' ? '億' : '亿'}${suffix}`
  if (Math.abs(value) >= 10_000) return `${(value / 10_000).toFixed(2)}${lang === 'hk' ? '萬' : '万'}${suffix}`
  return `${value.toLocaleString('zh-CN', { maximumFractionDigits: 2 })}${suffix}`
}

export default function Holdings() {
  const { portfolioId } = useAuth()
  return <HoldingsContent key={portfolioId} />
}

function HoldingsContent() {
  const { portfolioId } = useAuth()
  const { positiveClass, negativeClass } = useSettings()
  const { t, lang } = useT()
  const [order, setOrder] = useState<string[]>([])
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<StockSearchItem[]>([])
  const [showAdd, setShowAdd] = useState(false)
  const [managing, setManaging] = useState(false)
  const [sparkData, setSparkData] = useState<Record<string, number[]>>({})
  const sparkCache = useRef<Record<string, number[]>>({})
  const [dragKey, setDragKey] = useState<string | null>(null)
  const [dragIdx, setDragIdx] = useState<number | null>(null)
  const [dropIdx, setDropIdx] = useState<number | null>(null)

  const holdingsLoader = useCallback(async (signal: AbortSignal): Promise<HoldingsResponse> => {
    const response = await fetch(`${BASE}/api/holdings`, { credentials: 'include', signal })
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    return response.json() as Promise<HoldingsResponse>
  }, [])
  const watchLoader = useCallback(async (signal: AbortSignal): Promise<WatchItem[]> => {
    const response = await fetch(`${BASE}/api/watchlist`, { credentials: 'include', signal })
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    return response.json() as Promise<WatchItem[]>
  }, [])
  const holdings = useAsyncResource(portfolioId ? String(portfolioId) : null, holdingsLoader)
  const watchlist = useAsyncResource(portfolioId ? String(portfolioId) : null, watchLoader)
  const reloadHoldings = holdings.reload
  const reloadWatchlist = watchlist.reload
  const load = useCallback(() => { reloadHoldings(); reloadWatchlist() }, [reloadHoldings, reloadWatchlist])
  const items = useMemo(() => {
      const held: HoldingSnapshot[] = holdings.data?.snapshots || []
      const heldById = new Map(held.map(s => [s.stockId, s]))
      const watchItems = watchlist.data || []

      const merged: WatchItem[] = []

      // Holdings first
      for (const s of held) {
        merged.push({
          id: -(s.stockId), stock_id: s.stockId, symbol: s.stockSymbol, name: s.stockName,
          market: s.market, currency: s.currency || '', price: s.nativePrice ?? s.currentPrice,
          changeToday: s.nativeChangeToday ?? null, changePctToday: s.changePctToday,
          priceLive: s.priceLive,
          priceSource: s.priceSource,
          priceTimestamp: s.priceTimestamp,
          quoteMetrics: s.quoteMetrics,
        })
      }

      // Watched but not held
      for (const w of watchItems) {
        if (!heldById.has(w.stock_id)) merged.push(w)
      }

      if (order.length) {
        const positions = new Map(order.map((symbol, index) => [symbol, index]))
        merged.sort((a,b) => (positions.get(a.symbol) ?? 100000) - (positions.get(b.symbol) ?? 100000))
      }
      return merged
  }, [holdings.data, watchlist.data, order])
  const [lastRefresh] = useTimedRefresh(load, String(portfolioId))


  useEffect(() => {
    const missing = items.filter(item => !sparkCache.current[item.symbol])
    if (missing.length === 0) return
    const controller = new AbortController()
    let cursor = 0
    const worker = async () => {
      while (cursor < missing.length && !controller.signal.aborted) {
        const item = missing[cursor++]
        try {
          const raw = await chartAPI.price(item.symbol, 30, undefined, undefined, undefined, controller.signal)
          const prices: PriceData[] = Array.isArray(raw) ? raw : raw.prices
          if (!controller.signal.aborted) {
            const values = prices.map(price => price.close)
            sparkCache.current[item.symbol] = values
            setSparkData(previous => ({ ...previous, [item.symbol]: values }))
          }
        } catch { /* Keep the quote row usable when its sparkline is unavailable. */ }
      }
    }
    void Promise.allSettled(Array.from({ length: Math.min(4, missing.length) }, worker))
    return () => controller.abort()
  }, [items])


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
    setOrder(next.map(item => item.symbol))
    setDragKey(null)
    setDragIdx(null)
    setDropIdx(null)
    const body = next.map((item, i) => ({ id: item.id, sortOrder: i }))
    fetch(`${BASE}/api/watchlist/reorder`, {
      method: 'PUT', credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }).catch(() => {})
  }


  useEffect(() => { if (query.length >= 1) searchStocks(query).then(setResults) }, [query])

  async function addToWatch(s: StockSearchItem) {
    const form = new URLSearchParams({ stockId: s.id })
    await fetch(`${BASE}/api/watchlist`, { method: 'POST', body: form, credentials: 'include' })
    setShowAdd(false)
    setQuery('')
    setResults([])
    load()
  }

  async function removeWatch(stockId: number) {
    await fetch(`${BASE}/api/watchlist/${stockId}`, { method: 'DELETE', credentials: 'include' })
    load()
  }

  const groups: MarketGroup[] = GROUP_KEYS.map(key => ({
      key,
      label: t.holdings.marketGroupLabels[key],
      flag: GROUP_META[key].flag,
      items: items.filter(item => marketToGroup(item.market) === key),
    })).filter(g => g.items.length > 0)
  const loading = holdings.loading || watchlist.loading
  const loadError = holdings.error ?? watchlist.error
  return (
    <div className="p-6 space-y-6">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <h2 className="text-xl font-bold text-slate-900 tracking-tight">{t.holdings.watchlistTitle}</h2>
          {lastRefresh && <span className="text-[10px] text-slate-400">{timeAgo(lastRefresh)}</span>}
        </div>
        <div className="relative flex items-center gap-2">
          <button onClick={() => { setManaging(!managing); setShowAdd(false) }}
            className={`h-9 px-4 rounded-xl text-xs font-medium transition-colors border ${managing ? 'bg-slate-900 text-white border-slate-900' : 'bg-white text-slate-600 border-slate-200 hover:bg-slate-50'}`}>
            {t.common.edit}
          </button>
          <button onClick={() => setShowAdd(!showAdd)}
            className="inline-flex items-center gap-1.5 h-9 px-4 rounded-xl bg-slate-900 text-white text-xs font-medium hover:bg-slate-800 transition-colors">
            <Plus className="w-3.5 h-3.5" />{t.common.add}
          </button>
          {showAdd && (
            <div className="absolute right-0 top-full mt-1 w-72 bg-white rounded-xl border border-slate-200 shadow-lg overflow-hidden z-50">
              <div className="p-2">
                <div className="relative">
                  <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-slate-400" />
                  <input type="text" placeholder={t.common.search} value={query}
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
                <div className="px-4 py-3 text-xs text-slate-400">{t.common.none}</div>
              )}
            </div>
          )}
        </div>
      </div>

      <AsyncState loading={items.length === 0 && loading} error={loadError} retry={load} />
      {items.length === 0 ? (!loading && !loadError && (
        <div className="py-12 text-center text-slate-500 text-sm">{t.holdings.noWatchlist}</div>
      )) : (
        <div className="space-y-6">
          {groups.map(group => (
            <Card key={group.key}>
              <div className="flex items-center gap-2 px-6 pt-4 pb-2">
                <img src={group.flag} alt="" className="w-5 h-3.5 rounded-sm shadow-sm" />
                <h3 className="text-sm font-bold text-slate-700">{group.label}</h3>
                <span className="text-xs text-slate-400">{group.items.length}{t.holdings.stockCountUnit}</span>
              </div>
              <CardContent className="p-0">
                {/* One scrollable table on desktop and mobile; the stock identity stays visible. */}
                <div className="overflow-x-auto overscroll-x-contain" tabIndex={0} role="region" aria-label={group.label + t.holdings.watchlistTitle}>
                  <table className="w-full min-w-[1440px] table-fixed text-sm whitespace-nowrap">
                    <colgroup>
                      {[180,88,112,100,100,100,128,128,112,112,112,112,56].map((width,index) => <col key={index} style={{ width }} />)}
                    </colgroup>
                    <thead>
                      <tr className="border-b border-slate-100">
                        <th className="sticky left-0 z-20 bg-white min-w-[180px] text-left text-xs font-medium text-slate-500 px-6 py-2">{t.holdings.stock}</th>
                        <th className="text-center text-xs font-medium text-slate-500 px-1 py-2 w-[68px]">{t.holdings.oneMonth}</th>
                        <th className="text-right text-xs font-medium text-slate-500 px-3 py-2">{t.stockDetail.price}</th>
                        <th className="text-right text-xs font-medium text-slate-500 px-3 py-2">{t.market.change}</th>
                        <th className="text-right text-xs font-medium text-slate-500 px-3 py-2">{t.market.changePct}</th>
                        {(['amplitude','volume','turnover','open','high','low','previousClose'] as const).map(column => (
                          <th key={column} className="text-right text-xs font-medium text-slate-500 px-3 py-2">{t.holdings[column]}</th>
                        ))}
                        <th className="text-right text-xs font-medium text-slate-500 px-3 py-2 w-10"></th>
                      </tr>
                    </thead>
                    <tbody>
                      {group.items.map((item, idx) => {
                        const valid = item.price != null && Number(item.price) !== 0
                        const chg = Number(item.changeToday)
                        const hasChange = item.changeToday != null && Number.isFinite(chg)
                        const chgPct = Number(item.changePctToday)
                        const hasPct = item.changePctToday != null && Number.isFinite(chgPct)
                        const up = chg >= 0
                        const pctUp = chgPct >= 0
                        const metrics = item.quoteMetrics
                        const amplitude = metrics?.previousClose != null && metrics.previousClose > 0
                          && metrics.high != null && metrics.low != null && metrics.low > 0 && metrics.high >= metrics.low
                          ? (metrics.high - metrics.low) / metrics.previousClose * 100 : null
                        return (
                          <tr key={`${item.stock_id}-${item.id}`}
                            draggable={managing}
                            onDragStart={() => handleDragStart(group.key, idx)}
                            onDragOver={(e) => handleDragOver(e, group.key, idx)}
                            onDrop={() => handleDrop(group, idx)}
                            onDragEnd={() => { setDragKey(null); setDragIdx(null); setDropIdx(null) }}
                            className={`border-b border-slate-50 hover:bg-slate-50/50 transition-colors ${managing ? 'cursor-grab active:cursor-grabbing' : ''} ${dropIdx === idx && dragIdx !== idx && dragKey === group.key ? 'border-t-2 border-t-slate-900' : ''}`}>
                            <td className="sticky left-0 z-10 bg-white px-6 py-3">
                              <div className="flex items-center gap-2">
                                {managing && <GripVertical className="w-3.5 h-3.5 text-slate-300 shrink-0" />}
                                <Link to={`/stock?symbol=${encodeURIComponent(item.symbol)}`}
                                  className="font-medium text-slate-900 hover:text-blue-600">{item.name}</Link>
                              </div>
                              <div className="text-xs text-slate-400">{displaySymbol(item.symbol, item.market)}</div>
                            </td>
                            <td className="px-1 py-3 text-center">
                              <span className="inline-flex"><Sparkline data={sparkData[item.symbol] ?? []} /></span>
                            </td>
                            <td className="px-3 py-3 text-right tabular-nums">
                              <div className="inline-flex items-center gap-1"><QuoteNumber value={valid ? Number(item.price) : null} />{valid && <QuoteWarning live={item.priceLive} timestamp={item.priceTimestamp} source={item.priceSource} />}</div>
                            </td>
                            <td className={`px-3 py-3 text-right font-medium tabular-nums ${valid && hasChange ? (up ? positiveClass : negativeClass) : 'text-slate-400'}`}>
                              <QuoteNumber value={valid && hasChange ? chg : null} signed />
                            </td>
                            <td className={`px-3 py-3 text-right font-medium tabular-nums ${valid && hasPct ? (pctUp ? positiveClass : negativeClass) : 'text-slate-400'}`}>
                              <QuoteNumber value={valid && hasPct ? chgPct : null} signed suffix="%" />
                            </td>
                            <td className="px-3 py-3 text-right tabular-nums"><QuoteNumber value={amplitude} suffix="%" /></td>
                            <td className="px-3 py-3 text-right tabular-nums"><QuoteNumber value={metrics?.volume} format={value => compactMetric(value, t.holdings.sharesUnit, lang)} /></td>
                            <td className="px-3 py-3 text-right tabular-nums"><QuoteNumber value={metrics?.turnover} format={value => compactMetric(value, '', lang)} /></td>
                            {(['open','high','low','previousClose'] as const).map(column => (
                              <td key={column} className="px-3 py-3 text-right tabular-nums"><QuoteNumber value={metrics?.[column] != null && metrics[column] > 0 ? metrics[column] : null} /></td>
                            ))}
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
