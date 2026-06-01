import { Routes, Route, Navigate } from 'react-router-dom'
import { useEffect } from 'react'
import { AuthProvider, useAuth } from '@/hooks/use-auth'
import { SettingsProvider } from '@/hooks/use-settings'
import { ThemeProvider } from '@/hooks/use-theme'
import { ToastProvider } from '@/components/Toast'
import { NotificationBubbleProvider } from '@/components/NotificationBubble'
import { preloadSuggestions } from '@/services/aiPreload'
import { ConfirmProvider } from '@/hooks/use-confirm'
import { I18nProvider, useT } from '@/i18n/I18nContext'
import Layout from '@/components/Layout'
import TitleBar from '@/components/TitleBar'
import Hero from '@/pages/Hero'
import Login from '@/pages/Login'
import Register from '@/pages/Register'
import Admin from '@/pages/Admin'
import Dashboard from '@/pages/Dashboard'
import Market from '@/pages/Market'
import Holdings from '@/pages/Holdings'
import Transactions from '@/pages/Transactions'
import AddTransaction from '@/pages/AddTransaction'
import Dividends from '@/pages/Dividends'
import StockDetail from '@/pages/StockDetail'
import PnlCalendar from '@/pages/PnlCalendar'
import Portfolio from '@/pages/Portfolio'
import Settings from '@/pages/Settings'
import Quant from '@/pages/Quant'
import Screener from '@/pages/Screener'
import Research from '@/pages/Research'

const isElectron = !!(window as any).electronAPI?.isDesktop

function LoadingScreen() {
  const { t } = useT()
  return (
    <div className="flex flex-col items-center justify-center gap-3 h-full bg-slate-50">
      <div className="w-8 h-8 border-2 border-slate-300 border-t-slate-900 rounded-full animate-spin" />
      <span className="text-sm text-slate-400">{t.common.loading}</span>
    </div>
  )
}

function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const { authenticated, loading } = useAuth()
  useEffect(() => { if (authenticated) preloadSuggestions() }, [authenticated])
  if (loading) return <LoadingScreen />
  return authenticated ? children : <Navigate to="/" replace />
}

function PublicRoute({ children }: { children: React.ReactNode }) {
  const { authenticated, loading, isAdmin } = useAuth()
  if (loading) return <LoadingScreen />
  return !authenticated ? children : <Navigate to={isAdmin ? '/admin' : '/dashboard'} replace />
}

export default function App() {
  return (
    <div className="h-screen flex flex-col" style={isElectron ? { borderRadius: '10px', overflow: 'hidden', border: '1px solid rgba(148,163,184,0.08)' } : undefined}>
      {isElectron && <TitleBar />}
      <div className="flex-1 min-h-0">
        <AuthProvider>
          <SettingsProvider>
          <ThemeProvider>
          <I18nProvider>
          <ToastProvider>
          <NotificationBubbleProvider>
          <ConfirmProvider>
          <Routes>
            <Route path="/" element={<PublicRoute><Hero /></PublicRoute>} />
            <Route path="/login" element={<PublicRoute><Login /></PublicRoute>} />
            <Route path="/register" element={<PublicRoute><Register /></PublicRoute>} />
            <Route element={<ProtectedRoute><Layout /></ProtectedRoute>}>
              <Route path="/market" element={<Market />} />
              <Route path="/watchlist" element={<Navigate to="/holdings" replace />} />
              <Route path="/dashboard" element={<Dashboard />} />
              <Route path="/holdings" element={<Holdings />} />
              <Route path="/transactions" element={<Transactions />} />
              <Route path="/transactions/add" element={<AddTransaction />} />
              <Route path="/dividends" element={<Dividends />} />
              <Route path="/stock" element={<StockDetail />} />
              <Route path="/pnl-calendar" element={<PnlCalendar />} />
              <Route path="/portfolio" element={<Portfolio />} />
              <Route path="/settings" element={<Settings />} />
              <Route path="/quant" element={<Navigate to="/research" replace />} />
              <Route path="/screener" element={<Navigate to="/research" replace />} />
              <Route path="/research" element={<Research />} />
              <Route path="/admin" element={<Admin />} />
            </Route>
            <Route path="*" element={<Navigate to="/dashboard" replace />} />
          </Routes>
          </ConfirmProvider>
          </NotificationBubbleProvider>
          </ToastProvider>
          </I18nProvider>
          </ThemeProvider>
          </SettingsProvider>
        </AuthProvider>
      </div>
    </div>
  )
}
