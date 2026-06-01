import { useState, useCallback, useEffect, useMemo } from 'react'
import { Search, RefreshCw, ChevronDown, ChevronRight, SlidersHorizontal, X } from 'lucide-react'
import { AnimatePresence, motion } from 'framer-motion'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import MarketRegimeBadge from '@/components/MarketRegimeBadge'
import FactorRadarChart from '@/components/FactorRadarChart'
import { useToast } from '@/components/Toast'
import { useSettings } from '@/hooks/use-settings'
import {
  BASE, getFactorScores, getFactorBreakdown, getScanResults,
  getRegimeStatus, searchStocks,
} from '@/services/api'
import { displaySymbol, shortSymbol } from '@/lib/format'
import type { FactorScore, FactorBreakdown, FactorDetail, RegimeStatus, ScanResult, StockSearchItem } from '@/types'

/** Strip "1." / "0." prefix if present, keeping only the short symbol */
function normSym(sym: string): string { return shortSymbol(sym) }

type SortKey = 'buyScore' | 'sellScore' | 'totalScore' | 'symbol'

const FACTOR_GROUPS: { key: string; label: string }[] = [
  { key: 'value', label: '价值' },
  { key: 'growth', label: '成长' },
  { key: 'momentum', label: '动量' },
  { key: 'quality', label: '质量' },
  { key: 'technical', label: '技术' },
  { key: 'event', label: '事件' },
  { key: 'social', label: '情绪' },
]

const MARKETS = [
  { key: '', label: '全部' },
  { key: 'SH', label: '沪市' },
  { key: 'SZ', label: '深市' },
]

export default function Screener({ embedded }: { embedded?: boolean }) {
  const { positiveClass } = useSettings()
  const toast = useToast()
  const toastOk = (msg: string) => toast(msg, true)
  const toastErr = (msg: string) => toast(msg, false)

  const [query, setQuery] = useState('')
  const [searchResults, setSearchResults] = useState<StockSearchItem[]>([])
  const [symbols, setSymbols] = useState<string[]>([])  // 已添加的股票代码
  const [scores, setScores] = useState<Record<string, FactorScore>>({})
  const [loading, setLoading] = useState(false)
  const [regime, setRegime] = useState<RegimeStatus | null>(null)

  // Filters
  const [showFilters, setShowFilters] = useState(true)
  const [activeGroups, setActiveGroups] = useState<Set<string>>(new Set())
  const [minScore, setMinScore] = useState(0)
  const [maxScore, setMaxScore] = useState(100)
  const [marketFilter, setMarketFilter] = useState('')
  const [sortKey, setSortKey] = useState<SortKey>('totalScore')
  const [sortAsc, setSortAsc] = useState(false)

  // Expansion
  const [expanded, setExpanded] = useState<string | null>(null)
  const [breakdowns, setBreakdowns] = useState<Record<string, FactorBreakdown>>({})

  const SCAN_STRATEGIES = [
    { key: 'main', label: '多因子综合' },
    { key: 'golden_cross', label: '技术共振' },
    { key: 'hot', label: '热榜动量' },
    { key: 'chip', label: '筹码集中' },
  ]
  // Scan results tab
  const [tab, setTab] = useState<'screener' | 'picks'>('screener')
  const [scanResults, setScanResults] = useState<ScanResult[]>([])
  const [scanning, setScanning] = useState(false)
  const [scanStrategy, setScanStrategy] = useState('main')

  // Load regime on mount
  useEffect(() => {
    getRegimeStatus().then(r => {
      if (r?.regime) setRegime(r.regime)
    }).catch(() => {})
  }, [])

  // Search stocks
  const handleSearch = useCallback(async (q: string) => {
    setQuery(q)
    if (q.length < 1) { setSearchResults([]); return }
    try {
      const data = await searchStocks(q)
      setSearchResults(data || [])
    } catch { /* ignore */ }
  }, [])

  // Add symbol to list (no auto-score — scoring happens on scan button click)
  const addSymbol = useCallback((s: StockSearchItem) => {
    const sym = normSym(s.symbol)
    setSymbols(prev => prev.includes(sym) ? prev : [...prev, sym])
    setQuery('')
    setSearchResults([])
  }, [])

  const removeSymbol = useCallback((sym: string) => {
    setSymbols(prev => prev.filter(s => s !== sym))
  }, [])

  // Toggle expand — load factor breakdown on first expand
  const toggleExpand = useCallback(async (sym: string) => {
    if (expanded === sym) { setExpanded(null); return }
    setExpanded(sym)
    if (!breakdowns[sym]) {
      try {
        const bd = await getFactorBreakdown(sym)
        setBreakdowns(prev => ({ ...prev, [sym]: bd }))
      } catch {
        setBreakdowns(prev => ({ ...prev, [sym]: { error: '加载失败' } as any }))
      }
    }
  }, [expanded, breakdowns])

  // Sort
  const toggleSort = useCallback((key: SortKey) => {
    if (sortKey === key) { setSortAsc(!sortAsc) }
    else { setSortKey(key); setSortAsc(false) }
  }, [sortKey, sortAsc])

  // Filter + sort
  const filteredScores = useMemo(() => {
    let list = Object.values(scores)
    if (minScore > 0) list = list.filter(s => (s.totalScore ?? 0) >= minScore)
    if (maxScore < 100) list = list.filter(s => (s.totalScore ?? 0) <= maxScore)
    list = [...list].sort((a, b) => {
      const va = a[sortKey] ?? 0
      const vb = b[sortKey] ?? 0
      return sortAsc ? va - vb : vb - va
    })
    return list
  }, [scores, minScore, maxScore, sortKey, sortAsc])

  // Refresh scan — scan only the user's screening list for speed
  const startScan = useCallback(() => {
    if (symbols.length === 0) {
      toast('请先添加股票到筛选列表', true)
      return
    }
    setScanning(true)
    // Pass symbols to SSE endpoint so it only scans those stocks
    const url = `${BASE}/api/stocksage/refresh?symbols=${symbols.join(',')}&strategy=${scanStrategy}`
    const es = new EventSource(url, { withCredentials: true })
    es.addEventListener('progress', (e: any) => {
      try {
        const d = JSON.parse(e.data)
        toast(`扫描进度: ${d.current}/${d.total}`, true)
      } catch { /* ignore */ }
    })
    es.addEventListener('result', (e: any) => {
      try {
        const data = JSON.parse(e.data)
        if (data.scores) {
          // score_stocks response: {scores: {code: {buy_score, sell_score, total_score}}}
          const map: Record<string, FactorScore> = {}
          for (const [code, s] of Object.entries(data.scores as Record<string, any>)) {
            map[code] = {
              symbol: code,
              buyScore: s.buy_score ?? 0,
              sellScore: s.sell_score ?? 0,
              totalScore: s.total_score ?? 0,
            }
          }
          setScores(map)
          toastOk(`扫描完成: ${Object.keys(map).length} 只股票`)
        } else if (data.picks) {
          setScanResults(data.picks)
          toastOk('扫描完成')
        }
      } catch { /* ignore */ }
      setScanning(false)
      es.close()
    })
    es.onerror = () => {
      toastErr('扫描中断')
      setScanning(false)
      es.close()
    }
    setTimeout(() => { if (es.readyState !== 2) { setScanning(false); es.close() } }, 300000)
  }, [symbols, toast, toastOk, toastErr])

  const sortIcon = (key: SortKey) => {
    if (sortKey !== key) return null
    return <span className="ml-1 text-xs">{sortAsc ? '↑' : '↓'}</span>
  }

  return (
    <div className={embedded ? '' : 'p-6 space-y-6 max-w-6xl mx-auto'}>
      {/* Header — hidden when embedded in Research page */}
      {!embedded && (
        <div className="flex items-center justify-between">
          <h2 className="text-xl font-bold text-slate-900 tracking-tight">选股器</h2>
          <div className="flex items-center gap-3">
            {regime && <MarketRegimeBadge regime={regime.signal} />}
            <div className="flex bg-slate-100 rounded-lg p-0.5">
              <button onClick={() => setTab('screener')}
                className={`px-3 py-1.5 rounded-md text-xs font-medium transition-colors ${tab === 'screener' ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'}`}>
                <SlidersHorizontal className="w-3.5 h-3.5 inline mr-1" />自选筛选
              </button>
              <button onClick={() => setTab('picks')}
                className={`px-3 py-1.5 rounded-md text-xs font-medium transition-colors ${tab === 'picks' ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'}`}>
                <Search className="w-3.5 h-3.5 inline mr-1" />今日推荐
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Top bar */}
      <div className="flex items-center gap-3 flex-wrap">
        <div className="relative flex-1 min-w-[200px] max-w-md">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
          <input value={query} onChange={e => handleSearch(e.target.value)}
            placeholder="搜索股票代码或名称..."
            className="w-full pl-9 pr-4 py-2 border border-slate-200 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500/20 focus:border-blue-400" />
          <AnimatePresence>
            {searchResults.length > 0 && (
              <motion.div initial={{ opacity: 0, y: 4 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }}
                className="absolute top-full mt-1 w-full bg-white border border-slate-200 rounded-lg shadow-lg z-50 max-h-60 overflow-auto">
                {searchResults.map(r => (
                  <button key={r.symbol} onClick={() => addSymbol(r)}
                    className="w-full text-left px-3 py-2 hover:bg-slate-50 text-sm border-b border-slate-100 last:border-b-0">
                    <span className="font-medium text-slate-900">{displaySymbol(r.symbol, r.market)}</span>
                    <span className="ml-2 text-slate-600">{r.name}</span>
                    <span className="ml-2 text-xs text-slate-400">{r.market}</span>
                    {r.price > 0 && <span className="ml-2 text-xs text-blue-600">¥{r.price.toFixed(2)}</span>}
                  </button>
                ))}
              </motion.div>
            )}
          </AnimatePresence>
        </div>
        <select value={scanStrategy} onChange={e => setScanStrategy(e.target.value)}
          className="px-2 py-2 text-sm border border-slate-200 rounded-lg bg-white text-slate-600 focus:outline-none focus:ring-2 focus:ring-blue-500/20">
          {SCAN_STRATEGIES.map(s => <option key={s.key} value={s.key}>{s.label}</option>)}
        </select>
        <button onClick={startScan} disabled={scanning}
          className={`flex items-center gap-1.5 px-3 py-2 text-sm font-medium rounded-lg transition-colors ${scanning ? 'text-slate-400 bg-slate-100 cursor-not-allowed' : 'text-white bg-emerald-600 hover:bg-emerald-700'}`}>
          <Search className="w-4 h-4" />
          {scanning ? '扫描中...' : '扫描'}
        </button>
        <button onClick={() => setShowFilters(!showFilters)}
          className="flex items-center gap-1 px-3 py-2 text-sm font-medium text-slate-500 hover:text-slate-700 transition-colors">
          <SlidersHorizontal className="w-4 h-4" />
          {showFilters ? '收起' : '筛选'}
        </button>
      </div>

      {/* Active symbols chips */}
      {symbols.length > 0 && (
        <div className="flex flex-wrap gap-1.5">
          <span className="text-xs text-slate-500 py-1">{symbols.length} 只股票</span>
          {symbols.map(s => (
            <button key={s} onClick={() => removeSymbol(s)}
              className="inline-flex items-center gap-1 px-2 py-0.5 text-xs rounded-md bg-blue-50 text-blue-700 hover:bg-blue-100 transition-colors">
              {s} <X className="w-3 h-3" />
            </button>
          ))}
        </div>
      )}

      {/* Filter panel */}
      <AnimatePresence>
        {showFilters && (
          <motion.div initial={{ height: 0, opacity: 0 }} animate={{ height: 'auto', opacity: 1 }} exit={{ height: 0, opacity: 0 }}
            className="overflow-hidden">
            <div className="grid grid-cols-1 md:grid-cols-3 gap-4 p-4 bg-slate-50 rounded-xl border border-slate-100">
              {/* Factor groups */}
              <div>
                <p className="text-xs font-medium text-slate-500 mb-2">因子组</p>
                <div className="flex flex-wrap gap-1.5">
                  {FACTOR_GROUPS.map(g => (
                    <button key={g.key} onClick={() => {
                      const next = new Set(activeGroups)
                      next.has(g.key) ? next.delete(g.key) : next.add(g.key)
                      setActiveGroups(next)
                    }}
                      className={`px-2 py-0.5 rounded text-xs transition-colors ${activeGroups.has(g.key) ? 'bg-blue-100 text-blue-700' : 'bg-white text-slate-500 border border-slate-200'}`}>
                      {g.label}
                    </button>
                  ))}
                </div>
              </div>
              {/* Score range — single slider, filters minimum */}
              <div>
                <p className="text-xs font-medium text-slate-500 mb-2">最低评分: {minScore}</p>
                <input type="range" min={0} max={100} value={minScore} onChange={e => setMinScore(+e.target.value)}
                  className="w-full accent-blue-600" />
              </div>
              {/* Market filter */}
              <div>
                <p className="text-xs font-medium text-slate-500 mb-2">市场</p>
                <div className="flex gap-1.5">
                  {MARKETS.map(m => (
                    <button key={m.key} onClick={() => setMarketFilter(m.key)}
                      className={`px-2 py-0.5 rounded text-xs transition-colors ${marketFilter === m.key ? 'bg-blue-100 text-blue-700' : 'bg-white text-slate-500 border border-slate-200'}`}>
                      {m.label}
                    </button>
                  ))}
                </div>
              </div>
            </div>
          </motion.div>
        )}
      </AnimatePresence>

      {/* Results Table */}
      {tab === 'screener' ? (
        symbols.length === 0 ? (
          <Card>
            <CardContent className="py-12 text-center text-slate-500">
              <Search className="w-10 h-10 mx-auto mb-3 text-slate-300" />
              <p className="text-sm">搜索并添加股票或点击"全市场扫描"探索推荐</p>
              <p className="text-xs text-slate-400 mt-1">持仓分析请切换到"风控"标签</p>
            </CardContent>
          </Card>
        ) : loading ? (
          <div className="space-y-3">
            {[1,2,3,4,5].map(i => (
              <div key={i} className="h-12 bg-slate-100 rounded-lg animate-pulse" />
            ))}
          </div>
        ) : filteredScores.length === 0 ? (
          <Card>
            <CardContent className="py-12 text-center text-slate-500">
              <p className="text-sm">暂无评分数据，请点击"刷新评分"</p>
            </CardContent>
          </Card>
        ) : (
          <Card>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-slate-100 bg-slate-50/50">
                    <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500 w-8">#</th>
                    <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500 cursor-pointer hover:text-slate-700"
                      onClick={() => toggleSort('symbol')}>
                      代码{sortIcon('symbol')}
                    </th>
                    <th className="text-left px-4 py-2.5 text-xs font-medium text-slate-500">名称</th>
                    <th className="text-center px-4 py-2.5 text-xs font-medium text-slate-500 cursor-pointer hover:text-slate-700"
                      onClick={() => toggleSort('buyScore')}>
                      买入分{sortIcon('buyScore')}
                    </th>
                    <th className="text-center px-4 py-2.5 text-xs font-medium text-slate-500 cursor-pointer hover:text-slate-700"
                      onClick={() => toggleSort('sellScore')}>
                      卖出分{sortIcon('sellScore')}
                    </th>
                    <th className="text-center px-4 py-2.5 text-xs font-medium text-slate-500 cursor-pointer hover:text-slate-700"
                      onClick={() => toggleSort('totalScore')}>
                      综合分{sortIcon('totalScore')}
                    </th>
                    <th className="text-center px-4 py-2.5 text-xs font-medium text-slate-500">环境</th>
                    <th className="w-8" />
                  </tr>
                </thead>
                <tbody>
                  {filteredScores.map((s, i) => (
                    <TableRow key={s.symbol} rank={i + 1} score={s}
                      expanded={expanded === s.symbol}
                      breakdown={breakdowns[s.symbol]}
                      onToggle={() => toggleExpand(s.symbol)} />
                  ))}
                </tbody>
              </table>
            </div>
          </Card>
        )
      ) : (
        /* Today's Picks tab */
        <Card>
          <CardHeader>
            <CardTitle className="text-sm">今日推荐</CardTitle>
          </CardHeader>
          <CardContent>
            {scanResults.length === 0 ? (
              <div className="text-center py-8 text-slate-500 text-sm">
                <p>暂无推荐，请点击"全市场扫描"获取今日选股</p>
              </div>
            ) : (
              <div className="space-y-2">
                {scanResults.map(p => (
                  <div key={p.code} className="flex items-center gap-4 p-3 bg-slate-50 rounded-lg">
                    <span className="text-sm font-medium text-slate-900 w-16">{displaySymbol(p.code)}</span>
                    <span className="text-sm text-slate-700 flex-1">{p.name}</span>
                    <span className={`text-sm font-medium ${(p.buyScore ?? 0) >= 70 ? 'text-emerald-600' : 'text-slate-600'}`}>
                      {p.buyScore?.toFixed(0) ?? '-'}分
                    </span>
                    <div className="flex gap-1">
                      {(p.bullish || []).slice(0, 2).map((r, ri) => (
                        <span key={ri} className="text-xs px-1.5 py-0.5 bg-emerald-100 text-emerald-700 rounded">{r}</span>
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </CardContent>
        </Card>
      )}
    </div>
  )
}

// ── Table Row ────────────────────────────────────────────────────────────

function TableRow({ rank, score, expanded, breakdown, onToggle }: {
  rank: number
  score: FactorScore
  expanded: boolean
  breakdown?: FactorBreakdown | null
  onToggle: () => void
}) {
  const totalScore = score.totalScore ?? 0
  const scoreColor = totalScore >= 70 ? 'text-emerald-600' : totalScore >= 50 ? 'text-amber-600' : 'text-slate-600'
  const factors: FactorDetail[] = breakdown?.factors ?? []
  const hasError = breakdown && 'error' in breakdown

  return (
    <>
      <tr onClick={onToggle}
        className="border-b border-slate-50 hover:bg-slate-50/50 cursor-pointer transition-colors">
        <td className="px-4 py-2.5 text-xs text-slate-400">{rank}</td>
        <td className="px-4 py-2.5 font-medium text-slate-900 text-sm">{normSym(score.symbol)}</td>
        <td className="px-4 py-2.5 text-slate-600 text-sm">{score.name || '-'}</td>
        <td className="px-4 py-2.5 text-center text-sm font-medium text-emerald-600">
          {score.buyScore?.toFixed(1) ?? '-'}
        </td>
        <td className="px-4 py-2.5 text-center text-sm font-medium text-red-500">
          {score.sellScore?.toFixed(1) ?? '-'}
        </td>
        <td className={`px-4 py-2.5 text-center text-sm font-bold ${scoreColor}`}>
          {totalScore.toFixed(1)}
        </td>
        <td className="px-4 py-2.5 text-center">
          <MarketRegimeBadge regime={score.regime || 'NORMAL'} />
        </td>
        <td className="px-2 py-2.5">
          {expanded ? <ChevronDown className="w-4 h-4 text-slate-400" /> : <ChevronRight className="w-4 h-4 text-slate-400" />}
        </td>
      </tr>
      {expanded && (
        <tr className="bg-slate-50/30">
          <td colSpan={8} className="px-6 py-4">
            {!breakdown ? (
              <p className="text-sm text-slate-400">加载中...</p>
            ) : hasError ? (
              <p className="text-sm text-red-400">{(breakdown as any).error || '加载失败'}</p>
            ) : factors.length > 0 ? (
              <div className="flex gap-6">
                <FactorRadarChart factors={factors} size={180} />
                <div className="flex-1 space-y-1.5 max-h-[200px] overflow-auto">
                  {factors.map((f: any) => {
                    const buy = f.buyScore ?? f.buy_score ?? 0
                    const sell = f.sellScore ?? f.sell_score ?? 0
                    return (
                    <div key={f.name} className="flex items-center gap-2">
                      <span className="text-xs text-slate-500 w-20 truncate" title={f.description}>{f.name}</span>
                      <span className="flex-1 h-1.5 bg-slate-100 rounded-full overflow-hidden">
                        <span className="block h-full bg-emerald-400 rounded-full" style={{ width: `${Math.min(buy * 10, 100)}%` }} />
                      </span>
                      <span className="text-xs font-medium text-emerald-600 w-8 text-right">{Number(buy).toFixed(1)}</span>
                      <span className="text-xs font-medium text-red-500 w-8 text-right">{Number(sell).toFixed(1)}</span>
                    </div>
                  )})}
                </div>
              </div>
            ) : (
              <p className="text-sm text-slate-400">暂无因子明细</p>
            )}
          </td>
        </tr>
      )}
    </>
  )
}
