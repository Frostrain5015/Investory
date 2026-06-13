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

// StockSage Alpha 组合分析结果（Python 桥接，snake_case 字段）
export interface PortfolioAnalysisHolding {
  symbol?: string
  code?: string
  name?: string
  market?: string
  weight?: number
  total_score?: number
  buy_score?: number
  error?: string
}

export interface PortfolioGroupExposure {
  buy_score?: number
  [key: string]: number | undefined
}

export interface PortfolioAnalysisResult {
  portfolio_score?: number
  holdings_scored?: number
  holdings_total?: number
  top_holdings?: PortfolioAnalysisHolding[]
  bottom_holdings?: PortfolioAnalysisHolding[]
  all_holdings?: PortfolioAnalysisHolding[]
  group_exposure?: Record<string, PortfolioGroupExposure>
  error?: string
  _error?: string
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

export interface SavedStrategy {
  id: number
  name: string
  strategy_type: string
  strategy_json: string
  updated_at?: string
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
  metrics_preview?: string
}

export interface IndicatorDef {
  name: string
  label: string
  ic?: number  // Information Coefficient: how predictive this indicator is (>0.05=strong, <0.02=noise)
  params: { name: string; label: string; type: 'number'; default: number; min?: number; max?: number }[]
  conditions?: { value: string; label: string }[]
}

export interface HoldingCorrelation {
  symbol: string
  name: string
  correlation_30d: number
}

export interface CompareResult {
  id: number
  name: string
  strategyType: string
  startDate: string
  endDate: string
  metrics: BacktestMetrics
  equityCurveNormalized: { date: string; value: number }[]
}

// ── StockSage Alpha types ──────────────────────────────────────────────────

export interface FactorScore {
  symbol: string
  name?: string
  buyScore: number
  sellScore: number
  totalScore: number
  regime?: string
  factorsCount?: number
  cached?: boolean
  message?: string
  bullish?: string[]
  bearish?: string[]
}

export interface FactorDetail {
  name: string
  group: string
  value: number
  buyScore: number
  sellScore: number
  description: string
}

export interface FactorBreakdown {
  symbol: string
  totalScore: number
  buyScore: number
  sellScore: number
  factors: FactorDetail[]
  rawMetrics?: Record<string, unknown>
}

export interface ScanResult {
  code: string
  name: string
  buyScore: number
  sellScore: number
  totalScore: number
  bullish?: string[]
  bearish?: string[]
}

export interface RegimeStatus {
  signal: string
  score: number
  exposure: number
  description: string
  indicators?: Record<string, number>
  timestamp?: string
}

export interface ChipLevel {
  label: string
  shares: number
  pct: number
  avgCost: number
}

export interface ChipDistribution {
  symbol: string
  chip: {
    levels?: ChipLevel[]
    avgCost?: number
    supportLevel?: number
    resistanceLevel?: number
    currentPrice?: number
    profitZone?: { pct: number; shares: number }
    lossZone?: { pct: number; shares: number }
    [key: string]: unknown
  }
}

export interface StockAnalysis {
  symbol: string
  factors: FactorBreakdown | { error: string }
  chip: ChipDistribution | { error: string }
  regime: RegimeStatus | Record<string, unknown>
}

export interface DailyPick {
  id: number
  pickDate: string
  stockSymbol: string
  stockName: string
  buyScore: number
  sellScore: number
  totalScore: number
  strategyType: string
  regime: string
  reasonText: string
}

export interface FactorScoresResponse {
  scores: Record<string, FactorScore>
}

export interface ScanResultsResponse {
  type: string
  regime: RegimeStatus | Record<string, unknown>
  picks: ScanResult[]
  candidates: ScanResult[]
  scanned: number
  timestamp: string
}

// ── Admin ──────────────────────────────────────────────────────────────────

export interface AdminStatus {
  db: { stocks: number; prices: number; transactions: number }
  tables: Record<string, { rows: number; sizeMb: number }>
}

export interface AdminUser {
  id: number
  username: string
  email: string | null
  isAdmin: boolean
  createdAt: string
  frostIdId: string | null
}

export interface AdminCrawlHistoryItem {
  id: number
  market: string
  startedAt: string
  endedAt: string | null
  rowsWritten: number
  stocksFailed: number
  status: string
  logTail: string
}

// ── Watchlist ─────────────────────────────────────────────────────────────

export interface WatchlistItem {
  id: number
  stockId: number
  symbol: string
  name: string
  sortOrder: number
  price?: number
  changePct?: number
}

// ── AI ────────────────────────────────────────────────────────────────────

export interface AiSettings {
  provider: string
  model: string
  baseUrl: string
  hasKey: boolean
}

export interface AiModelsResponse {
  status?: string
  models?: string[]
  count?: number
  error?: string
}

export interface AiChatRequest {
  messages: { role: string; content: string }[]
  deepThink?: boolean
}

// ── Market ────────────────────────────────────────────────────────────────

export interface MarketIndexItem {
  symbol: string
  name: string
  price: number
  change: number
  changePct: number
  flag?: string
  lat?: number
  lng?: number
}

export interface MarketNewsItem {
  title: string
  url: string
  source: string
  pubDate: string
  category: 'finance' | 'geopolitics' | 'other'
  score: number
  countries: string[]
  summary?: string
}

export interface ExchangeRatesResponse {
  rates: Record<string, number>
  base: string
  updatedAt: string
}

// ── Account ───────────────────────────────────────────────────────────────

export interface StatusResponse {
  status: string
  error?: string
}
