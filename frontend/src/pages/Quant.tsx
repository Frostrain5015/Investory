import { useEffect, useState, useRef, useCallback, useMemo } from 'react'
import { BASE, getBacktestHistory, getBacktest, deleteBacktest, startBacktest, getBacktestStream, searchStocks, getHoldings } from '@/services/api'
import { useToast } from '@/components/Toast'
import { useConfirm } from '@/hooks/use-confirm'
import { useSettings } from '@/hooks/use-settings'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { BarChart2, RefreshCw, FlaskConical, Play, Trash2, Activity, ChevronDown, ChevronRight } from 'lucide-react'
import { AnimatePresence, motion } from 'framer-motion'
import { displaySymbol } from '@/lib/format'
import { ResponsiveContainer, AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip } from 'recharts'
import type { BacktestResult, BacktestMetrics, EquityPoint, TradeLogEntry, SseEvent } from '@/types'
import { useT } from '@/i18n/I18nContext'
import type { Translation } from '@/i18n/translations'

function buildEntryIndicators(t: Translation) {
  const q = t.quant
  return [
    { name: 'sma', label: q.indicatorSma, params: [{ name: 'period', label: q.indParamPeriod, type: 'number' as const, default: 20 }], conditions: [{ value: 'above', label: '> SMA' }, { value: 'below', label: '< SMA' }] },
    { name: 'ema', label: q.indicatorEma, params: [{ name: 'period', label: q.indParamPeriod, type: 'number' as const, default: 20 }], conditions: [{ value: 'above', label: '> EMA' }, { value: 'below', label: '< EMA' }] },
    { name: 'rsi', label: q.indicatorRsi, params: [{ name: 'period', label: q.indParamPeriod, type: 'number' as const, default: 14 }], conditions: [{ value: 'oversold', label: q.indCondOversold }, { value: 'overbought', label: q.indCondOverbought }] },
    { name: 'macd_histogram', label: q.indicatorMacdHist, params: [{ name: 'fast', label: q.indParamFast, type: 'number' as const, default: 12 }, { name: 'slow', label: q.indParamSlow, type: 'number' as const, default: 26 }], conditions: [{ value: 'above', label: '> 0' }, { value: 'below', label: '< 0' }] },
    { name: 'bollinger_lower', label: q.indicatorBollLower, params: [{ name: 'period', label: q.indParamPeriod, type: 'number' as const, default: 20 }], conditions: [{ value: 'below', label: q.indCondBelowLower }] },
    { name: 'volume_ma', label: q.indicatorVolMa, params: [{ name: 'period', label: q.indParamPeriod, type: 'number' as const, default: 20 }], conditions: [{ value: 'above', label: q.indCondSurge }] },
    { name: 'kdj_k', label: q.indicatorKdjK, params: [{ name: 'period', label: q.indParamPeriod, type: 'number' as const, default: 9 }], conditions: [{ value: 'oversold', label: q.indCondOversold }, { value: 'overbought', label: q.indCondOverbought }] },
  ]
}

function buildExitOnlyIndicators(t: Translation) {
  const q = t.quant
  return [
    { name: 'stop_loss', label: q.indicatorStopLoss, params: [{ name: 'pct', label: q.indParamPctLoss, type: 'number' as const, default: 8 }], conditions: [{ value: 'triggered', label: q.indCondStopTriggered }] },
    { name: 'take_profit', label: q.indicatorTakeProfit, params: [{ name: 'pct', label: q.indParamPctGain, type: 'number' as const, default: 20 }], conditions: [{ value: 'triggered', label: q.indCondTPTriggered }] },
    { name: 'trailing_stop', label: q.indicatorTrailingStop, params: [{ name: 'pct', label: q.indParamPullback, type: 'number' as const, default: 5 }], conditions: [{ value: 'triggered', label: q.indCondTrailTriggered }] },
  ]
}

interface SseProgress { current: number; total: number; pct: number; name: string }

function todayStr() { return new Date().toISOString().slice(0, 10) }
function yearAgoStr() { const d = new Date(); d.setFullYear(d.getFullYear() - 1); return d.toISOString().slice(0, 10) }

export default function Quant() {
  const { t } = useT()
  const [tab, setTab] = useState<'risk' | 'backtest'>('risk')

  return (
    <div className="p-6 space-y-6 max-w-6xl mx-auto">
      <div className="flex items-center justify-between">
        <h2 className="text-xl font-bold text-slate-900 tracking-tight">{t.quant.title}</h2>
        <div className="flex bg-slate-100 rounded-lg p-0.5">
          <button onClick={() => setTab('risk')}
            className={`px-3 py-1.5 rounded-md text-xs font-medium transition-colors ${tab === 'risk' ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'}`}>
            <BarChart2 className="w-3.5 h-3.5 inline mr-1" />{t.quant.tabRisk}
          </button>
          <button onClick={() => setTab('backtest')}
            className={`px-3 py-1.5 rounded-md text-xs font-medium transition-colors ${tab === 'backtest' ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'}`}>
            <FlaskConical className="w-3.5 h-3.5 inline mr-1" />{t.quant.tabBacktest}
          </button>
        </div>
      </div>

      {tab === 'risk' ? <RiskSection /> : <BacktestSection />}
    </div>
  )
}

// ── Risk Analysis Section ───────────────────────────────────────────────

function RiskSection() {
  const { t } = useT()
  const q = t.quant
  const { positiveClass } = useSettings()
  const [styleData, setStyleData] = useState<any>(null)
  const [loading, setLoading] = useState(true)
  const [refreshingMetrics, setRefreshingMetrics] = useState(false)
  const esRef = useRef<EventSource | null>(null)

  const loadStyle = useCallback(() => {
    setLoading(true)
    fetch(`${BASE}/api/quant/portfolio-style`, { credentials: 'include' })
      .then(r => r.json()).then(d => { if (!d.error) setStyleData(d); else setStyleData({ _error: d.error }) })
      .catch(() => {}).finally(() => setLoading(false))
  }, [])

  useEffect(() => { loadStyle() }, [loadStyle])
  useEffect(() => () => { if (esRef.current) esRef.current.close() }, [])

  function refreshMetrics() {
    setRefreshingMetrics(true)
    if (esRef.current) { esRef.current.close() }
    const es = new EventSource(`${BASE}/api/quant/refresh`, { withCredentials: true })
    esRef.current = es
    es.addEventListener('done', () => { setRefreshingMetrics(false); es.close() })
    es.addEventListener('error', () => { setRefreshingMetrics(false); es.close() })
    es.onerror = () => {}
  }

  const STYLE_COLORS: Record<string, string> = {
    '大盘价值': 'bg-blue-600', '大盘成长': 'bg-sky-500', '小盘价值': 'bg-amber-600', '小盘成长': 'bg-red-400',
    '科技成长': 'bg-blue-500', '金融价值': 'bg-amber-500', '消费防御': 'bg-emerald-500',
    '能源材料': 'bg-orange-500', '医疗健康': 'bg-purple-500', '地产基建': 'bg-slate-500',
    '综合其他': 'bg-slate-400',
    'Large Value': 'bg-blue-600', 'Large Growth': 'bg-sky-500', 'Small Value': 'bg-amber-600', 'Small Growth': 'bg-red-400',
    'Tech Growth': 'bg-blue-500', 'Financial Value': 'bg-amber-500', 'Consumer Defensive': 'bg-emerald-500',
    'Energy & Materials': 'bg-orange-500', 'Healthcare': 'bg-purple-500', 'Real Estate & Infra': 'bg-slate-500',
    'Diversified': 'bg-slate-400',
  }

  // Style names returned by the backend are in Chinese; provide an en mapping
  const STYLE_NAME_DISPLAY: Record<string, string> = {
    '大盘价值': 'Large Value', '大盘成长': 'Large Growth', '小盘价值': 'Small Value', '小盘成长': 'Small Growth',
    '科技成长': 'Tech Growth', '金融价值': 'Financial Value', '消费防御': 'Consumer Defensive',
    '能源材料': 'Energy & Materials', '医疗健康': 'Healthcare', '地产基建': 'Real Estate & Infra',
    '综合其他': 'Diversified',
  }

  const displayStyleName = (zhName: string) => {
    // If t.quant.title starts with a non-ASCII char, we're in zh mode
    if (/^[一-鿿]/.test(t.quant.title)) return zhName
    return STYLE_NAME_DISPLAY[zhName] || zhName
  }

  return (<>
    <div className="flex items-center gap-2">
      <button onClick={loadStyle} disabled={loading}
        className="inline-flex items-center gap-1.5 h-9 px-4 rounded-xl bg-slate-900 text-white text-xs font-medium hover:bg-slate-800 transition-colors disabled:opacity-60">
        <RefreshCw className={`w-3.5 h-3.5 ${loading ? 'animate-spin' : ''}`} />{loading ? t.common.loading : q.styleDiagnostics}
      </button>
      <button onClick={refreshMetrics} disabled={refreshingMetrics}
        className="inline-flex items-center gap-1.5 h-9 px-4 rounded-xl border border-slate-200 text-slate-600 text-xs font-medium hover:bg-slate-50 transition-colors disabled:opacity-40">
        <RefreshCw className={`w-3.5 h-3.5 ${refreshingMetrics ? 'animate-spin' : ''}`} />{refreshingMetrics ? t.common.loading : q.refreshMetrics}
      </button>
    </div>

    {styleData && !loading && (<>
      <Card>
        <CardContent className="py-4">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-xs text-slate-500">{q.portfolioStyleDiag}</p>
              <p className="text-lg font-bold text-slate-900">{styleData.styleSummary}</p>
            </div>
            <div className="flex items-center gap-6 text-xs text-slate-500">
              <div className="text-center"><p className="text-2xl font-bold text-slate-900">{styleData.positionCount}</p><p>{q.positions}</p></div>
              <div className="text-center"><p className="text-2xl font-bold text-slate-900">{(styleData.totalValue / 10000).toFixed(0)}{t.dashboard.chartUnitLarge}</p><p>{t.dashboard.totalValue}</p></div>
              <div className="text-center"><p className={`text-2xl font-bold ${styleData.weightedBeta != null ? (styleData.weightedBeta > 1 ? positiveClass : 'text-slate-900') : 'text-slate-400'}`}>{styleData.weightedBeta ?? '—'}</p><p>{q.weightedBeta}</p></div>
            </div>
          </div>
        </CardContent>
      </Card>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <Card>
          <CardHeader><CardTitle className="text-sm">{q.styleAllocation}</CardTitle></CardHeader>
          <CardContent className="space-y-2">
            {Object.entries(styleData.styleAllocation || {}).map(([style, data]: [string, any]) => (
              <div key={style} className="flex items-center gap-2">
                <span className={`w-2.5 h-2.5 rounded-full ${STYLE_COLORS[style] || STYLE_COLORS[displayStyleName(style)] || 'bg-slate-400'}`} />
                <span className="text-xs text-slate-600 w-16 truncate">{displayStyleName(style)}</span>
                <div className="flex-1 bg-slate-100 rounded-full h-2"><div className={`h-full rounded-full ${STYLE_COLORS[style] || STYLE_COLORS[displayStyleName(style)] || 'bg-slate-400'}`} style={{ width: `${Math.max(data.pct, 2)}%` }} /></div>
                <span className="text-xs text-slate-500 w-12 text-right">{data.pct}%</span>
              </div>
            ))}
          </CardContent>
        </Card>
        <Card>
          <CardHeader><CardTitle className="text-sm">{q.recommendations}</CardTitle></CardHeader>
          <CardContent className="space-y-2">
            {(styleData.recommendations || []).map((r: any, i: number) => (
              <div key={i} className={`text-xs p-2 rounded-lg ${r.severity === 'warning' ? 'bg-amber-50 text-amber-800' : 'bg-blue-50 text-blue-800'}`}>
                <p className="font-medium">{r.title}</p><p className="mt-0.5 opacity-80">{r.detail}</p>
              </div>
            ))}
            {!styleData.recommendations?.length && <p className="text-xs text-slate-400">{q.structureBalanced}</p>}
          </CardContent>
        </Card>
      </div>
    </>)}

    {loading && <div className="flex flex-col items-center justify-center h-48 gap-2"><div className="w-6 h-6 border-2 border-slate-300 border-t-slate-900 rounded-full animate-spin" /><span className="text-xs text-slate-400">{q.analyzingStyle}</span></div>}
    {!loading && !styleData && <Card><CardContent className="py-12 text-center"><BarChart2 className="w-8 h-8 text-slate-300 mx-auto mb-2" /><p className="text-sm text-slate-500">{q.noData}</p></CardContent></Card>}
    {!loading && styleData?._error && <Card><CardContent className="py-12 text-center"><BarChart2 className="w-8 h-8 text-slate-300 mx-auto mb-2" /><p className="text-sm text-slate-500">{styleData._error === 'no holdings' ? q.noHoldingsData : styleData._error}</p></CardContent></Card>}
  </>)
}

// ── Backtest Section ────────────────────────────────────────────────────

function BacktestSection() {
  const { t } = useT()
  const q = t.quant
  const confirm = useConfirm()
  const toast = useToast()
  const { positiveClass, negativeClass, positiveHex, negativeHex } = useSettings()

  const ENTRY_INDICATORS = useMemo(() => buildEntryIndicators(t), [t])
  const EXIT_INDICATORS = useMemo(() => [...buildEntryIndicators(t), ...buildExitOnlyIndicators(t)], [t])

  const [view, setView] = useState<'list' | 'builder' | 'run'>('list')
  const [strategies, setStrategies] = useState<any[]>([])
  const [editId, setEditId] = useState<number | null>(null)
  const [runStrategyId, setRunStrategyId] = useState<number | null>(null)
  const [results, setResults] = useState<BacktestResult[]>([])
  const [loading, setLoading] = useState(true)
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [equityCurve, setEquityCurve] = useState<EquityPoint[] | null>(null)
  const [metrics, setMetrics] = useState<BacktestMetrics | null>(null)
  const [tradeLog, setTradeLog] = useState<TradeLogEntry[] | null>(null)

  const [strategyType, setStrategyType] = useState<'simple' | 'advanced'>('simple')
  const [wfEnabled, setWfEnabled] = useState(false)
  const [wfWindow, setWfWindow] = useState(24)
  const [wfStep, setWfStep] = useState(6)
  const [wfOos, setWfOos] = useState(6)
  const [optimize, setOptimize] = useState(false)
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
  const [advancedCode, setAdvancedCode] = useState<string>(q.pythonTemplate)

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
  useEffect(() => () => { if (esRef.current) esRef.current.close() }, [])

  // Reconnect on mount
  useEffect(() => {
    fetch(`${BASE}/api/backtest/status`, { credentials: 'include' })
      .then(r => r.json())
      .then((data: any) => { if (data.active) { setRunning(true); if (data.progress) setProgress(data.progress); if (data.recentLogs) setSseLogs(data.recentLogs); reconnectSSE() } })
      .catch(() => {})
  }, [])

  function reconnectSSE() {
    if (esRef.current) { esRef.current.close(); esRef.current = null }
    getBacktestStream().then(resp => {
      if (!resp.ok) return
      const es = new EventSource(`${BASE}/api/backtest/stream`, { withCredentials: true })
      esRef.current = es; wireSSE(es)
    }).catch(() => {})
  }

  function wireSSE(es: EventSource) {
    es.addEventListener('status', e => { const d: SseEvent = JSON.parse(e.data); setSseLogs(l => [...l, `[${q.sseStatus}] ${d.msg}`]) })
    es.addEventListener('progress', e => { const d: SseEvent = JSON.parse(e.data); setProgress({ current: d.current!, total: d.total!, pct: d.pct!, name: d.name! }) })
    es.addEventListener('info', e => { const d: SseEvent = JSON.parse(e.data); setSseLogs(l => [...l, `[${q.sseInfo}] ${d.msg}`]) })
    es.addEventListener('log', e => { const d: SseEvent = JSON.parse(e.data); setSseLogs(l => [...l, d.msg!]) })
    es.addEventListener('done', e => { const d: SseEvent = JSON.parse(e.data); setDoneMsg(d.msg!); setSseLogs(l => [...l, `✓ ${d.msg}`]); setRunning(false); es.close(); loadHistory(); if (d.resultId) selectResult(Number(d.resultId)) })
    es.addEventListener('error', e => { const raw = (e as MessageEvent).data; if (raw) { try { const d: SseEvent = JSON.parse(raw); setErrorMsg(d.msg!); setSseLogs(l => [...l, `✗ ${d.msg}`]) } catch {} } setRunning(false); es.close() })
    es.onerror = () => {}
  }

  async function handleStart() {
    const manualStocks = stockInput.split(/[,;\s]+/).filter(Boolean)
    const stocks = [...selectedStocks.map(s => s.symbol), ...manualStocks]
    if (stocks.length === 0) { toast(q.toastEnterStock, false); return }
    if (!runStrategyId) { toast(q.toastSelectStrategy, false); return }
    const savedStrat = strategies.find(s => s.id === runStrategyId)
    if (!savedStrat) { toast(q.toastStrategyNotFound, false); return }
    let strategyData: any
    try { strategyData = JSON.parse(savedStrat.strategy_json || '{}') } catch { console.error('Invalid strategy JSON'); return }
    const strategy = { ...strategyData, stocks }
    const config: any = { startDate, endDate, initialCapital: Number(initialCapital), baseCurrency, commissionPct: 0.0003, slippagePct: 0.001 }
    const effectiveStrategyType = optimize ? 'optimize' : wfEnabled ? 'walk_forward' : savedStrat.strategy_type
    if (wfEnabled) {
      Object.assign(config, { windowMonths: wfWindow, stepMonths: wfStep, oosMonths: wfOos })
    }
    if (optimize) {
      const grid: Record<string, number[]> = {}
      const allRules = [...(strategy.entry?.rules || []), ...(strategy.exit?.rules || [])]
      for (const rule of allRules) {
        const ind = rule.indicator
        if (ind === 'stop_loss' || ind === 'take_profit' || ind === 'trailing_stop') continue
        if (rule.params?.period && !grid[`${ind}_period`]) {
          const p = rule.params.period
          grid[`${ind}_period`] = [Math.max(3, p - 10), p, p + 10, p + 20].filter(v => v > 0)
        }
      }
      if (Object.keys(grid).length > 0) {
        config.paramGrid = grid
      }
    }
    setRunning(true); setProgress(null); setSseLogs([]); setDoneMsg(null); setErrorMsg(null)
    const resp = await startBacktest({ name: savedStrat.name, strategyType: effectiveStrategyType, strategy, config })
    if (!resp.ok) { setErrorMsg(`HTTP ${resp.status}`); setRunning(false); return }
    if (esRef.current) { esRef.current.close(); esRef.current = null }
    const es = new EventSource(`${BASE}/api/backtest/stream`)
    esRef.current = es; wireSSE(es)
  }

  function selectResult(id: number) {
    setSelectedId(id)
    getBacktest(id).then(r => {
      try { setEquityCurve(JSON.parse(r.equity_curve_json)) } catch { setEquityCurve(null) }
      try { setMetrics(JSON.parse(r.metrics_json)) } catch { setMetrics(null) }
      try { setTradeLog(JSON.parse(r.trade_log_json)) } catch { setTradeLog(null) }
      setView('list')
    }).catch(() => {})
  }

  async function handleDelete(id: number) {
    if (!(await confirm(q.toastConfirmDelete))) return
    await deleteBacktest(id)
    if (selectedId === id) { setSelectedId(null); setEquityCurve(null); setMetrics(null); setTradeLog(null) }
    loadHistory()
  }

  function addRule(target: 'entry' | 'exit') {
    const rule = { indicator: 'sma', params: { period: 20 }, condition: 'above', threshold: 0 }
    if (target === 'entry') setEntryRules([...entryRules, rule]); else setExitRules([...exitRules, rule])
  }

  // ── Load strategies ────────────────────────────────────────────────
  const loadStrategies = useCallback(() => {
    fetch(`${BASE}/api/backtest/strategies`, { credentials: 'include' })
      .then(r => r.json()).then(setStrategies).catch(() => {})
  }, [])
  useEffect(() => { loadStrategies() }, [loadStrategies])

  // ── Save strategy ───────────────────────────────────────────────────
  async function saveStrategy() {
    const strategy = strategyType === 'advanced'
      ? { code: advancedCode }
      : { entry: { logic: entryLogic, rules: entryRules }, exit: { rules: exitRules } }
    const body: any = { name: strategyName || (strategyType === 'advanced' ? q.strategyTypeAdvanced : q.strategyTypeSimple), strategyType, strategy }
    if (editId) body.id = editId
    const resp = await fetch(`${BASE}/api/backtest/strategies`, { method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
    const data = await resp.json()
    if (data.error) { toast(data.error, false); return }
    toast(editId ? q.toastStrategyUpdated : q.toastStrategySaved, true)
    setView('list'); setEditId(null)
    loadStrategies()
  }

  async function deleteStrategy(id: number) {
    if (!(await confirm(q.toastConfirmDeleteStrategy))) return
    await fetch(`${BASE}/api/backtest/strategies/${id}`, { method: 'DELETE', credentials: 'include' })
    loadStrategies()
  }

  function editStrategy(s: any) {
    setEditId(s.id); setStrategyName(s.name); setStrategyType(s.strategy_type)
    try {
      const parsed = JSON.parse(s.strategy_json)
      if (s.strategy_type === 'advanced') { setAdvancedCode(parsed.code || '') }
      else {
        setEntryLogic(parsed.entry?.logic || 'all')
        setEntryRules(parsed.entry?.rules || [])
        setExitRules(parsed.exit?.rules || [])
      }
    } catch { setAdvancedCode('') }
    setView('builder')
  }

  function startNewStrategy() { setEditId(null); setStrategyName(''); setEntryRules([]); setExitRules([]); setStockInput(''); setSelectedStocks([]); setAdvancedCode(q.pythonTemplate); setView('builder') }

  return (<>
    <div className="flex items-center gap-2">
      <button onClick={startNewStrategy}
        className="inline-flex items-center gap-1.5 h-9 px-4 rounded-xl bg-slate-900 text-white text-xs font-medium hover:bg-slate-800 transition-colors">
        <FlaskConical className="w-3.5 h-3.5" />{q.newStrategy}
      </button>
      {strategies.length > 0 && (
        <button onClick={() => setView('run')}
          className={`inline-flex items-center gap-1.5 h-9 px-4 rounded-xl text-xs font-medium transition-colors border ${view === 'run' ? 'bg-slate-200 text-slate-700 border-slate-300' : 'bg-white text-slate-600 border-slate-200 hover:bg-slate-50'}`}>
          <Play className="w-3.5 h-3.5" />{q.startBacktest}
        </button>
      )}
    </div>

    {/* ── Strategy Builder ────────────────────────────────────────── */}
    <AnimatePresence>
      {view === 'builder' && (
        <motion.div initial={{ opacity: 0, height: 0 }} animate={{ opacity: 1, height: 'auto' }} exit={{ opacity: 0, height: 0 }} transition={{ duration: 0.2 }}>
          <Card>
            <CardHeader><CardTitle className="text-sm">{q.strategyConfig}</CardTitle></CardHeader>
            <CardContent className="space-y-4">
              <div className="flex items-center gap-4">
                <div className="flex bg-slate-100 rounded-lg p-0.5">
                  {(['simple', 'advanced'] as const).map(tp => (
                    <button key={tp} onClick={() => setStrategyType(tp)}
                      className={`px-3 py-1.5 rounded-md text-xs font-medium transition-colors ${strategyType === tp ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'}`}>
                      {tp === 'simple' ? q.simpleMode : q.advancedMode}
                    </button>
                  ))}
                </div>
                <input type="text" value={strategyName} onChange={e => setStrategyName(e.target.value)} placeholder={q.strategyNameOptional} className="h-8 px-3 rounded-lg border border-slate-200 text-xs flex-1 max-w-xs focus:outline-none focus:ring-2 focus:ring-slate-900/5" />
              </div>

              {strategyType === 'simple' ? (<>
                <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
                  <div>
                    <div className="flex items-center justify-between mb-2">
                      <label className="text-xs font-medium text-slate-600">{q.entryRules}</label>
                      <div className="flex items-center gap-2">
                        <select value={entryLogic} onChange={e => setEntryLogic(e.target.value as 'all' | 'any')} className="h-6 px-1.5 rounded text-xs border border-slate-200">
                          <option value="all">{q.allOf}</option>
                          <option value="any">{q.anyOf}</option>
                        </select>
                        <button onClick={() => addRule('entry')} className="h-6 px-2 rounded-md text-xs font-medium border border-slate-200 bg-white text-slate-600 hover:bg-slate-100 hover:border-slate-300 transition-colors">+ {t.common.add}</button>
                      </div>
                    </div>
                    {entryRules.map((rule, i) => <RuleEditor key={i} rule={rule} indicators={ENTRY_INDICATORS} t={t} onChange={r => { const e = [...entryRules]; e[i] = r; setEntryRules(e) }} onRemove={() => setEntryRules(entryRules.filter((_, j) => j !== i))} />)}
                  </div>
                  <div>
                    <div className="flex items-center justify-between mb-2">
                      <label className="text-xs font-medium text-slate-600">{q.exitRules}</label>
                      <button onClick={() => addRule('exit')} className="h-6 px-2 rounded-md text-xs font-medium border border-slate-200 bg-white text-slate-600 hover:bg-slate-100 hover:border-slate-300 transition-colors">+ {t.common.add}</button>
                    </div>
                    {exitRules.map((rule, i) => <RuleEditor key={i} rule={rule} indicators={EXIT_INDICATORS} t={t} onChange={r => { const e = [...exitRules]; e[i] = r; setExitRules(e) }} onRemove={() => setExitRules(exitRules.filter((_, j) => j !== i))} />)}
                  </div>
                </div>
              </>) : (
                <div>
                  <label className="text-xs font-medium text-slate-600 mb-1 block">{q.pythonCode}</label>
                  <textarea value={advancedCode} onChange={e => setAdvancedCode(e.target.value)} rows={10} spellCheck={false}
                    className="w-full font-mono text-xs p-3 rounded-lg border border-slate-200 bg-slate-900 text-green-400 focus:outline-none focus:ring-2 focus:ring-slate-900/5" />
                  <p className="text-[10px] text-slate-400 mt-1">{q.codeHintDefine} <code className="bg-slate-100 px-1 rounded">def decide(ctx)</code> {q.codeHintFuncReturn} <code className="bg-slate-100 px-1 rounded">{`{'action': 'BUY'|'SELL'|'HOLD', 'quantity': int}`}</code></p>
                </div>
              )}

              <div className="flex items-center gap-2">
                <button onClick={() => { setView('list'); setEditId(null) }} className="h-8 px-4 rounded-lg border border-slate-200 text-xs text-slate-500 hover:bg-slate-50">{t.common.close}</button>
                <button onClick={saveStrategy} className="h-8 px-4 rounded-lg bg-slate-900 text-white text-xs font-medium hover:bg-slate-800 inline-flex items-center justify-center gap-1">{q.saveStrategy}</button>
              </div>
            </CardContent>
          </Card>
        </motion.div>
      )}
    </AnimatePresence>

    {/* ── Strategy List ──────────────────────────────────────────── */}
    {view === 'list' && (
      loading ? (
        <div className="flex items-center justify-center h-32"><div className="w-6 h-6 border-2 border-slate-300 border-t-slate-900 rounded-full animate-spin" /></div>
      ) : strategies.length === 0 ? (
        <div className="text-center py-12 text-slate-400 text-sm">{q.noStrategies}</div>
      ) : (
        <div className="space-y-2">
          {strategies.map((s: any) => (
            <Card key={s.id} className="hover:border-blue-200 transition-colors">
              <CardContent className="flex items-center justify-between py-3">
                <div className="flex items-center gap-3">
                  <div>
                    <div className="text-sm font-medium text-slate-900">{s.name}</div>
                    <div className="text-xs text-slate-400">{s.strategy_type === 'advanced' ? q.advancedMode : q.simpleMode} · {s.updated_at?.slice(0, 10)}</div>
                  </div>
                </div>
                <div className="flex items-center gap-1">
                  <button onClick={() => editStrategy(s)}
                    className="h-7 px-2.5 rounded-md text-xs text-slate-600 hover:bg-slate-100">{t.common.edit}</button>
                  <button onClick={() => deleteStrategy(s.id)}
                    className="h-7 px-2.5 rounded-md text-xs text-red-500 hover:bg-red-50"><Trash2 className="w-3 h-3" /></button>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      )
    )}

    {/* ── Run Backtest ────────────────────────────────────────────── */}
    <AnimatePresence>
      {view === 'run' && (
        <motion.div initial={{ opacity: 0, y: -8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -8 }} transition={{ duration: 0.15 }}>
          <Card>
            <CardHeader><CardTitle className="text-sm">{q.configureBacktest}</CardTitle></CardHeader>
            <CardContent className="space-y-3">
              <div>
                <label className="text-xs font-medium text-slate-600 mb-1 block">{q.selectStrategy}</label>
                <select value={runStrategyId || ''} onChange={e => setRunStrategyId(Number(e.target.value) || null)}
                  className="w-full h-8 px-2 rounded-lg border border-slate-200 text-xs">
                  <option value="">{q.selectPlaceholder}</option>
                  {strategies.map((s: any) => <option key={s.id} value={s.id}>{s.name} ({s.strategy_type === 'walk_forward' ? 'WF' : s.strategy_type === 'advanced' ? q.strategyTypeAdvanced : q.strategyTypeSimple})</option>)}
                </select>
              </div>
              <div>
                <div className="flex items-center justify-between mb-1">
                  <label className="text-xs font-medium text-slate-600">{q.testStocks}</label>
                  <button type="button" onClick={async () => {
                    try { const data = await getHoldings(); const snaps = (data as any).snapshots || []; for (const s of snaps) { const sym = s.stockSymbol?.includes('.') ? s.stockSymbol : `${s.stockSymbol}.${s.market}`; if (!selectedStocks.find(x => x.symbol === sym)) setSelectedStocks(prev => [...prev, { symbol: sym, name: s.stockName || sym }]) } } catch {}
                  }} className="text-[10px] text-blue-600 hover:text-blue-800">{q.importHoldings}</button>
                </div>
                {selectedStocks.length > 0 && (
                  <div className="flex flex-wrap gap-1 mb-2">
                    {selectedStocks.map(s => (<span key={s.symbol} className="inline-flex items-center gap-1 px-2 py-0.5 rounded bg-blue-50 text-xs text-blue-700">{s.name} ({s.symbol})<button onClick={() => setSelectedStocks(selectedStocks.filter(x => x.symbol !== s.symbol))} className="text-blue-400 hover:text-red-500">×</button></span>))}
                  </div>
                )}
                <div className="relative">
                  <input type="text" value={stockInput} onChange={e => { setStockInput(e.target.value); if (e.target.value.length >= 1) searchStocks(e.target.value).then(setStockSearchResults).catch(() => {}) }}
                    onFocus={() => { if (stockInput.length >= 1) searchStocks(stockInput).then(setStockSearchResults).catch(() => {}) }}
                    onBlur={() => setTimeout(() => setStockSearchResults([]), 200)} placeholder={q.searchStocks} className="w-full h-8 px-3 rounded-lg border border-slate-200 text-xs focus:outline-none focus:ring-2 focus:ring-slate-900/5" />
                  {stockSearchResults.length > 0 && (
                    <div className="absolute left-0 right-0 top-full mt-1 bg-white border border-slate-200 rounded-lg shadow-lg z-50 max-h-48 overflow-auto">
                      {stockSearchResults.map((r: any) => {
                        const sym = displaySymbol(r.symbol, r.market)
                        return <button key={r.id} type="button" onMouseDown={e => e.preventDefault()} onClick={() => { if (!selectedStocks.find(s => s.symbol === sym)) setSelectedStocks([...selectedStocks, { symbol: sym, name: r.name }]); setStockInput(''); setStockSearchResults([]) }}
                          className="w-full flex items-center justify-between px-3 py-2 hover:bg-slate-50 text-left text-xs"><span className="font-medium text-slate-700">{r.name}</span><span className="text-slate-400">{sym}</span></button>
                      })}
                    </div>
                  )}
                </div>
              </div>
              {/* Walk-Forward validation toggle */}
              <div className="flex items-center gap-2 flex-wrap">
                <label className="flex items-center gap-1.5 cursor-pointer select-none">
                  <input type="checkbox" checked={wfEnabled} onChange={e => setWfEnabled(e.target.checked)}
                    className="w-3.5 h-3.5 rounded border-slate-300 text-amber-600 focus:ring-amber-500" />
                  <span className="text-xs text-slate-600">{q.wfLabel}</span>
                </label>
                {wfEnabled && (
                  <div className="flex items-center gap-1.5 text-xs">
                    <input type="number" value={wfWindow} onChange={e => setWfWindow(Number(e.target.value))}
                      className="w-10 h-6 px-1 rounded border border-amber-200 bg-amber-50 text-center text-[11px]" min={6} max={60} />
                    <span className="text-slate-400">{q.train}</span>
                    <input type="number" value={wfStep} onChange={e => setWfStep(Number(e.target.value))}
                      className="w-10 h-6 px-1 rounded border border-amber-200 bg-amber-50 text-center text-[11px]" min={1} max={12} />
                    <span className="text-slate-400">{q.step}</span>
                    <input type="number" value={wfOos} onChange={e => setWfOos(Number(e.target.value))}
                      className="w-10 h-6 px-1 rounded border border-amber-200 bg-amber-50 text-center text-[11px]" min={1} max={18} />
                    <span className="text-slate-400">{q.test}</span>
                    <span className="text-amber-300">{q.monthUnit}</span>
                  </div>
                )}
                <span className="text-slate-300">|</span>
                <label className="flex items-center gap-1.5 cursor-pointer select-none">
                  <input type="checkbox" checked={optimize} onChange={e => setOptimize(e.target.checked)}
                    className="w-3.5 h-3.5 rounded border-slate-300 text-violet-600 focus:ring-violet-500" />
                  <span className="text-xs text-slate-600">{q.paramGrid}</span>
                </label>
              </div>
              <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-5 gap-3">
                <div><label className="text-[10px] text-slate-500">{q.startDate}</label><input type="date" value={startDate} onChange={e => setStartDate(e.target.value)} className="w-full h-8 px-2 rounded-lg border border-slate-200 text-xs" /></div>
                <div><label className="text-[10px] text-slate-500">{q.endDate}</label><input type="date" value={endDate} onChange={e => setEndDate(e.target.value)} className="w-full h-8 px-2 rounded-lg border border-slate-200 text-xs" /></div>
                <div><label className="text-[10px] text-slate-500">{q.initialCapital}</label><input type="number" value={initialCapital} onChange={e => setInitialCapital(e.target.value)} className="w-full h-8 px-2 rounded-lg border border-slate-200 text-xs" /></div>
                <div><label className="text-[10px] text-slate-500">{q.baseCurrency}</label><select value={baseCurrency} onChange={e => setBaseCurrency(e.target.value)} className="w-full h-8 px-1 rounded-lg border border-slate-200 text-xs"><option value="CNY">CNY</option><option value="HKD">HKD</option><option value="USD">USD</option></select></div>
                <div className="flex items-end gap-2">
                  <button onClick={() => setView('list')} className="h-8 px-2 rounded-lg border border-slate-200 text-xs text-slate-500">{t.common.cancel}</button>
                  <button onClick={handleStart} disabled={running || !runStrategyId}
                    className="flex-1 h-8 rounded-lg bg-slate-900 text-white text-xs font-medium hover:bg-slate-800 disabled:opacity-40 inline-flex items-center justify-center gap-1"><Play className="w-3 h-3" />{q.startBacktest}</button>
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
            <CardHeader><CardTitle className="text-sm flex items-center gap-2"><Activity className="w-3.5 h-3.5 animate-spin" />{q.backtesting}</CardTitle></CardHeader>
            <CardContent>
              {progress && (<div className="mb-3"><div className="flex justify-between text-xs text-slate-500 mb-1"><span className="truncate">{progress.name}</span><span>{progress.current}/{progress.total} ({progress.pct.toFixed(1)}%)</span></div><div className="w-full bg-slate-100 rounded-full h-2 overflow-hidden"><div className="bg-slate-900 h-full rounded-full transition-all duration-300" style={{ width: `${progress.pct}%` }} /></div></div>)}
              <div className="bg-slate-900 rounded-xl p-4 max-h-60 overflow-auto font-mono text-xs">
                {sseLogs.map((line, i) => (<div key={i} className={`py-0.5 ${line.startsWith('✓') ? 'text-emerald-400' : line.startsWith('✗') ? 'text-red-400' : line.startsWith(`[${q.sseStatus}]`) ? 'text-sky-400' : 'text-slate-300'}`}>{line}</div>))}
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
        <Card>
          <CardContent className="py-4">
            <div className="grid grid-cols-3 md:grid-cols-5 gap-x-6 gap-y-3 text-sm">
              <div><span className="text-slate-400">{q.totalReturn}</span> <span className={`font-bold ${metrics.totalReturnPct != null ? (metrics.totalReturnPct >= 0 ? positiveClass : negativeClass) : 'text-slate-400'}`}>{metrics.totalReturnPct != null ? `${metrics.totalReturnPct >= 0 ? '+' : ''}${metrics.totalReturnPct}%` : '—'}</span></div>
              <div><span className="text-slate-400">{q.annualReturn}</span> <span className={`font-bold ${metrics.annualReturnPct != null ? (metrics.annualReturnPct >= 0 ? positiveClass : negativeClass) : 'text-slate-400'}`}>{metrics.annualReturnPct != null ? `${metrics.annualReturnPct >= 0 ? '+' : ''}${metrics.annualReturnPct}%` : '—'}</span></div>
              <div><span className="text-slate-400">Sharpe</span> <span className="font-bold text-slate-900">{metrics.sharpeRatio != null ? metrics.sharpeRatio : '—'}</span></div>
              <div><span className="text-slate-400">{q.maxDrawdown}</span> <span className={`font-bold ${negativeClass}`}>{metrics.maxDrawdownPct != null ? `${metrics.maxDrawdownPct}%` : '—'}</span></div>
              <div><span className="text-slate-400">{q.winRate}</span> <span className="font-bold text-slate-900">{metrics.winRatePct != null ? `${metrics.winRatePct}%` : '—'}</span></div>
              <div><span className="text-slate-400">{q.profitFactor}</span> <span className="font-bold text-slate-900">{metrics.profitFactor != null ? metrics.profitFactor : '—'}</span></div>
              <div><span className="text-slate-400">{q.trades}</span> <span className="font-bold text-slate-900">{metrics.totalTrades != null ? `${metrics.totalTrades}${q.tradesUnit}` : '—'}</span></div>
              <div><span className="text-slate-400">{q.avgWin}</span> <span className={`font-bold ${positiveClass}`}>{metrics.avgProfitPct != null ? `${metrics.avgProfitPct}%` : '—'}</span></div>
              <div><span className="text-slate-400">{q.avgLoss}</span> <span className={`font-bold ${negativeClass}`}>{metrics.avgLossPct != null ? `${metrics.avgLossPct}%` : '—'}</span></div>
            </div>
            {metrics.wfStability != null && (
              <div className="mt-4 pt-4 border-t border-amber-100">
                <div className="flex items-center gap-2 mb-3">
                  <span className="w-1.5 h-1.5 rounded-full bg-amber-500" />
                  <span className="text-xs font-semibold text-amber-800">{q.wfStability}</span>
                </div>
                <div className="grid grid-cols-4 gap-x-6 gap-y-2 text-sm">
                  <div><span className="text-slate-400 text-xs">{q.wfWindows}</span> <span className="font-bold text-slate-900">{metrics.wfWindows ?? '—'}</span></div>
                  <div><span className="text-slate-400 text-xs">{q.wfStabilityScore}</span> <span className={`font-bold ${metrics.wfStability >= 0.7 ? 'text-emerald-600' : metrics.wfStability >= 0.4 ? 'text-amber-600' : 'text-red-500'}`}>{metrics.wfStability}</span></div>
                  <div><span className="text-slate-400 text-xs">{q.wfOosSharpeAvg}</span> <span className="font-bold text-slate-900">{metrics.wfOosSharpeAvg ?? '—'}</span></div>
                  <div><span className="text-slate-400 text-xs">{q.wfOosReturnAvg}</span> <span className={`font-bold ${(metrics.wfOosReturnAvg ?? 0) >= 0 ? positiveClass : negativeClass}`}>{metrics.wfOosReturnAvg != null ? `${metrics.wfOosReturnAvg >= 0 ? '+' : ''}${metrics.wfOosReturnAvg}%` : '—'}</span></div>
                </div>
                <p className="text-[10px] text-amber-500 mt-2">{q.wfStabilityDesc}</p>
              </div>
            )}
          </CardContent>
        </Card>

        {equityCurve && equityCurve.length > 1 && (
          <Card>
            <CardHeader className="flex-row items-center justify-between"><CardTitle className="text-sm flex items-center gap-2"><button onClick={() => setShowEquity(!showEquity)} className="hover:text-blue-600">{showEquity ? <ChevronDown className="w-3.5 h-3.5" /> : <ChevronRight className="w-3.5 h-3.5" />}</button>{q.equityCurve}</CardTitle></CardHeader>
            {showEquity && <CardContent>
              <div className="flex items-center gap-3 mb-3 text-xs">
                <span className="inline-flex items-center gap-1"><span className="w-2.5 h-2.5 rounded-full" style={{ backgroundColor: positiveHex }} />{q.buyLabel}(B)</span>
                <span className="inline-flex items-center gap-1"><span className="w-2.5 h-2.5 rounded-full" style={{ backgroundColor: negativeHex }} />{q.sellLabel}(S)</span>
                <span className="text-slate-400">|</span>
                <span className="text-slate-400">{q.initialEquity} {Number(equityCurve[0]?.equity).toLocaleString()}</span>
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
                  <YAxis tick={{ fontSize: 11, fill: '#94a3b8' }} domain={['auto', 'auto']} tickFormatter={(v: number) => `${(v / 10000).toFixed(0)}${q.yAxisUnit}`} />
                  <Tooltip content={({ active, payload, label }) => {
                    if (!active || !payload?.[0]) return null
                    const v = payload[0].value
                    const trade = tradeLog?.find(t => t.date === label)
                    return (
                      <div className="bg-white border border-slate-200 rounded-lg p-2 shadow text-xs">
                        <div className="text-slate-500 mb-1">{q.date}: {label}</div>
                        <div className="font-medium">{q.equityCurve}: {Number(v).toLocaleString()}</div>
                        {trade && <div className={`mt-1 ${trade.action === 'BUY' ? positiveClass : negativeClass}`}>{trade.action === 'BUY' ? q.buyLabel : q.sellLabel} {trade.symbol} {trade.quantity}{q.sharesUnit} @ {trade.price?.toFixed(2)}</div>}
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
            <CardHeader className="flex-row items-center justify-between"><CardTitle className="text-sm flex items-center gap-2"><button onClick={() => setShowTrades(!showTrades)} className="hover:text-blue-600">{showTrades ? <ChevronDown className="w-3.5 h-3.5" /> : <ChevronRight className="w-3.5 h-3.5" />}</button>{q.tradeLog} ({tradeLog.length} {q.tradesCountUnit})</CardTitle></CardHeader>
            {showTrades && <CardContent className="p-0">
              {/* Desktop table: all columns */}
              <div className="hidden md:block overflow-auto max-h-96">
                <table className="w-full text-xs"><thead><tr className="border-b border-slate-100"><th className="text-left font-medium text-slate-500 px-4 py-2">{q.date}</th><th className="text-left font-medium text-slate-500 px-3 py-2">{q.stock}</th><th className="text-center font-medium text-slate-500 px-3 py-2 w-12">{q.action}</th><th className="text-right font-medium text-slate-500 px-3 py-2">{q.qty}</th><th className="text-right font-medium text-slate-500 px-3 py-2">{q.price}</th><th className="text-right font-medium text-slate-500 px-3 py-2">{q.pnl}</th><th className="text-left font-medium text-slate-500 px-4 py-2">{q.reason}</th></tr></thead><tbody>{tradeLog.map((t, i) => (<tr key={i} className="border-b border-slate-50 hover:bg-slate-50/50"><td className="px-4 py-2 text-slate-500">{t.date}</td><td className="px-3 py-2 font-medium text-slate-700">{t.symbol}</td><td className="px-3 py-2 text-center"><span className={`inline-flex px-1.5 py-0.5 rounded text-[10px] font-medium ${t.action === 'BUY' ? 'bg-red-50 text-red-600' : 'bg-emerald-50 text-emerald-600'}`}>{t.action === 'BUY' ? q.buyShort : q.sellShort}</span></td><td className="px-3 py-2 text-right tabular-nums">{t.quantity}</td><td className="px-3 py-2 text-right tabular-nums">{t.price.toFixed(2)}</td><td className={`px-3 py-2 text-right tabular-nums font-medium ${t.pnl == null ? 'text-slate-400' : t.pnl >= 0 ? 'text-red-600' : 'text-emerald-600'}`}>{t.pnl != null ? `${t.pnl >= 0 ? '+' : ''}${t.pnl.toFixed(2)}` : '—'}</td><td className="px-4 py-2 text-slate-400">{t.reason}</td></tr>))}</tbody></table>
              </div>
              {/* Mobile table: date, symbol, action/side, pnl only */}
              <div className="md:hidden overflow-auto max-h-96">
                <table className="w-full text-xs"><thead><tr className="border-b border-slate-100"><th className="text-left font-medium text-slate-500 px-3 py-2">{q.date}</th><th className="text-left font-medium text-slate-500 px-3 py-2">{q.stock}</th><th className="text-center font-medium text-slate-500 px-3 py-2 w-12">{q.action}</th><th className="text-right font-medium text-slate-500 px-3 py-2">{q.pnl}</th></tr></thead><tbody>{tradeLog.map((t, i) => (<tr key={i} className="border-b border-slate-50 hover:bg-slate-50/50"><td className="px-3 py-2 text-slate-500">{t.date}</td><td className="px-3 py-2 font-medium text-slate-700">{t.symbol}</td><td className="px-3 py-2 text-center"><span className={`inline-flex px-1.5 py-0.5 rounded text-[10px] font-medium ${t.action === 'BUY' ? 'bg-red-50 text-red-600' : 'bg-emerald-50 text-emerald-600'}`}>{t.action === 'BUY' ? q.buyShort : q.sellShort}</span></td><td className={`px-3 py-2 text-right tabular-nums font-medium ${t.pnl == null ? 'text-slate-400' : t.pnl >= 0 ? 'text-red-600' : 'text-emerald-600'}`}>{t.pnl != null ? `${t.pnl >= 0 ? '+' : ''}${t.pnl.toFixed(2)}` : '—'}</td></tr>))}</tbody></table>
              </div>
            </CardContent>}
          </Card>
        )}
      </div>
    )}

    {/* History */}
    {loading ? (
      <div className="flex items-center justify-center h-32"><div className="w-6 h-6 border-2 border-slate-300 border-t-slate-900 rounded-full animate-spin" /></div>
    ) : results.length > 0 ? (
      <Card>
        <CardHeader><CardTitle className="text-sm flex items-center gap-2"><RefreshCw className="w-3.5 h-3.5" />{q.history}</CardTitle></CardHeader>
        <CardContent className="p-0"><table className="w-full text-xs"><thead><tr className="border-b border-slate-100"><th className="text-left font-medium text-slate-500 px-4 py-2">{q.historyName}</th><th className="text-left font-medium text-slate-500 px-3 py-2">{q.historyType}</th><th className="text-left font-medium text-slate-500 px-3 py-2">{q.historyPeriod}</th><th className="text-left font-medium text-slate-500 px-3 py-2">{q.historyTime}</th><th className="text-right font-medium text-slate-500 px-4 py-2"></th></tr></thead><tbody>{results.map(r => (<tr key={r.id} className={`border-b border-slate-50 hover:bg-slate-50/50 cursor-pointer ${r.id === selectedId ? 'bg-blue-50/50' : ''}`} onClick={() => selectResult(r.id)}><td className="px-4 py-2 font-medium text-slate-700">{r.name}</td><td className="px-3 py-2"><span className="inline-flex px-1.5 py-0.5 rounded text-[10px] font-medium bg-slate-100 text-slate-600">{r.strategy_type === 'advanced' ? q.strategyTypeAdvanced : q.strategyTypeSimple}</span></td><td className="px-3 py-2 text-slate-400">{r.start_date} ~ {r.end_date}</td><td className="px-3 py-2 text-slate-400">{r.created_at?.slice(0, 10)}</td><td className="px-4 py-2 text-right"><button onClick={e => { e.stopPropagation(); handleDelete(r.id) }} className="text-slate-400 hover:text-red-500"><Trash2 className="w-3 h-3" /></button></td></tr>))}</tbody></table></CardContent>
      </Card>
    ) : results.length === 0 && strategies.length > 0 ? (
      <div className="text-center py-12 text-slate-400 text-sm">{q.noRecords}</div>
    ) : null}
  </>)
}

// ── Shared components ───────────────────────────────────────────────────

function RuleEditor({ rule, indicators, onChange, onRemove, t }: { rule: any; indicators: any[]; onChange: (r: any) => void; onRemove: () => void; t: Translation }) {
  const q = t.quant
  const indicator = indicators.find(ind => ind.name === rule.indicator) || indicators[0]
  return (
    <div className="flex items-center gap-1.5 mb-1.5 bg-slate-50 rounded-lg p-2">
      <select value={rule.indicator} onChange={e => onChange({ ...rule, indicator: e.target.value, params: indicators.find(i => i.name === e.target.value)?.params.reduce((acc: any, p: any) => ({ ...acc, [p.name]: p.default }), {}) || {} })} className="h-7 px-1.5 rounded text-xs border border-slate-200 bg-white">
        {indicators.map(ind => <option key={ind.name} value={ind.name}>{ind.label}</option>)}
      </select>
      {indicator.params.map((p: any) => (
        <input key={p.name} type="number" value={rule.params?.[p.name] ?? p.default}
          onChange={e => onChange({ ...rule, params: { ...rule.params, [p.name]: Number(e.target.value) } })}
          className="w-14 h-7 px-1.5 rounded text-xs border border-slate-200 bg-white" placeholder={p.label} />
      ))}
      {indicator.conditions && (
        <select value={rule.condition} onChange={e => onChange({ ...rule, condition: e.target.value })} className="h-7 px-1.5 rounded text-xs border border-slate-200 bg-white">
          {indicator.conditions.map((c: any) => <option key={c.value} value={c.value}>{c.label}</option>)}
        </select>
      )}
      {(rule.condition === 'oversold' || rule.condition === 'overbought') && (
        <input type="number" value={rule.threshold ?? 30} onChange={e => onChange({ ...rule, threshold: Number(e.target.value) })} className="w-12 h-7 px-1.5 rounded text-xs border border-slate-200 bg-white" placeholder={q.threshold} />
      )}
      <button onClick={onRemove} className="text-slate-400 hover:text-red-500 ml-auto">×</button>
    </div>
  )
}
