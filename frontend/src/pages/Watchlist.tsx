import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useSettings } from '@/hooks/use-settings'
import { searchStocks } from '@/services/api'
import { displaySymbol } from '@/lib/format'
import { Card, CardContent } from '@/components/ui/card'
import type { StockSearchItem } from '@/types'
import { Search, X } from 'lucide-react'

interface WatchItem { id: number; stock_id: number; symbol: string; name: string; market: string; currency: string; price: number }

export default function Watchlist() {
  const { positiveClass, negativeClass } = useSettings()
  const [items, setItems] = useState<WatchItem[]>([])
  const [loading, setLoading] = useState(true)
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<StockSearchItem[]>([])
  const [showDropdown, setShowDropdown] = useState(false)

  function load() {
    fetch('/investory/api/watchlist', { credentials: 'include' })
      .then(r => r.json()).then(setItems)
      .finally(() => setLoading(false))
  }

  useEffect(() => { load() }, [])
  useEffect(() => { if (query.length >= 1) searchStocks(query).then(setResults) }, [query])

  async function addStock(s: StockSearchItem) {
    const form = new URLSearchParams({ stockId: s.id })
    await fetch('/investory/api/watchlist', { method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: form })
    setQuery(''); setResults([]); setShowDropdown(false); load()
  }

  async function removeStock(stockId: number) {
    await fetch(`/investory/api/watchlist/${stockId}`, { method: 'DELETE', credentials: 'include' })
    load()
  }

  if (loading) return <div className="flex items-center justify-center h-96"><div className="w-8 h-8 border-2 border-slate-300 border-t-slate-900 rounded-full animate-spin" /></div>

  return (
    <div className="p-6 space-y-6">
      <h2 className="text-xl font-bold text-slate-900 tracking-tight">自选</h2>

      {/* Search */}
      <div className="relative max-w-md">
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
        <input type="text" placeholder="搜索添加自选股票..." value={query}
          onChange={e => { setQuery(e.target.value); setShowDropdown(true) }}
          onFocus={() => results.length > 0 && setShowDropdown(true)}
          onBlur={() => setTimeout(() => setShowDropdown(false), 200)}
          className="w-full h-10 pl-10 pr-4 rounded-xl border border-slate-200 bg-white text-sm focus:outline-none focus:ring-2 focus:ring-slate-900/10" />
        {showDropdown && results.length > 0 && (
          <div className="absolute top-full mt-1 w-full bg-white rounded-xl border border-slate-200 shadow-lg overflow-hidden z-50">
            {results.map(s => (
              <button key={s.id} type="button" onClick={() => addStock(s)}
                className="w-full text-left px-4 py-2.5 hover:bg-slate-50 flex justify-between items-center">
                <span className="text-sm font-medium text-slate-900">{s.name}</span>
                <span className="text-xs text-slate-400">{displaySymbol(s.symbol, s.market)}</span>
              </button>
            ))}
          </div>
        )}
      </div>

      {/* List */}
      {items.length === 0 ? (
        <Card><CardContent className="py-12 text-center text-slate-400 text-sm">暂无自选，搜索股票添加</CardContent></Card>
      ) : (
        <div className="space-y-1">
          {items.map(item => {
            const up = Number(item.price) > 0 // We need yesterday's close to determine direction
            return (
              <div key={item.id} className="flex items-center gap-4 px-4 py-3 rounded-xl hover:bg-slate-50 transition-colors">
                <div className="flex-1 min-w-0">
                  <Link to={`/stock?symbol=${encodeURIComponent(item.symbol)}`}
                    className="text-sm font-medium text-slate-900 hover:text-blue-600 transition-colors">{item.name}</Link>
                  <span className="text-xs text-slate-400 ml-2">{displaySymbol(item.symbol, item.market)}</span>
                </div>
                <span className={`text-sm font-semibold tabular-nums ${up ? positiveClass : negativeClass}`}>
                  {item.price > 0 ? Number(item.price).toFixed(2) : '—'}
                </span>
                <button onClick={() => removeStock(item.stock_id)}
                  className="p-1 rounded-lg hover:bg-slate-200 transition-colors"><X className="w-3.5 h-3.5 text-slate-400" /></button>
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}
