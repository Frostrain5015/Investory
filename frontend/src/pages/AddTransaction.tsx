import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { searchStocks } from '@/services/api'
import type { StockSearchItem } from '@/types'
import { Card, CardContent } from '@/components/ui/card'

export default function AddTransaction() {
  const navigate = useNavigate()
  const [stockQuery, setStockQuery] = useState('')
  const [stocks, setStocks] = useState<StockSearchItem[]>([])
  const [selectedStock, setSelectedStock] = useState<StockSearchItem | null>(null)
  const [showDropdown, setShowDropdown] = useState(false)
  const [type, setType] = useState<'BUY' | 'SELL'>('BUY')
  const [shares, setShares] = useState('')
  const [price, setPrice] = useState('')
  const [fee, setFee] = useState('')
  const [tradeDate, setTradeDate] = useState(new Date().toISOString().slice(0, 10))
  const [note, setNote] = useState('')
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => { if (stockQuery.length >= 1) searchStocks(stockQuery).then(setStocks) }, [stockQuery])

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!selectedStock) return
    setSubmitting(true)
    const form = new URLSearchParams({ stockId: selectedStock.id, type, shares, price, fee: fee || '', tradeDate, note: note || '' })
    await fetch('/investory/api/transactions', {
      method: 'POST', credentials: 'include',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: form.toString(),
    })
    navigate('/transactions')
  }

  return (
    <div className="p-6 max-w-lg mx-auto space-y-6">
      <h2 className="text-xl font-bold text-slate-900 tracking-tight">添加交易</h2>
      <Card>
        <CardContent className="pt-6">
          <form onSubmit={handleSubmit} className="space-y-4">
            <div className="relative">
              <label className="block text-sm font-medium text-slate-700 mb-1.5">股票</label>
              <input type="text" value={selectedStock ? `${selectedStock.name} (${selectedStock.symbol})` : stockQuery}
                onChange={(e) => { setSelectedStock(null); setStockQuery(e.target.value); setShowDropdown(true) }}
                onFocus={() => stocks.length > 0 && setShowDropdown(true)}
                onBlur={() => setTimeout(() => setShowDropdown(false), 200)}
                placeholder="搜索股票代码或名称..." required
                className="w-full h-10 rounded-xl border border-slate-200 px-3.5 text-sm focus:outline-none focus:ring-2 focus:ring-slate-900/10" />
              {showDropdown && stocks.length > 0 && !selectedStock && (
                <div className="absolute top-full mt-1 w-full bg-white rounded-xl border border-slate-200 shadow-lg overflow-hidden z-50">
                  {stocks.map(s => (
                    <button key={s.id} type="button"
                      onClick={() => { setSelectedStock(s); setStockQuery(''); setShowDropdown(false) }}
                      className="w-full text-left px-4 py-2.5 hover:bg-slate-50 flex justify-between">
                      <span className="text-sm font-medium">{s.name}</span>
                      <span className="text-xs text-slate-400">{s.symbol}</span>
                    </button>
                  ))}
                </div>
              )}
            </div>
            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1.5">操作类型</label>
              <div className="flex gap-2">
                {['BUY', 'SELL'].map(t => (
                  <button key={t} type="button"
                    onClick={() => setType(t as 'BUY' | 'SELL')}
                    className={`flex-1 h-10 rounded-xl text-sm font-medium transition-colors ${type === t ? 'bg-slate-900 text-white' : 'bg-slate-100 text-slate-600 hover:bg-slate-200'}`}>
                    {t === 'BUY' ? '买入' : '卖出'}
                  </button>
                ))}
              </div>
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">股数</label>
                <input type="number" step="any" value={shares} onChange={e => setShares(e.target.value)} required
                  className="w-full h-10 rounded-xl border border-slate-200 px-3.5 text-sm focus:outline-none focus:ring-2 focus:ring-slate-900/10" />
              </div>
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">价格</label>
                <input type="number" step="any" value={price} onChange={e => setPrice(e.target.value)} required
                  className="w-full h-10 rounded-xl border border-slate-200 px-3.5 text-sm focus:outline-none focus:ring-2 focus:ring-slate-900/10" />
              </div>
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">手续费</label>
                <input type="number" step="any" value={fee} onChange={e => setFee(e.target.value)}
                  className="w-full h-10 rounded-xl border border-slate-200 px-3.5 text-sm focus:outline-none focus:ring-2 focus:ring-slate-900/10" />
              </div>
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">交易日期</label>
                <input type="date" value={tradeDate} onChange={e => setTradeDate(e.target.value)} required
                  className="w-full h-10 rounded-xl border border-slate-200 px-3.5 text-sm focus:outline-none focus:ring-2 focus:ring-slate-900/10" />
              </div>
            </div>
            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1.5">备注</label>
              <input type="text" value={note} onChange={e => setNote(e.target.value)}
                className="w-full h-10 rounded-xl border border-slate-200 px-3.5 text-sm focus:outline-none focus:ring-2 focus:ring-slate-900/10" />
            </div>
            <div className="flex gap-3">
              <button type="button" onClick={() => navigate('/transactions')}
                className="flex-1 h-10 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors">取消</button>
              <button type="submit" disabled={submitting || !selectedStock}
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
