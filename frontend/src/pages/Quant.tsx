import { useEffect, useState, useRef, useCallback } from 'react'
import { getQuantData, getBacktestHistory, getBacktest, deleteBacktest, startBacktest, getBacktestStream, searchStocks, getHoldings } from '@/services/api'
import { useToast } from '@/components/Toast'
import { useSettings } from '@/hooks/use-settings'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { BarChart2, RefreshCw, FlaskConical, Play, Trash2, TrendingUp, Target, AlertTriangle, BarChart3, Activity, ChevronDown, ChevronRight } from 'lucide-react'
import { AnimatePresence, motion } from 'framer-motion'
import { displaySymbol } from '@/lib/format'
import { ResponsiveContainer, AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip } from 'recharts'
import type { ScenarioResult, PortfolioRiskSummary, ScenarioHoldingDetail, BacktestResult, BacktestMetrics, EquityPoint, TradeLogEntry, SseEvent } from '@/types'

const SCENARIO_META: Record<string, { borderColor: string; bgColor: string; benchmark: string }> = {
  crisis_2008: { borderColor: 'border-red-200',    bgColor: 'bg-red-50',    benchmark: 'S&P 500 -47%' },
  crisis_2015: { borderColor: 'border-orange-200', bgColor: 'bg-orange-50', benchmark: 'CSI300 -43%' },
  crisis_2020: { borderColor: 'border-yellow-200', bgColor: 'bg-yellow-50', benchmark: '全球 -30%' },
  crisis_2022: { borderColor: 'border-purple-200', bgColor: 'bg-purple-50', benchmark: '科技股 -50%' },
}

const INDICATORS = [
  { name: 'sma', label: 'SMA', params: [{ name: 'period', label: '周期', type: 'number' as const, default: 20 }], conditions: [{ value: 'above', label: '> SMA' }, { value: 'below', label: '< SMA' }] },
  { name: 'ema', label: 'EMA', params: [{ name: 'period', label: '周期', type: 'number' as const, default: 20 }], conditions: [{ value: 'above', label: '> EMA' }, { value: 'below', label: '< EMA' }] },
  { name: 'rsi', label: 'RSI', params: [{ name: 'period', label: '周期', type: 'number' as const, default: 14 }], conditions: [{ value: 'oversold', label: '超卖' }, { value: 'overbought', label: '超买' }] },
  { name: 'macd_histogram', label: 'MACD柱', params: [{ name: 'fast', label: '快线', type: 'number' as const, default: 12 }, { name: 'slow', label: '慢线', type: 'number' as const, default: 26 }], conditions: [{ value: 'above', label: '> 0' }, { value: 'below', label: '< 0' }] },
  { name: 'bollinger_lower', label: '布林下轨', params: [{ name: 'period', label: '周期', type: 'number' as const, default: 20 }], conditions: [{ value: 'below', label: '< 下轨' }] },
  { name: 'volume_ma', label: '成交量MA', params: [{ name: 'period', label: '周期', type: 'number' as const, default: 20 }], conditions: [{ value: 'above', label: '放量' }] },
  { name: 'kdj_k', label: 'KDJ-K', params: [{ name: 'period', label: '周期', type: 'number' as const, default: 9 }], conditions: [{ value: 'oversold', label: '超卖' }, { value: 'overbought', label: '超买' }] },
  { name: 'stop_loss', label: '止损', params: [{ name: 'pct', label: '跌幅%', type: 'number' as const, default: 8 }], conditions: [{ value: 'triggered', label: '触发止损' }] },
  { name: 'take_profit', label: '止盈', params: [{ name: 'pct', label: '涨幅%', type: 'number' as const, default: 20 }], conditions: [{ value: 'triggered', label: '触发止盈' }] },
  { name: 'trailing_stop', label: '移动止损', params: [{ name: 'pct', label: '回落%', type: 'number' as const, default: 5 }], conditions: [{ value: 'triggered', label: '触发移动止损' }] },
]

interface SseProgress { current: number; total: number; pct: number; name: string }

function todayStr() { return new Date().toISOString().slice(0, 10) }
function yearAgoStr() { const d = new Date(); d.setFullYear(d.getFullYear() - 1); return d.toISOString().slice(0, 10) }

export default function Quant() {
  const [tab, setTab] = useState<'risk' | 'backtest'>('risk')

  return (
    <div className="p-6 space-y-6 max-w-6xl mx-auto">
      <div className="flex items-center justify-between">
        <h2 className="text-xl font-bold text-slate-900 tracking-tight">量化分析</h2>
        <div className="flex bg-slate-100 rounded-lg p-0.5">
          <button onClick={() => setTab('risk')}
            className={`px-3 py-1.5 rounded-md text-xs font-medium transition-colors ${tab === 'risk' ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'}`}>
            <BarChart2 className="w-3.5 h-3.5 inline mr-1" />风险分析
          </button>
          <button onClick={() => setTab('backtest')}
            className={`px-3 py-1.5 rounded-md text-xs font-medium transition-colors ${tab === 'backtest' ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'}`}>
            <FlaskConical className="w-3.5 h-3.5 inline mr-1" />策略回测
          </button>
        </div>
      </div>

      {tab === 'risk' ? <RiskSection /> : <BacktestSection />}
    </div>
  )
}

// ── Risk Analysis Section ───────────────────────────────────────────────

function RiskSection() {
  const [scenarios, setScenarios] = useState<ScenarioResult[]>([])
  const [risk, setRisk] = useState<PortfolioRiskSummary | null>(null)
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [progress, setProgress] = useState<SseProgress | null>(null)
  const [logs, setLogs] = useState<string[]>([])
  const [doneMsg, setDoneMsg] = useState<string | null>(null)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)
  const logRef = useRef<HTMLDivElement>(null)
  const esRef = useRef<EventSource | null>(null)

  const loadData = useCallback(() => {
    setLoading(true)
    getQuantData()
      .then(d => { setScenarios(d.scenarios || []); setRisk(Object.keys(d.risk || {}).length ? (d.risk as PortfolioRiskSummary) : null) })
      .catch(() => {}).finally(() => setLoading(false))
  }, [])

  useEffect(() => { loadData() }, [loadData])
  useEffect(() => { logRef.current?.scrollIntoView({ behavior: 'smooth' }) }, [logs])

  function startRefresh() {
    setRefreshing(true); setProgress(null); setLogs([]); setDoneMsg(null); setErrorMsg(null)
    if (esRef.current) { esRef.current.close(); esRef.current = null }
    const es = new EventSource('/investory/api/quant/refresh', { withCredentials: true })
    esRef.current = es
    es.addEventListener('status', e => { const d = JSON.parse(e.data); setLogs(l => [...l, `[状态] ${d.msg}`]) })
    es.addEventListener('progress', e => { const d = JSON.parse(e.data); setProgress({ current: d.current, total: d.total, pct: d.pct, name: d.name }) })
    es.addEventListener('info', e => { const d = JSON.parse(e.data); setLogs(l => [...l, `[信息] ${d.msg}`]) })
    es.addEventListener('log', e => { const d = JSON.parse(e.data); setLogs(l => [...l, d.msg]) })
    es.addEventListener('done', e => { const d = JSON.parse(e.data); setDoneMsg(d.msg); setLogs(l => [...l, `✓ ${d.msg}`]); setRefreshing(false); es.close(); loadData() })
    es.addEventListener('error', e => {
      try { const d = JSON.parse((e as MessageEvent).data); setErrorMsg(d.msg); setLogs(l => [...l, `✗ ${d.msg}`]) } catch {}
      setRefreshing(false); es.close()
    })
    es.onerror = () => {}
  }

  return (<>
    <div className="flex items-center gap-2">
      <button onClick={startRefresh} disabled={refreshing}
        className="inline-flex items-center gap-1.5 h-9 px-4 rounded-xl bg-slate-900 text-white text-xs font-medium hover:bg-slate-800 transition-colors disabled:opacity-60">
        <RefreshCw className={`w-3.5 h-3.5 ${refreshing ? 'animate-spin' : ''}`} />{refreshing ? '分析中...' : '开始分析'}
      </button>
    </div>

    {(refreshing || doneMsg || errorMsg) && (
      <Card>
        <CardHeader><CardTitle className="text-sm flex items-center gap-2"><RefreshCw className={`w-3.5 h-3.5 ${refreshing ? 'animate-spin' : ''}`} />{refreshing ? '正在分析' : '分析完成'}</CardTitle></CardHeader>
        <CardContent>
          {progress && (<div className="mb-3"><div className="flex justify-between text-xs text-slate-500 mb-1"><span className="truncate max-w-[300px]">{progress.name}</span><span>{progress.pct.toFixed(1)}%</span></div><div className="w-full bg-slate-100 rounded-full h-2 overflow-hidden"><div className="bg-slate-900 h-full rounded-full transition-all duration-300" style={{ width: `${progress.pct}%` }} /></div></div>)}
          <div className="bg-slate-900 rounded-xl p-4 max-h-80 overflow-auto font-mono text-xs">
            {logs.map((line, i) => (<div key={i} className={`py-0.5 ${line.startsWith('✓') ? 'text-emerald-400' : line.startsWith('✗') ? 'text-red-400' : line.startsWith('[状态]') ? 'text-sky-400' : 'text-slate-300'}`}>{line}</div>))}
            <div ref={logRef} />
          </div>
          {doneMsg && <div className="mt-3 text-sm text-emerald-600 font-medium">{doneMsg}</div>}
          {errorMsg && <div className="mt-3 text-sm text-red-500 font-medium">{errorMsg}</div>}
        </CardContent>
      </Card>
    )}

    <div>
      <h3 className="text-sm font-bold text-slate-700 mb-3">历史危机压测</h3>
      {!loading && scenarios.length === 0 ? (
        <Card><CardContent className="py-12 text-center"><BarChart2 className="w-8 h-8 text-slate-300 mx-auto mb-2" /><p className="text-sm text-slate-500">暂无数据</p></CardContent></Card>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {scenarios.map(s => {
            const meta = SCENARIO_META[s.scenario_key] || { borderColor: 'border-slate-200', bgColor: 'bg-slate-50', benchmark: '' }
            const pnl = s.total_pnl_pct != null ? Number(s.total_pnl_pct) : null
            let holdings: ScenarioHoldingDetail[] = []
            try { const arr = JSON.parse(s.detail_json || '[]'); holdings = (Array.isArray(arr) ? arr : []).sort((a: any, b: any) => a.returnPct - b.returnPct).slice(0, 5) } catch {}
            const pnlColor = pnl == null ? 'text-slate-400' : pnl >= 0 ? 'text-red-600' : 'text-emerald-600'
            return (
              <Card key={s.scenario_key} className={`border-l-4 ${meta.borderColor}`}>
                <CardHeader className={meta.bgColor}>
                  <CardTitle className="text-sm">{s.scenario_name}</CardTitle>
                  <p className="text-xs text-slate-500">{s.start_date} ~ {s.end_date}</p>
                  {meta.benchmark && <p className="text-[10px] text-slate-400">参考跌幅：{meta.benchmark}</p>}
                </CardHeader>
                <CardContent className="pt-4">
                  <p className={`text-2xl font-bold ${pnlColor}`}>{pnl != null ? `${pnl >= 0 ? '+' : ''}${pnl.toFixed(2)}%` : '—'}</p>
                  {holdings.length > 0 && (<div className="mt-3 space-y-1"><p className="text-[10px] text-slate-400 mb-1">最差持仓</p>{holdings.map((h, i) => { const rp = h.returnPct ?? 0; return (<div key={i} className="flex justify-between text-xs"><span className="text-slate-600">{h.stockName}</span><span className={rp >= 0 ? 'text-red-500' : 'text-emerald-500'}>{rp >= 0 ? '+' : ''}{rp.toFixed(1)}%</span></div>) })}</div>)}
                </CardContent>
              </Card>
            )
          })}
        </div>
      )}
    </div>

    {(risk || loading) && (
      <Card>
        <CardHeader><CardTitle className="text-sm">组合风险汇总</CardTitle></CardHeader>
        <CardContent>
          {loading ? <div className="h-16 flex flex-col items-center justify-center gap-2"><div className="w-5 h-5 border-2 border-slate-300 border-t-slate-900 rounded-full animate-spin" /><span className="text-xs text-slate-400">正在加载量化数据...</span></div>
          : risk && (
            <div className="grid grid-cols-3 gap-4 text-center">
              <div><p className="text-xs text-slate-500">加权 Beta</p><p className="text-2xl font-bold text-slate-900 mt-1">{risk.weighted_beta != null ? Number(risk.weighted_beta).toFixed(2) : '—'}</p></div>
              <div><p className="text-xs text-slate-500">VaR 95% (日)</p><p className={`text-2xl font-bold mt-1 ${risk.var_95_pct != null ? 'text-red-500' : 'text-slate-400'}`}>{risk.var_95_pct != null ? `${Number(risk.var_95_pct).toFixed(2)}%` : '—'}</p></div>
              <div><p className="text-xs text-slate-500">1Y 最大回撤</p><p className={`text-2xl font-bold mt-1 ${risk.portfolio_maxdd != null ? 'text-red-500' : 'text-slate-400'}`}>{risk.portfolio_maxdd != null ? `${Number(risk.portfolio_maxdd).toFixed(2)}%` : '—'}</p></div>
              {risk.computed_at && <div className="col-span-3 text-right text-[10px] text-slate-400 mt-2">计算于 {new Date(risk.computed_at).toLocaleString('zh-CN')}</div>}
            </div>
          )}
        </CardContent>
      </Card>
    )}
  </>)
}

// ── Backtest Section ────────────────────────────────────────────────────

function BacktestSection() {
  const toast = useToast()
  const { positiveClass, negativeClass, positiveHex, negativeHex } = useSettings()
  const [results, setResults] = useState<BacktestResult[]>([])
  const [loading, setLoading] = useState(true)
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [equityCurve, setEquityCurve] = useState<EquityPoint[] | null>(null)
  const [metrics, setMetrics] = useState<BacktestMetrics | null>(null)
  const [tradeLog, setTradeLog] = useState<TradeLogEntry[] | null>(null)

  // Builder
  const [showBuilder, setShowBuilder] = useState(false)
  const [strategyType, setStrategyType] = useState<'simple' | 'advanced'>('simple')
  const [strategyName, setStrategyName] = useState('')
  const [stockInput, setStockInput] = useState('')
  const [stockSearchResults, setStockSearchResults] = useState<any[]>([])
  const [selectedStocks, setSelectedStocks] = useState<{ symbol: string; name: string }[]>([])
  const [entryLogic, setEntryLogic] = useState<'all' | 'any'>('all')
  const [entryRules, setEntryRules] = useState<any[]>([])
  const [exitRules, setExitRules] = useState<any[]>([])
  const [startDate, setStartDate] = useState(yearAgoStr())
  const [baseCurrency, setBaseCurrency] = useState('CNY')
  const [endDate, setEndDate] = useState(todayStr())
  const [initialCapital, setInitialCapital] = useState('100000')
  const [advancedCode, setAdvancedCode] = useState(`def decide(ctx):
    if not ctx['has_position']:
        return {'action': 'BUY', 'quantity': 100}
    return {'action': 'HOLD', 'quantity': 0}`)

  // SSE
  const [running, setRunning] = useState(false)
  const [progress, setProgress] = useState<SseProgress | null>(null)
  const [sseLogs, setSseLogs] = useState<string[]>([])
  const [doneMsg, setDoneMsg] = useState<string | null>(null)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)
  const esRef = useRef<EventSource | null>(null)
  const logRef = useRef<HTMLDivElement>(null)

  // Detail toggles
  const [showTrades, setShowTrades] = useState(false)
  const [showEquity, setShowEquity] = useState(true)

  useEffect(() => { logRef.current?.scrollIntoView({ behavior: 'smooth' }) }, [sseLogs])

  const loadHistory = useCallback(() => {
    getBacktestHistory().then(setResults).catch(() => {}).finally(() => setLoading(false))
  }, [])

  useEffect(() => { loadHistory() }, [loadHistory])

  // Reconnect on mount
  useEffect(() => {
    fetch('/investory/api/backtest/status', { credentials: 'include' })
      .then(r => r.json())
      .then((data: any) => { if (data.active) { setRunning(true); if (data.progress) setProgress(data.progress); if (data.recentLogs) setSseLogs(data.recentLogs); reconnectSSE() } })
      .catch(() => {})
  }, [])

  function reconnectSSE() {
    if (esRef.current) { esRef.current.close(); esRef.current = null }
    getBacktestStream().then(resp => {
      if (!resp.ok) return
      const es = new EventSource(`${window.location.origin}/investory/api/backtest/stream`)
      esRef.current = es; wireSSE(es)
    }).catch(() => {})
  }

  function wireSSE(es: EventSource) {
    es.addEventListener('status', e => { const d: SseEvent = JSON.parse(e.data); setSseLogs(l => [...l, `[状态] ${d.msg}`]) })
    es.addEventListener('progress', e => { const d: SseEvent = JSON.parse(e.data); setProgress({ current: d.current!, total: d.total!, pct: d.pct!, name: d.name! }) })
    es.addEventListener('info', e => { const d: SseEvent = JSON.parse(e.data); setSseLogs(l => [...l, `[信息] ${d.msg}`]) })
    es.addEventListener('log', e => { const d: SseEvent = JSON.parse(e.data); setSseLogs(l => [...l, d.msg!]) })
    es.addEventListener('done', e => { const d: SseEvent = JSON.parse(e.data); setDoneMsg(d.msg!); setSseLogs(l => [...l, `✓ ${d.msg}`]); setRunning(false); es.close(); loadHistory(); if (d.resultId) selectResult(Number(d.resultId)) })
    es.addEventListener('error', e => { const raw = (e as MessageEvent).data; if (raw) { try { const d: SseEvent = JSON.parse(raw); setErrorMsg(d.msg!); setSseLogs(l => [...l, `✗ ${d.msg}`]) } catch {} } setRunning(false); es.close() })
    es.onerror = () => {}
  }

  async function handleStart() {
    const manualStocks = stockInput.split(/[,;\s]+/).filter(Boolean)
    const stocks = [...selectedStocks.map(s => s.symbol), ...manualStocks]
    if (stocks.length === 0) { toast('请至少输入一只股票', false); return }
    const strategy = strategyType === 'simple'
      ? { stocks, entry: { logic: entryLogic, rules: entryRules }, exit: { rules: exitRules }, positionSizing: { method: 'equal_weight', value: 10 } }
      : { stocks, code: advancedCode }
    const config = { startDate, endDate, initialCapital: Number(initialCapital), baseCurrency, commissionPct: 0.0003, slippagePct: 0.001 }
    setRunning(true); setProgress(null); setSseLogs([]); setDoneMsg(null); setErrorMsg(null)
    const resp = await startBacktest({ name: strategyName || `${strategyType === 'simple' ? '简单' : '高级'}策略`, strategyType, strategy, config })
    if (!resp.ok) { setErrorMsg(`HTTP ${resp.status}`); setRunning(false); return }
    if (esRef.current) { esRef.current.close(); esRef.current = null }
    const es = new EventSource(`${window.location.origin}/investory/api/backtest/stream`)
    esRef.current = es; wireSSE(es)
  }

  function selectResult(id: number) {
    setSelectedId(id)
    getBacktest(id).then(r => {
      try { setEquityCurve(JSON.parse(r.equity_curve_json)) } catch { setEquityCurve(null) }
      try { setMetrics(JSON.parse(r.metrics_json)) } catch { setMetrics(null) }
      try { setTradeLog(JSON.parse(r.trade_log_json)) } catch { setTradeLog(null) }
      setShowBuilder(false)
    }).catch(() => {})
  }

  async function handleDelete(id: number) {
    if (!confirm('确认删除？')) return
    await deleteBacktest(id)
    if (selectedId === id) { setSelectedId(null); setEquityCurve(null); setMetrics(null); setTradeLog(null) }
    loadHistory()
  }

  function addRule(target: 'entry' | 'exit') {
    const rule = { indicator: 'sma', params: { period: 20 }, condition: 'above', threshold: 0 }
    if (target === 'entry') setEntryRules([...entryRules, rule]); else setExitRules([...exitRules, rule])
  }

  return (<>
    <div className="flex items-center gap-2">
      <button onClick={() => setShowBuilder(!showBuilder)}
        className={`inline-flex items-center gap-1.5 h-9 px-4 rounded-xl text-xs font-medium transition-colors ${showBuilder ? 'bg-slate-200 text-slate-700' : 'bg-slate-900 text-white hover:bg-slate-800'}`}>
        <FlaskConical className="w-3.5 h-3.5" />{showBuilder ? '关闭' : '新建回测'}
      </button>
    </div>

    {/* Builder */}
    <AnimatePresence>
      {showBuilder && (
        <motion.div initial={{ opacity: 0, height: 0 }} animate={{ opacity: 1, height: 'auto' }} exit={{ opacity: 0, height: 0 }} transition={{ duration: 0.2 }}>
          <Card>
            <CardHeader><CardTitle className="text-sm">策略配置</CardTitle></CardHeader>
            <CardContent className="space-y-4">
              <div className="flex items-center gap-4">
                <div className="flex bg-slate-100 rounded-lg p-0.5">
                  {(['simple', 'advanced'] as const).map(t => (
                    <button key={t} onClick={() => setStrategyType(t)} className={`px-3 py-1.5 rounded-md text-xs font-medium transition-colors ${strategyType === t ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'}`}>
                      {t === 'simple' ? '简单模式' : '高级模式'}
                    </button>
                  ))}
                </div>
                <input type="text" value={strategyName} onChange={e => setStrategyName(e.target.value)} placeholder="策略名称（可选）" className="h-8 px-3 rounded-lg border border-slate-200 text-xs flex-1 max-w-xs focus:outline-none focus:ring-2 focus:ring-slate-900/5" />
              </div>

              <div>
                <div className="flex items-center justify-between mb-1">
                  <label className="text-xs font-medium text-slate-600">测试股票</label>
                  <button type="button" onClick={async () => {
                    try {
                      const data = await getHoldings()
                      const snaps = (data as any).snapshots || []
                      for (const s of snaps) {
                        const sym = s.stockSymbol?.includes('.') ? s.stockSymbol : `${s.stockSymbol}.${s.market}`
                        if (!selectedStocks.find(x => x.symbol === sym)) {
                          setSelectedStocks(prev => [...prev, { symbol: sym, name: s.stockName || sym }])
                        }
                      }
                    } catch {}
                  }}
                    className="text-[10px] text-blue-600 hover:text-blue-800">导入持仓</button>
                </div>
                {selectedStocks.length > 0 && (
                  <div className="flex flex-wrap gap-1 mb-2">
                    {selectedStocks.map(s => (
                      <span key={s.symbol} className="inline-flex items-center gap-1 px-2 py-0.5 rounded bg-blue-50 text-xs text-blue-700">
                        {s.name} ({s.symbol})
                        <button onClick={() => setSelectedStocks(selectedStocks.filter(x => x.symbol !== s.symbol))} className="text-blue-400 hover:text-red-500">×</button>
                      </span>
                    ))}
                  </div>
                )}
                <div className="relative">
                  <input type="text" value={stockInput}
                    onChange={e => { setStockInput(e.target.value); if (e.target.value.length >= 1) searchStocks(e.target.value).then(setStockSearchResults).catch(() => {}) }}
                    onFocus={() => { if (stockInput.length >= 1) searchStocks(stockInput).then(setStockSearchResults).catch(() => {}) }}
                    onBlur={() => setTimeout(() => setStockSearchResults([]), 200)}
                    placeholder="搜索股票代码或名称..."
                    className="w-full h-8 px-3 rounded-lg border border-slate-200 text-xs focus:outline-none focus:ring-2 focus:ring-slate-900/5" />
                  {stockSearchResults.length > 0 && (
                    <div className="absolute left-0 right-0 top-full mt-1 bg-white border border-slate-200 rounded-lg shadow-lg z-50 max-h-48 overflow-auto">
                      {stockSearchResults.map(r => (
                        <button key={r.id} type="button"
                          onMouseDown={e => e.preventDefault()}
                          onClick={() => {
                            const stockSym = displaySymbol(r.symbol, r.market)
                          if (!selectedStocks.find(s => s.symbol === stockSym)) {
                              setSelectedStocks([...selectedStocks, { symbol: stockSym, name: r.name }])
                            }
                            setStockInput('')
                            setStockSearchResults([])
                          }}
                          className="w-full flex items-center justify-between px-3 py-2 hover:bg-slate-50 text-left text-xs">
                          <span className="font-medium text-slate-700">{r.name}</span>
                          <span className="text-slate-400">{displaySymbol(r.symbol, r.market)}</span>
                        </button>
                      ))}
                    </div>
                  )}
                  <p className="text-[10px] text-slate-400 mt-1">搜索并点击添加，或直接输入代码逗号分隔</p>
                </div>
              </div>

              {strategyType === 'simple' ? (<>
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <div className="flex items-center justify-between mb-2">
                      <label className="text-xs font-medium text-slate-600">入场规则</label>
                      <div className="flex items-center gap-2">
                        <select value={entryLogic} onChange={e => setEntryLogic(e.target.value as 'all' | 'any')} className="h-6 px-1.5 rounded text-xs border border-slate-200"><option value="all">全部满足</option><option value="any">任一满足</option></select>
                        <button onClick={() => addRule('entry')} className="text-xs text-blue-600 hover:text-blue-800">+ 添加</button>
                      </div>
                    </div>
                    {entryRules.map((rule, i) => <RuleEditor key={i} rule={rule} onChange={r => { const e = [...entryRules]; e[i] = r; setEntryRules(e) }} onRemove={() => setEntryRules(entryRules.filter((_, j) => j !== i))} />)}
                  </div>
                  <div>
                    <div className="flex items-center justify-between mb-2">
                      <label className="text-xs font-medium text-slate-600">离场规则</label>
                      <button onClick={() => addRule('exit')} className="text-xs text-blue-600 hover:text-blue-800">+ 添加</button>
                    </div>
                    {exitRules.map((rule, i) => <RuleEditor key={i} rule={rule} onChange={r => { const e = [...exitRules]; e[i] = r; setExitRules(e) }} onRemove={() => setExitRules(exitRules.filter((_, j) => j !== i))} />)}
                  </div>
                </div>
              </>) : (
                <div>
                  <label className="text-xs font-medium text-slate-600 mb-1 block">Python 策略代码</label>
                  <textarea value={advancedCode} onChange={e => setAdvancedCode(e.target.value)} rows={10} spellCheck={false}
                    className="w-full font-mono text-xs p-3 rounded-lg border border-slate-200 bg-slate-900 text-green-400 focus:outline-none focus:ring-2 focus:ring-slate-900/5" />
                  <p className="text-[10px] text-slate-400 mt-1">定义 <code className="bg-slate-100 px-1 rounded">def decide(ctx)</code> 函数，返回 <code className="bg-slate-100 px-1 rounded">{`{'action': 'BUY'|'SELL'|'HOLD', 'quantity': int}`}</code></p>
                </div>
              )}

              <div className="grid grid-cols-5 gap-3">
                <div><label className="text-[10px] text-slate-500">起始日期</label><input type="date" value={startDate} onChange={e => setStartDate(e.target.value)} className="w-full h-8 px-2 rounded-lg border border-slate-200 text-xs" /></div>
                <div><label className="text-[10px] text-slate-500">结束日期</label><input type="date" value={endDate} onChange={e => setEndDate(e.target.value)} className="w-full h-8 px-2 rounded-lg border border-slate-200 text-xs" /></div>
                <div><label className="text-[10px] text-slate-500">初始资金</label><input type="number" value={initialCapital} onChange={e => setInitialCapital(e.target.value)} className="w-full h-8 px-2 rounded-lg border border-slate-200 text-xs" /></div>
                <div><label className="text-[10px] text-slate-500">基准货币</label><select value={baseCurrency} onChange={e => setBaseCurrency(e.target.value)} className="w-full h-8 px-1 rounded-lg border border-slate-200 text-xs"><option value="CNY">CNY 人民币</option><option value="HKD">HKD 港币</option><option value="USD">USD 美元</option></select></div>
                <div className="flex items-end">
                  <button onClick={handleStart} disabled={running} className="w-full h-8 rounded-lg bg-slate-900 text-white text-xs font-medium hover:bg-slate-800 disabled:opacity-40 inline-flex items-center justify-center gap-1"><Play className="w-3 h-3" />开始回测</button>
                </div>
              </div>
            </CardContent>
          </Card>
        </motion.div>
      )}
    </AnimatePresence>

    {/* SSE Progress */}
    <AnimatePresence>
      {running && (
        <motion.div initial={{ opacity: 0, y: -12 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -12 }}>
          <Card>
            <CardHeader><CardTitle className="text-sm flex items-center gap-2"><Activity className="w-3.5 h-3.5 animate-spin" />正在回测</CardTitle></CardHeader>
            <CardContent>
              {progress && (<div className="mb-3"><div className="flex justify-between text-xs text-slate-500 mb-1"><span className="truncate">{progress.name}</span><span>{progress.current}/{progress.total} ({progress.pct.toFixed(1)}%)</span></div><div className="w-full bg-slate-100 rounded-full h-2 overflow-hidden"><div className="bg-slate-900 h-full rounded-full transition-all duration-300" style={{ width: `${progress.pct}%` }} /></div></div>)}
              <div className="bg-slate-900 rounded-xl p-4 max-h-60 overflow-auto font-mono text-xs">
                {sseLogs.map((line, i) => (<div key={i} className={`py-0.5 ${line.startsWith('✓') ? 'text-emerald-400' : line.startsWith('✗') ? 'text-red-400' : line.startsWith('[状态]') ? 'text-sky-400' : 'text-slate-300'}`}>{line}</div>))}
                <div ref={logRef} />
              </div>
              {doneMsg && <div className="mt-3 text-sm text-emerald-600 font-medium">{doneMsg}</div>}
              {errorMsg && <div className="mt-3 text-sm text-red-500 font-medium">{errorMsg}</div>}
            </CardContent>
          </Card>
        </motion.div>
      )}
    </AnimatePresence>

    {/* Results */}
    {metrics && (
      <div className="space-y-4">
        <div className="grid grid-cols-2 lg:grid-cols-5 gap-3">
          {metrics && (<>
          <MetricCard label="总收益" value={metrics.totalReturnPct != null ? `${metrics.totalReturnPct >= 0 ? '+' : ''}${metrics.totalReturnPct}%` : '—'} color={metrics.totalReturnPct != null ? (metrics.totalReturnPct >= 0 ? positiveClass : negativeClass) : 'text-slate-400'} icon={<TrendingUp className="w-3.5 h-3.5" />} />
          <MetricCard label="年化收益" value={metrics.annualReturnPct != null ? `${metrics.annualReturnPct >= 0 ? '+' : ''}${metrics.annualReturnPct}%` : '—'} color={metrics.annualReturnPct != null ? (metrics.annualReturnPct >= 0 ? positiveClass : negativeClass) : 'text-slate-400'} icon={<Target className="w-3.5 h-3.5" />} />
          <MetricCard label="Sharpe" value={metrics.sharpeRatio != null ? `${metrics.sharpeRatio}` : '—'} color="text-slate-900" icon={<BarChart3 className="w-3.5 h-3.5" />} />
          <MetricCard label="最大回撤" value={metrics.maxDrawdownPct != null ? `${metrics.maxDrawdownPct}%` : '—'} color={negativeClass} icon={<AlertTriangle className="w-3.5 h-3.5" />} />
          <MetricCard label="胜率" value={metrics.winRatePct != null ? `${metrics.winRatePct}%` : '—'} color={metrics.winRatePct != null ? (metrics.winRatePct >= 50 ? positiveClass : negativeClass) : 'text-slate-900'} icon={<Activity className="w-3.5 h-3.5" />} />
          <MetricCard label="盈亏比" value={metrics.profitFactor != null ? `${metrics.profitFactor}` : '—'} color={metrics.profitFactor != null ? (metrics.profitFactor >= 1 ? positiveClass : negativeClass) : 'text-slate-900'} />
          <MetricCard label="交易次数" value={metrics.totalTrades != null ? `${metrics.totalTrades}` : '—'} color="text-slate-900" />
          <MetricCard label="平均盈利" value={metrics.avgProfitPct != null ? `${metrics.avgProfitPct}%` : '—'} color={positiveClass} />
          <MetricCard label="平均亏损" value={metrics.avgLossPct != null ? `${metrics.avgLossPct}%` : '—'} color={negativeClass} />
          </>)}
        </div>

        {equityCurve && equityCurve.length > 1 && (
          <Card>
            <CardHeader className="flex-row items-center justify-between"><CardTitle className="text-sm flex items-center gap-2"><button onClick={() => setShowEquity(!showEquity)} className="hover:text-blue-600">{showEquity ? <ChevronDown className="w-3.5 h-3.5" /> : <ChevronRight className="w-3.5 h-3.5" />}</button>权益曲线</CardTitle></CardHeader>
            {showEquity && <CardContent>
              <div className="flex items-center gap-3 mb-3 text-xs">
                <span className="inline-flex items-center gap-1"><span className="w-2.5 h-2.5 rounded-full" style={{ backgroundColor: positiveHex }} />买入(B)</span>
                <span className="inline-flex items-center gap-1"><span className="w-2.5 h-2.5 rounded-full" style={{ backgroundColor: negativeHex }} />卖出(S)</span>
                <span className="text-slate-400">|</span>
                <span className="text-slate-400">初始权益 {Number(equityCurve[0]?.equity).toLocaleString()}</span>
              </div>
              <ResponsiveContainer width="100%" height={280}>
                <AreaChart data={equityCurve}>
                  <defs>
                    <linearGradient id="colorEquity" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%" stopColor="#2563eb" stopOpacity={0.12} />
                      <stop offset="95%" stopColor="#2563eb" stopOpacity={0} />
                    </linearGradient>
                  </defs>
                  <CartesianGrid strokeDasharray="3 3" stroke="#f1f5f9" />
                  <XAxis dataKey="date" tick={{ fontSize: 11, fill: '#94a3b8' }} tickFormatter={(v: string) => v.slice(5)} />
                  <YAxis tick={{ fontSize: 11, fill: '#94a3b8' }} domain={['auto', 'auto']} tickFormatter={(v: number) => `${(v / 10000).toFixed(0)}万`} />
                  <Tooltip content={({ active, payload, label }) => {
                    if (!active || !payload?.[0]) return null
                    const v = payload[0].value
                    const trade = tradeLog?.find(t => t.date === label)
                    return (
                      <div className="bg-white border border-slate-200 rounded-lg p-2 shadow text-xs">
                        <div className="text-slate-500 mb-1">日期: {label}</div>
                        <div className="font-medium">权益: {Number(v).toLocaleString()}</div>
                        {trade && <div className={`mt-1 ${trade.action === 'BUY' ? positiveClass : negativeClass}`}>{trade.action === 'BUY' ? '买入' : '卖出'} {trade.symbol} {trade.quantity}股 @ {trade.price?.toFixed(2)}</div>}
                      </div>
                    )
                  }} />
                  <Area type="monotone" dataKey="equity" stroke="#2563eb" fill="url(#colorEquity)" strokeWidth={2}
                    dot={(props: any) => {
                      const { cx, cy, index } = props
                      const date = equityCurve[index]?.date
                      const trade = tradeLog?.find(t => t.date === date)
                      if (!trade) return <circle cx={cx} cy={cy} r={0} fill="none" />
                      const isBuy = trade.action === 'BUY'
                      return <circle cx={cx} cy={cy} r={4} fill={isBuy ? positiveHex : negativeHex} stroke="white" strokeWidth={1.5} />
                    }}
                  />
                </AreaChart>
              </ResponsiveContainer>
            </CardContent>}
          </Card>
        )}

        {tradeLog && tradeLog.length > 0 && (
          <Card>
            <CardHeader className="flex-row items-center justify-between"><CardTitle className="text-sm flex items-center gap-2"><button onClick={() => setShowTrades(!showTrades)} className="hover:text-blue-600">{showTrades ? <ChevronDown className="w-3.5 h-3.5" /> : <ChevronRight className="w-3.5 h-3.5" />}</button>交易日志 ({tradeLog.length} 笔)</CardTitle></CardHeader>
            {showTrades && <CardContent className="p-0"><div className="overflow-auto max-h-96"><table className="w-full text-xs"><thead><tr className="border-b border-slate-100"><th className="text-left font-medium text-slate-500 px-4 py-2">日期</th><th className="text-left font-medium text-slate-500 px-3 py-2">股票</th><th className="text-center font-medium text-slate-500 px-3 py-2 w-12">操作</th><th className="text-right font-medium text-slate-500 px-3 py-2">数量</th><th className="text-right font-medium text-slate-500 px-3 py-2">价格</th><th className="text-right font-medium text-slate-500 px-3 py-2">盈亏</th><th className="text-left font-medium text-slate-500 px-4 py-2">原因</th></tr></thead><tbody>{tradeLog.map((t, i) => (<tr key={i} className="border-b border-slate-50 hover:bg-slate-50/50"><td className="px-4 py-2 text-slate-500">{t.date}</td><td className="px-3 py-2 font-medium text-slate-700">{t.symbol}</td><td className="px-3 py-2 text-center"><span className={`inline-flex px-1.5 py-0.5 rounded text-[10px] font-medium ${t.action === 'BUY' ? 'bg-red-50 text-red-600' : 'bg-emerald-50 text-emerald-600'}`}>{t.action === 'BUY' ? '买' : '卖'}</span></td><td className="px-3 py-2 text-right tabular-nums">{t.quantity}</td><td className="px-3 py-2 text-right tabular-nums">{t.price.toFixed(2)}</td><td className={`px-3 py-2 text-right tabular-nums font-medium ${t.pnl == null ? 'text-slate-400' : t.pnl >= 0 ? 'text-red-600' : 'text-emerald-600'}`}>{t.pnl != null ? `${t.pnl >= 0 ? '+' : ''}${t.pnl.toFixed(2)}` : '—'}</td><td className="px-4 py-2 text-slate-400">{t.reason}</td></tr>))}</tbody></table></div></CardContent>}
          </Card>
        )}
      </div>
    )}

    {/* History */}
    {loading ? (
      <div className="flex items-center justify-center h-32"><div className="w-6 h-6 border-2 border-slate-300 border-t-slate-900 rounded-full animate-spin" /></div>
    ) : results.length > 0 ? (
      <Card>
        <CardHeader><CardTitle className="text-sm flex items-center gap-2"><RefreshCw className="w-3.5 h-3.5" />回测历史</CardTitle></CardHeader>
        <CardContent className="p-0"><table className="w-full text-xs"><thead><tr className="border-b border-slate-100"><th className="text-left font-medium text-slate-500 px-4 py-2">名称</th><th className="text-left font-medium text-slate-500 px-3 py-2">类型</th><th className="text-left font-medium text-slate-500 px-3 py-2">区间</th><th className="text-left font-medium text-slate-500 px-3 py-2">时间</th><th className="text-right font-medium text-slate-500 px-4 py-2"></th></tr></thead><tbody>{results.map(r => (<tr key={r.id} className={`border-b border-slate-50 hover:bg-slate-50/50 cursor-pointer ${r.id === selectedId ? 'bg-blue-50/50' : ''}`} onClick={() => selectResult(r.id)}><td className="px-4 py-2 font-medium text-slate-700">{r.name}</td><td className="px-3 py-2"><span className="inline-flex px-1.5 py-0.5 rounded text-[10px] font-medium bg-slate-100 text-slate-600">{r.strategy_type === 'advanced' ? '高级' : '简单'}</span></td><td className="px-3 py-2 text-slate-400">{r.start_date} ~ {r.end_date}</td><td className="px-3 py-2 text-slate-400">{r.created_at?.slice(0, 10)}</td><td className="px-4 py-2 text-right"><button onClick={e => { e.stopPropagation(); handleDelete(r.id) }} className="text-slate-400 hover:text-red-500"><Trash2 className="w-3 h-3" /></button></td></tr>))}</tbody></table></CardContent>
      </Card>
    ) : (
      <div className="text-center py-12 text-slate-400 text-sm">暂无回测记录，点击"新建回测"开始</div>
    )}
  </>)
}

// ── Shared components ───────────────────────────────────────────────────

function MetricCard({ label, value, color, icon }: { label: string; value: string; color: string; icon?: React.ReactNode }) {
  return <Card><CardContent className="pt-4 pb-3"><p className="text-[10px] text-slate-500 font-medium flex items-center gap-1">{icon}{label}</p><p className={`text-lg font-bold mt-1 tabular-nums ${color}`}>{value}</p></CardContent></Card>
}

function RuleEditor({ rule, onChange, onRemove }: { rule: any; onChange: (r: any) => void; onRemove: () => void }) {
  const indicator = INDICATORS.find(ind => ind.name === rule.indicator) || INDICATORS[0]
  return (
    <div className="flex items-center gap-1.5 mb-1.5 bg-slate-50 rounded-lg p-2">
      <select value={rule.indicator} onChange={e => onChange({ ...rule, indicator: e.target.value, params: INDICATORS.find(i => i.name === e.target.value)?.params.reduce((acc, p) => ({ ...acc, [p.name]: p.default }), {}) || {} })} className="h-7 px-1.5 rounded text-xs border border-slate-200 bg-white">
        {INDICATORS.map(ind => <option key={ind.name} value={ind.name}>{ind.label}</option>)}
      </select>
      {indicator.params.map(p => (
        <input key={p.name} type="number" value={rule.params?.[p.name] ?? p.default}
          onChange={e => onChange({ ...rule, params: { ...rule.params, [p.name]: Number(e.target.value) } })}
          className="w-14 h-7 px-1.5 rounded text-xs border border-slate-200 bg-white" placeholder={p.label} />
      ))}
      {indicator.conditions && (
        <select value={rule.condition} onChange={e => onChange({ ...rule, condition: e.target.value })} className="h-7 px-1.5 rounded text-xs border border-slate-200 bg-white">
          {indicator.conditions.map(c => <option key={c.value} value={c.value}>{c.label}</option>)}
        </select>
      )}
      {(rule.condition === 'oversold' || rule.condition === 'overbought') && (
        <input type="number" value={rule.threshold ?? 30} onChange={e => onChange({ ...rule, threshold: Number(e.target.value) })} className="w-12 h-7 px-1.5 rounded text-xs border border-slate-200 bg-white" placeholder="阈值" />
      )}
      <button onClick={onRemove} className="text-slate-400 hover:text-red-500 ml-auto">×</button>
    </div>
  )
}
