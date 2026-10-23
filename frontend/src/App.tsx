import { Routes, Route, Navigate } from 'react-router-dom'
import { useEffect, lazy } from 'react'
import { AuthProvider } from '@/providers/AuthProvider'
import { useAuth } from '@/hooks/use-auth'
import { SettingsProvider } from '@/providers/SettingsProvider'
import { ModelsProvider } from '@/providers/ModelsProvider'
import { ThemeProvider } from '@/providers/ThemeProvider'
import { ToastProvider } from '@/components/Toast'
import { NotificationBubbleProvider } from '@/components/NotificationBubble'
import { preloadSuggestions } from '@/services/aiPreload'
import { ConfirmProvider } from '@/providers/ConfirmProvider'
import { PromptProvider } from '@/providers/PromptProvider'
import { I18nProvider } from '@/providers/I18nProvider'
import PageLoading from '@/components/PageLoading'
import PageTransition from '@/components/PageTransition'
import Layout from '@/components/Layout'
import TitleBar from '@/components/TitleBar'
import ErrorBoundary from '@/components/ErrorBoundary'

// Light pages: eager-loaded (critical path)
import Hero from '@/pages/Hero'
import Login from '@/pages/Login'
import Register from '@/pages/Register'
import Portfolio from '@/pages/Portfolio'
import Settings from '@/pages/Settings'
import PnlCalendar from '@/pages/PnlCalendar'

// Heavy pages: lazy-loaded (code-split per route)
const Admin = lazy(() => import('@/pages/Admin'))
const Dashboard = lazy(() => import('@/pages/Dashboard'))
const Market = lazy(() => import('@/pages/Market'))
const Holdings = lazy(() => import('@/pages/Holdings'))
const Transactions = lazy(() => import('@/pages/Transactions'))
const AddTransaction = lazy(() => import('@/pages/AddTransaction'))
const Dividends = lazy(() => import('@/pages/Dividends'))
const StockDetail = lazy(() => import('@/pages/StockDetail'))
const Research = lazy(() => import('@/pages/Research'))

const isElectron = !!window.electronAPI?.isDesktop

function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const { authenticated, loading } = useAuth()
  useEffect(() => { if (authenticated) preloadSuggestions() }, [authenticated])
  if (loading) return <PageLoading />
  return authenticated ? children : <Navigate to="/" replace />
}

function PublicRoute({ children }: { children: React.ReactNode }) {
  const { authenticated, loading, isAdmin } = useAuth()
  if (loading) return <PageLoading />
  return !authenticated ? children : <Navigate to={isAdmin ? '/admin' : '/dashboard'} replace />
}

export default function App() {
  return (
    <div className="h-screen flex flex-col" style={isElectron ? { borderRadius: '10px', overflow: 'hidden', border: '1px solid rgba(148,163,184,0.08)' } : undefined}>
      {isElectron && <TitleBar />}
      <div className="flex-1 min-h-0">
        <AuthProvider>
          <ModelsProvider>
          <SettingsProvider>
          <ThemeProvider>
          <I18nProvider>
          <ToastProvider>
          <NotificationBubbleProvider>
          <ConfirmProvider>
          <PromptProvider>
          <ErrorBoundary>
          <Routes>
            <Route element={<PublicRoute><PageTransition /></PublicRoute>}>
              <Route path="/" element={<Hero />} />
              <Route path="/login" element={<Login />} />
              <Route path="/register" element={<Register />} />
            </Route>
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
          </ErrorBoundary>
          </PromptProvider>
          </ConfirmProvider>
          </NotificationBubbleProvider>
          </ToastProvider>
          </I18nProvider>
          </ThemeProvider>
          </SettingsProvider>
          </ModelsProvider>
        </AuthProvider>
      </div>
    </div>
  )
}
