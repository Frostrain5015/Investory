import { BASE } from '@/services/api'

export interface PreloadMarketIndex {
  name: string
  flag: string
  lat: number
  lng: number
  price: number
  change: number
  changePct: number
  symbol: string
  fetchedAt?: string
}

export interface PreloadMarketIndicator {
  name: string
  symbol: string
  price: number
  change: number
  changePct: number
  fetchedAt?: string
}

export interface PreloadMarketSnapshot {
  indices: PreloadMarketIndex[]
  indicators: PreloadMarketIndicator[]
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
  price_rows: number
  latest_date: string
  earliest_date: string
}

export interface PreloadAdminDbStatus {
  markets: PreloadAdminMarketStat[]
  totals: { stock_count: number; price_rows: number }
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
let marketNewsPromise: Promise<PreloadMarketNewsItem[]> | null = null
let worldMapPromise: Promise<unknown> | null = null
let adminStatusPromise: Promise<PreloadAdminDbStatus> | null = null
let adminUsersPromise: Promise<PreloadAdminUser[]> | null = null
let adminCrawlHistoryPromise: Promise<PreloadAdminCrawlHistory[]> | null = null

async function jsonRequest<T>(path: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`, { credentials: 'include' })
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

export function getPreloadedMarketSnapshot(options: { force?: boolean } = {}) {
  return cachePromise(
    marketSnapshotPromise,
    next => { marketSnapshotPromise = next },
    () => jsonRequest<PreloadMarketSnapshot>('/api/market/indices'),
    options.force,
  )
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
  void getPreloadedMarketSnapshot().catch(() => {})
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
