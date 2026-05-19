import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Card, CardContent } from '@/components/ui/card'

interface Tx {
  id: number; stockName?: string; stockSymbol?: string; type: 'BUY' | 'SELL'
  shares: number; price: number; fee: number; tradeDate: string; note?: string
}

export default function Transactions() {
  const [txns, setTxns] = useState<Tx[]>([])
  const [loading, setLoading] = useState(true)

  function load() {
    fetch('/investory/api/transactions', { credentials: 'include' })
      .then(r => r.json()).then(d => setTxns(d.transactions || []))
      .finally(() => setLoading(false))
  }

  useEffect(() => { load() }, [])

  async function handleDelete(id: number) {
    if (!confirm('确认删除这笔交易？')) return
    await fetch(`/investory/api/transactions/${id}`, { method: 'DELETE', credentials: 'include' })
    load()
  }

  if (loading) {
    return <div className="flex items-center justify-center h-96"><div className="w-8 h-8 border-2 border-slate-300 border-t-slate-900 rounded-full animate-spin" /></div>
  }

  return (
    <div className="p-6 space-y-6">
      <div className="flex items-center justify-between">
        <h2 className="text-xl font-bold text-slate-900 tracking-tight">交易记录</h2>
        <Link to="/transactions/add"
          className="inline-flex items-center gap-1.5 h-9 px-4 rounded-xl bg-slate-900 text-white text-xs font-medium hover:bg-slate-800 transition-colors">
          添加交易
        </Link>
      </div>
      <Card>
        <CardContent className="p-0">
          {txns.length === 0 ? (
            <div className="py-12 text-center text-slate-500 text-sm">暂无交易记录</div>
          ) : (
            <div className="overflow-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-slate-100">
                    <th className="text-left text-xs font-medium text-slate-500 px-6 py-3">日期</th>
                    <th className="text-left text-xs font-medium text-slate-500 px-3 py-3">股票</th>
                    <th className="text-left text-xs font-medium text-slate-500 px-3 py-3">操作</th>
                    <th className="text-right text-xs font-medium text-slate-500 px-3 py-3">股数</th>
                    <th className="text-right text-xs font-medium text-slate-500 px-3 py-3">价格</th>
                    <th className="text-right text-xs font-medium text-slate-500 px-3 py-3">手续费</th>
                    <th className="text-right text-xs font-medium text-slate-500 px-6 py-3"></th>
                  </tr>
                </thead>
                <tbody>
                  {txns.map(t => (
                    <tr key={t.id} className="border-b border-slate-50 hover:bg-slate-50/50 transition-colors">
                      <td className="px-6 py-3 text-slate-600">{t.tradeDate}</td>
                      <td className="px-3 py-3">
                        <span className="font-medium text-slate-900">{t.stockName}</span>
                        <span className="text-xs text-slate-400 ml-1">{t.stockSymbol}</span>
                      </td>
                      <td className="px-3 py-3">
                        <span className={`inline-flex items-center rounded-lg px-2 py-0.5 text-xs font-medium ${t.type === 'BUY' ? 'bg-red-50 text-red-600' : 'bg-emerald-50 text-emerald-600'}`}>
                          {t.type === 'BUY' ? '买入' : '卖出'}
                        </span>
                      </td>
                      <td className="px-3 py-3 text-right tabular-nums">{t.shares}</td>
                      <td className="px-3 py-3 text-right tabular-nums">{t.price?.toFixed(2)}</td>
                      <td className="px-3 py-3 text-right tabular-nums">{t.fee?.toFixed(2)}</td>
                      <td className="px-6 py-3 text-right">
                        <button onClick={() => handleDelete(t.id)}
                          className="text-xs text-slate-400 hover:text-red-500 transition-colors">删除</button>
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
