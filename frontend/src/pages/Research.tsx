import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { BarChart2, FlaskConical } from 'lucide-react'
import { RiskSection } from '@/features/research/RiskSection'
import { BacktestSection } from '@/features/research/BacktestSection'

type Tab = 'risk' | 'backtest'
const TABS = [
  { key: 'risk' as const, icon: BarChart2, label: '风控' },
  { key: 'backtest' as const, icon: FlaskConical, label: '回测' },
]
export default function Research() {
  const [searchParams] = useSearchParams()
  const [tab, setTab] = useState<Tab>(searchParams.get('backtest') ? 'backtest' : 'risk')
  return <div className="p-6 space-y-6 max-w-6xl mx-auto">
    <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
      <h2 className="text-xl font-bold text-slate-900 tracking-tight">投研
        <span className="ml-2 px-1.5 py-0.5 text-[10px] font-medium bg-amber-100 text-amber-700 rounded align-middle">Beta</span>
      </h2>
      <div className="flex bg-slate-100 rounded-lg p-0.5 self-start" role="tablist" aria-label="投研功能">
        {TABS.map(({ key, icon: Icon, label }) => <button key={key} role="tab" aria-selected={tab === key}
          onClick={() => setTab(key)} className={`px-3 py-1.5 rounded-md text-xs font-medium transition-colors ${tab === key ? 'bg-white text-slate-900 shadow-sm' : 'text-slate-500'}`}>
          <Icon aria-hidden="true" className="w-3.5 h-3.5 inline mr-1" />{label}
        </button>)}
      </div>
    </div>
    {tab === 'risk' ? <RiskSection /> : <BacktestSection />}
  </div>
}
