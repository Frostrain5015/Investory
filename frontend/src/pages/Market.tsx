import { useEffect, useState, useRef, useMemo } from 'react'
import * as echarts from 'echarts'
import { useSettings } from '@/hooks/use-settings'

interface IndexData { name: string; flag: string; lat: number; lng: number; price: number; change: number; changePct: number }

const LEADING = ['上证指数', '恒生指数', '标普500']

export default function Market() {
  const { positiveHex, negativeHex } = useSettings()
  const [indices, setIndices] = useState<IndexData[]>([])
  const [loading, setLoading] = useState(true)
  const chartRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    fetch('/investory/api/market/indices', { credentials: 'include' })
      .then(r => r.json()).then(setIndices)
      .finally(() => setLoading(false))
  }, [])

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
                  const u = Number(d.change) >= 0
                  return `<div style="display:flex;align-items:center;gap:12px;padding:3px 0;font-size:12px">
                    <span style="min-width:56px;color:#475569">${d.name}</span>
                    <span style="font-weight:600;min-width:85px;text-align:right;color:${u ? positiveHex : negativeHex}">${Number(d.price).toLocaleString()}</span>
                    <span style="min-width:95px;text-align:right;color:${u ? positiveHex : negativeHex}">${u ? '+' : ''}${Number(d.change).toFixed(2)} (${u ? '+' : ''}${Number(d.changePct).toFixed(2)}%)</span>
                  </div>`
                }).join('')}`
            },
          },
          geo: {
            map: 'world', roam: false, zoom: 1.25, center: [18, 25], aspectScale: 0.85,
            itemStyle: { areaColor: '#f1f5f9', borderColor: '#cbd5e1', borderWidth: 0.5 },
            emphasis: { itemStyle: { areaColor: '#e2e8f0' }, label: { show: false } },
          },
          series: [{
            type: 'scatter', coordinateSystem: 'geo',
            data: markers.map(group => {
              const leader = group.find(d => LEADING.includes(d.name)) || group[0]
              const up = Number(leader.change) >= 0
              return {
                value: [group[0].lng, group[0].lat],
                itemStyle: {
                  color: up ? positiveHex : negativeHex,
                  shadowBlur: 12,
                  shadowColor: (up ? positiveHex : negativeHex) + '80',
                },
              }
            }),
            symbol: 'circle', symbolSize: 18,
            emphasis: { scale: 1.6, itemStyle: { shadowBlur: 24 } },
            label: {
              show: true, position: 'top', distance: 10,
              formatter: (params: any) => {
                const f = markers[params.dataIndex]?.[0]?.flag?.toLowerCase()
                const labels: Record<string, string> = { cn: '中国', hk: '中国香港', us: '美国', jp: '日本', kr: '韩国', gb: '英国', de: '德国', fr: '法国', tw: '中国台湾', sg: '新加坡', in: '印度', au: '澳大利亚', ca: '加拿大', br: '巴西' }
                return labels[f] || ''
              },
              fontSize: 11, fontWeight: 700, color: '#475569',
            },
          }],
        })
      })

    return () => chart.dispose()
  }, [markers, positiveHex, negativeHex])

  if (loading) return <div className="flex items-center justify-center h-screen">
    <div className="w-8 h-8 border-2 border-slate-300 border-t-slate-900 rounded-full animate-spin" />
  </div>

  return (
    <div className="h-full flex flex-col">
      <div className="px-6 pt-6 pb-2 flex items-center justify-between shrink-0">
        <h2 className="text-xl font-bold text-slate-900 tracking-tight">大盘指数</h2>
        <span className="text-[10px] text-slate-400">数据来源：Sina / Yahoo Finance</span>
      </div>
      <div className="flex-1 min-h-0 px-2 pb-2">
        <div ref={chartRef} className="w-full h-full" />
      </div>
    </div>
  )
}
