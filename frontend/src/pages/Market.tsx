import { useEffect, useState } from 'react'
import { Card, CardContent } from '@/components/ui/card'
import { useSettings } from '@/hooks/use-settings'

interface IndexData {
  name: string; flag: string; lat: number; lng: number
  price: number; change: number; changePct: number
}

const FLAG_EMOJI: Record<string, string> = { CN: '🇨🇳', HK: '🇭🇰', US: '🇺🇸', UK: '🇬🇧' }

function mapX(lng: number) { return ((lng + 180) / 360 * 100).toFixed(1) + '%' }
function mapY(lat: number) { return ((90 - lat) / 180 * 100).toFixed(1) + '%' }

export default function Market() {
  const { positiveClass, negativeClass } = useSettings()
  const [indices, setIndices] = useState<IndexData[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    fetch('/investory/api/market/indices', { credentials: 'include' })
      .then(r => r.json()).then(setIndices)
      .finally(() => setLoading(false))
  }, [])

  if (loading) {
    return <div className="flex items-center justify-center h-96">
      <div className="w-8 h-8 border-2 border-slate-300 border-t-slate-900 rounded-full animate-spin" />
    </div>
  }

  return (
    <div className="p-6 space-y-6">
      <h2 className="text-xl font-bold text-slate-900 tracking-tight">大盘指数</h2>

      {/* World map container */}
      <Card>
        <CardContent className="relative h-[360px] overflow-hidden bg-gradient-to-b from-slate-50 to-blue-50/30 rounded-xl">
          {/* Simplified map grid lines */}
          <svg className="absolute inset-0 w-full h-full opacity-10" viewBox="0 0 800 400">
            {[0, 90, 180, 270, 360].map(x => <line key={`v${x}`} x1={x/360*800} y1={0} x2={x/360*800} y2={400} stroke="#475569" strokeWidth={0.5} />)}
            {[0, 30, 60, 90, 120, 150].map(y => <line key={`h${y}`} x1={0} y1={y/180*400} x2={800} y2={y/180*400} stroke="#475569" strokeWidth={0.5} />)}
          </svg>

          {/* Index markers */}
          {indices.map(idx => {
            const up = Number(idx.change) >= 0
            return (
              <div key={idx.name}
                className="absolute z-10 transform -translate-x-1/2 -translate-y-1/2"
                style={{ left: mapX(idx.lng), top: mapY(idx.lat) }}>
                <div className="bg-white border border-slate-200 rounded-xl px-3 py-2 shadow-lg hover:shadow-xl hover:scale-110 transition-all duration-200 cursor-default min-w-[120px]">
                  <div className="flex items-center gap-1.5 mb-1">
                    <span className="text-base leading-none">{FLAG_EMOJI[idx.flag] || '📍'}</span>
                    <span className="text-[11px] font-medium text-slate-600 truncate">{idx.name}</span>
                  </div>
                  <div className="text-sm font-bold tabular-nums text-slate-900">{Number(idx.price).toLocaleString()}</div>
                  {Number(idx.change) !== 0 && (
                    <div className={`text-[11px] font-semibold tabular-nums ${up ? positiveClass : negativeClass}`}>
                      {up ? '+' : ''}{Number(idx.change).toFixed(2)} ({up ? '+' : ''}{Number(idx.changePct).toFixed(2)}%)
                    </div>
                  )}
                </div>
              </div>
            )
          })}

          {/* Legend */}
          <div className="absolute bottom-3 right-4 bg-white/80 rounded-lg px-2 py-1 text-[10px] text-slate-400">
            数据来源：Sina / Yahoo Finance
          </div>
        </CardContent>
      </Card>

      {/* Index list */}
      <div className="grid grid-cols-2 lg:grid-cols-3 gap-3">
        {indices.map(idx => {
          const up = Number(idx.change) >= 0
          return (
            <Card key={idx.name}>
              <CardContent className="pt-4 pb-4">
                <div className="flex items-center gap-2 mb-1.5">
                  <span className="text-lg">{FLAG_EMOJI[idx.flag] || '📍'}</span>
                  <span className="text-sm font-medium text-slate-700">{idx.name}</span>
                </div>
                <div className="text-xl font-bold tabular-nums text-slate-900">{Number(idx.price).toLocaleString()}</div>
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
