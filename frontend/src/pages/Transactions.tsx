import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Card, CardContent } from '@/components/ui/card'
import { shortSymbol } from '@/lib/format'

interface Activity {
  id: number; date: string; type: 'BUY' | 'SELL' | 'DIV'
  stockName?: string; stockSymbol?: string
  shares?: number; price?: number; fee?: number; note?: string
  amountPerShare?: number; sharesHeld?: number; totalAmount?: number
}

export default function Transactions() {
  const [items, setItems] = useState<Activity[]>([])
  const [loading, setLoading] = useState(true)

  function load() {
    fetch('/investory/api/transactions', { credentials: 'include' })
      .then(r => r.json()).then(setItems)
      .finally(() => setLoading(false))
  }

  useEffect(() => { load() }, [])

  async function handleDelete(id: number, type: string) {
    if (!confirm(type === 'DIV' ? '确认删除这笔分红记录？' : '确认删除这笔交易？')) return
    const endpoint = type === 'DIV' ? `/api/dividends/${id}` : `/api/transactions/${id}`
    await fetch(`/investory${endpoint}`, { method: 'DELETE', credentials: 'include' })
    load()
  }

  if (loading) {
    return <div className="flex items-center justify-center h-96"><div className="w-8 h-8 border-2 border-slate-300 border-t-slate-900 rounded-full animate-spin" /></div>
  }

  const typeBadge = (t: string) => {
    switch (t) {
      case 'BUY':  return { label: '买', cls: 'bg-red-50 text-red-600' }
      case 'SELL': return { label: '卖', cls: 'bg-emerald-50 text-emerald-600' }
      case 'DIV':  return { label: '分红', cls: 'bg-blue-50 text-blue-600' }
      default:     return { label: t, cls: 'bg-slate-50' }
    }
  }

  return (
    <div className="p-6 space-y-6">
      <div className="flex items-center justify-between">
        <h2 className="text-xl font-bold text-slate-900 tracking-tight">交易记录</h2>
        <div className="flex gap-2">
          <Link to="/transactions/add"
            className="inline-flex items-center gap-1.5 h-9 px-4 rounded-xl bg-slate-900 text-white text-xs font-medium hover:bg-slate-800 transition-colors">
            添加交易
          </Link>
          <Link to="/dividends/add"
            className="inline-flex items-center gap-1.5 h-9 px-4 rounded-xl border border-slate-200 text-slate-700 text-xs font-medium hover:bg-slate-50 transition-colors">
            添加分红
          </Link>
        </div>
      </div>
      <Card>
        <CardContent className="p-0">
          {items.length === 0 ? (
            <div className="py-12 text-center text-slate-500 text-sm">暂无交易记录</div>
          ) : (
            <div className="overflow-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-slate-100">
                    <th className="text-left text-xs font-medium text-slate-500 px-6 py-3">日期</th>
                    <th className="text-left text-xs font-medium text-slate-500 px-3 py-3">股票</th>
                    <th className="text-left text-xs font-medium text-slate-500 px-3 py-3">类型</th>
                    <th className="text-right text-xs font-medium text-slate-500 px-3 py-3">详情</th>
                    <th className="text-right text-xs font-medium text-slate-500 px-3 py-3">金额</th>
                    <th className="w-16"></th>
                  </tr>
                </thead>
                <tbody>
                  {items.map(item => {
                    const badge = typeBadge(item.type)
                    const detail = item.type === 'DIV'
                      ? `${item.amountPerShare} × ${item.sharesHeld}股`
                      : `${item.shares}股 @ ${item.price?.toFixed(2)}`
                    const amount = item.type === 'DIV'
                      ? item.totalAmount?.toFixed(2)
                      : (item.price && item.shares ? (item.price * item.shares).toFixed(2) : '')
                    return (
                      <tr key={`${item.type}-${item.id}`} className="border-b border-slate-50 hover:bg-slate-50/50 transition-colors">
                        <td className="px-6 py-3 text-slate-600">{item.date}</td>
                        <td className="px-3 py-3">
                          <Link to={`/stock?symbol=${encodeURIComponent(item.stockSymbol || '')}`}
                            className="font-medium text-slate-900 hover:text-blue-600 transition-colors">{item.stockName}</Link>
                          <span className="text-xs text-slate-400 ml-1">{shortSymbol(item.stockSymbol || '')}</span>
                        </td>
                        <td className="px-3 py-3">
                          <span className={`inline-flex items-center rounded-lg px-2 py-0.5 text-xs font-medium ${badge.cls}`}>{badge.label}</span>
                        </td>
                        <td className="px-3 py-3 text-right tabular-nums">{detail}</td>
                        <td className={`px-3 py-3 text-right font-medium tabular-nums ${item.type === 'DIV' ? 'text-blue-600' : 'text-slate-900'}`}>
                          {item.type === 'DIV' && '+'}{amount}
                        </td>
                        <td className="pr-6 text-right">
                          <button onClick={() => handleDelete(item.id, item.type)}
                            className="text-xs text-slate-400 hover:text-red-500 transition-colors">删除</button>
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
