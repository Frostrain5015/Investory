import { type Lang } from './translations'

type StockNameEntry = { zh?: string; en?: string }
type StockNameMap = Record<string, StockNameEntry>

let _map: StockNameMap | null = null
let _loading: Promise<StockNameMap> | null = null

async function loadMap(): Promise<StockNameMap> {
  if (_map) return _map
  if (_loading) return _loading
  _loading = fetch('/investory/stock_names.json')
    .then(r => r.json())
    .then((data: StockNameMap) => {
      _map = data
      return data
    })
    .catch(() => {
      _loading = null
      return {} as StockNameMap
    })
  return _loading
}

/** Get the display name for a stock symbol in the given language. Falls back gracefully. */
export function getStockName(symbol: string, lang: Lang): string {
  const entry = _map?.[symbol]
  if (!entry) return symbol
  // Prefer exact match, fall back to any available language, then symbol
  if (lang === 'zh' || lang === 'hk') return entry.zh || entry.en || symbol
  return entry.en || entry.zh || symbol
}

/** Preload stock names. Call once at app init. */
export function preloadStockNames(): void {
  loadMap()
}

/** React hook — returns stock name, re-renders when map loads. Not reactive to lang changes
 *  since the component using it should call getStockName directly with current lang. */
export function useStockName(): (symbol: string, lang: Lang) => string {
  return getStockName
}
