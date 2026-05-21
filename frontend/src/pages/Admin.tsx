import { useEffect, useState, useRef, useCallback } from 'react'
import { useAuth } from '@/hooks/use-auth'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Database, HardDrive, Play, RefreshCw, Terminal, Globe } from 'lucide-react'

interface MarketStat { market: string; stock_count: number; price_rows: number; latest_date: string; earliest_date: string }
interface TableStat { table_name: string; data_mb: number; index_mb: number; total_mb: number; table_rows: number }
interface DbStatus { markets: MarketStat[]; totals: { stock_count: number; price_rows: number }; tables: TableStat[] }
interface ProgressData { current: number; total: number; pct: number; name: string }
interface SseEvent { event: string; msg?: string; current?: number; total?: number; pct?: number; name?: string; market?: string }

const MARKET_LABELS: Record<string, string> = { SH: 'A股(沪)', SZ: 'A股(深)', HK: '港股', US: '美股' }

const DAYS_OPTIONS = [
  { label: '10天', value: 10 },
  { label: '30天', value: 30 },
  { label: '90天', value: 90 },
  { label: '1年', value: 365 },
  { label: '3年', value: 1095 },
  { label: '全部', value: 36500 },
]

export default function Admin() {
  const { isAdmin } = useAuth()
  const [status, setStatus] = useState<DbStatus | null>(null)
  const [loadingStatus, setLoadingStatus] = useState(true)
  const [crawling, setCrawling] = useState<string | null>(null)
  const [progress, setProgress] = useState<ProgressData | null>(null)
  const [logs, setLogs] = useState<string[]>([])
  const [doneMsg, setDoneMsg] = useState<string | null>(null)
  const [daysBack, setDaysBack] = useState(10)
  const logEndRef = useRef<HTMLDivElement>(null)

  const fetchStatus = useCallback(() => {
    setLoadingStatus(true)
    fetch('/investory/api/admin/status', { credentials: 'include' })
      .then(r => r.json())
      .then(setStatus)
      .finally(() => setLoadingStatus(false))
  }, [])

  useEffect(() => { fetchStatus() }, [fetchStatus])

  useEffect(() => {
    logEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [logs])

  function startCrawl(market: string) {
    setCrawling(market)
    setProgress(null)
    setLogs([])
    setDoneMsg(null)

    const eventSource = new EventSource(`/investory/api/admin/crawl/${market}?days=${daysBack}`)

    eventSource.addEventListener('status', (e) => {
      const d: SseEvent = JSON.parse(e.data)
      setLogs(prev => [...prev, `[状态] ${d.msg}`])
    })
    eventSource.addEventListener('progress', (e) => {
      const d: SseEvent = JSON.parse(e.data)
      setProgress({ current: d.current!, total: d.total!, pct: d.pct!, name: d.name! })
      setLogs(prev => [...prev, `[${d.current}/${d.total} ${d.pct}%] ${d.name}`])
    })
    eventSource.addEventListener('info', (e) => {
      const d: SseEvent = JSON.parse(e.data)
      setLogs(prev => [...prev, `[信息] ${d.msg}`])
    })
    eventSource.addEventListener('log', (e) => {
      const d: SseEvent = JSON.parse(e.data)
      setLogs(prev => [...prev, d.msg!])
    })
    eventSource.addEventListener('done', (e) => {
      const d: SseEvent = JSON.parse(e.data)
      setDoneMsg(d.msg!)
      setLogs(prev => [...prev, `✓ ${d.msg}`])
      setCrawling(null)
      setProgress(null)
      fetchStatus()
      eventSource.close()
    })
    eventSource.addEventListener('error', (e) => {
      let msg = '连接错误'
      try { msg = JSON.parse((e as any).data)?.msg || msg } catch {}
      setLogs(prev => [...prev, `✗ ${msg}`])
      setCrawling(null)
      eventSource.close()
    })
  }

  if (!isAdmin) {
    return <div className="flex items-center justify-center h-screen bg-slate-50 text-slate-500">无权限</div>
  }

  return (
    <div className="p-6 space-y-6 max-w-6xl mx-auto">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-xl font-bold text-slate-900 tracking-tight">管理后台</h2>
          <p className="text-xs text-slate-400 mt-1">数据库状态 & 数据抓取控制</p>
        </div>
        <div className="flex items-center gap-3">
          <div className="flex items-center gap-1.5">
            <span className="text-[10px] text-slate-400">范围</span>
            <div className="flex bg-slate-100 rounded-lg p-0.5">
              {DAYS_OPTIONS.map(o => (
                <button key={o.value} onClick={() => setDaysBack(o.value)}
                  className={`px-2 py-1 rounded-md text-[10px] font-medium transition-colors ${daysBack === o.value ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'}`}>
                  {o.label}
                </button>
              ))}
            </div>
          </div>
          <button onClick={() => startCrawl('all')}
            disabled={crawling !== null}
            className="inline-flex items-center gap-1.5 h-9 px-4 rounded-xl bg-slate-900 text-white text-xs font-medium hover:bg-slate-800 transition-colors disabled:opacity-40">
            <Globe className="w-3.5 h-3.5" />全市场抓取
          </button>
          <button onClick={fetchStatus} disabled={loadingStatus}
            className="inline-flex items-center gap-1.5 h-9 px-4 rounded-xl bg-slate-100 text-slate-600 text-xs font-medium hover:bg-slate-200 transition-colors">
            <RefreshCw className={`w-3.5 h-3.5 ${loadingStatus ? 'animate-spin' : ''}`} />刷新
          </button>
        </div>
      </div>

      {/* Market status cards */}
      {loadingStatus ? (
        <div className="flex items-center justify-center h-48"><div className="w-6 h-6 border-2 border-slate-300 border-t-slate-900 rounded-full animate-spin" /></div>
      ) : status ? (
        <>
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
            {status.markets.map(m => (
              <Card key={m.market}>
                <CardHeader className="pb-2">
                  <CardTitle className="text-sm flex items-center justify-between">
                    <span>{MARKET_LABELS[m.market] ?? m.market}</span>
                    <button onClick={() => startCrawl(m.market.toLowerCase())}
                      disabled={crawling !== null}
                      className="inline-flex items-center gap-1 h-7 px-2.5 rounded-lg bg-slate-900 text-white text-[10px] font-medium hover:bg-slate-800 transition-colors disabled:opacity-40">
                      <Play className="w-3 h-3" />抓取
                    </button>
                  </CardTitle>
                </CardHeader>
                <CardContent>
                  <div className="grid grid-cols-2 gap-x-2 gap-y-1 text-sm">
                    <span className="text-slate-400">股票数</span>
                    <span className="text-right font-medium tabular-nums">{m.stock_count.toLocaleString()}</span>
                    <span className="text-slate-400">K线行数</span>
                    <span className="text-right font-medium tabular-nums">{Number(m.price_rows).toLocaleString()}</span>
                    <span className="text-slate-400">最早</span>
                    <span className="text-right font-medium tabular-nums text-xs">{m.earliest_date}</span>
                    <span className="text-slate-400">最新</span>
                    <span className="text-right font-medium tabular-nums text-xs">{m.latest_date}</span>
                  </div>
                </CardContent>
              </Card>
            ))}
          </div>

          {/* Totals */}
          <div className="flex items-center gap-6 text-sm text-slate-500">
            <span className="flex items-center gap-1.5"><Database className="w-3.5 h-3.5" />总股票 {status.totals.stock_count.toLocaleString()} 只</span>
            <span className="flex items-center gap-1.5"><HardDrive className="w-3.5 h-3.5" />K线 {Number(status.totals.price_rows).toLocaleString()} 行</span>
            <span className="flex items-center gap-1.5">
              数据库占 {status.tables.reduce((s, t) => s + t.total_mb, 0).toFixed(1)} MB
            </span>
          </div>

          {/* Tables detail */}
          <Card>
            <CardHeader><CardTitle className="text-sm">数据表</CardTitle></CardHeader>
            <CardContent className="p-0">
              <table className="w-full text-xs">
                <thead>
                  <tr className="border-b border-slate-100">
                    <th className="text-left font-medium text-slate-500 px-4 py-2">表名</th>
                    <th className="text-right font-medium text-slate-500 px-3 py-2">数据(MB)</th>
                    <th className="text-right font-medium text-slate-500 px-3 py-2">索引(MB)</th>
                    <th className="text-right font-medium text-slate-500 px-3 py-2">合计(MB)</th>
                    <th className="text-right font-medium text-slate-500 px-4 py-2">行数</th>
                  </tr>
                </thead>
                <tbody>
                  {status.tables.map(t => (
                    <tr key={t.table_name} className="border-b border-slate-50">
                      <td className="px-4 py-1.5 font-medium text-slate-700">{t.table_name}</td>
                      <td className="px-3 py-1.5 text-right tabular-nums">{t.data_mb}</td>
                      <td className="px-3 py-1.5 text-right tabular-nums">{t.index_mb}</td>
                      <td className="px-3 py-1.5 text-right tabular-nums font-semibold">{t.total_mb}</td>
                      <td className="px-4 py-1.5 text-right tabular-nums">{Number(t.table_rows).toLocaleString()}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </CardContent>
          </Card>
        </>
      ) : (
        <div className="text-center py-12 text-slate-400 text-sm">加载失败</div>
      )}

      {/* Crawl progress & log */}
      {crawling && (
        <Card>
          <CardHeader>
            <CardTitle className="text-sm flex items-center gap-2">
              <Terminal className="w-3.5 h-3.5" />
              正在抓取 {crawling === 'all' ? '全市场' : crawling.toUpperCase()}
              {progress && (
                <span className="ml-auto text-xs font-normal text-slate-400">
                  {progress.current}/{progress.total} ({progress.pct.toFixed(1)}%)
                </span>
              )}
            </CardTitle>
          </CardHeader>
          <CardContent>
            {progress && (
              <div className="mb-3">
                <div className="flex justify-between text-xs text-slate-500 mb-1">
                  <span className="truncate max-w-[300px]">{progress.name}</span>
                  <span>{progress.pct.toFixed(1)}%</span>
                </div>
                <div className="w-full bg-slate-100 rounded-full h-2 overflow-hidden">
                  <div className="bg-slate-900 h-full rounded-full transition-all duration-300" style={{ width: `${progress.pct}%` }} />
                </div>
              </div>
            )}
            <div className="bg-slate-900 rounded-xl p-4 max-h-80 overflow-auto font-mono text-xs">
              {logs.map((line, i) => (
                <div key={i} className={`py-0.5 ${line.startsWith('✓') ? 'text-emerald-400' : line.startsWith('✗') ? 'text-red-400' : line.startsWith('[状态]') ? 'text-sky-400' : line.startsWith('[信息]') ? 'text-slate-400' : 'text-slate-300'}`}>
                  {line}
                </div>
              ))}
              {logs.length === 0 && <div className="text-slate-500">等待输出...</div>}
              <div ref={logEndRef} />
            </div>
          </CardContent>
        </Card>
      )}

      {doneMsg && !crawling && (
        <div className="bg-emerald-50 text-emerald-700 rounded-xl px-4 py-3 text-sm">{doneMsg}</div>
      )}
    </div>
  )
}
