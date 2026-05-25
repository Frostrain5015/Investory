export interface Stock {
  id: number
  symbol: string
  name: string
  market: 'SH' | 'SZ' | 'HK' | 'US' | 'JP' | 'KR' | 'GB' | 'DE' | 'FR' | 'TW' | 'SG' | 'IN' | 'AU' | 'CA' | 'BR' | 'IDX' | 'CMD' | 'CCY'
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
  changeToday: number
  changePctToday: number
  nativePrice: number
  nativeAvgCost: number
  nativeInvested: number
  nativeMarketValue: number
  nativeUnrealizedPnl: number
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

export interface BenchmarkItem {
  date: string
  close: number
  base100: number    // stock normalized to 100
  bmClose: number    // benchmark raw close
  bmBase100: number  // benchmark normalized to 100
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
  isAdmin?: boolean
}

export interface DashboardResponse {
  snapshots: HoldingSnapshot[]
  allocation: AllocationItem[]
  totalMarketValue: number
  totalInvested: number
  totalPnl: number
  realizedPnl: number
  cumulativePnl: number
  totalReturnPct: number
  cumulativeReturnPct: number
  todayPnl: number
  todayPnlPct: number
  cashBalance: number
  cashByCurrency: { currency: string; amount: number }[]
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
  livePrice?: number
  livePriceTs?: string
}

export interface AllocationItem {
  name: string
  symbol: string
  value: number
  pct: number
  currency: string
}

export interface CumulativeReturnItem {
  date: string
  value: number
  valueExTransfer?: number
  return: number
}

export type PnlCalendarItem = [string, number, number]  // [date, dailyPnl, totalValue]

export interface StockSearchItem {
  id: string
  symbol: string
  name: string
  market: string
  currency: string
  price: number
}

export interface CreateResponse {
  id?: number
  status?: string
}

// ── Quant analysis ─────────────────────────────────────────────────────────

export interface StockMetrics {
  stock_id: number
  percentile_5y: number | null
  beta_1y: number | null
  volatility_1y: number | null
  max_drawdown_1y: number | null
  benchmark_symbol: string | null
  computed_at: string | null
}

export interface ScenarioHoldingDetail {
  stockId: number
  stockName: string
  holdingPct: number
  returnPct: number | null
}

export interface ScenarioResult {
  scenario_key: string
  scenario_name: string
  start_date: string
  end_date: string
  total_pnl_pct: number | null
  detail_json: string | null
  computed_at: string | null
}

export interface PortfolioRiskSummary {
  weighted_beta: number | null
  var_95_pct: number | null
  portfolio_maxdd: number | null
  computed_at: string | null
}

export interface QuantData {
  scenarios: ScenarioResult[]
  risk: PortfolioRiskSummary
}

export interface HoldingsMetricsResponse {
  metrics: Record<string, StockMetrics>
}

export interface SseEvent { event: string; msg?: string; current?: number; total?: number; pct?: number; name?: string; market?: string; resultId?: number }

// ── Backtest types ───────────────────────────────────────────────────────

export interface BacktestConfig {
  startDate: string
  endDate: string
  initialCapital: number
  commissionPct: number
  slippagePct: number
  minCommission?: number
}

export interface SimpleRule {
  indicator: string
  params: Record<string, number>
  condition?: string
  threshold?: number
  direction?: string
}

export interface SimpleStrategy {
  stocks: string[]
  entry: { logic: 'all' | 'any'; rules: SimpleRule[] }
  exit: {
    stopLossPct?: number
    takeProfitPct?: number
    trailingStopPct?: number
    rules: SimpleRule[]
  }
  positionSizing: { method: 'equal_weight' | 'fixed_pct'; value: number }
}

export interface BacktestMetrics {
  totalReturnPct: number
  annualReturnPct: number
  sharpeRatio: number
  maxDrawdownPct: number
  winRatePct: number
  totalTrades: number
  avgProfitPct: number
  avgLossPct: number
  profitFactor: number
  // Walk-Forward specific
  wfWindows?: number
  wfStability?: number
  wfOosSharpeAvg?: number
  wfOosReturnAvg?: number
}

export interface TradeLogEntry {
  date: string
  symbol: string
  action: 'BUY' | 'SELL'
  quantity: number
  price: number
  pnl: number | null
  pnlPct: number | null
  reason: string
}

export interface EquityPoint {
  date: string
  equity: number
  cash: number
}

export interface BacktestResult {
  id: number
  name: string
  strategy_type: string
  strategy_json: string
  config_json: string
  start_date: string
  end_date: string
  equity_curve_json: string
  metrics_json: string
  trade_log_json: string
  created_at: string
}

export interface IndicatorDef {
  name: string
  label: string
  params: { name: string; label: string; type: 'number'; default: number; min?: number; max?: number }[]
  conditions?: { value: string; label: string }[]
}
