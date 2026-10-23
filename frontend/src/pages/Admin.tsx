import { useEffect, useState, useCallback } from 'react'
import { useAuth } from '@/hooks/use-auth'
import { useToast } from '@/hooks/use-toast'
import { useConfirm } from '@/hooks/use-confirm'
import { useT } from '@/i18n/I18nContext'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Database, RefreshCw, LogIn, UserX, Clock } from 'lucide-react'
import { BASE } from '@/services/api'
import {
  getPreloadedAdminCrawlHistory,
  getPreloadedAdminStatus,
  getPreloadedAdminUsers,
  type PreloadAdminCrawlHistory,
  type PreloadAdminDbStatus,
  type PreloadAdminUser,
} from '@/services/pagePreload'

type DbStatus = PreloadAdminDbStatus
type UserRow = PreloadAdminUser
type CrawlHistoryRow = PreloadAdminCrawlHistory

const MARKET_FLAGS: Record<string, string> = { A: 'cn', HK: 'hk', US: 'us', IDX: 'un' }

export default function Admin() {
  const { isAdmin } = useAuth()
  const toast = useToast()
  const confirm = useConfirm()
  const { t, lang } = useT()
  const [status, setStatus] = useState<DbStatus | null>(null)
  const [loadingStatus, setLoadingStatus] = useState(true)
  const [users, setUsers] = useState<UserRow[]>([])
  const [crawlHistory, setCrawlHistory] = useState<CrawlHistoryRow[]>([])

  // Market label lookup from i18n keys
  const marketLabels: Record<string, string> = {
    A: t.admin.aShares,
    HK: t.admin.hkStocks,
    US: t.admin.usStocks,
    IDX: t.admin.indices,
  }

  const fetchStatus = useCallback((force = false) => {
    getPreloadedAdminStatus({ force })
      .then(setStatus)
      .catch(() => {})
      .finally(() => setLoadingStatus(false))
  }, [])

  const fetchUsers = useCallback((force = false) => {
    getPreloadedAdminUsers({ force })
      .then(setUsers)
      .catch(() => {})
  }, [])

  const fetchCrawlHistory = useCallback((force = false) => {
    getPreloadedAdminCrawlHistory({ force })
      .then(setCrawlHistory)
      .catch(() => {})
  }, [])

  useEffect(() => { fetchStatus(); fetchUsers(); fetchCrawlHistory() }, [fetchStatus, fetchUsers, fetchCrawlHistory])
  async function impersonate(userId: number) {
    if (!(await confirm(t.admin.confirmImpersonate))) return
    await fetch(`${BASE}/api/admin/impersonate/${userId}`, { method: 'POST', credentials: 'include' })
    window.location.assign(import.meta.env.BASE_URL + 'dashboard')
  }

  async function deleteUser(userId: number, username: string) {
    if (!(await confirm(`${t.admin.confirmDeleteUserPrefix} "${username}"？${t.admin.irreversible}`))) return
    const res = await fetch(`${BASE}/api/admin/users/${userId}`, { method: 'DELETE', credentials: 'include' })
    const data = await res.json()
    if (data.error) { toast(data.error, false); return }
    fetchUsers(true)
  }

  const crawlStatusLabel = (s: string) => {
    if (s === 'ok') return t.admin.crawlOk
    if (s === 'running') return t.admin.crawlRunning
    if (s === 'pending') return t.admin.crawlPending
    if (s === 'no_change') return t.admin.crawlNoChange
    return t.admin.crawlFailed
  }
  const crawlOutcome = (row: CrawlHistoryRow) => row.status === 'ok' && Number(row.rows_written) === 0 && Number(row.stocks_failed) === 0 ? 'no_change' : row.status
  const crawlBadge = (status: string) => status === 'ok' ? 'bg-emerald-50 text-emerald-600'
    : status === 'running' || status === 'pending' ? 'bg-amber-50 text-amber-600'
    : status === 'no_change' ? 'bg-slate-100 text-slate-500' : 'bg-red-50 text-red-600'

  if (!isAdmin) {
    return <div className="flex items-center justify-center h-screen bg-slate-50 text-slate-500">{t.admin.noPermission}</div>
  }

  return (
    <div className="p-6 space-y-6 max-w-6xl mx-auto">
      <div className="flex items-center justify-between flex-wrap gap-3">
        <h2 className="text-xl font-bold text-slate-900 tracking-tight">{t.admin.title}</h2>
        <div className="flex items-center gap-3">
          <button onClick={() => { setLoadingStatus(true); fetchStatus(true); fetchUsers(true); fetchCrawlHistory(true) }} disabled={loadingStatus}
            className="inline-flex items-center gap-1.5 h-9 px-4 rounded-xl bg-slate-100 text-slate-600 text-xs font-medium hover:bg-slate-200 transition-colors">
            <RefreshCw className={`w-3.5 h-3.5 ${loadingStatus ? 'animate-spin' : ''}`} />{t.common.refresh}
          </button>
        </div>
      </div>

      {/* Market status cards */}
      {loadingStatus && !status ? (
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

                  </CardTitle>
                </CardHeader>
                <CardContent>
                  <div className="grid grid-cols-2 gap-x-2 gap-y-1 text-sm">
                    <span className="text-slate-400">{t.admin.stockCount}</span>
                    <span className="text-right font-medium tabular-nums">{m.stock_count.toLocaleString()}</span>
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
                      await fetch(`${BASE}/api/admin/crawl-history`, { method: 'DELETE', credentials: 'include' })
                      fetchCrawlHistory(true)
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
                              <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-[10px] font-medium ${crawlBadge(crawlOutcome(h))}`}>
                                {crawlStatusLabel(crawlOutcome(h))}
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
                    const statusBadge = crawlBadge(crawlOutcome(h))
                    return (
                      <div key={h.market} className="px-4 py-3 space-y-2">
                        <div className="flex items-center justify-between">
                          <span className="font-medium text-slate-700 text-xs">{marketLabels[h.market] ?? h.market}</span>
                          <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-[10px] font-medium ${statusBadge}`}>
                            {crawlStatusLabel(crawlOutcome(h))}
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
