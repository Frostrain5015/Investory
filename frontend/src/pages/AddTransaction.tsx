import { useState, useEffect } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { AnimatePresence, motion } from 'framer-motion'
import { searchStocks, getCashBalances } from '@/services/api'
import type { StockSearchItem } from '@/types'
import { displaySymbol } from '@/lib/format'
import { Card, CardContent } from '@/components/ui/card'
import { useT } from '@/i18n/I18nContext'
import { BASE } from '@/services/api'

const CURRENCY_SYMBOL: Record<string, string> = { CNY: '¥', HKD: 'HK$', USD: '$' }

const TYPES = ['BUY', 'SELL', 'DIV', 'TRANSFER_IN', 'TRANSFER_OUT'] as const

type TxType = typeof TYPES[number]

const inputCls = 'w-full h-10 rounded-xl border border-slate-200 px-3.5 text-sm focus:outline-none focus:ring-2 focus:ring-slate-900/10'

function typeLabel(txType: TxType, lang: string): string {
  switch (txType) {
    case 'BUY':          return lang === 'zh' ? '买入' : 'Buy'
    case 'SELL':         return lang === 'zh' ? '卖出' : 'Sell'
    case 'DIV':          return lang === 'zh' ? '分红' : 'Dividend'
    case 'TRANSFER_IN':  return lang === 'zh' ? '转入' : 'Transfer In'
    case 'TRANSFER_OUT': return lang === 'zh' ? '转出' : 'Transfer Out'
  }
}

export default function AddTransaction() {
  const navigate = useNavigate()
  const { t, lang } = useT()
  const [params] = useSearchParams()
  const editId = params.get('edit')
  const initType = (params.get('type') as TxType) || 'BUY'

  const [stockQuery, setStockQuery] = useState('')
  const [stocks, setStocks] = useState<StockSearchItem[]>([])
  const [selectedStock, setSelectedStock] = useState<StockSearchItem | null>(null)
  const [showDropdown, setShowDropdown] = useState(false)
  const [type, setType] = useState<TxType>(initType)
  const [txStockId, setTxStockId] = useState<number>(0)
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

  useEffect(() => {
    if (!editId) return
    const isDiv = initType === 'DIV'
    const url = isDiv
      ? `${BASE}/api/dividends/${editId}`
      : `${BASE}/api/transactions/${editId}`
    fetch(url, { credentials: 'include' })
      .then(r => r.json())
      .then(item => {
        if (!item || item.error) return
        if (isDiv) {
          setType('DIV')
          setTxStockId(item.stockId || 0)
          setDividendPerShare(String(item.amountPerShare || ''))
          setTradeDate(item.date)
          setStockQuery(item.stockName || '')
          setSelectedStock({ id: String(item.stockId || 0), symbol: item.stockSymbol, name: item.stockName, market: '', currency: 'CNY', price: 0 })
        } else {
          setType(item.type)
          setTxStockId(item.stockId || 0)
          setShares(String(item.shares || ''))
          setPrice(String(item.price || ''))
          setFee(String(item.fee || ''))
          setTradeDate(item.date)
          setNote(item.note || '')
          setCurrency(item.currency || 'CNY')
          if (item.stockSymbol) {
            setStockQuery(item.stockName || '')
            setSelectedStock({ id: String(item.stockId || 0), symbol: item.stockSymbol, name: item.stockName, market: item.stockMarket || '', currency: item.currency || 'CNY', price: 0 })
          }
        }
      })
  }, [editId])

  useEffect(() => {
    if (stockQuery.length >= 1 && !selectedStock) searchStocks(stockQuery).then(setStocks)
  }, [stockQuery])

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (type !== 'TRANSFER_IN' && type !== 'TRANSFER_OUT' && !selectedStock) return
    setSubmitting(true)
    setSubmitError(null)
    if (type === 'TRANSFER_IN' || type === 'TRANSFER_OUT') {
      const form = new URLSearchParams({
        stockId: '0', type, shares, price: '0', tradeDate, currency, note: note || '',
      })
      const url = editId ? `${BASE}/api/transactions/${editId}` : `${BASE}/api/transactions`
      const res = await fetch(url, {
        method: editId ? 'PUT' : 'POST', credentials: 'include',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: form.toString(),
      })
      if (!res.ok) {
        const body = await res.json().catch(() => ({}))
        if (body.error === 'INSUFFICIENT_CASH') {
          const sym = CURRENCY_SYMBOL[currency] ?? currency
          const bal = Number(body.balance).toFixed(2)
          const req = Number(body.required).toFixed(2)
          setSubmitError(lang === 'zh'
            ? `现金余额不足：${sym}${bal}，本次需要 ${sym}${req}，请转入资金`
            : `Insufficient balance: ${sym}${bal}, required ${sym}${req}, please transfer funds`)
        } else {
          setSubmitError(lang === 'zh' ? '提交失败，请重试' : 'Submit failed, please retry')
        }
        setSubmitting(false)
        return
      }
    } else if (type === 'DIV' && selectedStock) {
      const form = new URLSearchParams({
        stockId: String(editId ? txStockId : selectedStock.id), amountPerShare: dividendPerShare, recordDate: tradeDate,
      })
      const url = editId ? `${BASE}/api/dividends/${editId}` : `${BASE}/api/dividends`
      await fetch(url, {
        method: editId ? 'PUT' : 'POST', credentials: 'include',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: form.toString(),
      })
    } else if (selectedStock) {
      const form = new URLSearchParams({
        stockId: String(editId ? txStockId : selectedStock.id), type, shares, price,
        fee: fee || '', tradeDate, note: note || ''
      })
      const url = editId ? `${BASE}/api/transactions/${editId}` : `${BASE}/api/transactions`
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
          setSubmitError(lang === 'zh'
            ? `现金余额不足：${sym}${bal}，本次需要 ${sym}${req}，请转入资金`
            : `Insufficient balance: ${sym}${bal}, required ${sym}${req}, please transfer funds`)
        } else {
          setSubmitError(lang === 'zh' ? '提交失败，请重试' : 'Submit failed, please retry')
        }
        setSubmitting(false)
        return
      }
    }
    navigate('/transactions')
  }

  const selectedCurrency = selectedStock?.currency ?? 'CNY'
  const cashHintLabel = lang === 'zh' ? '当前可用现金：' : 'Available cash: '
  const cashHint = type === 'BUY' && selectedStock
    ? (cashBalances[selectedCurrency] != null
        ? `${CURRENCY_SYMBOL[selectedCurrency] ?? selectedCurrency}${Number(cashBalances[selectedCurrency]).toFixed(2)} ${lang === 'zh' ? '可用' : 'available'}`
        : null)
    : null

  const needsStock = type !== 'TRANSFER_IN' && type !== 'TRANSFER_OUT'

  const currencyOptions = [
    { value: 'CNY', label: lang === 'zh' ? '¥ 人民币' : '¥ CNY' },
    { value: 'HKD', label: lang === 'zh' ? 'HK$ 港币' : 'HK$ HKD' },
    { value: 'USD', label: lang === 'zh' ? '$ 美元' : '$ USD' },
  ]

  return (
    <div className="p-6 max-w-lg mx-auto space-y-6">
      <h2 className="text-xl font-bold text-slate-900 tracking-tight">
        {editId
          ? (lang === 'zh' ? '编辑交易' : 'Edit Transaction')
          : (lang === 'zh' ? '添加交易' : 'Add Transaction')}
      </h2>
      <Card>
        <CardContent className="pt-6">
          <form onSubmit={handleSubmit} className="space-y-4">

            {/* Segmented type selector */}
            <div className="relative flex rounded-xl bg-slate-100 p-1 overflow-x-auto">
              {TYPES.map(txType => (
                <button key={txType} type="button" onClick={() => { setType(txType); setSubmitError(null) }}
                  className="relative flex-1 py-1.5 text-xs font-medium z-10 transition-colors"
                  style={{ color: type === txType ? '#0f172a' : '#64748b' }}>
                  {type === txType && (
                    <motion.div layoutId="activeTab"
                      className="absolute inset-0 bg-white rounded-lg shadow-sm"
                      transition={{ type: 'spring', stiffness: 500, damping: 40 }} />
                  )}
                  <span className="relative">{typeLabel(txType, lang)}</span>
                </button>
              ))}
            </div>

            {/* Stock search (BUY / SELL / DIV) */}
            {needsStock && (
              <div className="relative">
                <label className="block text-sm font-medium text-slate-700 mb-1.5">{t.transactions.stock}</label>
                <input type="text"
                  value={selectedStock ? `${selectedStock.name} (${selectedStock.symbol ? displaySymbol(selectedStock.symbol, selectedStock.market) : ''})` : stockQuery}
                  onChange={e => { setSelectedStock(null); setStockQuery(e.target.value); setShowDropdown(true) }}
                  onFocus={() => stocks.length > 0 && setShowDropdown(true)}
                  onBlur={() => setTimeout(() => setShowDropdown(false), 200)}
                  placeholder={lang === 'zh' ? '搜索股票代码或名称...' : 'Search by symbol or name...'}
                  required
                  disabled={!!editId}
                  className={inputCls + ' disabled:bg-slate-50 disabled:text-slate-500'} />
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

            {/* Animated field group */}
            <AnimatePresence mode="wait">
              <motion.div key={type}
                initial={{ opacity: 0, y: 8 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -8 }}
                transition={{ duration: 0.2 }}
                className="space-y-4">

                {(type === 'TRANSFER_IN' || type === 'TRANSFER_OUT') && (
                  <>
                    <div className="grid grid-cols-2 gap-4">
                      <div>
                        <label className="block text-sm font-medium text-slate-700 mb-1.5">{t.transactions.amount}</label>
                        <input type="number" step="any" value={shares} onChange={e => setShares(e.target.value)} required className={inputCls} />
                      </div>
                      <div>
                        <label className="block text-sm font-medium text-slate-700 mb-1.5">{lang === 'zh' ? '币种' : 'Currency'}</label>
                        <select value={currency} onChange={e => setCurrency(e.target.value)} className={inputCls}>
                          {currencyOptions.map(opt => (
                            <option key={opt.value} value={opt.value}>{opt.label}</option>
                          ))}
                        </select>
                      </div>
                    </div>
                    <div>
                      <label className="block text-sm font-medium text-slate-700 mb-1.5">{t.transactions.date}</label>
                      <input type="date" value={tradeDate} onChange={e => setTradeDate(e.target.value)} required className={inputCls} />
                    </div>
                  </>
                )}

                {type === 'DIV' && (
                  <div className="grid grid-cols-2 gap-4">
                    <div>
                      <label className="block text-sm font-medium text-slate-700 mb-1.5">{lang === 'zh' ? '每股分红' : 'Per Share'}</label>
                      <input type="number" step="any" value={dividendPerShare} onChange={e => setDividendPerShare(e.target.value)} required className={inputCls} />
                    </div>
                    <div>
                      <label className="block text-sm font-medium text-slate-700 mb-1.5">{lang === 'zh' ? '登记日期' : 'Record Date'}</label>
                      <input type="date" value={tradeDate} onChange={e => setTradeDate(e.target.value)} required className={inputCls} />
                    </div>
                  </div>
                )}

                {(type === 'BUY' || type === 'SELL') && (
                  <>
                    <div className="grid grid-cols-2 gap-4">
                      <div>
                        <label className="block text-sm font-medium text-slate-700 mb-1.5">{t.transactions.shares}</label>
                        <input type="number" step="any" value={shares} onChange={e => setShares(e.target.value)} required className={inputCls} />
                      </div>
                      <div>
                        <label className="block text-sm font-medium text-slate-700 mb-1.5">{t.transactions.price}</label>
                        <input type="number" step="any" value={price} onChange={e => setPrice(e.target.value)} required className={inputCls} />
                      </div>
                    </div>
                    <div className="grid grid-cols-2 gap-4">
                      <div>
                        <label className="block text-sm font-medium text-slate-700 mb-1.5">{lang === 'zh' ? '手续费' : 'Fee'}</label>
                        <input type="number" step="any" value={fee} onChange={e => setFee(e.target.value)} className={inputCls} />
                      </div>
                      <div>
                        <label className="block text-sm font-medium text-slate-700 mb-1.5">{t.transactions.date}</label>
                        <input type="date" value={tradeDate} onChange={e => setTradeDate(e.target.value)} required className={inputCls} />
                      </div>
                    </div>
                    {cashHint && <p className="text-xs text-slate-400">{cashHintLabel}{cashHint}</p>}
                  </>
                )}

              </motion.div>
            </AnimatePresence>

            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1.5">{lang === 'zh' ? '备注' : 'Note'}</label>
              <input type="text" value={note} onChange={e => setNote(e.target.value)} className={inputCls} />
            </div>

            {submitError && (
              <p className="text-sm text-red-600 bg-red-50 rounded-xl px-4 py-2.5">{submitError}</p>
            )}

            <div className="flex gap-3">
              <button type="button" onClick={() => navigate('/transactions')}
                className="flex-1 h-10 rounded-xl border border-slate-200 text-sm font-medium text-slate-600 hover:bg-slate-50 transition-colors">{t.common.cancel}</button>
              <button type="submit" disabled={submitting || (needsStock && !selectedStock)}
                className="flex-1 h-10 rounded-xl bg-slate-900 text-white text-sm font-medium hover:bg-slate-800 transition-colors disabled:opacity-50">
                {submitting
                  ? (lang === 'zh' ? '提交中...' : 'Submitting...')
                  : editId
                    ? (lang === 'zh' ? '保存修改' : 'Save Changes')
                    : t.common.confirm}
              </button>
            </div>
          </form>
        </CardContent>
      </Card>
    </div>
  )
}
