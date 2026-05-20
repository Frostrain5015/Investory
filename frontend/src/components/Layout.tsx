import { NavLink, Outlet } from 'react-router-dom'
import { useAuth } from '@/hooks/use-auth'
import { useSettings } from '@/hooks/use-settings'
import {
  LayoutDashboard, Wallet, ArrowRightLeft, CalendarDays,
  Briefcase, LogOut, TrendingUp, User, Search, Settings
} from 'lucide-react'
import { useState } from 'react'
import { searchStocks } from '@/services/api'
import type { StockSearchItem } from '@/types'
import { displaySymbol } from '@/lib/format'

const navItems = [
  { to: '/dashboard', icon: LayoutDashboard, label: '总览' },
  { to: '/market',    icon: TrendingUp,       label: '市场' },
  { to: '/holdings', icon: Wallet, label: '自选' },
  { to: '/transactions', icon: ArrowRightLeft, label: '交易' },
  { to: '/pnl-calendar', icon: CalendarDays, label: '盈亏日历' },
]

export default function Layout() {
  const { username, logout } = useAuth()
  const { positiveHex } = useSettings()
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<StockSearchItem[]>([])
  const [showDropdown, setShowDropdown] = useState(false)

  async function handleSearch(q: string) {
    setQuery(q)
    if (q.length < 1) { setResults([]); return }
    const data = await searchStocks(q)
    setResults(data || [])
    setShowDropdown(true)
  }

  return (
    <div className="flex h-screen bg-slate-50">
      {/* Sidebar */}
      <aside className="w-60 flex flex-col bg-slate-900 text-slate-300 shrink-0">
        <a href="/investory/dashboard" className="flex items-center gap-3 px-5 h-16 border-b border-slate-800">
          <div className="w-8 h-8 rounded-lg flex items-center justify-center" style={{ backgroundColor: positiveHex }}>
            <TrendingUp className="w-5 h-5 text-white" />
          </div>
          <span className="text-lg font-bold text-white tracking-tight">Investory</span>
        </a>

        <nav className="flex-1 px-3 py-4 space-y-0.5">
          {navItems.map(({ to, icon: Icon, label }) => (
            <NavLink
              key={to}
              to={to}
              className={({ isActive }) =>
                `flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-medium transition-colors ${
                  isActive
                    ? 'bg-slate-800 text-white'
                    : 'text-slate-400 hover:text-white hover:bg-slate-800/50'
                }`
              }
            >
              <Icon className="w-4 h-4" />
              {label}
            </NavLink>
          ))}
        </nav>

        <div className="p-3 border-t border-slate-800">
          <NavLink
            to="/portfolio"
            className={({ isActive }) =>
              `flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-medium transition-colors ${
                isActive ? 'bg-slate-800 text-white' : 'text-slate-400 hover:text-white hover:bg-slate-800/50'
              }`
            }
          >
            <Briefcase className="w-4 h-4" />
            投资组合
          </NavLink>
          <NavLink
            to="/settings"
            className={({ isActive }) =>
              `flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-medium transition-colors ${
                isActive ? 'bg-slate-800 text-white' : 'text-slate-400 hover:text-white hover:bg-slate-800/50'
              }`
            }
          >
            <Settings className="w-4 h-4" />
            设置
          </NavLink>
          <NavLink
            to="/login"
            onClick={(e) => { e.preventDefault(); logout() }}
            className="flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-medium text-slate-500 hover:text-red-400 hover:bg-slate-800/50 transition-colors mt-0.5"
          >
            <LogOut className="w-4 h-4" />
            退出
          </NavLink>
        </div>
      </aside>

      {/* Main */}
      <div className="flex-1 flex flex-col min-w-0">
        {/* Top bar */}
        <header className="h-16 border-b border-slate-200 bg-white flex items-center justify-between px-6 shrink-0">
          <div className="relative flex-1 max-w-md">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
            <input
              type="text"
              placeholder="搜索股票..."
              value={query}
              onChange={(e) => handleSearch(e.target.value)}
              onBlur={() => setTimeout(() => setShowDropdown(false), 200)}
              onFocus={() => results.length > 0 && setShowDropdown(true)}
              className="w-full h-10 pl-10 pr-4 rounded-xl border border-slate-200 bg-slate-50 text-sm placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-slate-900/5 focus:border-slate-300 transition-colors"
            />
            {showDropdown && results.length > 0 && (
              <div className="absolute top-full mt-1 w-full bg-white rounded-xl border border-slate-200 shadow-lg overflow-hidden z-50">
                {results.map((s) => (
                  <a
                    key={s.id}
                    href={`/investory/stock?symbol=${encodeURIComponent(s.symbol)}`}
                    className="flex items-center justify-between px-4 py-2.5 hover:bg-slate-50 transition-colors"
                  >
                    <span className="text-sm font-medium text-slate-900">{s.name}</span>
                    <span className="text-xs text-slate-400">{displaySymbol(s.symbol, s.market)}</span>
                  </a>
                ))}
              </div>
            )}
          </div>
          <div className="flex items-center gap-3">
            <div className="flex items-center gap-2">
              <div className="w-8 h-8 rounded-full bg-slate-100 flex items-center justify-center">
                <User className="w-4 h-4 text-slate-500" />
              </div>
              <span className="text-sm font-medium text-slate-700">{username}</span>
            </div>
          </div>
        </header>

        {/* Content */}
        <main className="flex-1 overflow-auto">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
