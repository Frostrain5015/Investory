export interface PortfolioCard {
  portfolio_score: number
  holdings_scored: number
  top_holdings: { symbol: string; name: string; total_score: number }[]
  bottom_holdings: { symbol: string; name: string; total_score: number }[]
  group_exposure: Record<string, { buy_score: number }>
}

export interface PicksCard {
  regime: string
  picks: { code: string; name: string; total_score: number; buy_score: number; bullish: string[] }[]
  scanned: number
}

export interface ReportArtifact {
  id?: number
  type: string
  title: string
  summary?: string
  contentJson?: unknown
  content_json?: unknown
  contentMarkdown?: string
  content_markdown?: string
  createdAt?: string
  created_at?: string
}

export type ToolCategory = 'query' | 'analysis' | 'mutation'

export type TimelineStep =
  | { kind: 'thinking'; text: string; _ts?: number; _elapsed?: number }
  | { kind: 'kb'; topic: string }
  | { kind: 'memory'; count?: string | number }
  | {
      kind: 'tool'
      name: string
      category?: ToolCategory
      done: boolean
      error?: string
      interrupted?: boolean
      summary?: string
      callId?: string
      detail?: string
    }
  // The assistant's user-facing answer, interleaved in true emission order with
  // thinking/tool steps so a chunk of answer written *before* a later tool call
  // renders above that tool call — not dumped below the whole timeline.
  | { kind: 'text'; text: string }

export interface Message {
  role: 'user' | 'assistant' | 'system'
  content: string
  thinking?: string
  timeline?: TimelineStep[]
  hasCode?: boolean
  strategyName?: string
  strategyDesc?: string
  strategyCode?: string
  confirm?: ConfirmData
  portfolioCard?: PortfolioCard
  picksCard?: PicksCard
  artifacts?: ReportArtifact[]
  notice?: { kind: 'error' | 'stopped' }
}

export interface ConfirmBody {
  stockName?: string
  type?: string
  shares?: number
  price?: number
  fee?: number
  tradeDate?: string
  currency?: string
  amountPerShare?: number
  note?: string
  [key: string]: string | number | undefined
}

export interface ConfirmItem {
  action: string
  label: string
  endpoint: string
  method: string
  body: ConfirmBody
}

export interface ConfirmData {
  id: string
  title: string
  items: ConfirmItem[]
}

export type ConfirmStatus = 'pending' | 'accepted' | 'refused' | 'failed'
