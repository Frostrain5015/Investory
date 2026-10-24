import type {
  SessionResponse, DashboardResponse, HoldingsResponse,
  TransactionsResponse, DividendsResponse, StockDetailResponse,
  AllocationItem, PnlCalendarItem, CumulativeReturnItem,
  StockSearchItem, PriceData, BenchmarkItem, Portfolio,
  HoldingsMetricsResponse, QuantData, BacktestResult,
  HoldingCorrelation, CompareResult,
  AdminStatus, AdminUser, AdminCrawlHistoryItem, StatusResponse,
  WatchlistItem, AiSettings, AiModelSettings, AiChatRequest,
  MarketNewsItem, ExchangeRatesResponse,
} from '@/types'

export const BASE = import.meta.env.VITE_API_BASE || ''

function joinUrl(base: string, path: string) {
  return `${base.replace(/\/$/, '')}/${path.replace(/^\//, '')}`
}

export function getFrostIdLoginUrl() {
  const url = new URL(joinUrl(BASE, '/oauth/frost-id/login'), window.location.origin)
  if (window.electronAPI?.isDesktop) {
    // Desktop: login runs in the system browser; the backend hands the session
    // back via an investory:// deep link rather than redirecting to a page.
    url.searchParams.set('client', 'desktop')
  }
  return url.toString()
}

/**
 * Desktop only: exchange the one-time token from the investory:// deep link for a
 * session cookie in the app's own jar. Returns true once the app is logged in.
 */
export async function exchangeFrostIdToken(token: string): Promise<boolean> {
  try {
    const res = await fetch(BASE + '/oauth/frost-id/exchange?token=' + encodeURIComponent(token), {
      credentials: 'include',
    })
    if (!res.ok) return false
    const data = (await res.json()) as { ok?: boolean }
    return data.ok === true
  } catch {
    return false
  }
}

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const res = await fetch(BASE + url, { credentials: 'include', ...options })
  if (res.status === 401) {
    window.dispatchEvent(new CustomEvent('investory:auth-expired'))
    throw new Error('Authentication expired')
  }
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  const text = await res.text()
  if (!text) throw new Error('Expected a JSON response')
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

export function getDashboard(signal?: AbortSignal): Promise<DashboardResponse> {
  return request<DashboardResponse>('/api/dashboard', { signal })
}

// ── Holdings ───────────────────────────────────────────────────────────

export function getHoldings(signal?: AbortSignal): Promise<HoldingsResponse> {
  return request<HoldingsResponse>('/api/holdings', { signal })
}

// ── Transactions ───────────────────────────────────────────────────────

export function getTransactions(signal?: AbortSignal): Promise<TransactionsResponse> {
  return request<TransactionsResponse>('/api/transactions', { signal })
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

export function getDividends(signal?: AbortSignal): Promise<DividendsResponse> {
  return request<DividendsResponse>('/api/dividends', { signal })
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

export function getStockDetail(symbol: string, signal?: AbortSignal): Promise<StockDetailResponse> {
  return request<StockDetailResponse>(`/api/stocks/${encodeURIComponent(symbol)}`, { signal })
}

// ── Portfolios ─────────────────────────────────────────────────────────

export function getPortfolios(signal?: AbortSignal): Promise<Portfolio[]> {
  return request<Portfolio[]>('/api/portfolios', { signal })
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
  price(symbol: string, days: number, start?: string, end?: string, benchmark?: string, signal?: AbortSignal): Promise<PriceData[] | { prices: PriceData[]; benchmark: BenchmarkItem[] }> {
    let url = start && end
      ? `/api/chart?type=price&symbol=${encodeURIComponent(symbol)}&start=${start}&end=${end}`
      : `/api/chart?type=price&symbol=${encodeURIComponent(symbol)}&days=${days}`
    if (benchmark) url += `&benchmark=${encodeURIComponent(benchmark)}`
    return request(url, { signal })
  },
  allocation(portfolioId: number): Promise<AllocationItem[]> {
    return request<AllocationItem[]>(`/api/chart?type=allocation&portfolioId=${portfolioId}`)
  },
  pnlCalendar(portfolioId: number, year: number, signal?: AbortSignal): Promise<PnlCalendarItem[]> {
    return request<PnlCalendarItem[]>(`/api/chart?type=pnl_calendar&portfolioId=${portfolioId}&year=${year}`, { signal })
  },
  cumulativeReturn(portfolioId: number, days: number, start?: string, end?: string, signal?: AbortSignal): Promise<CumulativeReturnItem[]> {
    if (start && end)
      return request<CumulativeReturnItem[]>(`/api/chart?type=cumulative_return&portfolioId=${portfolioId}&start=${start}&end=${end}`, { signal })
    return request<CumulativeReturnItem[]>(`/api/chart?type=cumulative_return&portfolioId=${portfolioId}&days=${days}`, { signal })
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
  strategy: Record<string, unknown>
  config: Record<string, unknown>
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

// ── Admin ────────────────────────────────────────────────────────────────

export function adminGetStatus(): Promise<AdminStatus> { return request('/api/admin/status') }
export function adminGetUsers(): Promise<AdminUser[]> { return request('/api/admin/users') }
export function adminImpersonate(userId: number): Promise<StatusResponse> { return request('/api/admin/impersonate/' + userId, { method: 'POST' }) }
export function adminDeleteUser(userId: number): Promise<StatusResponse> { return request('/api/admin/users/' + userId, { method: 'DELETE' }) }
export function adminGetCrawlHistory(): Promise<AdminCrawlHistoryItem[]> { return request('/api/admin/crawl-history') }
export function adminClearCrawlHistory(): Promise<StatusResponse> { return request('/api/admin/crawl-history', { method: 'DELETE' }) }
export function adminCrawlStart(market: string): Promise<Response> { return fetch(`${BASE}/api/admin/crawl/${market}`, { credentials: 'include' }) }
export function adminCrawlStop(): Promise<Response> { return fetch(`${BASE}/api/admin/crawl/stop`, { method: 'POST', credentials: 'include' }) }
export function adminCrawlPause(): Promise<Response> { return fetch(`${BASE}/api/admin/crawl/pause`, { method: 'POST', credentials: 'include' }) }
export function adminCrawlResume(): Promise<Response> { return fetch(`${BASE}/api/admin/crawl/resume`, { method: 'POST', credentials: 'include' }) }
export function adminCrawlStream(): EventSource { return new EventSource(`${BASE}/api/admin/crawl/sse`, { withCredentials: true }) }

// ── Watchlist ────────────────────────────────────────────────────────────

export function getWatchlist(): Promise<WatchlistItem[]> { return request('/api/watchlist') }
export function addToWatchlist(stockId: number): Promise<WatchlistItem> {
  return request('/api/watchlist', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ stockId: String(stockId) }).toString() })
}
export function removeFromWatchlist(stockId: number): Promise<StatusResponse> { return request(`/api/watchlist/${stockId}`, { method: 'DELETE' }) }
export function reorderWatchlist(items: { id: number; sortOrder: number }[]): Promise<StatusResponse> {
  return request('/api/watchlist/reorder', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(items) })
}

// ── AI ────────────────────────────────────────────────────────────────────

export function aiChat(messages: AiChatRequest['messages'], deepThink?: boolean): Promise<unknown> {
  return request('/api/ai/chat', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ messages, deepThink }) })
}
export function aiClear(): Promise<StatusResponse> { return request('/api/ai/clear', { method: 'POST' }) }
export function aiStream(): EventSource { return new EventSource(`${BASE}/api/ai/stream`, { withCredentials: true }) }
export function aiGetSettings(): Promise<AiModelSettings> { return request('/api/ai/settings') }
export function aiSaveSettings(data: Pick<AiSettings, 'id' | 'provider' | 'model' | 'baseUrl' | 'contextWindow'> & { apiKey?: string }): Promise<AiModelSettings> {
  return request('/api/ai/settings', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) })
}
export function aiListModels(data: { id: number; provider: string; baseUrl?: string; apiKey?: string }): Promise<import('@/types').AiModelsResponse> {
  return request('/api/ai/models', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) })
}
export function aiRemoveModel(id: number): Promise<AiModelSettings> {
  return request(`/api/ai/settings/${id}`, { method: 'DELETE' })
}
export function aiSelectModel(id: number): Promise<AiModelSettings> {
  return request('/api/ai/settings/active', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ id }) })
}

// ── Market ────────────────────────────────────────────────────────────────

export function getMarketIndices(): Promise<import('./pagePreload').PreloadMarketSnapshot> { return request('/api/market/indices') }
export function getExchangeRates(): Promise<ExchangeRatesResponse> { return request('/api/market/exchange-rates') }
export function getMarketNews(): Promise<MarketNewsItem[]> { return request('/api/market/news') }

// ── Quant extras ─────────────────────────────────────────────────────────

export function getHoldingsCorrelation(symbol: string, signal?: AbortSignal): Promise<HoldingCorrelation[]> {
  return request<HoldingCorrelation[]>(`/api/quant/holdings-correlation?symbol=${encodeURIComponent(symbol)}`, { signal })
}

export function getBacktestCompare(ids: number[]): Promise<CompareResult[]> {
  return request<CompareResult[]>(`/api/backtest/compare?ids=${ids.join(',')}`)
}

// ── Account ───────────────────────────────────────────────────────────────

export function refreshPortfolio(): Promise<StatusResponse> { return request('/api/portfolio/refresh', { method: 'POST' }) }
