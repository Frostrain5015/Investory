import { useEffect, useState, useCallback } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '@/hooks/use-auth'
import { useSettings } from '@/hooks/use-settings'
import { useTimedRefresh, timeAgo } from '@/hooks/use-timed-refresh'
import { searchStocks } from '@/services/api'
import { Card, CardContent } from '@/components/ui/card'
import { displaySymbol } from '@/lib/format'
import type { StockSearchItem } from '@/types'
import { Search, X, Plus, GripVertical } from 'lucide-react'

interface WatchItem { id: number; stock_id: number; symbol: string; name: string; market: string; currency: string; price: number; changeToday?: number; changePctToday?: number }

export default function Holdings() {
  const { portfolioId } = useAuth()
  const { positiveClass, negativeClass } = useSettings()
  const [items, setItems] = useState<WatchItem[]>([])
  const [loading, setLoading] = useState(true)
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<StockSearchItem[]>([])
  const [showAdd, setShowAdd] = useState(false)
  const [managing, setManaging] = useState(false)
  const [dragIdx, setDragIdx] = useState<number | null>(null)
  const [dropIdx, setDropIdx] = useState<number | null>(null)

  function handleDragStart(idx: number) {
    setDragIdx(idx)
  }
  function handleDragOver(e: React.DragEvent, idx: number) {
    e.preventDefault()
    setDropIdx(idx)
  }
  function handleDrop(idx: number) {
    if (dragIdx == null || dragIdx === idx) { setDragIdx(null); setDropIdx(null); return }
    const next = [...items]
    const [moved] = next.splice(dragIdx, 1)
    next.splice(idx, 0, moved)
    setItems(next)
    setDragIdx(null)
    setDropIdx(null)
    // Persist new order
    const body = next.map((item, i) => ({ id: item.id, sortOrder: i }))
    fetch('/investory/api/watchlist/reorder', {
      method: 'PUT', credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }).catch(() => {})
  }

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

  if (loading) {
    return <div className="flex items-center justify-center h-96">
      <div className="w-8 h-8 border-2 border-slate-300 border-t-slate-900 rounded-full animate-spin" />
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

      <Card>
        <CardContent className="p-0">
          {items.length === 0 ? (
            <div className="py-12 text-center text-slate-500 text-sm">暂无数据，请添加自选或录入交易</div>
          ) : (
            <div className="overflow-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-slate-100">
                    <th className="text-left text-xs font-medium text-slate-500 px-6 py-3">股票</th>
                    <th className="text-right text-xs font-medium text-slate-500 px-3 py-3">现价</th>
                    <th className="text-right text-xs font-medium text-slate-500 px-3 py-3">今日涨跌</th>
                    <th className="text-right text-xs font-medium text-slate-500 px-3 py-3">涨跌幅</th>
                    <th className="text-right text-xs font-medium text-slate-500 px-3 py-3 w-10"></th>
                  </tr>
                </thead>
                <tbody>
                  {items.map((item, idx) => {
                    const valid = item.price != null && Number(item.price) !== 0
                    const chg = Number(item.changeToday ?? 0)
                    const chgPct = Number(item.changePctToday ?? 0)
                    const up = chg >= 0
                    return (
                      <tr key={`${item.stock_id}-${item.id}`}
                        draggable={managing}
                        onDragStart={() => handleDragStart(idx)}
                        onDragOver={(e) => handleDragOver(e, idx)}
                        onDrop={() => handleDrop(idx)}
                        onDragEnd={() => { setDragIdx(null); setDropIdx(null) }}
                        className={`border-b border-slate-50 hover:bg-slate-50/50 transition-colors ${managing ? 'cursor-grab active:cursor-grabbing' : ''} ${dropIdx === idx && dragIdx !== idx ? 'border-t-2 border-t-slate-900' : ''}`}>
                        <td className="px-6 py-3">
                          <div className="flex items-center gap-2">
                            {managing && <GripVertical className="w-3.5 h-3.5 text-slate-300 shrink-0" />}
                            <Link to={`/stock?symbol=${encodeURIComponent(item.symbol)}`}
                              className="font-medium text-slate-900 hover:text-blue-600">{item.name}</Link>
                          </div>
                          <div className="text-xs text-slate-400">{displaySymbol(item.symbol, item.market)}</div>
                        </td>
                        <td className="px-3 py-3 text-right tabular-nums">{valid ? Number(item.price).toFixed(2) : '—'}</td>
                        <td className={`px-3 py-3 text-right font-medium tabular-nums ${valid ? (up ? positiveClass : negativeClass) : 'text-slate-400'}`}>
                          {valid ? `${up ? '+' : ''}${chg.toFixed(2)}` : '—'}
                        </td>
                        <td className={`px-3 py-3 text-right font-medium tabular-nums ${valid ? (up ? positiveClass : negativeClass) : 'text-slate-400'}`}>
                          {valid ? `${up ? '+' : ''}${chgPct.toFixed(2)}%` : '—'}
                        </td>
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
          )}
        </CardContent>
      </Card>
    </div>
  )
}
