import { NavLink, Outlet } from 'react-router-dom'
import { useAuth } from '@/hooks/use-auth'
import { useSettings } from '@/hooks/use-settings'
import { usePortfolioPreload } from '@/hooks/use-portfolio-preload'
import { useT } from '@/i18n/I18nContext'
import LangSwitcher from '@/components/LangSwitcher'
import {
  LayoutDashboard, Wallet, ArrowRightLeft, CalendarDays,
  LogOut, TrendingUp, User, Search, Menu, Shield, FlaskConical, Sparkles
} from 'lucide-react'
import { createContext, useContext, useEffect, useState } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import { searchStocks, getPortfolios } from '@/services/api'
import ChatPanel from '@/pages/ChatPanel'
import UpdateBanner from '@/components/UpdateBanner'
import type { StockSearchItem } from '@/types'
import { displaySymbol } from '@/lib/format'

interface ChatContextType { openChatWith: (message: string) => void }
export const ChatContext = createContext<ChatContextType>({ openChatWith: () => {} })
export function useChatContext() { return useContext(ChatContext) }

export default function Layout() {
  const { t, lang } = useT()
  const { username, portfolioId, isAdmin, setPortfolioName, logout } = useAuth()
  usePortfolioPreload()  // triggers background analysis on login
  const { positiveHex } = useSettings()
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<StockSearchItem[]>([])
  const [showDropdown, setShowDropdown] = useState(false)
  const [sidebarOpen, setSidebarOpen] = useState(false)
  const [chatOpen, setChatOpen] = useState(false)
  const [chatInitialMessage, setChatInitialMessage] = useState('')

  function openChatWith(message: string) {
    setChatInitialMessage(message)
    setChatOpen(true)
  }

  const navItems = [
    { to: '/dashboard', icon: LayoutDashboard, label: t.nav.dashboard },
    { to: '/market',    icon: TrendingUp,       label: t.nav.market },
    { to: '/holdings', icon: Wallet, label: t.nav.holdings },
    { to: '/transactions', icon: ArrowRightLeft, label: t.nav.transactions },
    { to: '/pnl-calendar', icon: CalendarDays, label: t.nav.pnl },
    { to: '/research', icon: FlaskConical, label: <>{t.nav.research}<span className="ml-1.5 px-1 py-0.5 text-[9px] font-medium bg-amber-100/15 text-amber-400 rounded">Beta</span></> },
  ]

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
    try {
      const data = await searchStocks(q)
      setResults(data || [])
      setShowDropdown(true)
    } catch { /* search API failed, silently ignore */ }
  }

  const sidebar = (
    <aside className="w-60 flex flex-col bg-slate-900 text-slate-300 shrink-0 h-full">
      <a href={`${import.meta.env.BASE_URL}dashboard`} onClick={() => setSidebarOpen(false)} className="flex items-center gap-3 px-5 h-16 border-b border-slate-800">
        <div className="w-8 h-8 rounded-lg flex items-center justify-center" style={{ background: 'linear-gradient(135deg, #863bff, #47bfff)' }}>
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
            <Shield className="w-4 h-4" />{t.admin.title}
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
          <LogOut className="w-4 h-4" />{lang === 'zh' ? '退出' : 'Logout'}
        </NavLink>
      </div>
    </aside>
  )

  return (
    <ChatContext.Provider value={{ openChatWith }}>
    <div className="flex h-full bg-slate-50">
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
            <input type="text" value={query}
              onChange={(e) => handleSearch(e.target.value)}
              onBlur={() => setTimeout(() => setShowDropdown(false), 200)}
              onFocus={() => results.length > 0 && setShowDropdown(true)}
              placeholder={lang === 'zh' ? '搜索股票...' : 'Search stocks...'}
              className="w-full h-9 lg:h-10 pl-9 lg:pl-10 pr-3 rounded-xl border border-slate-200 bg-slate-50 text-sm placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-slate-900/5 focus:border-slate-300 transition-colors" />
            {showDropdown && results.length > 0 && (
              <div className="absolute top-full mt-1 w-full bg-white rounded-xl border border-slate-200 shadow-lg overflow-hidden z-50">
                {results.map((s) => (
                  <a key={s.id} href={`${import.meta.env.BASE_URL}stock?symbol=${encodeURIComponent(s.symbol)}`}
                    className="flex items-center justify-between px-4 py-2.5 hover:bg-slate-50 transition-colors">
                    <span className="text-sm font-medium text-slate-900 truncate">{s.name}</span>
                    <span className="text-xs text-slate-400 shrink-0 ml-2">{displaySymbol(s.symbol, s.market)}</span>
                  </a>
                ))}
              </div>
            )}
          </div>
          <LangSwitcher />
        </header>
        <UpdateBanner />
        <main className="flex-1 overflow-auto">
          <Outlet />
        </main>
      </div>

      {/* Floating AI Button */}
      <AnimatePresence>
        {!chatOpen && (
          <motion.div initial={{ scale: 0 }} animate={{ scale: 1 }} exit={{ scale: 0 }}
            className="fixed right-6 z-40" style={{ bottom: `calc(1.5rem + env(safe-area-inset-bottom, 0px))` }}>
            <button onClick={() => setChatOpen(true)}
              className="w-11 h-11 rounded-full text-white shadow-lg shadow-purple-500/25 hover:shadow-purple-500/40 hover:scale-105 transition-all flex items-center justify-center"
              style={{ background: 'linear-gradient(135deg, #863bff, #47bfff)' }}>
              <Sparkles className="w-5 h-5" />
            </button>
          </motion.div>
        )}
      </AnimatePresence>

      {/* Chat Panel */}
      <AnimatePresence>
        {chatOpen && <ChatPanel onClose={() => { setChatOpen(false); setChatInitialMessage('') }} initialMessage={chatInitialMessage} />}
      </AnimatePresence>
    </div>
    </ChatContext.Provider>
  )
}
