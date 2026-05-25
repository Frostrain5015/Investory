import { useEffect, useState } from 'react'
import { useSettings } from '@/hooks/use-settings'
import { useT } from '@/i18n/I18nContext'
import { displaySymbol } from '@/lib/format'
import { X } from 'lucide-react'
import { BASE } from '@/services/api'

interface ClosedItem {
  stock_id: number; symbol: string; name: string; market: string
  total_bought: number; total_sold: number; buy_cost: number; sell_proceeds: number; dividends: number
}

export default function ClosedPositions({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { positiveClass, negativeClass, formatCurrency } = useSettings()
  const { t, lang } = useT()
  const [items, setItems] = useState<ClosedItem[]>([])
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (!open) return
    setLoading(true)
    fetch(`${BASE}/api/closed-positions`, { credentials: 'include' })
      .then(r => r.json()).then(setItems)
      .catch(() => setItems([]))
      .finally(() => setLoading(false))
  }, [open])

  if (!open) return null

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      <div className="absolute inset-0 bg-black/40" onClick={onClose} />
      <div className="relative bg-white rounded-2xl shadow-2xl max-w-2xl w-full mx-4 max-h-[80vh] flex flex-col">
        <div className="flex items-center justify-between px-6 py-4 border-b border-slate-100">
          <h3 className="text-base font-bold text-slate-900">
            {lang === 'zh' ? '已清仓标的' : 'Closed Positions'}
          </h3>
          <button onClick={onClose} className="p-1 rounded-lg hover:bg-slate-100"><X className="w-4 h-4 text-slate-400" /></button>
        </div>
        <div className="overflow-auto flex-1 p-6">
          {loading ? (
            <div className="flex flex-col items-center justify-center gap-2 py-8">
              <div className="w-6 h-6 border-2 border-slate-300 border-t-slate-600 rounded-full animate-spin" />
              <span className="text-xs text-slate-400">
                {lang === 'zh' ? '正在加载平仓记录...' : 'Loading closed positions...'}
              </span>
            </div>
          ) : items.length === 0 ? (
            <p className="text-sm text-slate-400 text-center py-8">
              {lang === 'zh' ? '暂无已清仓标的' : 'No closed positions'}
            </p>
          ) : (
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-slate-100">
                  <th className="text-left text-xs font-medium text-slate-500 px-3 py-2">{t.holdings.stock}</th>
                  <th className="text-right text-xs font-medium text-slate-500 px-3 py-2">
                    {lang === 'zh' ? '清仓数量' : 'Closed Shares'}
                  </th>
                  <th className="text-right text-xs font-medium text-slate-500 px-3 py-2">
                    {lang === 'zh' ? '卖出回款' : 'Sell Proceeds'}
                  </th>
                  <th className="text-right text-xs font-medium text-slate-500 px-3 py-2">
                    {lang === 'zh' ? '已实现盈亏' : 'Realized P&L'}
                  </th>
                  <th className="text-right text-xs font-medium text-slate-500 px-3 py-2">{t.transactions.dividend}</th>
                </tr>
              </thead>
              <tbody>
                {items.map(item => {
                  const ratio = item.total_bought > 0 ? item.total_sold / item.total_bought : 0
                  const allocatedCost = item.buy_cost * ratio
                  const realized = item.sell_proceeds - allocatedCost + item.dividends
                  return (
                    <tr key={item.stock_id} className="border-b border-slate-50">
                      <td className="px-3 py-2.5">
                        <span className="font-medium text-slate-900">{item.name}</span>
                        <span className="text-xs text-slate-400 ml-1.5">{displaySymbol(item.symbol, item.market)}</span>
                      </td>
                      <td className="px-3 py-2.5 text-right tabular-nums">{item.total_sold}</td>
                      <td className="px-3 py-2.5 text-right tabular-nums">{formatCurrency(item.sell_proceeds)}</td>
                      <td className={`px-3 py-2.5 text-right font-medium tabular-nums ${realized >= 0 ? positiveClass : negativeClass}`}>
                        {realized >= 0 ? '+' : ''}{formatCurrency(Math.abs(realized))}
                      </td>
                      <td className="px-3 py-2.5 text-right tabular-nums text-sky-600">{item.dividends > 0 ? formatCurrency(item.dividends) : '—'}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </div>
  )
}
