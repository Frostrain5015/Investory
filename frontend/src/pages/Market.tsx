import { useEffect, useState, useRef } from 'react'
import * as echarts from 'echarts'

interface IndexData {
  name: string; flag: string; lat: number; lng: number
  price: number; change: number; changePct: number
}

export default function Market() {
  const [indices, setIndices] = useState<IndexData[]>([])
  const [loading, setLoading] = useState(true)
  const chartRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    fetch('/investory/api/market/indices', { credentials: 'include' })
      .then(r => r.json()).then(setIndices)
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    if (!chartRef.current || indices.length === 0) return
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
            borderRadius: 12,
            padding: [10, 14],
            textStyle: { color: '#334155', fontSize: 12 },
            formatter: (params: any) => {
              if (params.seriesType === 'scatter' && params.data) {
                const d = indices[params.dataIndex]
                const up = Number(d.change) >= 0
                return `<div style="font-weight:600;font-size:13px;margin-bottom:4px">${d.name}</div>
                  <div style="font-size:20px;font-weight:700;color:${up ? '#dc2626' : '#059669'}">${Number(d.price).toLocaleString()}</div>
                  <div style="font-size:12px;color:${up ? '#ef4444' : '#10b981'};margin-top:2px">
                    ${up ? '+' : ''}${Number(d.change).toFixed(2)} (${up ? '+' : ''}${Number(d.changePct).toFixed(2)}%)</div>`
              }
              return params.name
            },
          },
          geo: {
            map: 'world',
            roam: false,
            zoom: 1.25,
            center: [18, 25],
            aspectScale: 0.85,
            itemStyle: { areaColor: '#f1f5f9', borderColor: '#cbd5e1', borderWidth: 0.5 },
            emphasis: { itemStyle: { areaColor: '#e2e8f0' }, label: { show: false } },
          },
          series: [{
            type: 'scatter',
            coordinateSystem: 'geo',
            data: indices.map(d => ({
              name: d.name,
              value: [d.lng, d.lat],
              itemStyle: {
                color: Number(d.change) >= 0 ? '#ef4444' : '#10b981',
                shadowBlur: 10,
                shadowColor: Number(d.change) >= 0 ? 'rgba(239,68,68,0.5)' : 'rgba(16,185,129,0.5)',
              },
            })),
            symbol: 'circle',
            symbolSize: 16,
            emphasis: { scale: 1.8, itemStyle: { shadowBlur: 20 } },
            label: {
              show: true,
              formatter: '{b}',
              position: 'right',
              distance: 8,
              fontSize: 10,
              fontWeight: 600,
              color: '#475569',
            },
          }],
        })
      })

    return () => chart.dispose()
  }, [indices])

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
