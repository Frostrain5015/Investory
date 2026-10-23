import { BASE } from '@/services/api'

export interface PreloadMarketQuote {
  name: string
  symbol: string
  price: number | null
  change: number | null
  changePct: number | null
  source: string | null
  state: 'available' | 'stale' | 'unavailable'
  quoteTime: string | null
  timestampScope: 'BATCH' | 'EXCHANGE' | 'CLOSE' | 'UNKNOWN'
  asOfDate: string | null
  receivedAt: string | null
  fallbackReason: string | null
}
export interface PreloadMarketIndex extends PreloadMarketQuote {
  flag: string
  lat: number
  lng: number
}
export type PreloadMarketIndicator = PreloadMarketQuote
export interface PreloadMarketSnapshot {
  refreshing?: boolean
  completedCount?: number
  generation?: number
  indices: PreloadMarketIndex[]
  indicators: PreloadMarketIndicator[]
  updatedAt: string
  sources: string[]
  hithinkStatus: 'active' | 'disabled' | 'not_configured' | 'fallback' | 'partial'
  hithinkError: string | null
}

export interface PreloadMarketNewsItem {
  title: string
  source: string
  url: string
  summary: string
  category: string
  country_code: string
  published_at: string
}

export interface PreloadAdminMarketStat {
  market: string
  stock_count: number
}

export interface PreloadAdminDbStatus {
  markets: PreloadAdminMarketStat[]
  totals: { stock_count: number }
  tables: { total_mb: number }[]
}

export interface PreloadAdminUser {
  id: number
  username: string
  email: string | null
  created_at: string
  txn_count: number
  portfolio_count: number
}

export interface PreloadAdminCrawlHistory {
  market: string
  started_at: string
  ended_at: string
  rows_written: number
  stocks_failed: number
  status: string
}

let marketPagePromise: Promise<unknown> | null = null
let adminPagePromise: Promise<unknown> | null = null
let marketSnapshotPromise: Promise<PreloadMarketSnapshot> | null = null
let marketSnapshotPending = false
let marketSnapshotLoadedAt = 0
let marketNewsPromise: Promise<PreloadMarketNewsItem[]> | null = null
let worldMapPromise: Promise<unknown> | null = null
let adminStatusPromise: Promise<PreloadAdminDbStatus> | null = null
let adminUsersPromise: Promise<PreloadAdminUser[]> | null = null
let adminCrawlHistoryPromise: Promise<PreloadAdminCrawlHistory[]> | null = null

async function jsonRequest<T>(path: string, signal?: AbortSignal): Promise<T> {
  const res = await fetch(`${BASE}${path}`, { credentials: 'include', signal })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return (await res.json()) as T
}

function cachePromise<T>(
  current: Promise<T> | null,
  assign: (next: Promise<T> | null) => void,
  loader: () => Promise<T>,
  force = false,
) {
  if (!current || force) {
    const next = loader().catch((err: unknown) => {
      assign(null)
      throw err
    })
    assign(next)
    return next
  }
  return current
}

export function getPreloadedMarketSnapshot(options: { force?: boolean; signal?: AbortSignal } = {}) {
  if (options.signal) return jsonRequest<PreloadMarketSnapshot>('/api/market/indices/progress', options.signal)
  if (marketSnapshotPromise && (marketSnapshotPending || (!options.force && Date.now() - marketSnapshotLoadedAt < 20_000))) return marketSnapshotPromise
  marketSnapshotPending = true
  const next = jsonRequest<PreloadMarketSnapshot>('/api/market/indices/progress').then(data => {
    marketSnapshotLoadedAt = Date.now()
    return data
  }).catch((error: unknown) => {
    if (marketSnapshotPromise === next) marketSnapshotPromise = null
    throw error
  }).finally(() => { if (marketSnapshotPromise === next || marketSnapshotPromise === null) marketSnapshotPending = false })
  marketSnapshotPromise = next
  return next
}

export function getPreloadedMarketNews(options: { force?: boolean } = {}) {
  return cachePromise(
    marketNewsPromise,
    next => { marketNewsPromise = next },
    () => jsonRequest<PreloadMarketNewsItem[]>('/api/market/news'),
    options.force,
  )
}

export function getPreloadedWorldMap(options: { force?: boolean } = {}) {
  return cachePromise(
    worldMapPromise,
    next => { worldMapPromise = next },
    async () => {
      const res = await fetch(`${import.meta.env.BASE_URL}world.json`)
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      return (await res.json()) as unknown
    },
    options.force,
  )
}

export function getPreloadedAdminStatus(options: { force?: boolean } = {}) {
  return cachePromise(
    adminStatusPromise,
    next => { adminStatusPromise = next },
    () => jsonRequest<PreloadAdminDbStatus>('/api/admin/status'),
    options.force,
  )
}

export function getPreloadedAdminUsers(options: { force?: boolean } = {}) {
  return cachePromise(
    adminUsersPromise,
    next => { adminUsersPromise = next },
    () => jsonRequest<PreloadAdminUser[]>('/api/admin/users'),
    options.force,
  )
}

export function getPreloadedAdminCrawlHistory(options: { force?: boolean } = {}) {
  return cachePromise(
    adminCrawlHistoryPromise,
    next => { adminCrawlHistoryPromise = next },
    () => jsonRequest<PreloadAdminCrawlHistory[]>('/api/admin/crawl-history'),
    options.force,
  )
}

export function preloadWorldMarketPage() {
  marketPagePromise ||= import('@/pages/Market')
  void getPreloadedMarketNews().catch(() => {})
  void getPreloadedWorldMap().catch(() => {})
}

export function preloadAdminDatabasePage() {
  adminPagePromise ||= import('@/pages/Admin')
  void getPreloadedAdminStatus().catch(() => {})
  void getPreloadedAdminUsers().catch(() => {})
  void getPreloadedAdminCrawlHistory().catch(() => {})
}

export function preloadPostLoginPages(isAdmin: boolean) {
  preloadWorldMarketPage()
  if (isAdmin) preloadAdminDatabasePage()
}
