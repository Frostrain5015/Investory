import { useEffect, useMemo, useState } from 'react'
import { useAuth } from '@/hooks/use-auth'
import { useSettings } from '@/hooks/use-settings'
import { chartAPI } from '@/services/api'
import type { PnlCalendarItem } from '@/types'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { ChevronLeft, ChevronRight } from 'lucide-react'

const WEEKDAYS = ['一', '二', '三', '四', '五', '六', '日']

type ViewMode = 'yearly' | 'monthly'

export default function PnlCalendar() {
  const { portfolioId } = useAuth()
  const { positiveClass, negativeClass, positiveBgClass, negativeBgClass, positiveHex, negativeHex } = useSettings()
  const [viewMode, setViewMode] = useState<ViewMode>('yearly')
  const [year, setYear] = useState(new Date().getFullYear())
  const [month, setMonth] = useState(new Date().getMonth())
  const [data, setData] = useState<PnlCalendarItem[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    if (!portfolioId) return
    setLoading(true)
    chartAPI.pnlCalendar(portfolioId, year)
      .then((d: PnlCalendarItem[]) => setData(d))
      .finally(() => setLoading(false))
  }, [portfolioId, year])

  const dateMap = useMemo(() => {
    const map = new Map<string, number>()
    for (const [date, pnl] of data) {
      map.set(date, pnl)
    }
    return map
  }, [data])

  // Yearly: aggregate by month
  const monthlyTotals = useMemo(() => {
    const totals = new Array(12).fill(0)
    for (const [dateStr, pnl] of data) {
      const m = parseInt(dateStr.substring(5, 7), 10) - 1
      totals[m] += pnl
    }
    return totals.map((v) => Math.round(v * 100) / 100)
  }, [data])

  // Monthly grid
  const monthlyGrid = useMemo(() => {
    const daysInMonth = new Date(year, month + 1, 0).getDate()
    const firstDay = new Date(year, month, 1).getDay()
    const adjustedFirstDay = firstDay === 0 ? 6 : firstDay - 1
    const cells: (number | null)[] = []
    for (let i = 0; i < adjustedFirstDay; i++) cells.push(null)
    for (let d = 1; d <= daysInMonth; d++) {
      const ds = `${year}-${String(month + 1).padStart(2, '0')}-${String(d).padStart(2, '0')}`
      cells.push(dateMap.get(ds) ?? null)
    }
    return { cells, daysInMonth, firstDay: adjustedFirstDay }
  }, [dateMap, year, month])

  if (loading) {
    return <div className="flex items-center justify-center h-96">
      <div className="w-8 h-8 border-2 border-slate-300 border-t-slate-900 rounded-full animate-spin" />
    </div>
  }

  const maxAbs = Math.max(...monthlyTotals.map(Math.abs), 1)

  return (
    <div className="p-6 space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <h2 className="text-xl font-bold text-slate-900 tracking-tight">盈亏历</h2>
        <div className="flex items-center gap-4">
          <div className="flex bg-slate-100 rounded-lg p-0.5">
            <button onClick={() => setViewMode('yearly')}
              className={`px-3 py-1.5 rounded-md text-xs font-medium transition-colors ${viewMode === 'yearly' ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'}`}>
              按年
            </button>
            <button onClick={() => setViewMode('monthly')}
              className={`px-3 py-1.5 rounded-md text-xs font-medium transition-colors ${viewMode === 'monthly' ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'}`}>
              按月
            </button>
          </div>
          <div className="flex items-center gap-2">
            <button onClick={() => viewMode === 'yearly' ? setYear(y => y - 1) : setMonth(m => { if (m === 0) { setYear(y => y - 1); return 11 } return m - 1 })}
              className="h-8 w-8 flex items-center justify-center rounded-lg hover:bg-slate-100 transition-colors">
              <ChevronLeft className="w-4 h-4" />
            </button>
            <span className="text-sm font-medium w-28 text-center">
              {viewMode === 'yearly' ? `${year}年` : `${year}年 ${month + 1}月`}
            </span>
            <button onClick={() => viewMode === 'yearly' ? setYear(y => y + 1) : setMonth(m => { if (m === 11) { setYear(y => y + 1); return 0 } return m + 1 })}
              className="h-8 w-8 flex items-center justify-center rounded-lg hover:bg-slate-100 transition-colors">
              <ChevronRight className="w-4 h-4" />
            </button>
          </div>
        </div>
      </div>

      {viewMode === 'yearly' ? (
        /* Yearly: bar chart of monthly totals */
        <Card>
          <CardHeader><CardTitle className="text-base">{year}年 月度盈亏</CardTitle></CardHeader>
          <CardContent>
            <div className="flex items-end gap-1 h-64 px-2">
              {monthlyTotals.map((total, i) => (
                <div key={i} className="flex-1 flex flex-col items-center gap-1 h-full justify-end">
                  <span className={`text-[10px] font-medium tabular-nums ${total >= 0 ? positiveClass : negativeClass}`}>
                    {total === 0 ? '' : total >= 0 ? `+${total.toFixed(0)}` : total.toFixed(0)}
                  </span>
                  <div
                    className="w-full rounded-t-md transition-all"
                    style={{ height: `${(Math.abs(total) / maxAbs) * 80 + 2}%`, minHeight: 4, backgroundColor: total >= 0 ? positiveHex : negativeHex }}
                  />
                  <span className="text-[10px] text-slate-400 mt-1">{i + 1}月</span>
                </div>
              ))}
            </div>
          </CardContent>
        </Card>
      ) : (
        /* Monthly: calendar grid */
        <Card>
          <CardContent className="pt-6">
            <div className="grid grid-cols-7 gap-1.5 text-center">
              {WEEKDAYS.map(d => <div key={d} className="text-[11px] text-slate-400 font-medium py-1">{d}</div>)}
              {monthlyGrid.cells.map((pnl, i) => {
                if (pnl === null) return <div key={i} />
                const dayOfMonth = i - monthlyGrid.firstDay + 1
                const cls = pnl > 0 ? positiveBgClass : pnl < 0 ? negativeBgClass : 'bg-slate-50 text-slate-400'
                return (
                  <div key={i}
                    className={`h-16 flex flex-col items-center justify-center rounded-xl text-xs font-medium ${cls}`}
                    title={pnl !== 0 ? `¥${Number(pnl).toFixed(2)}` : '0'}>
                    <span className="text-sm leading-tight font-semibold">{dayOfMonth}</span>
                    {pnl !== 0 && <span className="leading-tight mt-0.5">{pnl > 0 ? '+' : ''}{Math.abs(pnl) < 10 ? pnl.toFixed(1) : Math.round(pnl)}</span>}
                  </div>
                )
              })}
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  )
}
