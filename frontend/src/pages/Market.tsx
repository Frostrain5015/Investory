import QuoteNumber from '@/components/QuoteNumber'
import { useEffect, useState, useRef, useMemo, useCallback } from 'react'
import * as echarts from 'echarts'
import { useSettings } from '@/hooks/use-settings'
import { useTimedRefresh } from '@/hooks/use-timed-refresh'
import { useT } from '@/i18n/I18nContext'
import InfoTip from '@/components/InfoTip'
import { quoteDescription as describeQuote, quoteNeedsWarning, type QuoteContext } from '@/lib/quotePresentation'
import {
  getPreloadedMarketNews,
  getPreloadedMarketSnapshot,
  getPreloadedWorldMap,
  type PreloadMarketQuote,
  type PreloadMarketIndex,
  type PreloadMarketIndicator,
  type PreloadMarketNewsItem,
} from '@/services/pagePreload'

type IndexData = PreloadMarketIndex
type IndicatorData = PreloadMarketIndicator
type NewsItem = PreloadMarketNewsItem

// 散点(新闻)系列的数据项形状，与下方 series.data 映射保持一致
interface NewsPointData {
  url?: string
  title?: string
  source?: string
  category?: string
  country_code?: string
}

// echarts 回调参数最小形状（仅取本组件实际访问的字段）。
// echarts 官方回调类型为复杂联合，这里用结构化最小子集 + 边界处 cast 衔接。
interface EChartsCallbackParams {
  dataIndex: number
  data?: NewsPointData
  name?: string
  seriesType?: string
  seriesIndex?: number
}

const LEADING = ['上证指数', '恒生指数', '标普500']

// Flag → GeoJSON country names + leading index for country coloring
const COUNTRY_MAP: Record<string, { lead: string; names: string[] }> = {
  CN: { lead: '上证指数',   names: ['China', 'Hong Kong', 'Taiwan'] },
  US: { lead: '标普500',   names: ['United States of America', 'United States'] },
  JP: { lead: '日经225',   names: ['Japan'] },
  KR: { lead: '韩国KOSPI', names: ['South Korea', 'Korea'] },
  GB: { lead: '富时100',   names: ['United Kingdom'] },
  DE: { lead: '德国DAX',   names: ['Germany'] },
  FR: { lead: '法国CAC40', names: ['France'] },
  SG: { lead: '新加坡STI', names: ['Singapore'] },
  IN: { lead: '印度SENSEX',names: ['India'] },
  AU: { lead: '澳洲ASX200',names: ['Australia'] },
  CA: { lead: '加拿大TSX', names: ['Canada'] },
  BR: { lead: '巴西Bovespa', names: ['Brazil'] },
}

// [lng, lat] pairs for ECharts geo — first is primary, rest are fallback cities
// All coords shifted 5° west to correct world.json eastward projection bias
const NEWS_CITIES: Record<string, [number, number][]> = {
  US: [[-100.7, 37.1], [-79.0, 40.7], [-123.2, 34.0], [-92.6, 41.9]],
  CN: [[ 99.2, 35.9], [116.5, 31.2], [111.4, 39.9], [108.3, 23.1]],
  GB: [[ -6.8, 52.7], [ -5.1, 51.5], [ -8.2, 55.9], [ -6.9, 52.5]],
  FR: [[ -2.6, 46.6], [ -2.7, 48.9], [  0.4, 43.3]],
  DE: [[  5.5, 51.2], [  8.4, 52.5], [  3.7, 50.1]],
  JP: [[133.3, 36.5], [134.7, 35.7], [130.5, 34.7]],
  AU: [[128.8,-25.7], [146.2,-33.9], [139.9,-37.8]],
  CA: [[-101.8, 56.1], [-84.4, 43.7], [-128.1, 49.3]],
  IN: [[ 73.9, 20.6], [ 67.9, 19.1], [ 83.4, 22.6], [72.2, 28.6]],
  RU: [[ 55.0, 55.7], [ 32.6, 55.7], [ 25.3, 59.9]],
  UA: [[ 26.2, 49.0], [ 25.5, 50.4]],
  KR: [[122.8, 36.0], [122.0, 37.6], [124.1, 35.2]],
  BR: [[-56.9,-14.2], [-51.6,-23.5], [-48.2,-22.9]],
  IR: [[ 48.7, 32.4], [ 46.4, 35.7]],
  IL: [[ 30.0, 31.5], [ 29.8, 32.1]],
  SA: [[ 40.1, 23.9], [ 41.7, 24.7]],
  TR: [[ 30.2, 39.0], [ 23.9, 41.0]],
  MX: [[-107.6, 23.6], [-104.1, 19.4]],
  SG: [[ 98.8,  1.4]],
  TW: [[116.6, 25.0]],
  HK: [[109.2, 22.3]],
}

function hexToRgb(hex: string): [number, number, number] {
  const v = parseInt(hex.slice(1), 16)
  return [(v >> 16) & 255, (v >> 8) & 255, v & 255]
}

function fmtNum(n: number, dec: number, lang: string): string {
  if (lang === 'zh' && Math.abs(n) >= 10000) {
    return (n / 10000).toFixed(1) + '万'
  }
  return n.toLocaleString(lang === 'zh' ? 'zh-CN' : 'en-US', {
    minimumFractionDigits: dec,
    maximumFractionDigits: dec,
  })
}

function hasPrice(quote: PreloadMarketQuote): boolean { return typeof quote.price === 'number' && Number.isFinite(quote.price) && quote.price > 0 }
function numeric(value: number | null): value is number { return typeof value === 'number' && Number.isFinite(value) }
function sourceLabel(source: string | null): string {
  return ({ hithink_finance_api: '同花顺金融 API', yahoo: 'Yahoo Finance', sina: 'Sina', database: '本地收盘记录' } as Record<string, string>)[source ?? ''] ?? '未知来源'
}
function signed(value: number | null, lang: string): string {
  return numeric(value) ? `${value >= 0 ? '+' : '-'}${fmtNum(Math.abs(value), 2, lang)}` : '—'
}
function quoteContext(quote: PreloadMarketQuote): QuoteContext {
  return { source: quote.source, state: quote.state, timestamp: quote.quoteTime,
    timestampScope: quote.timestampScope, asOfDate: quote.asOfDate }
}
function quoteDescription(quote: PreloadMarketQuote): string { return describeQuote(quoteContext(quote)) }
function needsWarning(quote: PreloadMarketQuote): boolean { return quoteNeedsWarning(quoteContext(quote)) }

export default function Market() {
  const { t, lang } = useT()
  const { positiveHex, negativeHex, positiveClass, negativeClass } = useSettings()
  const [indices, setIndices] = useState<IndexData[]>([])
  const [indicators, setIndicators] = useState<IndicatorData[]>([])
  const [mapReady, setMapReady] = useState(false)
  const [mapError, setMapError] = useState(false)
  const [sources, setSources] = useState<string[]>([])
  const [dataError, setDataError] = useState('')
  const [providerWarning, setProviderWarning] = useState(false)
  const [news, setNews] = useState<NewsItem[]>([])
  const chartRef = useRef<HTMLDivElement>(null)
  const loadedInitialRef = useRef(false)
  const chartInstance = useRef<echarts.ECharts | null>(null)
  const activeRequest = useRef(0)
  const quoteAbort = useRef<AbortController | null>(null)


  const loadIndices = useCallback(async (force = false) => {
    const foreground = () => document.visibilityState !== 'hidden'
    if (!foreground()) return
    quoteAbort.current?.abort()
    const controller = new AbortController()
    quoteAbort.current = controller
    const request = ++activeRequest.current
    const shouldForce = force || loadedInitialRef.current
    loadedInitialRef.current = true
    void getPreloadedMarketNews({ force: shouldForce })
      .then(data => { if (request === activeRequest.current) setNews(Array.isArray(data) ? data : []) })
      .catch(() => {})
    let polling = shouldForce
    try {
      do {
        const data = await getPreloadedMarketSnapshot({ force: polling, signal: controller.signal })
        if (request !== activeRequest.current || !foreground()) return
        setIndices(data.indices || [])
        setIndicators(data.indicators || [])
        setSources(data.sources ?? [])
        setProviderWarning(data.hithinkStatus === 'fallback' || data.hithinkStatus === 'partial')
        setDataError('')
        if (!data.refreshing) return
        await new Promise<void>(resolve => window.setTimeout(resolve, 650))
        polling = true
      } while (request === activeRequest.current && foreground())
    } catch {
      if (!controller.signal.aborted && request === activeRequest.current) setDataError('行情更新失败，已保留上次显示的结果')
    }
  }, [])

  useEffect(() => {
    void loadIndices()
    const visibility = () => {
      if (document.visibilityState === 'hidden') { activeRequest.current++; quoteAbort.current?.abort() }
      else void loadIndices(true)
    }
    document.addEventListener('visibilitychange', visibility)
    const active = activeRequest
    const abort = quoteAbort
    return () => { active.current++; abort.current?.abort(); document.removeEventListener('visibilitychange', visibility) }
  }, [loadIndices])
  useTimedRefresh(() => loadIndices(true))

  useEffect(() => {
    if (!chartRef.current) return
    const chart = echarts.init(chartRef.current, null, { renderer: 'svg' })
    chartInstance.current = chart
    let disposed = false
    const resize = new ResizeObserver(() => { if (!disposed) chart.resize() })
    resize.observe(chartRef.current)
    void getPreloadedWorldMap().then(geoJson => {
      if (disposed) return
      echarts.registerMap('world', geoJson as Parameters<typeof echarts.registerMap>[1])
      setMapReady(true)
    }).catch(() => { if (!disposed) setMapError(true) })
    return () => { disposed = true; resize.disconnect(); chartInstance.current = null; chart.dispose() }
  }, [])

  // Country region coloring based on leading index change
  const countryRegions = useMemo(() => {
    if (indices.length === 0) return []
    const posRgb = hexToRgb(positiveHex)
    const negRgb = hexToRgb(negativeHex)

    // Find max abs changePct for intensity scaling (ignore zeros)
    const validPcts = indices.filter(d => hasPrice(d) && numeric(d.changePct)).map(d => Math.abs(Number(d.changePct)))
    const maxPct = Math.max(...validPcts, 0.01)

    return Object.entries(COUNTRY_MAP).flatMap(([flag, { lead, names }]) => {
      const leader = indices.find(d => d.flag === flag && d.name === lead)
        || indices.find(d => d.flag === flag)
      if (!leader || !hasPrice(leader) || !numeric(leader.changePct)) return []
      const pct = Number(leader.changePct)
      const up = pct >= 0
      const intensity = 0.12 + 0.78 * (Math.abs(pct) / maxPct)
      const [r, g, b] = up ? posRgb : negRgb
      const color = `rgba(${r},${g},${b},${intensity.toFixed(2)})`
      return names.map(name => ({ name, itemStyle: { areaColor: color } }))
    })
  }, [indices, positiveHex, negativeHex])

  // Group co-located markers, leader color drives the dot
  const markers = useMemo(() => {
    const map = new Map<string, IndexData[]>()
    for (const d of indices) {
      const key = `${d.lat},${d.lng}`
      if (!map.has(key)) map.set(key, [])
      map.get(key)!.push(d)
    }
    return Array.from(map.values())
  }, [indices])

  const newsPoints = useMemo(() => {
    const idxByCountry = new Map<string, number>()
    return news
      .filter(n => NEWS_CITIES[n.country_code])
      .map(n => {
        const cities = NEWS_CITIES[n.country_code]
        const idx = idxByCountry.get(n.country_code) || 0
        idxByCountry.set(n.country_code, idx + 1)
        const [lng, lat] = cities[idx % cities.length]
        return {
          value: [lng, lat],
          itemStyle: {
            color: n.category === 'finance' ? '#6366f1' : '#f97316',
            shadowBlur: 10,
            shadowColor: n.category === 'finance' ? '#6366f180' : '#f9731680',
          },
          url: n.url, title: n.title, source: n.source, category: n.category, country_code: n.country_code,
        }
      })
  }, [news])

  useEffect(() => {
    const chart = chartInstance.current
    if (!mapReady || !chart) return
        chart.setOption({
          backgroundColor: 'transparent',
          animationDuration: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 0 : 320,
          animationDurationUpdate: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 0 : 320,
          animationEasingUpdate: 'cubicOut',
          tooltip: {
            trigger: 'item',
            confine: true,
            backgroundColor: '#fff',
            borderColor: '#e2e8f0',
            borderWidth: 1,
            borderRadius: 16,
            padding: [14, 18],
            textStyle: { color: '#334155', fontSize: 13 },
            formatter: (raw: unknown) => {
              const params = raw as EChartsCallbackParams
              const group = markers[params.dataIndex]
              if (!group) return ''
              const f = group[0].flag.toLowerCase()
              const countryName = t.market.countryNames[f as keyof typeof t.market.countryNames] || group[0].flag
              return `<div style="display:flex;align-items:center;gap:8px;margin-bottom:8px;font-weight:700;font-size:14px">
                <img src="${import.meta.env.BASE_URL}flags/${f}.svg" onerror="this.style.display='none'" style="width:22px;height:15px;border-radius:2px"/>
                ${countryName}
              </div>${group.map(d => {
                  const valid = hasPrice(d)
                  const u = numeric(d.change) && d.change >= 0
                  const color = !valid || !numeric(d.change) ? '#9ca3af' : u ? positiveHex : negativeHex
                  const priceText = valid ? Number(d.price).toLocaleString() : '—'
                  const changeText = valid ? `${signed(d.change, lang)} (${numeric(d.changePct) ? signed(d.changePct, lang) + '%' : '—'})` : t.market.noData
                  const warning = valid && needsWarning(d)
                  return `<div style="display:flex;align-items:center;gap:12px;padding:3px 0;font-size:12px">
                    <span style="min-width:56px;color:#475569">${d.name}</span>
                    <span style="font-weight:600;min-width:85px;text-align:right;color:${color}">${priceText}</span>
                    <span style="min-width:95px;text-align:right;color:${color}">${changeText}</span>
                    ${warning ? `<span tabindex="0" aria-label="报价异常" title="${quoteDescription(d)}" style="color:#b45309;font-size:14px">&#9888;</span>` : ''}
                  </div>`
                }).join('')}`
            },
          },
          geo: {
            map: 'world', roam: false, zoom: 1.25, center: [18, 25],
            layoutCenter: ['50%', '30%'], aspectScale: 0.85,
            regions: countryRegions,
            itemStyle: { areaColor: '#f1f5f9', borderColor: '#cbd5e1', borderWidth: 0.5 },
            emphasis: { itemStyle: { areaColor: '#bfdbfe' }, label: { show: false } },
          },
          series: [
            {
              id: 'market-indices', type: 'scatter', coordinateSystem: 'geo',
              data: markers.map(group => {
                const leader = group.find(d => LEADING.includes(d.name)) || group[0]
                const valid = hasPrice(leader) && numeric(leader.change)
                const up = Number(leader.change) >= 0
                const color = !valid ? '#9ca3af' : up ? positiveHex : negativeHex
                return {
                  name: group[0].flag, value: [group[0].lng, group[0].lat],
                  itemStyle: {
                    color,
                    shadowBlur: valid ? 12 : 0,
                    shadowColor: valid ? (up ? positiveHex : negativeHex) + '80' : 'transparent',
                  },
                }
              }),
              symbol: 'circle', symbolSize: 18,
              emphasis: { scale: 1.6, itemStyle: { shadowBlur: 24 } },
              label: { show: false },
            },
            {
              id: 'market-news', type: 'scatter', coordinateSystem: 'geo',
              data: newsPoints,
              symbol: 'pin', symbolSize: 22, symbolOffset: [18, 0], z: 10,
              emphasis: { scale: 1.3 },
              label: { show: false },
              tooltip: {
                confine: true,
                backgroundColor: '#fff', borderColor: '#e2e8f0',
                borderWidth: 1, borderRadius: 16, padding: [12, 16],
                textStyle: { color: '#334155', fontSize: 13 },
                formatter: (raw: unknown) => {
                  const params = raw as EChartsCallbackParams
                  const d = params.data || {}
                  const cc = d.category === 'finance' ? '#6366f1' : '#f97316'
                  const cl = d.category === 'finance' ? t.market.categoryFinance : t.market.categoryGeopolitics
                  const flag = (d.country_code || '').toLowerCase()
                  const countryName = t.market.countryNames[flag as keyof typeof t.market.countryNames] || d.country_code || ''
                  return `<div style="max-width:300px">
                    <div style="display:flex;align-items:center;gap:6px;margin-bottom:4px">
                      ${flag ? `<img src="${import.meta.env.BASE_URL}flags/${flag}.svg" onerror="this.style.display='none'" style="width:18px;height:12px;border-radius:2px"/>` : ''}
                      <span style="background:${cc};color:#fff;font-size:10px;padding:2px 7px;border-radius:4px">${cl}</span>
                      <span style="font-size:10px;color:#64748b">${countryName}</span>
                    </div>
                    <div style="font-size:12px;font-weight:600;color:#1e293b;line-height:1.5;overflow-wrap:break-word;white-space:normal">${d.title}</div>
                    <div style="font-size:11px;color:#94a3b8;margin-top:4px">${d.source}</div>
                    <div style="font-size:10px;color:#3b82f6;margin-top:3px">${t.market.clickToViewSource}</div>
                  </div>`
                },
              },
            },
          ],
        })

        // Click on country → navigate to leading index detail page; click on news pin → open URL
        chart.off('click')
        chart.on('click', (raw: unknown) => {
          const params = raw as EChartsCallbackParams
          if (params.seriesType === 'scatter' && params.seriesIndex === 1) {
            const url = params.data?.url
            if (url) window.open(url, '_blank', 'noopener')
            return
          }
          let flag: string | null = null
          if (params.seriesType === 'scatter' && params.dataIndex != null) {
            flag = markers[params.dataIndex]?.[0]?.flag
          } else if (params.name) {
            const match = Object.entries(COUNTRY_MAP).find(([, v]) =>
              v.names.some(n => n.toLowerCase() === String(params.name).toLowerCase()))
            flag = match ? match[0] : null
          }
          if (!flag) return
          const entry = COUNTRY_MAP[flag]
          if (!entry) return
          const leader = indices.find(d => d.flag === flag && d.name === entry.lead)
            || indices.find(d => d.flag === flag)
          if (leader?.symbol) {
            window.location.href = import.meta.env.BASE_URL + `stock?symbol=${encodeURIComponent(leader.symbol)}`
          }
        })
  }, [mapReady, indices, t, markers, countryRegions, positiveHex, negativeHex, newsPoints, lang, t.market.noData, t.market.clickToViewSource, t.market.categoryFinance, t.market.categoryGeopolitics, t.market.countryNames])


  return (
    <div className="h-full flex flex-col">
      <div className="px-4 sm:px-6 pt-6 pb-1 flex flex-wrap items-center justify-between gap-2 shrink-0">
        <div className="flex items-center gap-3">
          <h2 className="text-xl font-bold text-slate-900 tracking-tight">{t.market.title}</h2>
        </div>
        <div className="flex items-center text-[10px] text-slate-400"><span>{t.market.dataSource}：{sources.map(sourceLabel).join(' / ') || '—'}</span><InfoTip label="行情来源">A 股指数使用同花顺金融 API，其他市场使用对应行情来源。黄金为 COMEX 黄金期货，原油为 WTI 期货。</InfoTip></div>
      </div>
      {mapError && <p role="alert" className="px-6 text-xs text-red-600">地图加载失败，请刷新重试</p>}
      {dataError && <p role="alert" className="px-6 pb-2 text-xs text-red-600 dark:text-red-400">{dataError}</p>}
      {providerWarning && <p role="status" className="px-6 pb-2 text-xs text-amber-700 dark:text-amber-400">部分同花顺指数未更新，已尝试备用来源。</p>}
      {indicators.length > 0 && (
        <div className="px-6 pb-3 flex gap-4 shrink-0 flex-wrap">
          {indicators.map(ind => {
            const valid = hasPrice(ind)
            const changeKnown = valid && numeric(ind.change)
            const cls = changeKnown ? (Number(ind.change) >= 0 ? positiveClass : negativeClass) : 'text-slate-400'
            return (
              <div key={ind.symbol} className="relative flex-1 min-w-[145px] rounded-xl border border-slate-200 hover:bg-slate-50 transition-colors">
                <a href={`${import.meta.env.BASE_URL}stock?symbol=${encodeURIComponent(ind.symbol)}`} className="block px-4 py-3 no-underline">
                  <div className="text-[11px] text-slate-400 font-medium mb-0.5 pr-5">{ind.name}</div>
                  <div className="text-base font-bold text-slate-900 tabular-nums"><QuoteNumber value={valid ? Number(ind.price) : null} format={value => fmtNum(value, 2, lang)} /></div>
                  <div className="flex items-baseline gap-2 mt-0.5">
                    <span className={`text-xs font-medium tabular-nums ${cls}`}><QuoteNumber value={valid ? ind.change : null} signed format={value => fmtNum(value, 2, lang)} /></span>
                    <span className={`text-xs font-medium tabular-nums ${cls}`}><QuoteNumber value={valid ? ind.changePct : null} signed suffix="%" format={value => fmtNum(value, 2, lang)} /></span>
                  </div>
                </a>
                {valid && needsWarning(ind) && <span className="absolute right-2 top-2"><InfoTip warning label={`${ind.name}报价异常`}>{quoteDescription(ind)}</InfoTip></span>}
              </div>
            )
          })}
        </div>
      )}
      <div className="flex-1 min-h-0 px-2 pb-2 overflow-hidden">
        <div ref={chartRef} role="img" aria-label={t.market.title} className="market-map w-full h-full" style={{ overflow: 'hidden' }} />
      </div>
    </div>
  )
}
