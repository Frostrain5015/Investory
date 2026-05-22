import { useEffect, useState, useRef, useCallback } from 'react'
import { useAuth } from '@/hooks/use-auth'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Database, HardDrive, Play, RefreshCw, Terminal, Globe, LogIn, UserX, Clock, Square, Pause, PlayCircle } from 'lucide-react'
import { AnimatePresence, motion } from 'framer-motion'

interface MarketStat { market: string; stock_count: number; price_rows: number; latest_date: string; earliest_date: string }
interface DbStatus { markets: MarketStat[]; totals: { stock_count: number; price_rows: number }; tables: { total_mb: number }[] }
interface ProgressData { current: number; total: number; pct: number; name: string }
interface SseEvent { event: string; msg?: string; current?: number; total?: number; pct?: number; name?: string; market?: string }
interface UserRow { id: number; username: string; email: string | null; created_at: string; txn_count: number; portfolio_count: number }
interface CrawlHistoryRow { market: string; started_at: string; ended_at: string; rows_written: number; stocks_failed: number; status: string }

const MARKET_LABELS: Record<string, string> = { SH: 'A股(沪)', SZ: 'A股(深)', HK: '港股', US: '美股', IDX: '全球指数' }
const MARKET_FLAGS: Record<string, string> = { SH: 'cn', SZ: 'cn', HK: 'hk', US: 'us', IDX: 'un' }

function todayStr() { return new Date().toISOString().slice(0, 10) }
function daysAgoStr(n: number) { const d = new Date(); d.setDate(d.getDate() - n); return d.toISOString().slice(0, 10) }

export default function Admin() {
  const { isAdmin } = useAuth()
  const [status, setStatus] = useState<DbStatus | null>(null)
  const [loadingStatus, setLoadingStatus] = useState(true)
  const [crawling, setCrawling] = useState<string | null>(null)
  const [progress, setProgress] = useState<ProgressData | null>(null)
  const [logs, setLogs] = useState<string[]>([])
  const [doneMsg, setDoneMsg] = useState<string | null>(null)
  const [dateStart, setDateStart] = useState(daysAgoStr(10))
  const [dateEnd, setDateEnd] = useState(todayStr())
  const [users, setUsers] = useState<UserRow[]>([])
  const [crawlHistory, setCrawlHistory] = useState<CrawlHistoryRow[]>([])
  const [verbose, setVerbose] = useState(false)
  const [paused, setPaused] = useState(false)
  const pausedRef = useRef(false)
  const esRef = useRef<EventSource | null>(null)
  const logEndRef = useRef<HTMLDivElement>(null)

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
  }, [logs])

  function marketToScript(market: string): string {
    return market.toLowerCase()  // sh/sz/hk/us/idx pass through directly
  }

  function startCrawl(market: string) {
    setCrawling(market)
    setProgress(null)
    setLogs([])
    setDoneMsg(null)
    setPaused(false)
    pausedRef.current = false

    let lastEventTime = Date.now()
    const bump = () => { lastEventTime = Date.now() }
    const heartbeat = setInterval(() => {
      if (pausedRef.current) return
      if (Date.now() - lastEventTime > 15000) {
        setLogs(prev => [...prev, '✗ 连接超时'])
        setCrawling(null)
        setPaused(false)
        eventSource.close()
        clearInterval(heartbeat)
      }
    }, 3000)

    const eventSource = new EventSource(`/investory/api/admin/crawl/${market}?start=${dateStart}&end=${dateEnd}`)
    esRef.current = eventSource

    eventSource.addEventListener('status', (e) => {
      bump()
      const d: SseEvent = JSON.parse(e.data)
      setLogs(prev => [...prev, `[状态] ${d.msg}`])
    })
    eventSource.addEventListener('progress', (e) => {
      bump()
      const d: SseEvent = JSON.parse(e.data)
      setProgress({ current: d.current!, total: d.total!, pct: d.pct!, name: d.name! })
    })
    eventSource.addEventListener('info', (e) => {
      bump()
      const d: SseEvent = JSON.parse(e.data)
      setLogs(prev => [...prev, `[信息] ${d.msg}`])
    })
    eventSource.addEventListener('log', (e) => {
      bump()
      const d: SseEvent = JSON.parse(e.data)
      setLogs(prev => [...prev, d.msg!])
    })
    eventSource.addEventListener('done', (e) => {
      clearInterval(heartbeat)
      const d: SseEvent = JSON.parse(e.data)
      setDoneMsg(d.msg!)
      setLogs(prev => [...prev, `✓ ${d.msg}`])
      setCrawling(null)
      setPaused(false)
      setProgress(null)
      fetchStatus()
      eventSource.close()
      esRef.current = null
    })
    eventSource.addEventListener('stopped', (e) => {
      clearInterval(heartbeat)
      const d: SseEvent = JSON.parse(e.data)
      setLogs(prev => [...prev, `⏹ ${d.msg}`])
      setDoneMsg(`⏹ ${d.msg}`)
      setCrawling(null)
      setPaused(false)
      setProgress(null)
      eventSource.close()
      esRef.current = null
    })
    eventSource.addEventListener('error', (e) => {
      bump()
      const raw = (e as MessageEvent).data
      if (raw) {
        try {
          const d: SseEvent = JSON.parse(raw)
          setLogs(prev => [...prev, `✗ ${d.msg}`])
        } catch {}
        setCrawling(null)
        setPaused(false)
        eventSource.close()
        esRef.current = null
        clearInterval(heartbeat)
      }
      // Native errors (no .data) are reconnection attempts — let heartbeat handle timeout
    })
    eventSource.onerror = () => {}
  }

  async function stopCrawl() {
    await fetch('/investory/api/admin/crawl/stop', { method: 'POST', credentials: 'include' })
  }

  async function togglePause() {
    const nowPaused = !paused
    const endpoint = nowPaused ? '/investory/api/admin/crawl/pause' : '/investory/api/admin/crawl/resume'
    const res = await fetch(endpoint, { method: 'POST', credentials: 'include' })
    const data = await res.json()
    if (!data.error) {
      setPaused(nowPaused)
      pausedRef.current = nowPaused
      setLogs(prev => [...prev, nowPaused ? '⏸ 已暂停' : '▶ 已继续'])
    }
  }

  async function impersonate(userId: number) {
    if (!confirm('确认以该用户身份登录？你可以通过侧栏"管理后台"返回。')) return
    await fetch(`/investory/api/admin/impersonate/${userId}`, { method: 'POST', credentials: 'include' })
    window.location.href = '/investory/dashboard'
  }

  async function deleteUser(userId: number, username: string) {
    if (!confirm(`确认注销用户 "${username}" 及其所有数据？此操作不可撤销。`)) return
    const res = await fetch(`/investory/api/admin/users/${userId}`, { method: 'DELETE', credentials: 'include' })
    const data = await res.json()
    if (data.error) { alert(data.error); return }
    fetchUsers()
  }

  if (!isAdmin) {
    return <div className="flex items-center justify-center h-screen bg-slate-50 text-slate-500">无权限</div>
  }

  return (
    <div className="p-6 space-y-6 max-w-6xl mx-auto">
      <div className="flex items-center justify-between">
        <h2 className="text-xl font-bold text-slate-900 tracking-tight">管理后台</h2>
        <div className="flex items-center gap-3">
          <div className="flex items-center gap-1.5 text-xs">
            <input type="text" value={dateStart} onChange={e => { const v = e.target.value.replace(/[^0-9-]/g, '').slice(0, 10); setDateStart(v) }}
              onBlur={e => { if (!/^\d{4}-\d{2}-\d{2}$/.test(e.target.value)) setDateStart(daysAgoStr(10)) }}
              placeholder="YYY-MM-DD" style={{ width: 110 }}
              className="h-8 rounded-lg border border-slate-200 px-2 text-xs focus:outline-none focus:ring-2 focus:ring-slate-900/10 font-mono" />
            <span className="text-slate-400">—</span>
            <input type="text" value={dateEnd} onChange={e => { const v = e.target.value.replace(/[^0-9-]/g, '').slice(0, 10); setDateEnd(v) }}
              onBlur={e => { if (!/^\d{4}-\d{2}-\d{2}$/.test(e.target.value)) setDateEnd(todayStr()) }}
              placeholder="YYY-MM-DD" style={{ width: 110 }}
              className="h-8 rounded-lg border border-slate-200 px-2 text-xs focus:outline-none focus:ring-2 focus:ring-slate-900/10 font-mono" />
          </div>
          <button onClick={() => startCrawl('idx')}
            disabled={crawling !== null}
            className="inline-flex items-center gap-1.5 h-9 px-4 rounded-xl border border-slate-200 text-slate-600 text-xs font-medium hover:bg-slate-50 transition-colors disabled:opacity-40">
            指数抓取
          </button>
          <button onClick={() => startCrawl('all')}
            disabled={crawling !== null}
            className="inline-flex items-center gap-1.5 h-9 px-4 rounded-xl bg-slate-900 text-white text-xs font-medium hover:bg-slate-800 transition-colors disabled:opacity-40">
            <Globe className="w-3.5 h-3.5" />全市场抓取
          </button>
          <button onClick={() => { fetchStatus(); fetchUsers(); fetchCrawlHistory() }} disabled={loadingStatus}
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
                    <span className="flex items-center gap-1.5">
                      <img src={`https://flagcdn.com/${MARKET_FLAGS[m.market] ?? m.market}.svg`} alt="" className="w-4 h-3 rounded-sm" />
                      {MARKET_LABELS[m.market] ?? m.market}
                    </span>
                    <button onClick={() => startCrawl(marketToScript(m.market))}
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

          <div className="flex items-center gap-6 text-sm text-slate-500">
            <span className="flex items-center gap-1.5"><Database className="w-3.5 h-3.5" />总股票 {status.totals.stock_count.toLocaleString()} 只</span>
            <span className="flex items-center gap-1.5"><HardDrive className="w-3.5 h-3.5" />K线 {Number(status.totals.price_rows).toLocaleString()} 行</span>
            <span className="flex items-center gap-1.5"><HardDrive className="w-3.5 h-3.5" />{status.tables.reduce((s, t) => s + (t.total_mb || 0), 0).toFixed(0)} MB</span>
          </div>

          {crawlHistory.length > 0 && (
            <Card>
              <CardHeader><CardTitle className="text-sm flex items-center gap-2"><Clock className="w-3.5 h-3.5" />最近定时抓取</CardTitle></CardHeader>
              <CardContent className="p-0">
                <table className="w-full text-xs">
                  <thead>
                    <tr className="border-b border-slate-100">
                      <th className="text-left font-medium text-slate-500 px-4 py-2">市场</th>
                      <th className="text-left font-medium text-slate-500 px-3 py-2">开始时间</th>
                      <th className="text-right font-medium text-slate-500 px-3 py-2">写入行</th>
                      <th className="text-right font-medium text-slate-500 px-3 py-2">失败</th>
                      <th className="text-center font-medium text-slate-500 px-4 py-2">状态</th>
                    </tr>
                  </thead>
                  <tbody>
                    {crawlHistory.map(h => {
                      const started = h.started_at ? new Date(h.started_at) : null
                      return (
                        <tr key={h.market} className="border-b border-slate-50">
                          <td className="px-4 py-2 font-medium text-slate-700">{MARKET_LABELS[h.market] ?? h.market}</td>
                          <td className="px-3 py-2 text-slate-400">
                            {started ? started.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : '-'}
                          </td>
                          <td className="px-3 py-2 text-right tabular-nums">{Number(h.rows_written).toLocaleString()}</td>
                          <td className="px-3 py-2 text-right tabular-nums">{h.stocks_failed}</td>
                          <td className="px-4 py-2 text-center">
                            <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-[10px] font-medium ${h.status === 'ok' ? 'bg-emerald-50 text-emerald-600' : h.status === 'running' ? 'bg-amber-50 text-amber-600' : 'bg-red-50 text-red-600'}`}>
                              {h.status === 'ok' ? '成功' : h.status === 'running' ? '运行中' : '失败'}
                            </span>
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </CardContent>
            </Card>
          )}
        </>
      ) : (
        <div className="text-center py-12 text-slate-400 text-sm">加载失败</div>
      )}

      {/* Crawl progress & log */}
      <AnimatePresence>
        {crawling && (
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
                  {paused ? '已暂停' : '正在抓取'} {crawling === 'all' ? '全市场' : crawling!.toUpperCase()}
                  <button onClick={() => setVerbose(!verbose)}
                    className={`h-6 px-2 rounded-md text-[10px] font-medium ml-2 transition-colors ${verbose ? 'bg-slate-200 text-slate-600' : 'bg-slate-100 text-slate-500'}`}>
                    {verbose ? '简略' : '详细'}
                  </button>
                  <div className="ml-auto flex items-center gap-1.5">
                    {progress && (
                      <span className="text-xs font-normal text-slate-400 mr-1">
                        {progress.current}/{progress.total} ({progress.pct.toFixed(1)}%)
                      </span>
                    )}
                    <button onClick={togglePause}
                      className={`inline-flex items-center gap-1 h-6 px-2 rounded-md text-[10px] font-medium transition-colors ${paused ? 'bg-emerald-100 text-emerald-700 hover:bg-emerald-200' : 'bg-amber-100 text-amber-700 hover:bg-amber-200'}`}>
                      {paused ? <><PlayCircle className="w-3 h-3" />继续</> : <><Pause className="w-3 h-3" />暂停</>}
                    </button>
                    <button onClick={stopCrawl}
                      className="inline-flex items-center gap-1 h-6 px-2 rounded-md bg-red-100 text-red-600 hover:bg-red-200 text-[10px] font-medium transition-colors">
                      <Square className="w-3 h-3" />停止
                    </button>
                  </div>
                </CardTitle>
              </CardHeader>
              <CardContent>
                {progress ? (
                  <div className="mb-3">
                    <div className="flex justify-between text-xs text-slate-500 mb-1">
                      <span className="truncate max-w-[300px]">{progress.name}</span>
                      <span>{progress.pct.toFixed(1)}%</span>
                    </div>
                    <div className="w-full bg-slate-100 rounded-full h-2 overflow-hidden">
                      <div className="bg-slate-900 h-full rounded-full transition-all duration-300" style={{ width: `${progress.pct}%` }} />
                    </div>
                  </div>
                ) : (
                  <div className="flex items-center gap-2 text-sm text-slate-400 py-2 mb-3">
                    <div className="w-4 h-4 border-2 border-slate-300 border-t-slate-900 rounded-full animate-spin" />
                    正在启动...
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
                      {logs.map((line, i) => (
                        <div key={i} className={`py-0.5 ${line.startsWith('✓') ? 'text-emerald-400' : line.startsWith('✗') ? 'text-red-400' : line.startsWith('[状态]') ? 'text-sky-400' : line.startsWith('[信息]') ? 'text-slate-400' : 'text-slate-300'}`}>
                          {line}
                        </div>
                      ))}
                      {logs.length === 0 && <div className="text-slate-500">等待输出...</div>}
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
        {doneMsg && !crawling && (
          <motion.div
            key="crawl-done"
            initial={{ opacity: 0, y: -8 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0 }}
            className={`rounded-xl px-4 py-3 text-sm ${doneMsg!.startsWith('⏹') ? 'bg-amber-50 text-amber-700' : 'bg-emerald-50 text-emerald-700'}`}>
            {doneMsg}
          </motion.div>
        )}
      </AnimatePresence>

      {/* User list */}
      <Card>
        <CardHeader><CardTitle className="text-sm">用户管理</CardTitle></CardHeader>
        <CardContent className="p-0">
          <table className="w-full text-xs">
            <thead>
              <tr className="border-b border-slate-100">
                <th className="text-left font-medium text-slate-500 px-4 py-2">ID</th>
                <th className="text-left font-medium text-slate-500 px-3 py-2">用户名</th>
                <th className="text-left font-medium text-slate-500 px-3 py-2 hidden sm:table-cell">注册时间</th>
                <th className="text-right font-medium text-slate-500 px-3 py-2 hidden sm:table-cell">交易数</th>
                <th className="text-right font-medium text-slate-500 px-4 py-2">操作</th>
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
                        <LogIn className="w-3 h-3" />登录为
                      </button>
                      <button onClick={() => deleteUser(u.id, u.username)}
                        className="inline-flex items-center gap-1 h-6 px-2 rounded-md bg-red-50 text-red-500 hover:bg-red-100 text-[10px] transition-colors">
                        <UserX className="w-3 h-3" />注销
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
              {users.length === 0 && (
                <tr><td colSpan={5} className="text-center py-8 text-slate-400">加载中...</td></tr>
              )}
            </tbody>
          </table>
        </CardContent>
      </Card>
    </div>
  )
}
