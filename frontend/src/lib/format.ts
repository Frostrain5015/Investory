/**
 * Format a price timestamp. ISO-8601 strings → "实时 · HH:mm"; plain date strings → "MM/DD" (same year) or "YYYY/MM/DD".
 */
export function fmtPriceTs(ts?: string | null): string {
  if (!ts) return ''
  if (ts.includes('T')) {
    const d = new Date(ts)
    return `实时 · ${d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })}`
  }
  const [y, m, d] = ts.split('-')
  const thisYear = new Date().getFullYear().toString()
  return y === thisYear ? `${m}/${d}` : `${y}/${m}/${d}`
}

/** Extract stock code from symbol (handles both "116.00001" and "MMM.US" formats) */
export function shortSymbol(symbol: string): string {
  const dot = symbol.lastIndexOf('.')
  return dot >= 0 ? symbol.substring(dot + 1) : symbol
}

/** Convert to display format "601288.SH" / "00001.HK" / "AAPL.US" */
export function displaySymbol(symbol: string, market: string): string {
  if (!market) return symbol  // Can't format without market info
  const suffix = '.' + market
  if (symbol.endsWith(suffix)) return symbol
  return shortSymbol(symbol) + suffix
}
