import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import { BASE, checkSession, exchangeFrostIdToken, login as apiLogin, register as apiRegister } from '@/services/api'
import { preloadPostLoginPages } from '@/services/pagePreload'
import type { SessionResponse } from '@/types'

interface AuthState {
  userId: number | null
  username: string | null
  portfolioId: number | null
  portfolioName: string
  isAdmin: boolean
  setPortfolioName: (name: string) => void
  authenticated: boolean
  loading: boolean
  login: (username: string, password: string) => Promise<{ success: boolean; error?: string }>
  register: (username: string, password: string, email?: string) => Promise<{ success: boolean; error?: string }>
  logout: () => void
  setPortfolioId: (id: number) => void
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [userId, setUserId] = useState<number | null>(null)
  const [username, setUsername] = useState<string | null>(null)
  const [portfolioId, setPortfolioId] = useState<number | null>(null)
  const [portfolioName, setPortfolioName] = useState('')
  const [isAdmin, setIsAdmin] = useState(false)
  const [authenticated, setAuthenticated] = useState(false)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    checkSession()
      .then((data: SessionResponse) => {
        if (data.authenticated && data.userId) {
          setUserId(data.userId)
          setUsername(data.username ?? null)
          setPortfolioId(data.portfolioId ?? null)
          setIsAdmin(data.isAdmin ?? false)
          setAuthenticated(true)
        }
      })
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    const onExpired = () => { setUserId(null); setAuthenticated(false); window.location.href = import.meta.env.BASE_URL }
    window.addEventListener('investory:auth-expired', onExpired)
    return () => window.removeEventListener('investory:auth-expired', onExpired)
  }, [])

  // Desktop: Frost ID login completes in the system browser, which deep-links back
  // with a one-time token. Exchange it for a session here, then refresh auth state.
  useEffect(() => {
    if (!window.electronAPI?.onFrostIdCallback) return
    return window.electronAPI.onFrostIdCallback(async (token) => {
      const ok = await exchangeFrostIdToken(token)
      if (!ok) return
      const data: SessionResponse = await checkSession()
      if (data.authenticated && data.userId) {
        setUserId(data.userId)
        setUsername(data.username ?? null)
        setPortfolioId(data.portfolioId ?? null)
        setIsAdmin(data.isAdmin ?? false)
        setAuthenticated(true)
      }
    })
  }, [])

  useEffect(() => {
    if (!authenticated) return
    preloadPostLoginPages(isAdmin)
  }, [authenticated, isAdmin])

  async function login(username: string, password: string) {
    try {
      const text = await apiLogin(username, password)
      if (text === 'ok') {
        const data: SessionResponse = await checkSession()
        if (data.userId) {
          setUserId(data.userId)
          setUsername(data.username ?? null)
          setPortfolioId(data.portfolioId ?? null)
          setIsAdmin(data.isAdmin ?? false)
          setAuthenticated(true)
        }
        return { success: true }
      }
      // Return server-provided error text, or empty string to let the caller use its own fallback translation
      return { success: false, error: text !== 'error' ? text : '' }
    } catch {
      return { success: false, error: '' }
    }
  }

  async function register(username: string, password: string, email?: string) {
    try {
      const text = await apiRegister(username, password, email)
      if (text === 'ok') return { success: true }
      // Return server-provided error text, or empty string to let the caller use its own fallback translation
      return { success: false, error: text || '' }
    } catch {
      return { success: false, error: '' }
    }
  }

  function logout() {
    fetch(BASE + '/logout', { credentials: 'include' })
      .finally(() => { window.location.href = import.meta.env.BASE_URL })
  }

  return (
    <AuthContext.Provider value={{ userId, username, portfolioId, portfolioName, isAdmin, setPortfolioName, authenticated, loading, login, register, logout, setPortfolioId }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be inside AuthProvider')
  return ctx
}
