import { useState, useEffect } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { searchStocks, getCashBalances } from '@/services/api'
import type { StockSearchItem } from '@/types'
import { displaySymbol } from '@/lib/format'
import { Card, CardContent } from '@/components/ui/card'

const CURRENCY_SYMBOL: Record<string, string> = { CNY: '¥', HKD: 'HK$', USD: '$' }

export default function AddTransaction() {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const editId = params.get('edit')

  const [stockQuery, setStockQuery] = useState('')
  const [stocks, setStocks] = useState<StockSearchItem[]>([])
  const [selectedStock, setSelectedStock] = useState<StockSearchItem | null>(null)
  const [showDropdown, setShowDropdown] = useState(false)
  const [type, setType] = useState<'BUY' | 'SELL' | 'DIV' | 'TRANSFER_IN' | 'TRANSFER_OUT'>('BUY')
  const [currency, setCurrency] = useState('CNY')
  const [shares, setShares] = useState('')
  const [price, setPrice] = useState('')
  const [fee, setFee] = useState('')
  const [dividendPerShare, setDividendPerShare] = useState('')
  const [tradeDate, setTradeDate] = useState(new Date().toISOString().slice(0, 10))
  const [note, setNote] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [cashBalances, setCashBalances] = useState<Record<string, number>>({})

  useEffect(() => {
    getCashBalances().then(r => {
      const map: Record<string, number> = {}
      for (const b of r.balances) map[b.currency] = b.amount
      setCashBalances(map)
    }).catch(() => {})
  }, [])

  // Load existing transaction for editing
  useEffect(() => {
    if (!editId) return
    fetch(`/investory/api/transactions`, { credentials: 'include' })
      .then(r => r.json())
      .then(items => {
        const item = items.find((i: { id: number }) => i.id === Number(editId))
        if (!item) return
        setType(item.type)
        setShares(String(item.shares || ''))
        setPrice(String(item.price || ''))
        setFee(String(item.fee || ''))
        setTradeDate(item.date)
        setNote(item.note || '')
        setStockQuery(item.stockName)
        setSelectedStock({ id: '0', symbol: item.stockSymbol, name: item.stockName, market: '', currency: 'CNY', price: 0 })
      })
  }, [editId])

  useEffect(() => { if (stockQuery.length >= 1 && !selectedStock) searchStocks(stockQuery).then(setStocks) }, [stockQuery])

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (type !== 'TRANSFER_IN' && type !== 'TRANSFER_OUT' && !selectedStock) return
    setSubmitting(true)
    setSubmitError(null)
    if (type === 'TRANSFER_IN' || type === 'TRANSFER_OUT') {
      const form = new URLSearchParams({
        stockId: '0', type, shares, price: '0', tradeDate, currency, note: note || '',
      })
      await fetch('/investory/api/transactions', {
        method: 'POST', credentials: 'include',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: form.toString(),
      })
    } else if (type === 'DIV' && selectedStock) {
      const form = new URLSearchParams({
        stockId: String(selectedStock.id), amountPerShare: dividendPerShare, recordDate: tradeDate,
      })
      await fetch('/investory/api/dividends', {
        method: 'POST', credentials: 'include',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: form.toString(),
      })
    } else if (selectedStock) {
      const form = new URLSearchParams({
        stockId: String(selectedStock.id), type, shares, price,
        fee: fee || '', tradeDate, note: note || ''
      })
      const url = editId ? `/investory/api/transactions/${editId}` : '/investory/api/transactions'
      const res = await fetch(url, {
        method: editId ? 'PUT' : 'POST', credentials: 'include',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: form.toString(),
      })
      if (!res.ok) {
        const body = await res.json().catch(() => ({}))
        if (body.error === 'INSUFFICIENT_CASH') {
          const cur = selectedStock.currency || 'CNY'
          const sym = CURRENCY_SYMBOL[cur] ?? cur
          const bal = typeof body.balance === 'number' ? body.balance.toFixed(2) : Number(body.balance).toFixed(2)
          const req = typeof body.required === 'number' ? body.required.toFixed(2) : Number(body.required).toFixed(2)
          setSubmitError(`现金余额不足：${sym}${bal}，本次需要 ${sym}${req}，请先转入资金`)
        } else {
          setSubmitError('提交失败，请稍后重试')
        }
        setSubmitting(false)
        return
      }
    }
    navigate('/transactions')
  }

  const selectedCurrency = selectedStock?.currency ?? 'CNY'
  const cashHint = type === 'BUY' && selectedStock
    ? (cashBalances[selectedCurrency] != null
        ? `${CURRENCY_SYMBOL[selectedCurrency] ?? selectedCurrency}${Number(cashBalances[selectedCurrency]).toFixed(2)} 可用`
        : null)
    : null

  return (
    <div className="p-6 max-w-lg mx-auto space-y-6">
      <h2 className="text-xl font-bold text-slate-900 tracking-tight">{editId ? '编辑交易' : '添加交易'}</h2>
      <Card>
        <CardContent className="pt-6">
          <form onSubmit={handleSubmit} className="space-y-4">
            {(type !== 'TRANSFER_IN' && type !== 'TRANSFER_OUT') && (
            <div className="relative">
              <label className="block text-sm font-medium text-slate-700 mb-1.5">股票</label>
              <input type="text" value={selectedStock ? `${selectedStock.name} (${selectedStock.symbol ? displaySymbol(selectedStock.symbol, selectedStock.market) : ''})` : stockQuery}
                onChange={(e) => { setSelectedStock(null); setStockQuery(e.target.value); setShowDropdown(true) }}
                onFocus={() => stocks.length > 0 && setShowDropdown(true)}
                onBlur={() => setTimeout(() => setShowDropdown(false), 200)}
                placeholder="搜索股票代码或名称..." required
                disabled={!!editId}
                className="w-full h-10 rounded-xl border border-slate-200 px-3.5 text-sm focus:outline-none focus:ring-2 focus:ring-slate-900/10 disabled:bg-slate-50 disabled:text-slate-500" />
              {showDropdown && stocks.length > 0 && !selectedStock && (
                <div className="absolute top-full mt-1 w-full bg-white rounded-xl border border-slate-200 shadow-lg overflow-hidden z-50">
                  {stocks.map(s => (
                    <button key={s.id} type="button"
                      onClick={() => { setSelectedStock(s); setStockQuery(''); setShowDropdown(false) }}
                      className="w-full text-left px-4 py-2.5 hover:bg-slate-50 flex justify-between">
                      <span className="text-sm font-medium">{s.name}</span>
                      <span className="text-xs text-slate-400">{displaySymbol(s.symbol, s.market)}</span>
                    </button>
                  ))}
                </div>
              )}
            </div>
            )}
            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1.5">操作类型</label>
              <div className="flex gap-2">
                {([
                  ['BUY', '买入'],
                  ['SELL', '卖出'],
                  ['DIV', '股息/分红'],
                  ['TRANSFER_IN', '转入'],
                  ['TRANSFER_OUT', '转出'],
                ] as const).map(([val, label]) => (
                  <button key={val} type="button" onClick={() => setType(val)}
                    className={`flex-1 h-10 rounded-xl text-sm font-medium transition-colors ${type === val ? 'bg-slate-900 text-white' : 'bg-slate-100 text-slate-600 hover:bg-slate-200'}`}>
                    {label}
                  </button>
                ))}
              </div>
            </div>
            {(type === 'TRANSFER_IN' || type === 'TRANSFER_OUT') ? (
              <>
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-1.5">金额</label>
                  <input type="number" step="any" value={shares} onChange={e => setShares(e.target.value)} required
                    className="w-full h-10 rounded-xl border border-slate-200 px-3.5 text-sm focus:outline-none focus:ring-2 focus:ring-slate-900/10" />
                </div>
                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-1.5">币种</label>
                  <select value={currency} onChange={e => setCurrency(e.target.value)}
                    className="w-full h-10 rounded-xl border border-slate-200 px-3.5 text-sm focus:outline-none focus:ring-2 focus:ring-slate-900/10">
                    <option value="CNY">¥ 人民币</option>
                    <option value="HKD">HK$ 港币</option>
                    <option value="USD">$ 美元</option>
                  </select>
                </div>
              </div>
              <div>
                <label className="block text-sm font-medium text-slate-700 mb-1.5">日期</label>
                <input type="date" value={tradeDate} onChange={e => setTradeDate(e.target.value)} required
                  className="w-full h-10 rounded-xl border border-slate-200 px-3.5 text-sm focus:outline-none focus:ring-2 focus:ring-slate-900/10" />
              </div>
              </>
            ) : type === 'DIV' ? (
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-1.5">每股分红</label>
                  <input type="number" step="any" value={dividendPerShare} onChange={e => setDividendPerShare(e.target.value)} required
                    className="w-full h-10 rounded-xl border border-slate-200 px-3.5 text-sm focus:outline-none focus:ring-2 focus:ring-slate-900/10" />
                </div>
                <div>
                  <label className="block text-sm font-medium text-slate-700 mb-1.5">登记日期</label>
                  <input type="date" value={tradeDate} onChange={e => setTradeDate(e.target.value)} required
                    className="w-full h-10 rounded-xl border border-slate-200 px-3.5 text-sm focus:outline-none focus:ring-2 focus:ring-slate-900/10" />
                </div>
              </div>
            ) : (
              <>
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
                {cashHint && (
                  <p className="text-xs text-slate-400">当前可用现金：{cashHint}</p>
                )}
              </>
            )}
            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1.5">备注</label>
              <input type="text" value={note} onChange={e => setNote(e.target.value)}
                className="w-full h-10 rounded-xl border border-slate-200 px-3.5 text-sm focus:outline-none focus:ring-2 focus:ring-slate-900/10" />
            </div>
            {submitError && (
              <p className="text-sm text-red-600 bg-red-50 rounded-xl px-4 py-2.5">{submitError}</p>
            )}
            <div className="flex gap-3">
              <button type="button" onClick={() => navigate('/transactions')}
                className="flex-1 h-10 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors">取消</button>
              <button type="submit" disabled={submitting || (!selectedStock && type !== 'TRANSFER_IN' && type !== 'TRANSFER_OUT')}
                className="flex-1 h-10 rounded-xl bg-slate-900 text-white text-sm font-medium hover:bg-slate-800 transition-colors disabled:opacity-50">
                {submitting ? '提交中...' : editId ? '保存修改' : '确认'}
              </button>
            </div>
          </form>
        </CardContent>
      </Card>
    </div>
  )
}
