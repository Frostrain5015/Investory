import { useState } from 'react'
import { Search, BarChart2, FlaskConical } from 'lucide-react'
import Screener from './Screener'
import { RiskSection, BacktestSection } from './Quant'
import MarketRegimeBadge from '@/components/MarketRegimeBadge'
import { getRegimeStatus } from '@/services/api'
import type { RegimeStatus } from '@/types'
import { useEffect } from 'react'

type Tab = 'screener' | 'risk' | 'backtest'

const TABS: { key: Tab; icon: typeof Search; label: string }[] = [
  { key: 'screener', icon: Search,     label: '选股' },
  { key: 'risk',     icon: BarChart2,  label: '风控' },
  { key: 'backtest', icon: FlaskConical, label: '回测' },
]

export default function Research() {
  const [tab, setTab] = useState<Tab>('screener')
  const [regime, setRegime] = useState<RegimeStatus | null>(null)

  useEffect(() => {
    getRegimeStatus().then(r => {
      if (r?.regime) setRegime(r.regime)
    }).catch(() => {})
  }, [])

  return (
    <div className="p-6 space-y-6 max-w-6xl mx-auto">
      <div className="flex items-center justify-between">
        <h2 className="text-xl font-bold text-slate-900 tracking-tight">投研</h2>
        <div className="flex items-center gap-3">
          {regime && <MarketRegimeBadge regime={regime.signal} />}
          <div className="flex bg-slate-100 rounded-lg p-0.5">
            {TABS.map(({ key, icon: Icon, label }) => (
              <button key={key} onClick={() => setTab(key)}
                className={`px-3 py-1.5 rounded-md text-xs font-medium transition-colors ${tab === key ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'}`}>
                <Icon className="w-3.5 h-3.5 inline mr-1" />{label}
              </button>
            ))}
          </div>
        </div>
      </div>

      {tab === 'screener' && <Screener embedded />}
      {tab === 'risk' && <RiskSection />}
      {tab === 'backtest' && <BacktestSection />}
    </div>
  )
}
