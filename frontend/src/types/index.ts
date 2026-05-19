export interface Stock {
  id: number
  symbol: string
  name: string
  market: 'SH' | 'SZ'
  currency: string
}

export interface Holding {
  id: number
  portfolioId: number
  stockId: number
  totalShares: number
  avgCost: number
  dilutedCost: number
  totalInvested: number
  totalDividends: number
}

export interface HoldingSnapshot {
  portfolioId: number
  stockId: number
  stockSymbol: string
  stockName: string
  market: string
  currency: string
  totalShares: number
  avgCost: number
  dilutedCost: number
  totalInvested: number
  totalDividends: number
  currentPrice: number
  marketValue: number
  unrealizedPnl: number
  unrealizedPnlPct: number
}

export interface Transaction {
  id: number
  portfolioId: number
  stockId: number
  stockName?: string
  stockSymbol?: string
  type: 'BUY' | 'SELL'
  shares: number
  price: number
  fee: number
  tradeDate: string
  note?: string
}

export interface Dividend {
  id: number
  portfolioId: number
  stockId: number
  stockName?: string
  stockSymbol?: string
  amountPerShare: number
  sharesHeld: number
  totalAmount: number
  recordDate: string
}

export interface DailyValue {
  id: number
  portfolioId: number
  snapshotDate: string
  totalValue: number
  totalCost: number
  dailyPnl: number
}

export interface Portfolio {
  id: number
  userId: number
  name: string
}

export interface PriceData {
  date: string
  open: number
  close: number
  high: number
  low: number
  volume: number
}

export interface User {
  id: number
  username: string
}

// ── API response wrappers ──────────────────────────────────────────────

export interface SessionResponse {
  authenticated: boolean
  userId?: number
  username?: string
  portfolioId?: number
}

export interface DashboardResponse {
  snapshots: HoldingSnapshot[]
  totalMarketValue: number
  totalInvested: number
  totalPnl: number
  totalReturnPct: number
}

export interface HoldingsResponse {
  snapshots: HoldingSnapshot[]
}

export interface TransactionsResponse {
  transactions: Transaction[]
}

export interface DividendsResponse {
  dividends: Dividend[]
}

export interface StockDetailResponse {
  stock: Stock
  holding: Holding | null
  transactions: Transaction[]
  dividends: Dividend[]
}

export interface AllocationItem {
  name: string
  symbol: string
  value: number
  pct: number
}

export interface PnlRankItem {
  name: string
  symbol: string
  pnl: number
  pnlPct: number
}

export interface CumulativeReturnItem {
  date: string
  value: number
  return: number
}

export type PnlCalendarItem = [string, number, number]  // [date, dailyPnl, totalValue]

export interface StockSearchItem {
  id: string
  symbol: string
  name: string
  market: string
  price: number
}

export interface CreateResponse {
  id?: number
  status?: string
}
