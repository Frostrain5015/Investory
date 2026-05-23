import { useEffect, useMemo, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { useAuth } from '@/hooks/use-auth'
import { useSettings } from '@/hooks/use-settings'
import { useT } from '@/i18n/I18nContext'
import { chartAPI } from '@/services/api'
import type { PnlCalendarItem } from '@/types'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { ChevronLeft, ChevronRight, X } from 'lucide-react'

const WEEKDAYS_ZH = ['日', '一', '二', '三', '四', '五', '六'] as const
const WEEKDAYS_EN = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'] as const
const MONTHS_ZH = ['1月','2月','3月','4月','5月','6月','7月','8月','9月','10月','11月','12月'] as const
const MONTHS_EN = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'] as const

type ViewMode = 'yearly' | 'monthly'
type PnlDisplay = 'amount' | 'pct'

interface HoldingRow { stockName: string; symbol: string; pnl: number; priceChange: number }
interface TxRow { type: string; stockName?: string; shares?: number; price?: number }
interface Detail { title: string; totalPnl: number; holdings: HoldingRow[]; transactions: TxRow[] }
type Selected =
  | { kind: 'day'; date: string }
  | { kind: 'month'; year: number; month: number }

export default function PnlCalendar() {
  const { portfolioId } = useAuth()
  const { convertCurrency, positiveClass, negativeClass } = useSettings()
  const { t, lang } = useT()

  const WEEKDAYS = lang === 'zh' ? WEEKDAYS_ZH : WEEKDAYS_EN
  const MONTHS = lang === 'zh' ? MONTHS_ZH : MONTHS_EN
  const TYPE_LABELS: Record<string, string> = {
    BUY: t.pnl.buy,
    SELL: t.pnl.sell,
    DIV: t.pnl.dividend,
    TRANSFER_IN: t.pnl.transferIn,
    TRANSFER_OUT: t.pnl.transferOut,
  }

  function fmtNum(v: number): string {
    const cv = convertCurrency(v)
    const locale = lang === 'zh' ? 'zh-CN' : 'en-US'
    const s = Math.abs(cv).toLocaleString(locale, { minimumFractionDigits: 0, maximumFractionDigits: 2 })
    return s.includes('.') ? s.replace(/\.?0+$/, '') : s
  }
  function sign(v: number) { return v >= 0 ? '+' : '-' }

  /** replace {key} placeholders in a format string */
  function fmt(str: string, vars: Record<string, string | number>) {
    return str.replace(/\{(\w+)\}/g, (_, k) => String(vars[k] ?? k))
  }

  const [viewMode, setViewMode] = useState<ViewMode>('yearly')
  const [pnlDisplay, setPnlDisplay] = useState<PnlDisplay>('amount')
  const [year, setYear] = useState(new Date().getFullYear())
  const [month, setMonth] = useState(new Date().getMonth())
  const [data, setData] = useState<PnlCalendarItem[]>([])
  const [loading, setLoading] = useState(true)
  const [selected, setSelected] = useState<Selected | null>(null)
  const [detail, setDetail] = useState<Detail | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)

  useEffect(() => { setData([]); setLoading(true) }, [portfolioId])

  useEffect(() => {
    if (!portfolioId) return
    setLoading(true)
    chartAPI.pnlCalendar(portfolioId, year)
      .then((d: PnlCalendarItem[]) => setData(d))
      .finally(() => setLoading(false))
  }, [portfolioId, year])

  // Fetch detail whenever selected changes
  useEffect(() => {
    if (!selected) { setDetail(null); return }
    setDetailLoading(true)
    const url = selected.kind === 'day'
      ? `/investory/api/daily-detail?date=${selected.date}`
      : `/investory/api/monthly-detail?year=${selected.year}&month=${selected.month}`
    fetch(url, { credentials: 'include' })
      .then(r => r.json())
      .then((d: Record<string, unknown>) => {
        const title = selected.kind === 'day'
          ? fmt(t.pnl.dayDetailTitle, { date: selected.date })
          : fmt(t.pnl.monthDetailTitle, { year: selected.year, month: selected.month })
        setDetail({
          title,
          totalPnl: Number(d.totalPnl ?? 0),
          holdings: (d.holdings as HoldingRow[]) ?? [],
          transactions: (d.transactions as TxRow[]) ?? [],
        })
      })
      .catch(() => setDetail(null))
      .finally(() => setDetailLoading(false))
  }, [selected, t.pnl.dayDetailTitle, t.pnl.monthDetailTitle])

  const dateMap = useMemo(() => {
    const map = new Map<string, { pnl: number; total: number }>()
    for (const [date, pnl, total] of data) map.set(date, { pnl, total })
    return map
  }, [data])

  const monthlyTotals = useMemo(() => {
    const amounts = new Array(12).fill(0)
    const lastDayTotal = new Array(12).fill(0)
    for (const [dateStr, pnl, total] of data) {
      const mIdx = parseInt(dateStr.substring(5, 7), 10) - 1
      if (mIdx >= 0 && mIdx < 12) { amounts[mIdx] += pnl; lastDayTotal[mIdx] = total }
    }
    return amounts.map((v, i) => {
      const amt = Math.round(v * 100) / 100
      let pct = 0
      if (pnlDisplay === 'pct' && lastDayTotal[i] !== 0) {
        const sv = lastDayTotal[i] - v
        if (sv !== 0) pct = Math.round((v / sv) * 10000) / 100
      }
      return { amt, pct, lastDayTotal: lastDayTotal[i] }
    })
  }, [data, pnlDisplay])

  const yearMaxAbs = useMemo(() => Math.max(...monthlyTotals.map(m => pnlDisplay === 'amount' ? Math.abs(m.amt) : Math.abs(m.pct)), 1), [monthlyTotals, pnlDisplay])

  const periodTotalPnl = useMemo(() => {
    if (viewMode === 'yearly') {
      const total = monthlyTotals.reduce((s, m) => s + m.amt, 0)
      if (pnlDisplay === 'amount') return { amt: Math.round(total * 100) / 100, pct: 0 }
      const lastM = [...monthlyTotals].reverse().find(m => m.lastDayTotal !== 0)
      const endValue = lastM ? lastM.lastDayTotal : 0
      const sv = endValue - total
      return { amt: Math.round(total * 100) / 100, pct: sv !== 0 ? Math.round((total / sv) * 10000) / 100 : 0 }
    }
    const total = data.filter(([ds]) => parseInt(ds.substring(5, 7), 10) - 1 === month).reduce((s, [, pnl]) => s + pnl, 0)
    if (pnlDisplay === 'amount') return { amt: Math.round(total * 100) / 100, pct: 0 }
    const md = monthlyTotals[month]
    const sv = (md ? md.lastDayTotal : 0) - total
    return { amt: Math.round(total * 100) / 100, pct: sv !== 0 ? Math.round((total / sv) * 10000) / 100 : 0 }
  }, [viewMode, monthlyTotals, data, month, pnlDisplay])

  const monthlyGrid = useMemo(() => {
    const daysInMonth = new Date(year, month + 1, 0).getDate()
    const firstDay = new Date(year, month, 1).getDay()
    const cells: (number | null)[] = []
    for (let i = 0; i < firstDay; i++) cells.push(null)
    for (let d = 1; d <= daysInMonth; d++) {
      const ds = `${year}-${String(month + 1).padStart(2, '0')}-${String(d).padStart(2, '0')}`
      const entry = dateMap.get(ds)
      if (!entry) { cells.push(0); continue }
      cells.push(pnlDisplay === 'amount' ? entry.pnl : (entry.total > 0 ? (entry.pnl / entry.total) * 100 : 0))
    }
    return cells
  }, [dateMap, year, month, pnlDisplay])

  function cellStyle(val: number, noData: boolean) {
    const intensity = yearMaxAbs > 0 ? Math.min(Math.abs(val) / yearMaxAbs, 1) : 0
    if (val > 0) return { bg: `rgba(239,68,68,${0.10 + intensity * 0.80})`, text: 'text-red-700' }
    if (val < 0) return { bg: `rgba(16,185,129,${0.10 + intensity * 0.80})`, text: 'text-emerald-700' }
    if (noData) return { bg: '#f8fafc', text: 'text-slate-300' }
    return { bg: '#f8fafc', text: 'text-slate-400' }
  }

  if (loading) return (
    <div className="flex flex-col items-center justify-center gap-3 h-96">
      <div className="w-8 h-8 border-2 border-slate-300 border-t-slate-900 rounded-full animate-spin" />
      <span className="text-sm text-slate-400">{t.pnl.loadingCalendar}</span>
    </div>
  )

  const displayLabel = pnlDisplay === 'amount' ? t.pnl.pnlValue : t.pnl.pctChange

  return (
    <div className="p-6 space-y-6">
      {/* Header — stacks on mobile */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
        <div className="flex items-center gap-3">
          <h2 className="text-xl font-bold text-slate-900 tracking-tight">{t.pnl.title}</h2>
          {periodTotalPnl.amt !== 0 && (
            <span className={`text-sm font-bold ${periodTotalPnl.amt >= 0 ? 'text-red-700' : 'text-emerald-700'}`}>
              {sign(periodTotalPnl.amt)}{fmtNum(periodTotalPnl.amt)}
              {periodTotalPnl.pct !== 0 && <span className="ml-1.5">{sign(periodTotalPnl.pct)}{Math.abs(periodTotalPnl.pct).toFixed(1)}%</span>}
            </span>
          )}
        </div>
        <div className="flex items-center gap-2 flex-wrap">
          <div className="flex bg-slate-100 rounded-lg p-0.5">
            <button onClick={() => setPnlDisplay('amount')} className={`px-2.5 py-1.5 rounded-md text-xs font-medium ${pnlDisplay === 'amount' ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'}`}>{t.pnl.amount}</button>
            <button onClick={() => setPnlDisplay('pct')} className={`px-2.5 py-1.5 rounded-md text-xs font-medium ${pnlDisplay === 'pct' ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'}`}>{t.pnl.pctChange}</button>
          </div>
          <div className="flex bg-slate-100 rounded-lg p-0.5">
            <button onClick={() => setViewMode('yearly')} className={`px-2.5 py-1.5 rounded-md text-xs font-medium ${viewMode === 'yearly' ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'}`}>{t.pnl.yearly}</button>
            <button onClick={() => setViewMode('monthly')} className={`px-2.5 py-1.5 rounded-md text-xs font-medium ${viewMode === 'monthly' ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'}`}>{t.pnl.monthly}</button>
          </div>
          <div className="flex items-center gap-1">
            <button onClick={() => viewMode === 'yearly' ? setYear(y => y - 1) : setMonth(m => { if (m === 0) { setYear(y => y - 1); return 11 } return m - 1 })}
              className="h-8 w-8 flex items-center justify-center rounded-lg hover:bg-slate-100"><ChevronLeft className="w-4 h-4" /></button>
            <span className="text-sm font-medium w-20 sm:w-28 text-center">
              {viewMode === 'yearly'
                ? fmt(t.pnl.yearFormat, { year })
                : fmt(t.pnl.monthFormat, { year, month: month + 1 })}
            </span>
            <button onClick={() => viewMode === 'yearly' ? setYear(y => y + 1) : setMonth(m => { if (m === 11) { setYear(y => y + 1); return 0 } return m + 1 })}
              className="h-8 w-8 flex items-center justify-center rounded-lg hover:bg-slate-100"><ChevronRight className="w-4 h-4" /></button>
          </div>
        </div>
      </div>

      {/* Yearly grid */}
      {viewMode === 'yearly' ? (
        <Card>
          <CardHeader><CardTitle className="text-base">{fmt(t.pnl.monthlyTitle, { year, display: displayLabel })}</CardTitle></CardHeader>
          <CardContent>
            <div className="grid grid-cols-4 sm:grid-cols-6 lg:grid-cols-6 gap-3">
              {monthlyTotals.map((m, i) => {
                const val = pnlDisplay === 'amount' ? m.amt : m.pct
                const noData = m.lastDayTotal === 0
                const s = cellStyle(val, noData)
                return (
                  <div key={i}
                    onClick={() => !noData ? setSelected({ kind: 'month', year, month: i + 1 }) : undefined}
                    className={`h-20 sm:h-24 flex flex-col items-center justify-center rounded-xl text-sm font-medium ${!noData ? 'cursor-pointer hover:opacity-80 active:scale-95 transition-transform' : ''}`}
                    style={{ backgroundColor: s.bg }}>
                    <span className="text-xs text-slate-400">{MONTHS[i]}</span>
                    {!noData && (
                      <span className={`text-lg font-bold mt-1 ${s.text}`}>
                        {pnlDisplay === 'amount' ? `${sign(val)}${fmtNum(val)}` : `${sign(val)}${Math.abs(val).toFixed(1)}%`}
                      </span>
                    )}
                  </div>
                )
              })}
            </div>
          </CardContent>
        </Card>
      ) : (
        /* Monthly grid */
        <Card>
          <CardContent className="pt-6">
            <div className="grid grid-cols-7 gap-2 text-center">
              {WEEKDAYS.map(d => <div key={d} className="text-[11px] text-slate-400 font-medium py-1">{d}</div>)}
              {monthlyGrid.map((val, i) => {
                if (val === null) return <div key={i} />
                const dayOfMonth = i - (new Date(year, month, 1).getDay()) + 1
                const ds = `${year}-${String(month + 1).padStart(2, '0')}-${String(dayOfMonth).padStart(2, '0')}`
                const hasData = dateMap.has(ds) && val !== 0
                const s = cellStyle(val, false)
                return (
                  <div key={i}
                    onClick={() => hasData ? setSelected({ kind: 'day', date: ds }) : undefined}
                    className={`h-14 sm:h-20 flex flex-col items-center justify-center rounded-xl text-xs font-medium ${hasData ? 'cursor-pointer hover:opacity-80 active:scale-95 transition-transform' : ''}`}
                    style={{ backgroundColor: s.bg }}>
                    <span className={`text-base font-bold ${s.text}`}>{dayOfMonth}</span>
                    {val !== 0 && (
                      <span className={`text-sm font-semibold mt-0.5 ${s.text}`}>
                        {pnlDisplay === 'amount'
                          ? `${sign(val)}${fmtNum(val)}`
                          : `${val > 0 ? '+' : ''}${Math.abs(val) < 10 ? val.toFixed(1) : Math.round(val)}%`}
                      </span>
                    )}
                  </div>
                )
              })}
            </div>
          </CardContent>
        </Card>
      )}

      {/* Detail modal — bottom sheet on mobile, centered on desktop */}
      <AnimatePresence>
        {selected && (
          <motion.div
            initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
            className="fixed inset-0 bg-black/30 z-40 flex items-end lg:items-center justify-center lg:p-4"
            onClick={() => setSelected(null)}>
            <motion.div
              initial={{ y: '100%' }} animate={{ y: 0 }} exit={{ y: '100%' }}
              transition={{ type: 'spring', stiffness: 400, damping: 40 }}
              drag="y" dragConstraints={{ top: 0 }} dragElastic={0.1}
              onDragEnd={(_, info) => { if (info.offset.y > 80) setSelected(null) }}
              onClick={e => e.stopPropagation()}
              className="w-full lg:max-w-md bg-white rounded-t-2xl lg:rounded-2xl shadow-xl max-h-[70vh] flex flex-col">
              {/* Header */}
              <div className="flex items-center justify-between px-6 py-4 border-b border-slate-100 shrink-0">
                <div>
                  <h3 className="text-base font-bold text-slate-900">{detail?.title ?? '...'}</h3>
                  {detail && (
                    <p className={`text-sm font-semibold mt-0.5 ${detail.totalPnl >= 0 ? positiveClass : negativeClass}`}>
                      {sign(detail.totalPnl)}{fmtNum(detail.totalPnl)}
                    </p>
                  )}
                </div>
                <button onClick={() => setSelected(null)} className="w-8 h-8 flex items-center justify-center rounded-full hover:bg-slate-100 transition-colors">
                  <X className="w-4 h-4 text-slate-500" />
                </button>
              </div>
              {/* Body */}
              <div className="overflow-auto flex-1 px-6 py-4">
                {detailLoading ? (
                  <div className="flex flex-col items-center justify-center gap-2 h-24">
                    <div className="w-6 h-6 border-2 border-slate-300 border-t-slate-700 rounded-full animate-spin" />
                    <span className="text-xs text-slate-400">{t.pnl.loadingDetail}</span>
                  </div>
                ) : detail ? (
                  <div className="space-y-4">
                    {detail.holdings.length > 0 && (
                      <div>
                        <p className="text-xs font-semibold text-slate-400 uppercase tracking-wide mb-2">{t.pnl.holdingContribution}</p>
                        <div className="space-y-1.5">
                          {[...detail.holdings].sort((a, b) => Math.abs(b.pnl) - Math.abs(a.pnl)).map((h, i) => (
                            <div key={i} className="flex items-center justify-between py-1.5 px-3 rounded-xl bg-slate-50">
                              <span className="text-sm font-medium text-slate-700 truncate max-w-[120px]">{h.stockName}</span>
                              <div className="flex items-center gap-2 text-sm shrink-0">
                                <span className={`tabular-nums ${Number(h.priceChange) >= 0 ? positiveClass : negativeClass}`}>
                                  {sign(Number(h.priceChange))}{Math.abs(Number(h.priceChange)).toFixed(2)}%
                                </span>
                                <span className={`tabular-nums font-semibold min-w-[56px] text-right ${Number(h.pnl) >= 0 ? positiveClass : negativeClass}`}>
                                  {sign(Number(h.pnl))}{fmtNum(Number(h.pnl))}
                                </span>
                              </div>
                            </div>
                          ))}
                        </div>
                      </div>
                    )}
                    {detail.transactions.length > 0 && (
                      <div>
                        <p className="text-xs font-semibold text-slate-400 uppercase tracking-wide mb-2">{t.pnl.transactions}</p>
                        <div className="space-y-1.5">
                          {detail.transactions.map((tran, i) => (
                            <div key={i} className="flex items-center gap-3 py-1.5 px-3 rounded-xl bg-slate-50 text-sm">
                              <span className={`font-medium shrink-0 ${tran.type === 'BUY' ? 'text-red-600' : tran.type === 'SELL' ? 'text-emerald-600' : 'text-slate-600'}`}>
                                {TYPE_LABELS[tran.type] ?? tran.type}
                              </span>
                              <span className="text-slate-700 truncate">{tran.stockName || '—'}</span>
                              {tran.shares != null && tran.price != null && (
                                <span className="text-slate-400 ml-auto tabular-nums shrink-0">{tran.shares} x {tran.price}</span>
                              )}
                            </div>
                          ))}
                        </div>
                      </div>
                    )}
                    {detail.holdings.length === 0 && detail.transactions.length === 0 && (
                      <p className="text-sm text-slate-400 text-center py-8">{t.pnl.noDetailData}</p>
                    )}
                  </div>
                ) : (
                  <p className="text-sm text-slate-400 text-center py-8">{t.pnl.loadError}</p>
                )}
              </div>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  )
}
