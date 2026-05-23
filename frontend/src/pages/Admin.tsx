import { useEffect, useState, useRef, useCallback } from 'react'
import { useAuth } from '@/hooks/use-auth'
import { useToast } from '@/components/Toast'
import { useConfirm } from '@/hooks/use-confirm'
import { useT } from '@/i18n/I18nContext'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Database, Play, RefreshCw, Terminal, Globe, LogIn, UserX, Clock, Square, Pause, PlayCircle } from 'lucide-react'
import { AnimatePresence, motion } from 'framer-motion'

interface MarketStat { market: string; stock_count: number; price_rows: number; latest_date: string; earliest_date: string }
interface DbStatus { markets: MarketStat[]; totals: { stock_count: number; price_rows: number }; tables: { total_mb: number }[] }
interface ProgressData { current: number; total: number; pct: number; name: string }
interface SseEvent { event: string; msg?: string; current?: number; total?: number; pct?: number; name?: string; market?: string }
interface UserRow { id: number; username: string; email: string | null; created_at: string; txn_count: number; portfolio_count: number }
interface CrawlHistoryRow { market: string; started_at: string; ended_at: string; rows_written: number; stocks_failed: number; status: string }

const MARKET_FLAGS: Record<string, string> = { A: 'cn', HK: 'hk', US: 'us', IDX: 'un' }

function todayStr() { return new Date().toISOString().slice(0, 10) }
function daysAgoStr(n: number) { const d = new Date(); d.setDate(d.getDate() - n); return d.toISOString().slice(0, 10) }

// ── Module-level crawl state (survives SPA navigation) ──────────────
const crawlListeners = new Set<() => void>()
function notifyCrawlListeners() { crawlListeners.forEach(fn => fn()) }

let gCrawling: string | null = null
let gProgress: ProgressData | null = null
let gLogs: string[] = []
let gDoneMsg: string | null = null
let gPaused = false
let gEsRef: EventSource | null = null
let gHeartbeat: ReturnType<typeof setInterval> | null = null
let gLastBump = 0

function resetCrawlState() {
  gCrawling = null
  gProgress = null
  gLogs = []
  gDoneMsg = null
  gPaused = false
  if (gHeartbeat) { clearInterval(gHeartbeat); gHeartbeat = null }
  if (gEsRef) { gEsRef.close(); gEsRef = null }
  notifyCrawlListeners()
}

function useCrawlStore() {
  const [, setTick] = useState(0)
  useEffect(() => {
    const fn = () => setTick(t => t + 1)
    crawlListeners.add(fn)
    return () => { crawlListeners.delete(fn) }
  }, [])
  return {
    get crawling() { return gCrawling },
    get progress() { return gProgress },
    get logs() { return gLogs },
    get doneMsg() { return gDoneMsg },
    get paused() { return gPaused },
    get esRef() { return gEsRef },
    setCrawling(v: string | null) { gCrawling = v; notifyCrawlListeners() },
    setProgress(v: ProgressData | null) { gProgress = v; notifyCrawlListeners() },
    setLogs(v: string[] | ((prev: string[]) => string[])) {
      gLogs = typeof v === 'function' ? v(gLogs) : v
      notifyCrawlListeners()
    },
    setDoneMsg(v: string | null) { gDoneMsg = v; notifyCrawlListeners() },
    setPaused(v: boolean) { gPaused = v; notifyCrawlListeners() },
    setEsRef(v: EventSource | null) { gEsRef = v },
    bump() { gLastBump = Date.now() },
    heartbeat() { return gHeartbeat },
    setHeartbeat(v: ReturnType<typeof setInterval> | null) { gHeartbeat = v },
  }
}

export default function Admin() {
  const { isAdmin } = useAuth()
  const toast = useToast()
  const confirm = useConfirm()
  const { t, lang } = useT()
  const [status, setStatus] = useState<DbStatus | null>(null)
  const [loadingStatus, setLoadingStatus] = useState(true)
  const cs = useCrawlStore()
  const [dateStart, setDateStart] = useState(daysAgoStr(10))
  const [dateEnd, setDateEnd] = useState(todayStr())
  const [users, setUsers] = useState<UserRow[]>([])
  const [crawlHistory, setCrawlHistory] = useState<CrawlHistoryRow[]>([])
  const [verbose, setVerbose] = useState(false)
  const logEndRef = useRef<HTMLDivElement>(null)

  // Market label lookup from i18n keys
  const marketLabels: Record<string, string> = {
    A: t.admin.aShares,
    HK: t.admin.hkStocks,
    US: t.admin.usStocks,
    IDX: t.admin.indices,
  }

  const fetchStatus = useCallback(() => {
    setLoadingStatus(true)
    fetch('/investory/api/admin/status', { credentials: 'include' })
      .then(r => r.json())
      .then(setStatus)
      .finally(() => setLoadingStatus(false))
  }, [])

  const fetchUsers = useCallback(() => {
    fetch('/investory/api/admin/users', { credentials: 'include' })
      .then(r => r.json())
      .then(setUsers)
      .catch(() => {})
  }, [])

  const fetchCrawlHistory = useCallback(() => {
    fetch('/investory/api/admin/crawl-history', { credentials: 'include' })
      .then(r => r.json())
      .then(setCrawlHistory)
      .catch(() => {})
  }, [])

  useEffect(() => { fetchStatus(); fetchUsers(); fetchCrawlHistory() }, [fetchStatus, fetchUsers, fetchCrawlHistory])

  useEffect(() => {
    logEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [cs.logs])

  // On mount: check for an ongoing crawl and reconnect
  useEffect(() => {
    fetch('/investory/api/admin/crawl/status', { credentials: 'include' })
      .then(r => r.json())
      .then((data: { active?: boolean; market?: string; label?: string; startDate?: string; endDate?: string; progress?: ProgressData; recentLogs?: string[] }) => {
        if (!data.active || !data.market) return
        // Restore crawl state from server
        cs.setCrawling(data.market)
        if (data.progress) cs.setProgress(data.progress)
        if (data.recentLogs) cs.setLogs(data.recentLogs)

        // Reconnect to the live SSE stream
        if (gEsRef) { gEsRef.close(); gEsRef = null }
        if (gHeartbeat) { clearInterval(gHeartbeat); gHeartbeat = null }

        gLastBump = Date.now()
        const heartbeat = setInterval(() => {
          if (gPaused) return
          if (Date.now() - gLastBump > 15000) {
            cs.setLogs(prev => [...prev, `✗ ${t.admin.connTimeout}`])
            const es = gEsRef
            if (es) { es.close(); gEsRef = null }
            if (gHeartbeat) { clearInterval(gHeartbeat); gHeartbeat = null }
            resetCrawlState()
          }
        }, 3000)
        cs.setHeartbeat(heartbeat)

        const eventSource = new EventSource(`/investory/api/admin/crawl/${data.market}?reconnect=true`)
        cs.setEsRef(eventSource)

        eventSource.addEventListener('status', (e) => {
          gLastBump = Date.now()
          const d: SseEvent = JSON.parse(e.data)
          cs.setLogs(prev => [...prev, `${t.admin.statusLabel} ${d.msg}`])
        })
        eventSource.addEventListener('progress', (e) => {
          gLastBump = Date.now()
          const d: SseEvent = JSON.parse(e.data)
          cs.setProgress({ current: d.current!, total: d.total!, pct: d.pct!, name: d.name! })
        })
        eventSource.addEventListener('info', (e) => {
          gLastBump = Date.now()
          const d: SseEvent = JSON.parse(e.data)
          cs.setLogs(prev => [...prev, `${t.admin.infoLabel} ${d.msg}`])
        })
        eventSource.addEventListener('log', (e) => {
          gLastBump = Date.now()
          const d: SseEvent = JSON.parse(e.data)
          cs.setLogs(prev => [...prev, d.msg!])
        })
        eventSource.addEventListener('done', (e) => {
          if (gHeartbeat) { clearInterval(gHeartbeat); gHeartbeat = null }
          const d: SseEvent = JSON.parse(e.data)
          cs.setLogs(prev => [...prev, `✓ ${d.msg}`])
          cs.setDoneMsg(d.msg!)
          eventSource.close()
          gEsRef = null
          cs.setEsRef(null)
          resetCrawlState()
        })
        eventSource.addEventListener('stopped', (e) => {
          if (gHeartbeat) { clearInterval(gHeartbeat); gHeartbeat = null }
          const d: SseEvent = JSON.parse(e.data)
          cs.setLogs(prev => [...prev, `⏹ ${d.msg}`])
          cs.setDoneMsg(`⏹ ${d.msg}`)
          eventSource.close()
          gEsRef = null
          cs.setEsRef(null)
          resetCrawlState()
        })
        eventSource.addEventListener('error', (e) => {
          gLastBump = Date.now()
          const raw = (e as MessageEvent).data
          if (raw) {
            try {
              const d: SseEvent = JSON.parse(raw)
              cs.setLogs(prev => [...prev, `✗ ${d.msg}`])
            } catch {}
            eventSource.close()
            gEsRef = null
            if (gHeartbeat) { clearInterval(gHeartbeat); gHeartbeat = null }
            resetCrawlState()
          }
        })
        eventSource.onerror = () => {}
      })
      .catch(() => {})
  }, [])

  function marketToScript(market: string): string {
    if (market === 'A') return 'a'
    return market.toLowerCase()
  }

  function startCrawl(market: string) {
    // Close any stale connection first
    if (gEsRef) { gEsRef.close(); gEsRef = null }
    if (gHeartbeat) { clearInterval(gHeartbeat); gHeartbeat = null }

    cs.setCrawling(market)
    cs.setProgress(null)
    cs.setLogs([])
    cs.setDoneMsg(null)
    cs.setPaused(false)

    gLastBump = Date.now()
    const heartbeat = setInterval(() => {
      if (gPaused) return
      if (Date.now() - gLastBump > 15000) {
        cs.setLogs(prev => [...prev, `✗ ${t.admin.connTimeout}`])
        const es = gEsRef
        if (es) { es.close(); gEsRef = null }
        if (gHeartbeat) { clearInterval(gHeartbeat); gHeartbeat = null }
        resetCrawlState()
      }
    }, 3000)
    cs.setHeartbeat(heartbeat)

    const eventSource = new EventSource(`/investory/api/admin/crawl/${market}?start=${dateStart}&end=${dateEnd}`)
    cs.setEsRef(eventSource)

    eventSource.addEventListener('status', (e) => {
      gLastBump = Date.now()
      const d: SseEvent = JSON.parse(e.data)
      cs.setLogs(prev => [...prev, `${t.admin.statusLabel} ${d.msg}`])
    })
    eventSource.addEventListener('progress', (e) => {
      gLastBump = Date.now()
      const d: SseEvent = JSON.parse(e.data)
      cs.setProgress({ current: d.current!, total: d.total!, pct: d.pct!, name: d.name! })
    })
    eventSource.addEventListener('info', (e) => {
      gLastBump = Date.now()
      const d: SseEvent = JSON.parse(e.data)
      cs.setLogs(prev => [...prev, `${t.admin.infoLabel} ${d.msg}`])
    })
    eventSource.addEventListener('log', (e) => {
      gLastBump = Date.now()
      const d: SseEvent = JSON.parse(e.data)
      cs.setLogs(prev => [...prev, d.msg!])
    })
    eventSource.addEventListener('done', (e) => {
      if (gHeartbeat) { clearInterval(gHeartbeat); gHeartbeat = null }
      const d: SseEvent = JSON.parse(e.data)
      cs.setLogs(prev => [...prev, `✓ ${d.msg}`])
      cs.setDoneMsg(d.msg!)
      eventSource.close()
      gEsRef = null
      cs.setEsRef(null)
      resetCrawlState()
      fetchStatus()
    })
    eventSource.addEventListener('stopped', (e) => {
      if (gHeartbeat) { clearInterval(gHeartbeat); gHeartbeat = null }
      const d: SseEvent = JSON.parse(e.data)
      cs.setLogs(prev => [...prev, `⏹ ${d.msg}`])
      cs.setDoneMsg(`⏹ ${d.msg}`)
      eventSource.close()
      gEsRef = null
      cs.setEsRef(null)
      resetCrawlState()
    })
    eventSource.addEventListener('error', (e) => {
      gLastBump = Date.now()
      const raw = (e as MessageEvent).data
      if (raw) {
        try {
          const d: SseEvent = JSON.parse(raw)
          cs.setLogs(prev => [...prev, `✗ ${d.msg}`])
        } catch {}
        eventSource.close()
        gEsRef = null
        if (gHeartbeat) { clearInterval(gHeartbeat); gHeartbeat = null }
        resetCrawlState()
      }
    })
    eventSource.onerror = () => {}
  }

  async function stopCrawl() {
    await fetch('/investory/api/admin/crawl/stop', { method: 'POST', credentials: 'include' })
  }

  async function togglePause() {
    const nowPaused = !cs.paused
    const endpoint = nowPaused ? '/investory/api/admin/crawl/pause' : '/investory/api/admin/crawl/resume'
    const res = await fetch(endpoint, { method: 'POST', credentials: 'include' })
    const data = await res.json()
    if (!data.error) {
      cs.setPaused(nowPaused)
      cs.setLogs(prev => [...prev, nowPaused ? t.admin.pauseDone : t.admin.resumeDone])
    }
  }

  async function impersonate(userId: number) {
    if (!(await confirm(t.admin.confirmImpersonate))) return
    await fetch(`/investory/api/admin/impersonate/${userId}`, { method: 'POST', credentials: 'include' })
    window.location.href = '/investory/dashboard'
  }

  async function deleteUser(userId: number, username: string) {
    if (!(await confirm(`${t.admin.confirmDeleteUserPrefix} "${username}"？${t.admin.irreversible}`))) return
    const res = await fetch(`/investory/api/admin/users/${userId}`, { method: 'DELETE', credentials: 'include' })
    const data = await res.json()
    if (data.error) { toast(data.error, false); return }
    fetchUsers()
  }

  const crawlStatusLabel = (s: string) => {
    if (s === 'ok') return t.admin.crawlOk
    if (s === 'running') return t.admin.crawlRunning
    return t.admin.crawlFailed
  }

  if (!isAdmin) {
    return <div className="flex items-center justify-center h-screen bg-slate-50 text-slate-500">{t.admin.noPermission}</div>
  }

  return (
    <div className="p-6 space-y-6 max-w-6xl mx-auto">
      <div className="flex items-center justify-between flex-wrap gap-3">
        <h2 className="text-xl font-bold text-slate-900 tracking-tight">{t.admin.title}</h2>
        <div className="flex items-center gap-3">
          <div className="flex items-center gap-1.5 text-xs">
            <input type="date" value={dateStart}
              onChange={e => setDateStart(e.target.value)}
              className="h-8 rounded-lg border border-slate-200 px-2 text-xs focus:outline-none focus:ring-2 focus:ring-slate-900/10" />
            <span className="text-slate-400">—</span>
            <input type="date" value={dateEnd}
              onChange={e => setDateEnd(e.target.value)}
              className="h-8 rounded-lg border border-slate-200 px-2 text-xs focus:outline-none focus:ring-2 focus:ring-slate-900/10" />
          </div>
          <button onClick={() => startCrawl('all')}
            disabled={cs.crawling !== null}
            className="inline-flex items-center gap-1.5 h-9 px-4 rounded-xl bg-slate-900 text-white text-xs font-medium hover:bg-slate-800 transition-colors disabled:opacity-40">
            <Globe className="w-3.5 h-3.5" />{t.admin.allMarketCrawl}
          </button>
          <button onClick={() => { fetchStatus(); fetchUsers(); fetchCrawlHistory() }} disabled={loadingStatus}
            className="inline-flex items-center gap-1.5 h-9 px-4 rounded-xl bg-slate-100 text-slate-600 text-xs font-medium hover:bg-slate-200 transition-colors">
            <RefreshCw className={`w-3.5 h-3.5 ${loadingStatus ? 'animate-spin' : ''}`} />{t.common.refresh}
          </button>
        </div>
      </div>

      {/* Market status cards */}
      {loadingStatus ? (
        <div className="flex flex-col items-center justify-center gap-3 h-48"><div className="w-6 h-6 border-2 border-slate-300 border-t-slate-900 rounded-full animate-spin" /><span className="text-sm text-slate-400">{t.admin.loadingDb}</span></div>
      ) : status ? (
        <>
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
            {status.markets.map(m => (
              <Card key={m.market}>
                <CardHeader className="pb-2">
                  <CardTitle className="text-sm flex items-center justify-between">
                    <span className="flex items-center gap-1.5">
                      <img src={`https://flagcdn.com/${MARKET_FLAGS[m.market] ?? m.market}.svg`} alt="" className="w-4 h-3 rounded-sm" />
                      {marketLabels[m.market] ?? m.market}
                    </span>
                    <button onClick={() => startCrawl(marketToScript(m.market))}
                      disabled={cs.crawling !== null}
                      className="inline-flex items-center gap-1 h-7 px-2.5 rounded-lg bg-slate-900 text-white text-[10px] font-medium hover:bg-slate-800 transition-colors disabled:opacity-40">
                      <Play className="w-3 h-3" />{t.admin.crawl}
                    </button>
                  </CardTitle>
                </CardHeader>
                <CardContent>
                  <div className="grid grid-cols-2 gap-x-2 gap-y-1 text-sm">
                    <span className="text-slate-400">{t.admin.stockCount}</span>
                    <span className="text-right font-medium tabular-nums">{m.stock_count.toLocaleString()}</span>
                    <span className="text-slate-400">{t.admin.klineRows}</span>
                    <span className="text-right font-medium tabular-nums">{Number(m.price_rows).toLocaleString()}</span>
                    <span className="text-slate-400">{t.admin.earliest}</span>
                    <span className="text-right font-medium tabular-nums text-xs">{m.earliest_date}</span>
                    <span className="text-slate-400">{t.admin.latest}</span>
                    <span className="text-right font-medium tabular-nums text-xs">{m.latest_date}</span>
                  </div>
                </CardContent>
              </Card>
            ))}
          </div>

          <div className="flex items-center gap-1 text-sm text-slate-500">
            <Database className="w-3.5 h-3.5 mr-1" />
            <span>{t.admin.dbTitle}</span>
            <span className="text-slate-300">|</span>
            <span>{t.admin.totalPrefix}<strong className="text-slate-700">{status.totals.stock_count.toLocaleString()}</strong>{t.admin.stockUnit}</span>
            <span className="text-slate-300">|</span>
            <span><strong className="text-slate-700">{Number(status.totals.price_rows).toLocaleString()}</strong>{t.admin.klineRowsUnit}</span>
            <span className="text-slate-300">|</span>
            <span><strong className="text-slate-700">{status.tables.reduce((s, t) => s + (t.total_mb || 0), 0).toFixed(0)}</strong>{t.admin.dbSizeUnit}</span>
          </div>

          {crawlHistory.length > 0 && (
            <Card>
              <CardHeader>
                <CardTitle className="text-sm flex items-center justify-between">
                  <span className="flex items-center gap-2"><Clock className="w-3.5 h-3.5" />{t.admin.recentCrawls}</span>
                  <button
                    onClick={async () => {
                      if (!(await confirm(t.admin.confirmClearHistory))) return
                      await fetch('/investory/api/admin/crawl-history', { method: 'DELETE', credentials: 'include' })
                      fetchCrawlHistory()
                    }}
                    className="h-6 px-2 rounded-md bg-red-50 text-red-500 hover:bg-red-100 text-[10px] font-medium transition-colors">
                    {t.admin.clearHistory}
                  </button>
                </CardTitle>
              </CardHeader>
              <CardContent className="p-0">
                <div className="hidden md:block overflow-auto">
                  <table className="w-full text-xs">
                    <thead>
                      <tr className="border-b border-slate-100">
                        <th className="text-left font-medium text-slate-500 px-4 py-2">{t.admin.market_}</th>
                        <th className="text-left font-medium text-slate-500 px-3 py-2">{t.admin.startTime}</th>
                        <th className="text-right font-medium text-slate-500 px-3 py-2">{t.admin.rowsWritten}</th>
                        <th className="text-right font-medium text-slate-500 px-3 py-2">{t.admin.failed}</th>
                        <th className="text-center font-medium text-slate-500 px-4 py-2">{t.admin.status}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {crawlHistory.map(h => {
                        const started = h.started_at ? new Date(h.started_at) : null
                        return (
                          <tr key={h.market} className="border-b border-slate-50">
                            <td className="px-4 py-2 font-medium text-slate-700">{marketLabels[h.market] ?? h.market}</td>
                            <td className="px-3 py-2 text-slate-400">
                              {started ? started.toLocaleString(lang === 'zh' ? 'zh-CN' : 'en-US', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : '-'}
                            </td>
                            <td className="px-3 py-2 text-right tabular-nums">{Number(h.rows_written).toLocaleString()}</td>
                            <td className="px-3 py-2 text-right tabular-nums">{h.stocks_failed}</td>
                            <td className="px-4 py-2 text-center">
                              <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-[10px] font-medium ${h.status === 'ok' ? 'bg-emerald-50 text-emerald-600' : h.status === 'running' ? 'bg-amber-50 text-amber-600' : 'bg-red-50 text-red-600'}`}>
                                {crawlStatusLabel(h.status)}
                              </span>
                            </td>
                          </tr>
                        )
                      })}
                    </tbody>
                  </table>
                </div>
                <div className="md:hidden divide-y divide-slate-50">
                  {crawlHistory.map(h => {
                    const started = h.started_at ? new Date(h.started_at) : null
                    const statusBadge = h.status === 'ok' ? 'bg-emerald-50 text-emerald-600' : h.status === 'running' ? 'bg-amber-50 text-amber-600' : 'bg-red-50 text-red-600'
                    return (
                      <div key={h.market} className="px-4 py-3 space-y-2">
                        <div className="flex items-center justify-between">
                          <span className="font-medium text-slate-700 text-xs">{marketLabels[h.market] ?? h.market}</span>
                          <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-[10px] font-medium ${statusBadge}`}>
                            {crawlStatusLabel(h.status)}
                          </span>
                        </div>
                        <div className="flex items-center gap-x-4 text-xs text-slate-400">
                          <span>{started ? started.toLocaleString(lang === 'zh' ? 'zh-CN' : 'en-US', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : '-'}</span>
                          <span>{t.admin.rowsWritten}: {Number(h.rows_written).toLocaleString()}</span>
                          <span>{t.admin.failed}: {h.stocks_failed}</span>
                        </div>
                      </div>
                    )
                  })}
                </div>
              </CardContent>
            </Card>
          )}
        </>
      ) : (
        <div className="text-center py-12 text-slate-400 text-sm">{t.admin.loadFailed}</div>
      )}

      {/* Crawl progress & log */}
      <AnimatePresence>
        {cs.crawling && (
          <motion.div
            key="crawl-progress"
            initial={{ opacity: 0, y: -12 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -12 }}
            transition={{ duration: 0.25 }}>
            <Card>
              <CardHeader>
                <CardTitle className="text-sm flex items-center gap-2">
                  <Terminal className="w-3.5 h-3.5" />
                  {cs.paused ? t.admin.paused : t.admin.crawling} {cs.crawling === 'all' ? t.admin.allMarkets : cs.crawling!.toUpperCase()}
                  <button onClick={() => setVerbose(!verbose)}
                    className={`h-6 px-2 rounded-md text-[10px] font-medium ml-2 transition-colors ${verbose ? 'bg-slate-200 text-slate-600' : 'bg-slate-100 text-slate-500'}`}>
                    {verbose ? t.admin.brief : t.admin.verbose}
                  </button>
                  <div className="ml-auto flex items-center gap-1.5">
                    {cs.progress && (
                      <span className="text-xs font-normal text-slate-400 mr-1">
                        {cs.progress.current}/{cs.progress.total} ({cs.progress.pct.toFixed(1)}%)
                      </span>
                    )}
                    <button onClick={togglePause}
                      className={`inline-flex items-center gap-1 h-6 px-2 rounded-md text-[10px] font-medium transition-colors ${cs.paused ? 'bg-emerald-100 text-emerald-700 hover:bg-emerald-200' : 'bg-amber-100 text-amber-700 hover:bg-amber-200'}`}>
                      {cs.paused ? <><PlayCircle className="w-3 h-3" />{t.admin.resume}</> : <><Pause className="w-3 h-3" />{t.admin.pause}</>}
                    </button>
                    <button onClick={stopCrawl}
                      className="inline-flex items-center gap-1 h-6 px-2 rounded-md bg-red-100 text-red-600 hover:bg-red-200 text-[10px] font-medium transition-colors">
                      <Square className="w-3 h-3" />{t.admin.stop}
                    </button>
                  </div>
                </CardTitle>
              </CardHeader>
              <CardContent>
                {cs.progress ? (
                  <div className="mb-3">
                    <div className="flex justify-between text-xs text-slate-500 mb-1">
                      <span className="truncate max-w-[300px]">{cs.progress.name}</span>
                      <span>{cs.progress.pct.toFixed(1)}%</span>
                    </div>
                    <div className="w-full bg-slate-100 rounded-full h-2 overflow-hidden">
                      <div className="bg-slate-900 h-full rounded-full transition-all duration-300" style={{ width: `${cs.progress.pct}%` }} />
                    </div>
                  </div>
                ) : (
                  <div className="flex items-center gap-2 text-sm text-slate-400 py-2 mb-3">
                    <div className="w-4 h-4 border-2 border-slate-300 border-t-slate-900 rounded-full animate-spin" />
                    {t.admin.starting}
                  </div>
                )}
                <AnimatePresence>
                  {verbose && (
                    <motion.div
                      key="crawl-log"
                      initial={{ opacity: 0, height: 0 }}
                      animate={{ opacity: 1, height: 'auto' }}
                      exit={{ opacity: 0, height: 0 }}
                      transition={{ duration: 0.2 }}
                      className="bg-slate-900 rounded-xl p-4 max-h-80 overflow-auto font-mono text-xs">
                      {cs.logs.map((line, i) => (
                        <div key={i} className={`py-0.5 ${line.startsWith('✓') ? 'text-emerald-400' : line.startsWith('✗') ? 'text-red-400' : line.startsWith(t.admin.statusLabel) ? 'text-sky-400' : line.startsWith(t.admin.infoLabel) ? 'text-slate-400' : 'text-slate-300'}`}>
                          {line}
                        </div>
                      ))}
                      {cs.logs.length === 0 && <div className="text-slate-500">{t.admin.waitingOutput}</div>}
                      <div ref={logEndRef} />
                    </motion.div>
                  )}
                </AnimatePresence>
              </CardContent>
            </Card>
          </motion.div>
        )}
      </AnimatePresence>

      <AnimatePresence>
        {cs.doneMsg && !cs.crawling && (
          <motion.div
            key="crawl-done"
            initial={{ opacity: 0, y: -8 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0 }}
            className={`rounded-xl px-4 py-3 text-sm ${cs.doneMsg!.startsWith('⏹') ? 'bg-amber-50 text-amber-700' : 'bg-emerald-50 text-emerald-700'}`}>
            {cs.doneMsg}
          </motion.div>
        )}
      </AnimatePresence>

      {/* User list */}
      <Card>
        <CardHeader><CardTitle className="text-sm">{t.admin.userManagement}</CardTitle></CardHeader>
        <CardContent className="p-0">
          <table className="w-full text-xs">
            <thead>
              <tr className="border-b border-slate-100">
                <th className="text-left font-medium text-slate-500 px-4 py-2">{t.admin.userId}</th>
                <th className="text-left font-medium text-slate-500 px-3 py-2">{t.admin.username}</th>
                <th className="text-left font-medium text-slate-500 px-3 py-2 hidden sm:table-cell">{t.admin.registerTime}</th>
                <th className="text-right font-medium text-slate-500 px-3 py-2 hidden sm:table-cell">{t.admin.txnCount}</th>
                <th className="text-right font-medium text-slate-500 px-4 py-2">{t.admin.actions}</th>
              </tr>
            </thead>
            <tbody>
              {users.map(u => (
                <tr key={u.id} className="border-b border-slate-50 hover:bg-slate-50/50">
                  <td className="px-4 py-2 text-slate-400 tabular-nums">{u.id}</td>
                  <td className="px-3 py-2 font-medium text-slate-700">{u.username}</td>
                  <td className="px-3 py-2 text-slate-400 hidden sm:table-cell">{u.created_at?.slice(0, 10)}</td>
                  <td className="px-3 py-2 text-right tabular-nums hidden sm:table-cell">{Number(u.txn_count).toLocaleString()}</td>
                  <td className="px-4 py-2 text-right">
                    <div className="flex items-center gap-1.5 justify-end">
                      <button onClick={() => impersonate(u.id)}
                        className="inline-flex items-center gap-1 h-6 px-2 rounded-md bg-slate-100 text-slate-600 hover:bg-slate-200 text-[10px] transition-colors">
                        <LogIn className="w-3 h-3" />{t.admin.loginAs}
                      </button>
                      <button onClick={() => deleteUser(u.id, u.username)}
                        className="inline-flex items-center gap-1 h-6 px-2 rounded-md bg-red-50 text-red-500 hover:bg-red-100 text-[10px] transition-colors">
                        <UserX className="w-3 h-3" />{t.admin.deregister}
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
              {users.length === 0 && (
                <tr><td colSpan={5} className="text-center py-8 text-slate-400">{t.common.loading}</td></tr>
              )}
            </tbody>
          </table>
        </CardContent>
      </Card>
    </div>
  )
}
