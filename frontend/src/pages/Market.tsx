import { useEffect, useState, useRef } from 'react'
import * as echarts from 'echarts'
import { Card, CardContent } from '@/components/ui/card'
import { useSettings } from '@/hooks/use-settings'

interface IndexData {
  name: string; flag: string; lat: number; lng: number
  price: number; change: number; changePct: number
}

function flagUrl(code: string) { return `https://flagcdn.com/${code.toLowerCase()}.svg` }
const FLAG_CODE: Record<string, string> = { CN: 'cn', HK: 'hk', US: 'us' }

export default function Market() {
  const { positiveClass, negativeClass } = useSettings()
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
    const chart = echarts.init(chartRef.current)

    fetch('/investory/world.json')
      .then(r => r.json())
      .then(geoJson => {
        echarts.registerMap('world', geoJson)
        chart.setOption({
          backgroundColor: 'transparent',
          tooltip: {
            trigger: 'item',
            formatter: (params: any) => {
              if (params.seriesType === 'scatter') {
                const d = indices[params.dataIndex]
                return `<b>${d.name}</b><br/>${Number(d.price).toLocaleString()}<br/>${Number(d.change) >= 0 ? '+' : ''}${Number(d.change).toFixed(2)} (${Number(d.change) >= 0 ? '+' : ''}${Number(d.changePct).toFixed(2)}%)`
              }
              return params.name
            },
          },
          geo: {
            map: 'world',
            roam: false,
            itemStyle: { areaColor: '#e2e8f0', borderColor: '#cbd5e1' },
            emphasis: { itemStyle: { areaColor: '#cbd5e1' } },
          },
          series: [{
            type: 'scatter',
            coordinateSystem: 'geo',
            data: indices.map(d => ({
              name: d.name,
              value: [d.lng, d.lat, Number(d.price)],
              itemStyle: {
                color: Number(d.change) >= 0 ? '#ef4444' : '#10b981',
                shadowBlur: 8,
                shadowColor: Number(d.change) >= 0 ? 'rgba(239,68,68,0.4)' : 'rgba(16,185,129,0.4)',
              },
            })),
            symbolSize: (val: number[]) => Math.max(12, Math.min(28, Math.abs(val[2]) / 100 + 8)),
            encode: { tooltip: [2] },
            label: {
              show: true,
              formatter: '{b}',
              position: 'right',
              fontSize: 10,
              color: '#475569',
            },
            emphasis: {
              scale: 1.5,
            },
          }],
        })
      })

    return () => chart.dispose()
  }, [indices])

  if (loading) return <div className="flex items-center justify-center h-96">
    <div className="w-8 h-8 border-2 border-slate-300 border-t-slate-900 rounded-full animate-spin" />
  </div>

  return (
    <div className="p-6 space-y-6">
      <h2 className="text-xl font-bold text-slate-900 tracking-tight">大盘指数</h2>

      {/* World map */}
      <Card>
        <CardContent className="p-2">
          <div ref={chartRef} className="w-full h-[400px]" />
        </CardContent>
      </Card>

      {/* Index list */}
      <div className="grid grid-cols-2 lg:grid-cols-3 gap-3">
        {indices.map(idx => {
          const up = Number(idx.change) >= 0
          return (
            <Card key={idx.name} className={up ? 'bg-red-50/30 border-red-100' : 'bg-emerald-50/30 border-emerald-100'}>
              <CardContent className="pt-4 pb-4">
                <div className="flex items-center gap-2 mb-1.5">
                  <img src={flagUrl(FLAG_CODE[idx.flag] || idx.flag)} alt="" className="w-5 h-3.5 rounded-sm" />
                  <span className="text-sm font-medium text-slate-700">{idx.name}</span>
                </div>
                <div className={`text-xl font-bold tabular-nums ${up ? 'text-red-700' : 'text-emerald-700'}`}>
                  {Number(idx.price).toLocaleString()}
                </div>
                {Number(idx.change) !== 0 && (
                  <div className={`text-xs font-semibold mt-0.5 tabular-nums ${up ? positiveClass : negativeClass}`}>
                    {up ? '+' : ''}{Number(idx.change).toFixed(2)} ({up ? '+' : ''}{Number(idx.changePct).toFixed(2)}%)
                  </div>
                )}
              </CardContent>
            </Card>
          )
        })}
      </div>
    </div>
  )
}
