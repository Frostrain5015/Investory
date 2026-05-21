import { useEffect, useState, useRef, useMemo, useCallback } from 'react'
import * as echarts from 'echarts'
import { useSettings } from '@/hooks/use-settings'
import { useTimedRefresh, timeAgo } from '@/hooks/use-timed-refresh'

interface IndexData { name: string; flag: string; lat: number; lng: number; price: number; change: number; changePct: number; symbol: string; fetchedAt?: string }

interface IndicatorData { name: string; symbol: string; price: number; change: number; changePct: number; fetchedAt?: string }

interface NewsItem { title: string; source: string; url: string; summary: string; category: string; score: number; country_code: string; published_at: string }

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

function fmtNum(n: number, dec: number): string {
  if (Math.abs(n) >= 10000) return (n / 10000).toFixed(1) + '万'
  return n.toFixed(dec)
}

export default function Market() {
  const { positiveHex, negativeHex, positiveClass, negativeClass } = useSettings()
  const [indices, setIndices] = useState<IndexData[]>([])
  const [indicators, setIndicators] = useState<IndicatorData[]>([])
  const [loading, setLoading] = useState(true)
  const [news, setNews] = useState<NewsItem[]>([])
  const chartRef = useRef<HTMLDivElement>(null)

  const loadIndices = useCallback(() => {
    fetch('/investory/api/market/indices', { credentials: 'include' })
      .then(r => r.json()).then(data => {
        setIndices(data.indices || [])
        setIndicators(data.indicators || [])
      })
      .finally(() => setLoading(false))
    fetch('/investory/api/market/news', { credentials: 'include' })
      .then(r => r.json()).then(data => setNews(Array.isArray(data) ? data : []))
      .catch(() => {})
  }, [])

  useEffect(() => { loadIndices() }, [loadIndices])
  const [lastRefresh] = useTimedRefresh(loadIndices)

  // Country region coloring based on leading index change
  const countryRegions = useMemo(() => {
    if (indices.length === 0) return []
    const posRgb = hexToRgb(positiveHex)
    const negRgb = hexToRgb(negativeHex)

    // Find max abs changePct for intensity scaling (ignore zeros)
    const validPcts = indices.filter(d => Number(d.price) !== 0).map(d => Math.abs(Number(d.changePct)))
    const maxPct = Math.max(...validPcts, 0.01)

    return Object.entries(COUNTRY_MAP).flatMap(([flag, { lead, names }]) => {
      const leader = indices.find(d => d.flag === flag && d.name === lead)
        || indices.find(d => d.flag === flag)
      if (!leader || Number(leader.price) === 0) return []
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
    if (!chartRef.current || markers.length === 0) return
    const chart = echarts.init(chartRef.current, null, { renderer: 'svg' })

    fetch('/investory/world.json')
      .then(r => r.json())
      .then(geoJson => {
        echarts.registerMap('world', geoJson)
        chart.setOption({
          backgroundColor: 'transparent',
          tooltip: {
            trigger: 'item',
            confine: true,
            backgroundColor: '#fff',
            borderColor: '#e2e8f0',
            borderWidth: 1,
            borderRadius: 16,
            padding: [14, 18],
            textStyle: { color: '#334155', fontSize: 13 },
            formatter: (params: any) => {
              const group = markers[params.dataIndex]
              if (!group) return ''
              const f = group[0].flag.toLowerCase()
              const nameMap: Record<string, string> = { cn: '中国', hk: '中国香港', us: '美国', jp: '日本', kr: '韩国', gb: '英国', de: '德国', fr: '法国', tw: '中国台湾', sg: '新加坡', in: '印度', au: '澳大利亚', ca: '加拿大', br: '巴西' }
              return `<div style="display:flex;align-items:center;gap:8px;margin-bottom:8px;font-weight:700;font-size:14px">
                <img src="https://flagcdn.com/${f}.svg" style="width:22px;height:15px;border-radius:2px"/>
                ${nameMap[group[0].flag.toLowerCase()] || group[0].flag}
              </div>${group.map(d => {
                  const valid = Number(d.price) !== 0
                  const u = Number(d.change) >= 0
                  const color = !valid ? '#9ca3af' : u ? positiveHex : negativeHex
                  const priceText = valid ? Number(d.price).toLocaleString() : '—'
                  const changeText = valid ? `${u ? '+' : ''}${Number(d.change).toFixed(2)} (${u ? '+' : ''}${Number(d.changePct).toFixed(2)}%)` : '暂无数据'
                  const tsText = d.fetchedAt ? new Date(d.fetchedAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false }) : ''
                  return `<div style="display:flex;align-items:center;gap:12px;padding:3px 0;font-size:12px">
                    <span style="min-width:56px;color:#475569">${d.name}</span>
                    <span style="font-weight:600;min-width:85px;text-align:right;color:${color}">${priceText}</span>
                    <span style="min-width:95px;text-align:right;color:${color}">${changeText}</span>
                    ${tsText ? `<span style="color:#94a3b8;font-size:10px;min-width:34px">${tsText}</span>` : ''}
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
              type: 'scatter', coordinateSystem: 'geo',
              data: markers.map(group => {
                const leader = group.find(d => LEADING.includes(d.name)) || group[0]
                const valid = Number(leader.price) !== 0
                const up = Number(leader.change) >= 0
                const color = !valid ? '#9ca3af' : up ? positiveHex : negativeHex
                return {
                  value: [group[0].lng, group[0].lat],
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
              type: 'scatter', coordinateSystem: 'geo',
              data: newsPoints,
              symbol: 'pin', symbolSize: 22, symbolOffset: [18, 0], z: 10,
              emphasis: { scale: 1.3 },
              label: { show: false },
              tooltip: {
                confine: true,
                backgroundColor: '#fff', borderColor: '#e2e8f0',
                borderWidth: 1, borderRadius: 16, padding: [12, 16],
                textStyle: { color: '#334155', fontSize: 13 },
                formatter: (params: any) => {
                  const d = params.data
                  const cc = d.category === 'finance' ? '#6366f1' : '#f97316'
                  const cl = d.category === 'finance' ? '财经' : '地缘'
                  const flag = (d.country_code || '').toLowerCase()
                  const nameMap: Record<string, string> = { cn: '中国', hk: '中国香港', us: '美国', jp: '日本', kr: '韩国', gb: '英国', de: '德国', fr: '法国', tw: '中国台湾', sg: '新加坡', in: '印度', au: '澳大利亚', ca: '加拿大', br: '巴西', ua: '乌克兰', ru: '俄罗斯', ir: '伊朗', il: '以色列', tr: '土耳其', mx: '墨西哥', sa: '沙特阿拉伯' }
                  return `<div style="max-width:300px">
                    <div style="display:flex;align-items:center;gap:6px;margin-bottom:4px">
                      ${flag ? `<img src=\"https://flagcdn.com/${flag}.svg\" style=\"width:18px;height:12px;border-radius:2px\"/>` : ''}
                      <span style="background:${cc};color:#fff;font-size:10px;padding:2px 7px;border-radius:4px">${cl}</span>
                      <span style="font-size:10px;color:#64748b">${nameMap[d.country_code?.toLowerCase()] || d.country_code || ''}</span>
                    </div>
                    <div style="font-size:12px;font-weight:600;color:#1e293b;line-height:1.5;word-break:break-word;white-space:normal">${d.title}</div>
                    <div style="font-size:11px;color:#94a3b8;margin-top:4px">${d.source}</div>
                    <div style="font-size:10px;color:#3b82f6;margin-top:3px">点击查看原文 →</div>
                  </div>`
                },
              },
            },
          ],
        })

        // Click on country → navigate to leading index detail page; click on news pin → open URL
        chart.off('click')
        chart.on('click', (params: any) => {
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
            window.location.href = `/investory/stock?symbol=${encodeURIComponent(leader.symbol)}`
          }
        })
      })

    return () => chart.dispose()
  }, [markers, countryRegions, positiveHex, negativeHex, newsPoints])

  if (loading) return <div className="flex items-center justify-center h-screen">
    <div className="w-8 h-8 border-2 border-slate-300 border-t-slate-900 rounded-full animate-spin" />
  </div>

  return (
    <div className="h-full flex flex-col">
      <div className="px-6 pt-6 pb-1 flex items-center justify-between shrink-0">
        <div className="flex items-center gap-3">
          <h2 className="text-xl font-bold text-slate-900 tracking-tight">全球市场</h2>
          {lastRefresh && <span className="text-[10px] text-slate-400">{timeAgo(lastRefresh)}</span>}
        </div>
        <span className="text-[10px] text-slate-400">数据来源：Sina / Yahoo Finance</span>
      </div>
      {indicators.length > 0 && (
        <div className="px-6 pb-3 flex gap-4 shrink-0">
          {indicators.map(ind => {
            const valid = ind.price != null && Number(ind.price) !== 0
            const up = Number(ind.change) >= 0
            const cls = valid ? (up ? positiveClass : negativeClass) : 'text-slate-400'
            const priceText = valid ? fmtNum(Number(ind.price), 2) : '—'
            const chgText = valid ? `${up ? '+' : ''}${fmtNum(Math.abs(Number(ind.change)), 2)}` : '—'
            const pctText = valid ? `${up ? '+' : ''}${Number(ind.changePct).toFixed(2)}%` : '—'
            return (
              <a key={ind.symbol} href={`/investory/stock?symbol=${encodeURIComponent(ind.symbol)}`}
                className="flex-1 rounded-xl border border-slate-200 px-4 py-3 hover:bg-slate-50 transition-colors no-underline">
                <div className="text-[11px] text-slate-400 font-medium mb-0.5">{ind.name}</div>
                <div className="text-base font-bold text-slate-900 tabular-nums">{priceText}</div>
                <div className="flex items-baseline gap-2 mt-0.5">
                  <span className={`text-xs font-medium tabular-nums ${cls}`}>{chgText}</span>
                  <span className={`text-xs font-medium tabular-nums ${cls}`}>{pctText}</span>
                  {ind.fetchedAt && <span className="text-[10px] text-slate-400 ml-auto">{new Date(ind.fetchedAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })}</span>}
                </div>
              </a>
            )
          })}
        </div>
      )}
      <div className="flex-1 min-h-0 px-2 pb-2 overflow-hidden">
        <div ref={chartRef} className="w-full h-full" style={{ overflow: 'hidden' }} />
      </div>
    </div>
  )
}
