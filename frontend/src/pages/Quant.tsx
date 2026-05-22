import { useEffect, useState, useRef, useCallback } from 'react'
import { getQuantData } from '@/services/api'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { BarChart2, RefreshCw } from 'lucide-react'
import type { ScenarioResult, PortfolioRiskSummary, ScenarioHoldingDetail } from '@/types'

const SCENARIO_META: Record<string, { borderColor: string; bgColor: string; benchmark: string }> = {
  crisis_2008: { borderColor: 'border-red-200',    bgColor: 'bg-red-50',    benchmark: 'S&P 500 -47%' },
  crisis_2015: { borderColor: 'border-orange-200', bgColor: 'bg-orange-50', benchmark: 'CSI300 -43%' },
  crisis_2020: { borderColor: 'border-yellow-200', bgColor: 'bg-yellow-50', benchmark: '全球 -30%' },
  crisis_2022: { borderColor: 'border-purple-200', bgColor: 'bg-purple-50', benchmark: '科技股 -50%' },
}

interface SseProgress { current: number; total: number; pct: number; name: string }

export default function Quant() {
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
      .then(d => {
        setScenarios(d.scenarios || [])
        setRisk(Object.keys(d.risk || {}).length ? (d.risk as PortfolioRiskSummary) : null)
      })
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => { loadData() }, [loadData])

  useEffect(() => {
    if (logRef.current) logRef.current.scrollTop = logRef.current.scrollHeight
  }, [logs])

  function startRefresh() {
    if (esRef.current) { esRef.current.close(); esRef.current = null }
    setRefreshing(true)
    setProgress(null)
    setLogs([])
    setDoneMsg(null)
    setErrorMsg(null)

    const es = new EventSource('/investory/api/quant/refresh', { withCredentials: true })
    esRef.current = es

    es.addEventListener('status', (e) => {
      const d = JSON.parse((e as MessageEvent).data)
      setLogs(prev => [...prev, d.msg])
    })
    es.addEventListener('progress', (e) => {
      const d = JSON.parse((e as MessageEvent).data)
      setProgress({ current: d.current, total: d.total, pct: d.pct, name: d.name })
    })
    es.addEventListener('info', (e) => {
      const d = JSON.parse((e as MessageEvent).data)
      setLogs(prev => [...prev, d.msg])
    })
    es.addEventListener('log', (e) => {
      const d = JSON.parse((e as MessageEvent).data)
      if (d.msg?.trim()) setLogs(prev => [...prev, d.msg])
    })
    es.addEventListener('done', (e) => {
      const d = JSON.parse((e as MessageEvent).data)
      setDoneMsg(d.msg || '分析完成')
      setRefreshing(false)
      es.close()
      esRef.current = null
      loadData()
    })
    es.addEventListener('error', (e) => {
      let msg = '连接中断'
      try {
        const d = JSON.parse((e as MessageEvent).data)
        msg = d.msg || '未知错误'
      } catch {}
      setErrorMsg(`✗ ${msg}`)
      setRefreshing(false)
      es.close()
      esRef.current = null
    })
  }

  return (
    <div className="p-6 space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <h2 className="text-xl font-bold text-slate-900 dark:text-slate-100 tracking-tight">量化分析</h2>
        <button
          onClick={startRefresh}
          disabled={refreshing}
          className="inline-flex items-center gap-1.5 h-9 px-4 rounded-xl bg-slate-900 text-white text-xs font-medium hover:bg-slate-800 transition-colors disabled:opacity-60">
          <RefreshCw className={`w-3.5 h-3.5 ${refreshing ? 'animate-spin' : ''}`} />
          {refreshing ? '分析中...' : '刷新分析'}
        </button>
      </div>

      {/* SSE progress area */}
      {(refreshing || doneMsg || errorMsg) && (
        <Card>
          <CardContent className="pt-4 space-y-3">
            {progress && (
              <div className="space-y-1">
                <div className="flex justify-between text-xs text-slate-500">
                  <span className="truncate max-w-xs">{progress.name}</span>
                  <span className="shrink-0 ml-2">{progress.current}/{progress.total}</span>
                </div>
                <div className="h-1.5 bg-slate-100 rounded-full overflow-hidden">
                  <div
                    className="h-full bg-slate-900 rounded-full transition-all duration-300"
                    style={{ width: `${progress.pct}%` }} />
                </div>
              </div>
            )}
            {logs.length > 0 && (
              <div ref={logRef}
                className="max-h-32 overflow-auto bg-slate-50 rounded-lg p-2 font-mono text-[10px] text-slate-500 space-y-0.5">
                {logs.map((l, i) => <div key={i}>{l}</div>)}
              </div>
            )}
            {doneMsg && (
              <p className="text-xs text-emerald-600 font-medium">{doneMsg}</p>
            )}
            {errorMsg && (
              <p className="text-xs text-red-500 font-medium">{errorMsg}</p>
            )}
          </CardContent>
        </Card>
      )}

      {/* Scenario cards */}
      <div>
        <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-300 mb-3">历史危机压测</h3>
        {!loading && scenarios.length === 0 ? (
          <Card>
            <CardContent className="py-12 text-center">
              <BarChart2 className="w-8 h-8 mx-auto mb-2 text-slate-300" />
              <p className="text-sm text-slate-400">暂无数据，请点击「刷新分析」生成缓存</p>
            </CardContent>
          </Card>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {scenarios.map(s => {
              const meta = SCENARIO_META[s.scenario_key] ?? {
                borderColor: 'border-slate-200', bgColor: 'bg-slate-50', benchmark: ''
              }
              const pnl = s.total_pnl_pct
              const details: ScenarioHoldingDetail[] = s.detail_json
                ? JSON.parse(s.detail_json)
                : []
              const sorted = [...details]
                .filter(d => d.returnPct != null)
                .sort((a, b) => (a.returnPct ?? 0) - (b.returnPct ?? 0))

              return (
                <Card key={s.scenario_key} className={`border-2 ${meta.borderColor}`}>
                  <CardHeader className={`${meta.bgColor} rounded-t-lg pb-2`}>
                    <CardTitle className="text-sm font-semibold">{s.scenario_name}</CardTitle>
                    <p className="text-xs text-slate-500">{s.start_date} ~ {s.end_date}</p>
                    {meta.benchmark && (
                      <p className="text-[10px] text-slate-400">参考跌幅：{meta.benchmark}</p>
                    )}
                  </CardHeader>
                  <CardContent className="pt-4">
                    <div className="flex items-baseline justify-between mb-3">
                      <span className="text-xs text-slate-500">组合模拟收益</span>
                      <span className={`text-2xl font-bold ${
                        pnl == null ? 'text-slate-400'
                          : pnl < 0 ? 'text-red-500'
                          : 'text-emerald-600'
                      }`}>
                        {pnl != null ? `${pnl >= 0 ? '+' : ''}${pnl.toFixed(1)}%` : '—'}
                      </span>
                    </div>
                    {sorted.length > 0 && (
                      <div className="space-y-1 border-t border-slate-100 pt-2">
                        {sorted.slice(0, 5).map(d => (
                          <div key={d.stockId} className="flex justify-between text-xs">
                            <span className="text-slate-600 truncate max-w-[140px]">{d.stockName}</span>
                            <span className={d.returnPct != null && d.returnPct < 0 ? 'text-red-500' : 'text-emerald-600'}>
                              {d.returnPct != null ? `${d.returnPct >= 0 ? '+' : ''}${d.returnPct.toFixed(1)}%` : '—'}
                            </span>
                          </div>
                        ))}
                        {sorted.length > 5 && (
                          <p className="text-[10px] text-slate-400">另有 {sorted.length - 5} 只...</p>
                        )}
                      </div>
                    )}
                  </CardContent>
                </Card>
              )
            })}
          </div>
        )}
      </div>

      {/* Risk summary */}
      {(risk || loading) && (
        <Card>
          <CardHeader><CardTitle className="text-base">组合风险汇总</CardTitle></CardHeader>
          <CardContent>
            {loading ? (
              <div className="h-16 flex items-center justify-center">
                <div className="w-5 h-5 border-2 border-slate-300 border-t-slate-900 rounded-full animate-spin" />
              </div>
            ) : risk ? (
              <>
                <div className="grid grid-cols-3 gap-4 text-center">
                  <div>
                    <p className="text-2xl font-bold text-slate-900">
                      {risk.weighted_beta != null ? risk.weighted_beta.toFixed(2) : '—'}
                    </p>
                    <p className="text-xs text-slate-500 mt-1">加权 Beta</p>
                  </div>
                  <div>
                    <p className={`text-2xl font-bold ${risk.var_95_pct != null ? 'text-red-500' : 'text-slate-400'}`}>
                      {risk.var_95_pct != null ? `${risk.var_95_pct.toFixed(1)}%` : '—'}
                    </p>
                    <p className="text-xs text-slate-500 mt-1">VaR 95%（日）</p>
                  </div>
                  <div>
                    <p className={`text-2xl font-bold ${risk.portfolio_maxdd != null ? 'text-red-500' : 'text-slate-400'}`}>
                      {risk.portfolio_maxdd != null ? `${risk.portfolio_maxdd.toFixed(1)}%` : '—'}
                    </p>
                    <p className="text-xs text-slate-500 mt-1">1Y 最大回撤</p>
                  </div>
                </div>
                {risk.computed_at && (
                  <p className="text-[10px] text-slate-400 mt-4 text-right">
                    最后更新：{new Date(risk.computed_at).toLocaleString('zh-CN')}
                  </p>
                )}
              </>
            ) : null}
          </CardContent>
        </Card>
      )}
    </div>
  )
}
