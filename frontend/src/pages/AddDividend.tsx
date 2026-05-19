import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Card, CardContent } from '@/components/ui/card'

interface Snapshot { stockId: number; stockName: string; stockSymbol: string; totalShares: number }

export default function AddDividend() {
  const navigate = useNavigate()
  const [snapshots, setSnapshots] = useState<Snapshot[]>([])
  const [stockId, setStockId] = useState('')
  const [amountPerShare, setAmountPerShare] = useState('')
  const [recordDate, setRecordDate] = useState(new Date().toISOString().slice(0, 10))
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => {
    fetch('/investory/api/holdings', { credentials: 'include' })
      .then(r => r.json()).then(d => setSnapshots(d.snapshots || []))
  }, [])

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setSubmitting(true)
    const form = new URLSearchParams({ stockId, amountPerShare, recordDate })
    await fetch('/investory/api/dividends', {
      method: 'POST', credentials: 'include',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: form.toString(),
    })
    navigate('/dividends')
  }

  return (
    <div className="p-6 max-w-lg mx-auto space-y-6">
      <h2 className="text-xl font-bold text-slate-900 tracking-tight">添加分红</h2>
      <Card>
        <CardContent className="pt-6">
          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1.5">股票</label>
              <select value={stockId} onChange={e => setStockId(e.target.value)} required
                className="w-full h-10 rounded-xl border border-slate-200 px-3.5 text-sm focus:outline-none focus:ring-2 focus:ring-slate-900/10">
                <option value="">请选择持仓股票</option>
                {snapshots.map(s => (
                  <option key={s.stockId} value={s.stockId}>{s.stockName} ({s.stockSymbol}) — 持有 {s.totalShares} 股</option>
                ))}
              </select>
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">每股分红</label>
                <input type="number" step="any" value={amountPerShare} onChange={e => setAmountPerShare(e.target.value)} required
                  className="w-full h-10 rounded-xl border border-slate-200 px-3.5 text-sm focus:outline-none focus:ring-2 focus:ring-slate-900/10" />
              </div>
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">记录日期</label>
                <input type="date" value={recordDate} onChange={e => setRecordDate(e.target.value)} required
                  className="w-full h-10 rounded-xl border border-slate-200 px-3.5 text-sm focus:outline-none focus:ring-2 focus:ring-slate-900/10" />
              </div>
            </div>
            <div className="flex gap-3">
              <button type="button" onClick={() => navigate('/dividends')}
                className="flex-1 h-10 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors">取消</button>
              <button type="submit" disabled={submitting}
                className="flex-1 h-10 rounded-xl bg-slate-900 text-white text-sm font-medium hover:bg-slate-800 transition-colors disabled:opacity-50">
                {submitting ? '提交中...' : '确认'}
              </button>
            </div>
          </form>
        </CardContent>
      </Card>
    </div>
  )
}
