import { useEffect, useMemo, useState } from 'react'
import { useAuth } from '@/hooks/use-auth'
import { useSettings } from '@/hooks/use-settings'
import { chartAPI } from '@/services/api'
import type { PnlCalendarItem } from '@/types'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { ChevronLeft, ChevronRight } from 'lucide-react'

const WEEKDAYS = ['日', '一', '二', '三', '四', '五', '六']
const MONTHS = ['1月','2月','3月','4月','5月','6月','7月','8月','9月','10月','11月','12月']

type ViewMode = 'yearly' | 'monthly'
type PnlDisplay = 'amount' | 'pct'

export default function PnlCalendar() {
  const { portfolioId } = useAuth()
  const { formatCurrency } = useSettings()
  const [viewMode, setViewMode] = useState<ViewMode>('yearly')
  const [pnlDisplay, setPnlDisplay] = useState<PnlDisplay>('amount')
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
    const map = new Map<string, { pnl: number; total: number }>()
    for (const [date, pnl, total] of data) map.set(date, { pnl, total })
    return map
  }, [data])

  // Yearly: monthly P&L (sum of dailyPnl = lastDay.totalValue - prevMonth.lastDay.totalValue)
  const monthlyTotals = useMemo(() => {
    const amounts = new Array(12).fill(0)
    const lastDayTotal = new Array(12).fill(0) // month index -> last day totalValue
    for (const [dateStr, pnl, total] of data) {
      const mIdx = parseInt(dateStr.substring(5, 7), 10) - 1
      if (mIdx >= 0 && mIdx < 12) {
        amounts[mIdx] += pnl
        lastDayTotal[mIdx] = total
      }
    }
    return amounts.map((v, i) => {
      const amt = Math.round(v * 100) / 100
      let pct = 0
      if (pnlDisplay === 'pct' && lastDayTotal[i] !== 0) {
        const startValue = lastDayTotal[i] - v // prev month end or totalCost for first month
        if (startValue !== 0) pct = Math.round((v / startValue) * 10000) / 100
      }
      return { amt, pct, lastDayTotal: lastDayTotal[i] }
    })
  }, [data, pnlDisplay])

  const yearMaxAbs = useMemo(() => Math.max(...monthlyTotals.map(m => pnlDisplay === 'amount' ? Math.abs(m.amt) : Math.abs(m.pct)), 1), [monthlyTotals, pnlDisplay])

  // Period cumulative P&L for the header
  const periodTotalPnl = useMemo(() => {
    if (viewMode === 'yearly') {
      const total = monthlyTotals.reduce((s, m) => s + m.amt, 0)
      if (pnlDisplay === 'amount') return { amt: Math.round(total * 100) / 100, pct: 0 }
      const lastM = [...monthlyTotals].reverse().find(m => m.lastDayTotal !== 0)
      const endValue = lastM ? lastM.lastDayTotal : 0
      const startValue = endValue - total
      const pct = startValue !== 0 ? Math.round((total / startValue) * 10000) / 100 : 0
      return { amt: Math.round(total * 100) / 100, pct }
    }
    // Monthly: sum dailyPnl for the current month
    const mIdx = month
    const total = data
      .filter(([ds]) => parseInt(ds.substring(5, 7), 10) - 1 === mIdx)
      .reduce((s, [, pnl]) => s + pnl, 0)
    if (pnlDisplay === 'amount') return { amt: Math.round(total * 100) / 100, pct: 0 }
    const monthData = monthlyTotals[mIdx]
    const endValue = monthData ? monthData.lastDayTotal : 0
    const startValue = endValue - total
    const pct = startValue !== 0 ? Math.round((total / startValue) * 10000) / 100 : 0
    return { amt: Math.round(total * 100) / 100, pct }
  }, [viewMode, monthlyTotals, data, month, pnlDisplay])

  // Monthly grid
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

  if (loading) {
    return <div className="flex items-center justify-center h-96">
      <div className="w-8 h-8 border-2 border-slate-300 border-t-slate-900 rounded-full animate-spin" />
    </div>
  }

  function cellStyle(val: number, noData: boolean) {
    const intensity = yearMaxAbs > 0 ? Math.min(Math.abs(val) / yearMaxAbs, 1) : 0
    if (val > 0) return { bg: `rgba(239,68,68,${0.10 + intensity * 0.80})`, text: 'text-red-700' }
    if (val < 0) return { bg: `rgba(16,185,129,${0.10 + intensity * 0.80})`, text: 'text-emerald-700' }
    if (noData) return { bg: '#f8fafc', text: 'text-slate-300' }
    return { bg: '#f8fafc', text: 'text-slate-400' }
  }

  return (
    <div className="p-6 space-y-6">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <h2 className="text-xl font-bold text-slate-900 tracking-tight">盈亏日历</h2>
          {periodTotalPnl.amt !== 0 && (
            <span className={`text-sm font-bold ${periodTotalPnl.amt >= 0 ? 'text-red-700' : 'text-emerald-700'}`}>
              {pnlDisplay === 'amount'
                ? `${periodTotalPnl.amt >= 0 ? '+' : ''}${formatCurrency(Math.abs(periodTotalPnl.amt))}`
                : `${periodTotalPnl.pct >= 0 ? '+' : ''}${periodTotalPnl.pct.toFixed(1)}%`
              }
            </span>
          )}
        </div>
        <div className="flex items-center gap-3">
          <div className="flex bg-slate-100 rounded-lg p-0.5">
            <button onClick={() => setPnlDisplay('amount')}
              className={`px-3 py-1.5 rounded-md text-xs font-medium ${pnlDisplay === 'amount' ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'}`}>金额</button>
            <button onClick={() => setPnlDisplay('pct')}
              className={`px-3 py-1.5 rounded-md text-xs font-medium ${pnlDisplay === 'pct' ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'}`}>涨跌幅</button>
          </div>
          <div className="flex bg-slate-100 rounded-lg p-0.5">
            <button onClick={() => setViewMode('yearly')}
              className={`px-3 py-1.5 rounded-md text-xs font-medium ${viewMode === 'yearly' ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'}`}>按年</button>
            <button onClick={() => setViewMode('monthly')}
              className={`px-3 py-1.5 rounded-md text-xs font-medium ${viewMode === 'monthly' ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'}`}>按月</button>
          </div>
          <div className="flex items-center gap-2">
            <button onClick={() => viewMode === 'yearly' ? setYear(y => y - 1) : setMonth(m => { if (m === 0) { setYear(y => y - 1); return 11 } return m - 1 })}
              className="h-8 w-8 flex items-center justify-center rounded-lg hover:bg-slate-100"><ChevronLeft className="w-4 h-4" /></button>
            <span className="text-sm font-medium w-28 text-center">{viewMode === 'yearly' ? `${year}年` : `${year}年 ${month + 1}月`}</span>
            <button onClick={() => viewMode === 'yearly' ? setYear(y => y + 1) : setMonth(m => { if (m === 11) { setYear(y => y + 1); return 0 } return m + 1 })}
              className="h-8 w-8 flex items-center justify-center rounded-lg hover:bg-slate-100"><ChevronRight className="w-4 h-4" /></button>
          </div>
        </div>
      </div>

      {viewMode === 'yearly' ? (
        <Card>
          <CardHeader><CardTitle className="text-base">{year}年 月度{pnlDisplay === 'amount' ? '盈亏' : '涨跌幅'}</CardTitle></CardHeader>
          <CardContent>
            <div className="grid grid-cols-4 sm:grid-cols-6 lg:grid-cols-6 gap-3">
              {monthlyTotals.map((m, i) => {
                const val = pnlDisplay === 'amount' ? m.amt : m.pct
                const noData = m.lastDayTotal === 0
                const s = cellStyle(val, noData)
                return (
                  <div key={i}
                    className="h-24 flex flex-col items-center justify-center rounded-xl text-sm font-medium"
                    style={{ backgroundColor: s.bg }}
                    title={noData ? MONTHS[i] : `${val >= 0 ? '+' : ''}${val.toFixed(2)}${pnlDisplay === 'pct' ? '%' : ''}`}>
                    <span className="text-xs text-slate-400">{MONTHS[i]}</span>
                    {!noData && (
                      <span className={`text-lg font-bold mt-1 ${s.text}`}>
                        {val >= 0 ? '+' : ''}{val.toFixed(pnlDisplay === 'pct' ? 1 : 0)}{pnlDisplay === 'pct' ? '%' : ''}
                      </span>
                    )}
                  </div>
                )
              })}
            </div>
          </CardContent>
        </Card>
      ) : (
        <Card>
          <CardContent className="pt-6">
            <div className="grid grid-cols-7 gap-2 text-center">
              {WEEKDAYS.map(d => <div key={d} className="text-[11px] text-slate-400 font-medium py-1">{d}</div>)}
              {monthlyGrid.map((val, i) => {
                if (val === null) return <div key={i} />
                const dayOfMonth = i - (new Date(year, month, 1).getDay()) + 1
                const s = cellStyle(val, false)
                return (
                  <div key={i}
                    className="h-20 flex flex-col items-center justify-center rounded-xl text-xs font-medium"
                    style={{ backgroundColor: s.bg }}
                    title={val !== 0 ? `${pnlDisplay === 'amount' ? '¥' : ''}${Number(val).toFixed(2)}${pnlDisplay === 'pct' ? '%' : ''}` : ''}>
                    <span className={`text-sm leading-tight font-semibold ${s.text}`}>{dayOfMonth}</span>
                    {val !== 0 && (
                      <span className={`leading-tight mt-0.5 ${s.text}`}>
                        {val > 0 ? '+' : ''}{Math.abs(val) < 10 ? val.toFixed(1) : Math.round(val)}{pnlDisplay === 'pct' ? '%' : ''}
                      </span>
                    )}
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
