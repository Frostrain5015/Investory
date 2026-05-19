import { useEffect, useState } from 'react'
import { useAuth } from '@/hooks/use-auth'
import { chartAPI } from '@/services/api'
import type { PnlCalendarItem } from '@/types'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { ChevronLeft, ChevronRight } from 'lucide-react'

const MONTHS = ['一月', '二月', '三月', '四月', '五月', '六月', '七月', '八月', '九月', '十月', '十一月', '十二月']

export default function PnlCalendar() {
  const { portfolioId } = useAuth()
  const [year, setYear] = useState(new Date().getFullYear())
  const [data, setData] = useState<PnlCalendarItem[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    if (!portfolioId) return
    setLoading(true)
    chartAPI.pnlCalendar(portfolioId, year)
      .then((d: PnlCalendarItem[]) => setData(d || []))
      .finally(() => setLoading(false))
  }, [portfolioId, year])

  // Build calendar grid
  const dateMap = new Map(data.map(([date, pnl]: [string, number]) => [date, pnl]))

  function getDaysInMonth(month: number) {
    return new Date(year, month + 1, 0).getDate()
  }

  function getFirstDayOfMonth(month: number) {
    return new Date(year, month, 1).getDay()
  }

  if (loading) {
    return <div className="flex items-center justify-center h-96"><div className="w-8 h-8 border-2 border-slate-300 border-t-slate-900 rounded-full animate-spin" /></div>
  }

  return (
    <div className="p-6 space-y-6">
      <div className="flex items-center justify-between">
        <h2 className="text-xl font-bold text-slate-900 tracking-tight">盈亏历 {year}</h2>
        <div className="flex items-center gap-2">
          <button onClick={() => setYear(y => y - 1)}
            className="h-8 w-8 flex items-center justify-center rounded-lg hover:bg-slate-100 transition-colors">
            <ChevronLeft className="w-4 h-4" />
          </button>
          <span className="text-sm font-medium w-24 text-center">{year}</span>
          <button onClick={() => setYear(y => y + 1)}
            className="h-8 w-8 flex items-center justify-center rounded-lg hover:bg-slate-100 transition-colors">
            <ChevronRight className="w-4 h-4" />
          </button>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4">
        {MONTHS.map((monthName, monthIdx) => {
          const days = getDaysInMonth(monthIdx)
          const firstDay = getFirstDayOfMonth(monthIdx)
          const cells = []
          for (let i = 0; i < firstDay; i++) cells.push(null)
          for (let d = 1; d <= days; d++) {
            const dateStr = `${year}-${String(monthIdx + 1).padStart(2, '0')}-${String(d).padStart(2, '0')}`
            cells.push(dateMap.get(dateStr) ?? null)
          }

          return (
            <Card key={monthIdx}>
              <CardHeader className="pb-2">
                <CardTitle className="text-sm font-medium text-slate-500">{monthName}</CardTitle>
              </CardHeader>
              <CardContent>
                <div className="grid grid-cols-7 gap-0.5 text-center text-[10px]">
                  {['一','二','三','四','五','六','日'].map(d => (
                    <div key={d} className="text-slate-400 py-1">{d}</div>
                  ))}
                  {cells.map((pnl, i) => (
                    <div key={i}
                      className={`h-8 flex items-center justify-center rounded-md text-[10px] font-medium ${
                        pnl === null ? 'text-transparent' :
                        pnl > 0 ? 'bg-emerald-50 text-emerald-700' :
                        pnl < 0 ? 'bg-red-50 text-red-500' :
                        'bg-slate-50 text-slate-400'
                      }`}
                      title={pnl != null ? `¥${Number(pnl).toFixed(2)}` : ''}>
                      {pnl != null ? (pnl > 0 ? '+' : '') + (pnl === 0 ? '0' : Number(pnl).toFixed(0)) : ''}
                    </div>
                  ))}
                </div>
              </CardContent>
            </Card>
          )
        })}
      </div>
    </div>
  )
}
