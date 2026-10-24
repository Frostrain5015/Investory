export interface QuoteContext {
  live?: boolean
  timestamp?: string | null
  source?: string | null
  timestampScope?: 'BATCH' | 'EXCHANGE' | 'CLOSE' | 'UNKNOWN'
  asOfDate?: string | null
  state?: 'available' | 'stale' | 'unavailable'
}

/** Never expose raw ISO/UTC or relabel a provider batch as an exchange trade. */
export function quoteTimeDescription(quote: QuoteContext): string {
  if (quote.timestampScope === 'CLOSE' && quote.asOfDate && /^\d{4}-\d{2}-\d{2}$/.test(quote.asOfDate)) {
    return `收盘日期：${quote.asOfDate.replaceAll('-', '/')}`
  }
  const scope = quote.timestampScope ?? (quote.source === 'hithink_finance_api' ? 'BATCH' : 'UNKNOWN')
  if (!quote.timestamp || !Number.isFinite(Date.parse(quote.timestamp))) return ''
  // A timezone-free value must not be silently interpreted in the browser's zone.
  if (!/(Z|[+-]\d{2}:?\d{2})$/i.test(quote.timestamp)) return ''
  const formatted = new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }).format(new Date(quote.timestamp))
  if (scope === 'BATCH') return `接口更新：${formatted}（北京时间），不代表成交时间`
  if (scope === 'EXCHANGE') return `行情时间：${formatted}（北京时间）`
  return ''
}

export function quoteDescription(quote: QuoteContext): string {
  const time = quoteTimeDescription(quote)
  const state = quote.source === 'cost' ? '暂时无法获取报价，当前按持仓成本估值，不能代表市场价格'
    : quote.state === 'unavailable' || quote.source === 'unavailable' ? '暂未获取到报价'
    : quote.state === 'stale' ? '报价暂未更新，显示上次有效价格'
    : quote.live ? '当前为实时报价' : '当前显示最近可用报价，未确认为实时'
  return `${state}。${time ? `${time}。` : ''}`
}

export function quoteNeedsWarning(quote: QuoteContext): boolean {
  return quote.source === 'cost' || quote.source === 'unavailable' || quote.state === 'stale' || quote.state === 'unavailable'
}
