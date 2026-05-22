import { NavLink, Outlet } from 'react-router-dom'
import { useAuth } from '@/hooks/use-auth'
import { useSettings } from '@/hooks/use-settings'
import {
  LayoutDashboard, Wallet, ArrowRightLeft, CalendarDays,
  LogOut, TrendingUp, User, Search, Menu, Shield, BarChart2
} from 'lucide-react'
import { useEffect, useState } from 'react'
import { searchStocks, getPortfolios } from '@/services/api'
import type { StockSearchItem } from '@/types'
import { displaySymbol } from '@/lib/format'

const navItems = [
  { to: '/dashboard', icon: LayoutDashboard, label: '总览' },
  { to: '/market',    icon: TrendingUp,       label: '市场' },
  { to: '/holdings', icon: Wallet, label: '自选' },
  { to: '/transactions', icon: ArrowRightLeft, label: '交易' },
  { to: '/pnl-calendar', icon: CalendarDays, label: '日历' },
  { to: '/quant', icon: BarChart2, label: '量化' },
]

export default function Layout() {
  const { username, portfolioId, isAdmin, setPortfolioName, logout } = useAuth()
  const { positiveHex } = useSettings()
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<StockSearchItem[]>([])
  const [showDropdown, setShowDropdown] = useState(false)
  const [sidebarOpen, setSidebarOpen] = useState(false)

  useEffect(() => {
    if (!portfolioId) return
    getPortfolios().then(list => {
      const p = list.find(p => p.id === portfolioId)
      if (p) setPortfolioName(p.name)
    }).catch(() => {})
  }, [portfolioId])

  async function handleSearch(q: string) {
    setQuery(q)
    if (q.length < 1) { setResults([]); return }
    const data = await searchStocks(q)
    setResults(data || [])
    setShowDropdown(true)
  }

  const sidebar = (
    <aside className="w-60 flex flex-col bg-slate-900 text-slate-300 shrink-0 h-full">
      <a href="/investory/dashboard" onClick={() => setSidebarOpen(false)} className="flex items-center gap-3 px-5 h-16 border-b border-slate-800">
        <div className="w-8 h-8 rounded-lg flex items-center justify-center" style={{ backgroundColor: positiveHex }}>
          <TrendingUp className="w-5 h-5 text-white" />
        </div>
        <span className="text-lg font-bold text-white tracking-tight">Investory</span>
      </a>
      <nav className="flex-1 px-3 py-4 space-y-0.5 overflow-auto">
        {navItems.map(({ to, icon: Icon, label }) => (
          <NavLink key={to} to={to} onClick={() => setSidebarOpen(false)}
            className={({ isActive }) =>
              `flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-medium transition-colors ${isActive ? 'bg-slate-800 text-white' : 'text-slate-400 hover:text-white hover:bg-slate-800/50'}`} >
            <Icon className="w-4 h-4" />{label}
          </NavLink>
        ))}
        {isAdmin && (
          <NavLink to="/admin" onClick={() => setSidebarOpen(false)}
            className={({ isActive }) =>
              `flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-medium transition-colors ${isActive ? 'bg-amber-800 text-amber-200' : 'text-amber-500 hover:text-amber-300 hover:bg-slate-800/50'}`} >
            <Shield className="w-4 h-4" />管理后台
          </NavLink>
        )}
      </nav>
      <div className="p-3 border-t border-slate-800">
        <NavLink to="/settings" onClick={() => setSidebarOpen(false)}
          className="flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-medium text-slate-400 hover:text-white hover:bg-slate-800/50 transition-colors">
          <div className="w-7 h-7 rounded-full bg-slate-700 flex items-center justify-center shrink-0">
            <User className="w-3.5 h-3.5 text-slate-300" />
          </div>
          <span className="text-sm text-slate-300 truncate">{username}</span>
        </NavLink>
        <NavLink to="/login" onClick={(e) => { e.preventDefault(); logout() }}
          className="flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-medium text-slate-500 hover:text-red-400 hover:bg-slate-800/50 transition-colors mt-0.5" >
          <LogOut className="w-4 h-4" />退出
        </NavLink>
      </div>
    </aside>
  )

  return (
    <div className="flex h-screen bg-slate-50">
      {/* Desktop sidebar */}
      <div className="hidden lg:flex">{sidebar}</div>

      {/* Mobile overlay */}
      {sidebarOpen && (
        <div className="fixed inset-0 z-50 lg:hidden">
          <div className="absolute inset-0 bg-black/40" onClick={() => setSidebarOpen(false)} />
          <div className="relative z-10 h-full">{sidebar}</div>
        </div>
      )}

      {/* Main */}
      <div className="flex-1 flex flex-col min-w-0">
        <header className="h-14 lg:h-16 border-b border-slate-200 bg-white flex items-center gap-3 px-4 lg:px-6 shrink-0">
          <button onClick={() => setSidebarOpen(true)} className="lg:hidden p-1.5 -ml-1 rounded-lg hover:bg-slate-100">
            <Menu className="w-5 h-5 text-slate-600" />
          </button>
          <div className="relative flex-1 max-w-md">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
            <input type="text" placeholder="搜索股票..." value={query}
              onChange={(e) => handleSearch(e.target.value)}
              onBlur={() => setTimeout(() => setShowDropdown(false), 200)}
              onFocus={() => results.length > 0 && setShowDropdown(true)}
              className="w-full h-9 lg:h-10 pl-9 lg:pl-10 pr-3 rounded-xl border border-slate-200 bg-slate-50 text-sm placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-slate-900/5 focus:border-slate-300 transition-colors" />
            {showDropdown && results.length > 0 && (
              <div className="absolute top-full mt-1 w-full bg-white rounded-xl border border-slate-200 shadow-lg overflow-hidden z-50">
                {results.map((s) => (
                  <a key={s.id} href={`/investory/stock?symbol=${encodeURIComponent(s.symbol)}`}
                    className="flex items-center justify-between px-4 py-2.5 hover:bg-slate-50 transition-colors">
                    <span className="text-sm font-medium text-slate-900 truncate">{s.name}</span>
                    <span className="text-xs text-slate-400 shrink-0 ml-2">{displaySymbol(s.symbol, s.market)}</span>
                  </a>
                ))}
              </div>
            )}
          </div>
        </header>
        <main className="flex-1 overflow-auto">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
