import type {
  SessionResponse, DashboardResponse, HoldingsResponse,
  TransactionsResponse, DividendsResponse, StockDetailResponse,
  AllocationItem, PnlCalendarItem, CumulativeReturnItem,
  StockSearchItem, PriceData, Portfolio,
} from '@/types'

const BASE = '/investory'

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const res = await fetch(BASE + url, { credentials: 'include', ...options })
  if (res.status === 401) {
    window.location.href = BASE + '/login'
    throw new Error('Unauthorized')
  }
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  const text = await res.text()
  if (!text) return {} as T
  return JSON.parse(text) as T
}

// ── Auth ───────────────────────────────────────────────────────────────

async function authPost(path: string, data: Record<string, string>): Promise<string> {
  const form = new URLSearchParams(data)
  const res = await fetch(BASE + path, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: form.toString(),
  })
  return res.text()
}

export function login(username: string, password: string) {
  return authPost('/login', { username, password })
}

export function register(username: string, password: string, email?: string) {
  return authPost('/register', { username, password, ...(email ? { email } : {}) })
}

export function checkSession(): Promise<SessionResponse> {
  return request<SessionResponse>('/api/session')
}

// ── Dashboard ──────────────────────────────────────────────────────────

export function getDashboard(): Promise<DashboardResponse> {
  return request<DashboardResponse>('/api/dashboard')
}

// ── Holdings ───────────────────────────────────────────────────────────

export function getHoldings(): Promise<HoldingsResponse> {
  return request<HoldingsResponse>('/api/holdings')
}

// ── Transactions ───────────────────────────────────────────────────────

export function getTransactions(): Promise<TransactionsResponse> {
  return request<TransactionsResponse>('/api/transactions')
}

export function createTransaction(data: Record<string, string>): Promise<Response> {
  return fetch(BASE + '/api/transactions', {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(data).toString(),
  })
}

export function deleteTransaction(id: number): Promise<Response> {
  return fetch(BASE + `/api/transactions/${id}`, {
    method: 'DELETE',
    credentials: 'include',
  })
}

// ── Dividends ──────────────────────────────────────────────────────────

export function getDividends(): Promise<DividendsResponse> {
  return request<DividendsResponse>('/api/dividends')
}

export function createDividend(data: Record<string, string>): Promise<Response> {
  return fetch(BASE + '/api/dividends', {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(data).toString(),
  })
}

export function deleteDividend(id: number): Promise<Response> {
  return fetch(BASE + `/api/dividends/${id}`, {
    method: 'DELETE',
    credentials: 'include',
  })
}

// ── Stock detail ───────────────────────────────────────────────────────

export function getStockDetail(symbol: string): Promise<StockDetailResponse> {
  return request<StockDetailResponse>(`/api/stocks/${encodeURIComponent(symbol)}`)
}

// ── Portfolios ─────────────────────────────────────────────────────────

export function getPortfolios(): Promise<Portfolio[]> {
  return request<Portfolio[]>('/api/portfolios')
}

export function createPortfolio(name: string): Promise<Portfolio> {
  const form = new URLSearchParams({ name })
  return request<Portfolio>('/api/portfolios', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: form.toString(),
  })
}

export function setActivePortfolio(id: number): Promise<Response> {
  return fetch(BASE + `/api/portfolios/${id}`, {
    method: 'PUT',
    credentials: 'include',
  })
}

export function deletePortfolio(id: number): Promise<Response> {
  return fetch(BASE + `/api/portfolios/${id}`, {
    method: 'DELETE',
    credentials: 'include',
  })
}

// ── Charts ─────────────────────────────────────────────────────────────

export const chartAPI = {
  price(symbol: string, days: number): Promise<PriceData[]> {
    return request<PriceData[]>(`/api/chart?type=price&symbol=${encodeURIComponent(symbol)}&days=${days}`)
  },
  allocation(portfolioId: number): Promise<AllocationItem[]> {
    return request<AllocationItem[]>(`/api/chart?type=allocation&portfolioId=${portfolioId}`)
  },
  pnlCalendar(portfolioId: number, year: number): Promise<PnlCalendarItem[]> {
    return request<PnlCalendarItem[]>(`/api/chart?type=pnl_calendar&portfolioId=${portfolioId}&year=${year}`)
  },
  cumulativeReturn(portfolioId: number, days: number): Promise<CumulativeReturnItem[]> {
    return request<CumulativeReturnItem[]>(`/api/chart?type=cumulative_return&portfolioId=${portfolioId}&days=${days}`)
  },
}

// ── Cash balances ──────────────────────────────────────────────────────

export interface CashBalance {
  currency: string
  amount: number
}

export function getCashBalances(): Promise<{ balances: CashBalance[] }> {
  return request<{ balances: CashBalance[] }>('/api/cash')
}

// ── Stock search ───────────────────────────────────────────────────────

export function searchStocks(q: string): Promise<StockSearchItem[]> {
  if (!q.trim()) return Promise.resolve([])
  return request<StockSearchItem[]>(`/api/stock/search?q=${encodeURIComponent(q)}`)
}
