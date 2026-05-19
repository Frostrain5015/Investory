/** Convert EastMoney secid (e.g. "1.601288") to code only ("601288") */
export function shortSymbol(symbol: string): string {
  const dot = symbol.lastIndexOf('.')
  return dot >= 0 ? symbol.substring(dot + 1) : symbol
}

/** Convert to display format "601288.SH" when market is known */
export function displaySymbol(symbol: string, market: string): string {
  return shortSymbol(symbol) + '.' + market
}
