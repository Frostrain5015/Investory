import { createContext, useContext } from 'react'

export interface AuthState {
  userId: number | null
  username: string | null
  portfolioId: number | null
  portfolioName: string
  isAdmin: boolean
  setPortfolioName: (name: string) => void
  authenticated: boolean
  loading: boolean
  login: (username: string, password: string) => Promise<{ success: boolean; error?: string }>
  register: (
    username: string,
    password: string,
    email?: string,
  ) => Promise<{ success: boolean; error?: string }>
  logout: () => void
  setPortfolioId: (id: number) => void
}

export const AuthContext = createContext<AuthState | null>(null)

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be inside AuthProvider')
  return ctx
}
