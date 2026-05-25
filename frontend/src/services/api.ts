import type {
  SessionResponse, DashboardResponse, HoldingsResponse,
  TransactionsResponse, DividendsResponse, StockDetailResponse,
  AllocationItem, PnlCalendarItem, CumulativeReturnItem,
  StockSearchItem, PriceData, Portfolio,
  HoldingsMetricsResponse, QuantData, BacktestResult,
} from '@/types'

export const BASE = import.meta.env.VITE_API_BASE || '/investory'

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const res = await fetch(BASE + url, { credentials: 'include', ...options })
  if (res.status === 401) {
    window.dispatchEvent(new CustomEvent('investory:auth-expired'))
    return {} as T
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
  price(symbol: string, days: number, start?: string, end?: string, benchmark?: string): Promise<PriceData[] | { prices: PriceData[]; benchmark: any[] }> {
    let url = start && end
      ? `/api/chart?type=price&symbol=${encodeURIComponent(symbol)}&start=${start}&end=${end}`
      : `/api/chart?type=price&symbol=${encodeURIComponent(symbol)}&days=${days}`
    if (benchmark) url += `&benchmark=${encodeURIComponent(benchmark)}`
    return request(url)
  },
  allocation(portfolioId: number): Promise<AllocationItem[]> {
    return request<AllocationItem[]>(`/api/chart?type=allocation&portfolioId=${portfolioId}`)
  },
  pnlCalendar(portfolioId: number, year: number): Promise<PnlCalendarItem[]> {
    return request<PnlCalendarItem[]>(`/api/chart?type=pnl_calendar&portfolioId=${portfolioId}&year=${year}`)
  },
  cumulativeReturn(portfolioId: number, days: number, start?: string, end?: string): Promise<CumulativeReturnItem[]> {
    if (start && end)
      return request<CumulativeReturnItem[]>(`/api/chart?type=cumulative_return&portfolioId=${portfolioId}&start=${start}&end=${end}`)
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

// ── Quant analytics ────────────────────────────────────────────────────

export function getHoldingsMetrics(): Promise<HoldingsMetricsResponse> {
  return request<HoldingsMetricsResponse>('/api/quant/holdings-metrics')
}

export function getQuantData(): Promise<QuantData> {
  return request<QuantData>('/api/quant/portfolio-scenario')
}

// ── Backtest ─────────────────────────────────────────────────────────────

export async function startBacktest(data: {
  name: string
  strategyType: string
  strategy: any
  config: any
}): Promise<Response> {
  return fetch(`${BASE}/api/backtest/start`, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  })
}

export function getBacktestHistory(): Promise<BacktestResult[]> {
  return request<BacktestResult[]>('/api/backtest/history')
}

export function getBacktest(id: number): Promise<BacktestResult> {
  return request<BacktestResult>(`/api/backtest/${id}`)
}

export function deleteBacktest(id: number): Promise<{ status: string }> {
  return request<{ status: string }>(`/api/backtest/${id}`, { method: 'DELETE' })
}

export async function getBacktestStream(): Promise<Response> {
  return fetch(`${BASE}/api/backtest/stream`, { credentials: 'include' })
}

// ── Admin ────────────────────────────────────────────────────────────────

export function adminGetStatus(): Promise<any> { return request('/api/admin/status') }
export function adminGetUsers(): Promise<any[]> { return request('/api/admin/users') }
export function adminImpersonate(userId: number): Promise<any> { return request('/api/admin/impersonate/' + userId, { method: 'POST' }) }
export function adminDeleteUser(userId: number): Promise<any> { return request('/api/admin/users/' + userId, { method: 'DELETE' }) }
export function adminGetCrawlHistory(): Promise<any[]> { return request('/api/admin/crawl-history') }
export function adminClearCrawlHistory(): Promise<any> { return request('/api/admin/crawl-history', { method: 'DELETE' }) }
export function adminCrawlStart(market: string): Promise<Response> { return fetch(`${BASE}/api/admin/crawl/${market}`, { credentials: 'include' }) }
export function adminCrawlStop(): Promise<Response> { return fetch(`${BASE}/api/admin/crawl/stop`, { method: 'POST', credentials: 'include' }) }
export function adminCrawlPause(): Promise<Response> { return fetch(`${BASE}/api/admin/crawl/pause`, { method: 'POST', credentials: 'include' }) }
export function adminCrawlResume(): Promise<Response> { return fetch(`${BASE}/api/admin/crawl/resume`, { method: 'POST', credentials: 'include' }) }
export function adminCrawlStream(): EventSource { return new EventSource(`${BASE}/api/admin/crawl/sse`, { withCredentials: true }) }

// ── Watchlist ────────────────────────────────────────────────────────────

export function getWatchlist(): Promise<any[]> { return request('/api/watchlist') }
export function addToWatchlist(stockId: number): Promise<any> {
  return request('/api/watchlist', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ stockId: String(stockId) }).toString() })
}
export function removeFromWatchlist(stockId: number): Promise<any> { return request(`/api/watchlist/${stockId}`, { method: 'DELETE' }) }
export function reorderWatchlist(items: { id: number; sortOrder: number }[]): Promise<any> {
  return request('/api/watchlist/reorder', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(items) })
}

// ── AI ────────────────────────────────────────────────────────────────────

export function aiChat(messages: any[], deepThink?: boolean): Promise<any> {
  return request('/api/ai/chat', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ messages, deepThink }) })
}
export function aiClear(): Promise<any> { return request('/api/ai/clear', { method: 'POST' }) }
export function aiStream(): EventSource { return new EventSource(`${BASE}/api/ai/stream`, { withCredentials: true }) }
export function aiGetSettings(): Promise<any> { return request('/api/ai/settings') }
export function aiSaveSettings(data: any): Promise<any> {
  return request('/api/ai/settings', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) })
}

// ── Market ────────────────────────────────────────────────────────────────

export function getMarketIndices(): Promise<any> { return request('/api/market/indices') }
export function getExchangeRates(): Promise<any> { return request('/api/market/exchange-rates') }
export function getMarketNews(): Promise<any[]> { return request('/api/market/news') }

// ── Account ───────────────────────────────────────────────────────────────

export function changePassword(oldPassword: string, newPassword: string): Promise<any> {
  return request('/api/password', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ oldPassword, newPassword }).toString() })
}
export function deleteAccount(): Promise<any> { return request('/api/account', { method: 'DELETE' }) }
export function refreshPortfolio(): Promise<any> { return request('/api/portfolio/refresh', { method: 'POST' }) }
