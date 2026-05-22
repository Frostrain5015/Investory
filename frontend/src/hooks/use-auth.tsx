import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import { checkSession, login as apiLogin, register as apiRegister } from '@/services/api'
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
      return { success: false, error: text !== 'error' ? text : '用户名或密码错误' }
    } catch {
      return { success: false, error: '系统错误，请重试' }
    }
  }

  async function register(username: string, password: string, email?: string) {
    try {
      const text = await apiRegister(username, password, email)
      if (text === 'ok') return { success: true }
      return { success: false, error: text || '注册失败' }
    } catch {
      return { success: false, error: '系统错误，请重试' }
    }
  }

  function logout() {
    localStorage.removeItem('investory_creds')
    window.location.href = '/investory/logout'
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
