import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Card, CardContent } from '@/components/ui/card'
import { shortSymbol } from '@/lib/format'
import { Plus } from 'lucide-react'

interface Div { id: number; stockName?: string; stockSymbol?: string; amountPerShare: number; sharesHeld: number; totalAmount: number; recordDate: string }

export default function Dividends() {
  const [dividends, setDividends] = useState<Div[]>([])
  const [loading, setLoading] = useState(true)

  function load() {
    fetch('/investory/api/dividends', { credentials: 'include' })
      .then(r => r.json()).then(d => setDividends(d.dividends || []))
      .finally(() => setLoading(false))
  }

  useEffect(() => { load() }, [])

  async function handleDelete(id: number) {
    if (!confirm('确认删除？')) return
    await fetch(`/investory/api/dividends/${id}`, { method: 'DELETE', credentials: 'include' })
    load()
  }

  if (loading) {
    return <div className="flex flex-col items-center justify-center gap-3 h-96"><div className="w-8 h-8 border-2 border-slate-300 border-t-slate-900 rounded-full animate-spin" /><span className="text-sm text-slate-400">正在加载分红记录...</span></div>
  }

  return (
    <div className="p-6 space-y-6">
      <div className="flex items-center justify-between">
        <h2 className="text-xl font-bold text-slate-900 tracking-tight">分红记录</h2>
        <Link to="/transactions/add?type=DIV"
          className="inline-flex items-center gap-1.5 h-9 px-4 rounded-xl bg-slate-900 text-white text-xs font-medium hover:bg-slate-800 transition-colors">
          <Plus className="w-3.5 h-3.5" />添加分红
        </Link>
      </div>
      <Card>
        <CardContent className="p-0">
          {dividends.length === 0 ? (
            <div className="py-12 text-center text-slate-500 text-sm">暂无分红记录</div>
          ) : (
            <div className="overflow-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-slate-100">
                    <th className="text-left text-xs font-medium text-slate-500 px-6 py-3">记录日</th>
                    <th className="text-left text-xs font-medium text-slate-500 px-3 py-3">股票</th>
                    <th className="text-right text-xs font-medium text-slate-500 px-3 py-3">每股</th>
                    <th className="text-right text-xs font-medium text-slate-500 px-3 py-3">持股</th>
                    <th className="text-right text-xs font-medium text-slate-500 px-3 py-3">总额</th>
                    <th className="text-right text-xs font-medium text-slate-500 px-6 py-3"></th>
                  </tr>
                </thead>
                <tbody>
                  {dividends.map(d => (
                    <tr key={d.id} className="border-b border-slate-50 hover:bg-slate-50/50 transition-colors">
                      <td className="px-6 py-3 text-slate-600">{d.recordDate}</td>
                      <td className="px-3 py-3">
                        <span className="font-medium">{d.stockName}</span>
                        <span className="text-xs text-slate-400 ml-1">{shortSymbol(d.stockSymbol || '')}</span>
                      </td>
                      <td className="px-3 py-3 text-right tabular-nums">{d.amountPerShare}</td>
                      <td className="px-3 py-3 text-right tabular-nums">{d.sharesHeld}</td>
                      <td className="px-3 py-3 text-right text-emerald-600 font-semibold tabular-nums">{d.totalAmount}</td>
                      <td className="px-6 py-3 text-right">
                        <button onClick={() => handleDelete(d.id)} className="text-xs text-slate-400 hover:text-red-500 transition-colors">删除</button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
