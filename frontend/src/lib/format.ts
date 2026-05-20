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
