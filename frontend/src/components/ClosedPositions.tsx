import { useEffect, useState } from 'react'
import { useSettings } from '@/hooks/use-settings'
import { displaySymbol } from '@/lib/format'
import { X } from 'lucide-react'

interface ClosedItem {
  stock_id: number; symbol: string; name: string; market: string
  total_bought: number; total_sold: number; buy_cost: number; sell_proceeds: number; dividends: number
}

export default function ClosedPositions({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { positiveClass, negativeClass, formatCurrency } = useSettings()
  const [items, setItems] = useState<ClosedItem[]>([])
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (!open) return
    setLoading(true)
    fetch('/investory/api/closed-positions', { credentials: 'include' })
      .then(r => r.json()).then(setItems)
      .finally(() => setLoading(false))
  }, [open])

  if (!open) return null

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      <div className="absolute inset-0 bg-black/40" onClick={onClose} />
      <div className="relative bg-white rounded-2xl shadow-2xl max-w-2xl w-full mx-4 max-h-[80vh] flex flex-col">
        <div className="flex items-center justify-between px-6 py-4 border-b border-slate-100">
          <h3 className="text-base font-bold text-slate-900">已清仓标的</h3>
          <button onClick={onClose} className="p-1 rounded-lg hover:bg-slate-100"><X className="w-4 h-4 text-slate-400" /></button>
        </div>
        <div className="overflow-auto flex-1 p-6">
          {loading ? (
            <div className="flex justify-center py-8"><div className="w-6 h-6 border-2 border-slate-300 border-t-slate-600 rounded-full animate-spin" /></div>
          ) : items.length === 0 ? (
            <p className="text-sm text-slate-400 text-center py-8">暂无已清仓标的</p>
          ) : (
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-slate-100">
                  <th className="text-left text-xs font-medium text-slate-500 px-3 py-2">股票</th>
                  <th className="text-right text-xs font-medium text-slate-500 px-3 py-2">清仓数量</th>
                  <th className="text-right text-xs font-medium text-slate-500 px-3 py-2">卖出回款</th>
                  <th className="text-right text-xs font-medium text-slate-500 px-3 py-2">已实现盈亏</th>
                  <th className="text-right text-xs font-medium text-slate-500 px-3 py-2">分红</th>
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
