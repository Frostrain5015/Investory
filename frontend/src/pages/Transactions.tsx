import { useEffect, useState, useCallback } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '@/hooks/use-auth'
import { useConfirm } from '@/hooks/use-confirm'
import { Card, CardContent } from '@/components/ui/card'
import { displaySymbol } from '@/lib/format'
import { Pencil, Trash2, Plus } from 'lucide-react'
import { useT } from '@/i18n/I18nContext'

interface Activity {
  id: number; date: string; type: 'BUY' | 'SELL' | 'DIV' | 'TRANSFER_IN' | 'TRANSFER_OUT'
  stockName?: string; stockSymbol?: string
  shares?: number; price?: number; fee?: number; note?: string
  amountPerShare?: number; sharesHeld?: number; totalAmount?: number
}

export default function Transactions() {
  const confirm = useConfirm()
  const { portfolioId } = useAuth()
  const { t, lang } = useT()
  const [items, setItems] = useState<Activity[]>([])
  const [loading, setLoading] = useState(true)
  const [managing, setManaging] = useState(false)

  const load = useCallback(() => {
    fetch('/investory/api/transactions', { credentials: 'include' })
      .then(r => r.json()).then(setItems)
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => { setItems([]); setLoading(true); load() }, [portfolioId])
  useEffect(() => { load() }, [])

  async function handleDelete(id: number, type: string) {
    const msg = lang === 'zh'
      ? (type === 'DIV' ? '确认删除这笔分红？' : '确认删除这笔交易？')
      : (type === 'DIV' ? 'Delete this dividend?' : 'Delete this transaction?')
    if (!(await confirm(msg))) return
    const endpoint = type === 'DIV' ? `/api/dividends/${id}` : `/api/transactions/${id}`
    await fetch(`/investory${endpoint}`, { method: 'DELETE', credentials: 'include' })
    load()
  }

  const sharesUnit = lang === 'zh' ? '股' : ''

  if (loading) {
    return <div className="flex flex-col items-center justify-center gap-3 h-96"><div className="w-8 h-8 border-2 border-slate-300 border-t-slate-900 rounded-full animate-spin" /><span className="text-sm text-slate-400">{t.common.loading}</span></div>
  }

  const typeBadge = (txType: string) => {
    switch (txType) {
      case 'BUY':          return { label: t.transactions.buy,   cls: 'bg-red-50 text-red-600' }
      case 'SELL':         return { label: t.transactions.sell,  cls: 'bg-emerald-50 text-emerald-600' }
      case 'DIV':          return { label: t.transactions.dividend, cls: 'bg-blue-50 text-blue-600' }
      case 'TRANSFER_IN':  return { label: lang === 'zh' ? '转入' : 'Transfer In',  cls: 'bg-amber-50 text-amber-600' }
      case 'TRANSFER_OUT': return { label: lang === 'zh' ? '转出' : 'Transfer Out', cls: 'bg-orange-50 text-orange-600' }
      default:             return { label: txType, cls: 'bg-slate-50' }
    }
  }

  return (
    <div className="p-6 space-y-6">
      <div className="flex items-center justify-between">
        <h2 className="text-xl font-bold text-slate-900 tracking-tight">{t.transactions.title}</h2>
        <div className="flex gap-2">
          <button onClick={() => setManaging(!managing)}
            className={`h-9 px-4 rounded-xl text-xs font-medium transition-colors border ${managing ? 'bg-slate-900 text-white border-slate-900' : 'bg-white text-slate-600 border-slate-200 hover:bg-slate-50'}`}>
            {lang === 'zh' ? '管理' : 'Manage'}
          </button>
          <Link to="/transactions/add"
            className="inline-flex items-center gap-1.5 h-9 px-4 rounded-xl bg-slate-900 text-white text-xs font-medium hover:bg-slate-800 transition-colors">
            <Plus className="w-3.5 h-3.5" />{lang === 'zh' ? '添加交易' : 'Add Transaction'}
          </Link>
        </div>
      </div>
      <Card>
        <CardContent className="p-0">
          {items.length === 0 ? (
            <div className="py-12 text-center text-slate-500 text-sm">{t.transactions.noTransactions}</div>
          ) : (
            <>
              {/* Desktop table */}
              <div className="hidden lg:block overflow-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-slate-100">
                      <th className="text-left text-xs font-medium text-slate-500 px-6 py-3">{t.transactions.date}</th>
                      <th className="text-left text-xs font-medium text-slate-500 px-3 py-3">{t.transactions.stock}</th>
                      <th className="text-left text-xs font-medium text-slate-500 px-3 py-3">{t.transactions.type}</th>
                      <th className="text-right text-xs font-medium text-slate-500 px-3 py-3">{lang === 'zh' ? '详情' : 'Details'}</th>
                      <th className="text-right text-xs font-medium text-slate-500 px-3 py-3">{t.transactions.amount}</th>
                      <th className="w-16"></th>
                    </tr>
                  </thead>
                  <tbody>
                    {items.map(item => {
                      const badge = typeBadge(item.type)
                      const isTransfer = item.type === 'TRANSFER_IN' || item.type === 'TRANSFER_OUT'
                      const detail = item.type === 'DIV'
                        ? `${item.amountPerShare} × ${item.sharesHeld}`
                        : isTransfer
                        ? `${item.shares}`
                        : `${item.shares}${sharesUnit} @ ${item.price?.toFixed(2)}`
                      const amount = item.type === 'DIV'
                        ? item.totalAmount?.toFixed(2)
                        : isTransfer
                        ? item.shares?.toFixed(2)
                        : (item.price && item.shares ? (item.price * item.shares).toFixed(2) : '')
                      return (
                        <tr key={`${item.type}-${item.id}`} className="border-b border-slate-50 hover:bg-slate-50/50 transition-colors">
                          <td className="px-6 py-3 text-slate-600">{item.date}</td>
                          <td className="px-3 py-3">
                            {item.stockSymbol ? (
                              <Link to={`/stock?symbol=${encodeURIComponent(item.stockSymbol)}`}
                                className="font-medium text-slate-900 hover:text-blue-600 transition-colors">{item.stockName}</Link>
                            ) : (
                              <span className="font-medium text-slate-600">{item.stockName || '—'}</span>
                            )}
                            {item.stockSymbol && <span className="text-xs text-slate-400 ml-1">{displaySymbol(item.stockSymbol, (item as any).stockMarket || '')}</span>}
                          </td>
                          <td className="px-3 py-3">
                            <span className={`inline-flex items-center rounded-lg px-2 py-0.5 text-xs font-medium ${badge.cls}`}>{badge.label}</span>
                          </td>
                          <td className="px-3 py-3 text-right tabular-nums">{detail}</td>
                          <td className={`px-3 py-3 text-right font-medium tabular-nums ${item.type === 'DIV' ? 'text-blue-600' : 'text-slate-900'}`}>
                            {item.type === 'DIV' && '+'}{amount}
                          </td>
                          <td className="pr-6 text-right">
                            {managing && (
                              <div className="flex items-center gap-2 justify-end">
                                <Link to={`/transactions/add?edit=${item.id}${item.type === 'DIV' ? '&type=DIV' : ''}`}
                                  className="text-slate-400 hover:text-blue-500 transition-colors">
                                  <Pencil className="w-3.5 h-3.5" />
                                </Link>
                                <button onClick={() => handleDelete(item.id, item.type)}
                                  className="text-slate-400 hover:text-red-500 transition-colors">
                                  <Trash2 className="w-3.5 h-3.5" />
                                </button>
                              </div>
                            )}
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
              {/* Mobile cards */}
              <div className="lg:hidden divide-y divide-slate-50">
                {items.map(item => {
                  const badge = typeBadge(item.type)
                  const isTransfer = item.type === 'TRANSFER_IN' || item.type === 'TRANSFER_OUT'
                  const detail = item.type === 'DIV'
                    ? `${item.amountPerShare} × ${item.sharesHeld}`
                    : isTransfer
                    ? `${item.shares}`
                    : `${item.shares}${sharesUnit} @ ${item.price?.toFixed(2)}`
                  const amount = item.type === 'DIV'
                    ? item.totalAmount?.toFixed(2)
                    : isTransfer
                    ? item.shares?.toFixed(2)
                    : (item.price && item.shares ? (item.price * item.shares).toFixed(2) : '')
                  const isPositive = item.type === 'DIV' || (item.type === 'SELL' && item.price && item.shares)
                  return (
                    <div key={`${item.type}-${item.id}`} className="px-4 py-3">
                      <div className="flex items-center justify-between mb-1">
                        <span className="text-xs text-slate-400">{item.date}</span>
                        <span className={`inline-flex items-center rounded-lg px-2 py-0.5 text-xs font-medium ${badge.cls}`}>{badge.label}</span>
                      </div>
                      <div className="flex items-center justify-between">
                        {item.stockSymbol ? (
                          <Link to={`/stock?symbol=${encodeURIComponent(item.stockSymbol)}`} className="font-medium text-slate-900 hover:text-blue-600 truncate max-w-[180px]">{item.stockName}</Link>
                        ) : (
                          <span className="font-medium text-slate-600">{item.stockName || '—'}</span>
                        )}
                        <span className={`text-sm font-semibold tabular-nums ${isPositive ? 'text-emerald-600' : 'text-slate-900'}`}>
                          {item.type === 'DIV' && '+'}{amount}
                        </span>
                      </div>
                      <div className="flex justify-between items-center mt-1">
                        <span className="text-xs text-slate-400">{detail}</span>
                        {managing && (
                          <div className="flex items-center gap-2">
                            <Link to={`/transactions/add?edit=${item.id}${item.type === 'DIV' ? '&type=DIV' : ''}`} className="text-slate-400 hover:text-blue-500"><Pencil className="w-3.5 h-3.5" /></Link>
                            <button onClick={() => handleDelete(item.id, item.type)} className="text-slate-400 hover:text-red-500"><Trash2 className="w-3.5 h-3.5" /></button>
                          </div>
                        )}
                      </div>
                    </div>
                  )
                })}
              </div>
            </>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
