import { useAsyncResource } from '@/hooks/use-async-resource'
import { useAuth } from '@/hooks/use-auth'
import { useEffect, useState, useCallback } from 'react'

import { BASE, analyzePortfolio } from '@/services/api'

import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { BarChart2, RefreshCw } from 'lucide-react'

import { displaySymbol } from '@/lib/format'

import type {
  PortfolioAnalysisResult,
  PortfolioAnalysisHolding,
  PortfolioGroupExposure,
  HoldingsResponse,
} from '@/types'

const GROUP_COLORS: Record<string, string> = {
  价值: '#3b82f6',
  成长: '#22c55e',
  动量: '#f59e0b',
  质量: '#8b5cf6',
  技术: '#06b6d4',
  事件: '#f97316',
  资金: '#14b8a6',
  情绪: '#ec4899',
  风控: '#ef4444',
  其他: '#94a3b8',
}

export function RiskSection() {
  const { portfolioId } = useAuth()
  return <RiskSectionContent key={portfolioId} />
}

function RiskSectionContent() {
  const { portfolioId } = useAuth()
  const cacheKey = `investory_preloaded_analysis:${portfolioId}`
  const [preloaded] = useState<PortfolioAnalysisResult | null>(() => {
    try {
      const value = JSON.parse(
        sessionStorage.getItem(cacheKey) || 'null',
      ) as PortfolioAnalysisResult | null
      return value?.portfolio_score != null ? value : null
    } catch {
      return null
    }
  })
  const [revision, setRevision] = useState(0)
  const [progress, setProgress] = useState('加载持仓...')

  const loadAnalysis = useCallback(
    async (signal: AbortSignal): Promise<PortfolioAnalysisResult> => {
      const response = await fetch(`${BASE}/api/holdings`, { credentials: 'include', signal })
      if (!response.ok) throw new Error(`HTTP ${response.status}`)
      const holdings = ((await response.json()) as HoldingsResponse).snapshots || []
      if (holdings.length === 0) return { _error: 'no holdings' }
      const total = holdings.reduce(
        (sum, holding) => sum + (holding.marketValue ?? holding.totalInvested ?? 0),
        0,
      )
      if (signal.aborted) throw new DOMException('Cancelled', 'AbortError')
      setProgress('分析中...')
      const result = await analyzePortfolio(
        holdings.map((holding) => ({
          symbol: holding.stockSymbol,
          name: holding.stockName || holding.stockSymbol,
          weight:
            total > 0
              ? ((holding.marketValue ?? holding.totalInvested ?? 0) / total) * 100
              : 100 / holdings.length,
        })),
        signal,
      )
      return result.error ? { _error: result.error } : result
    },
    [],
  )
  const resource = useAsyncResource(
    portfolioId && (!preloaded || revision > 0) ? `${portfolioId}:${revision}` : null,
    loadAnalysis,
  )
  const data = resource.error ? { _error: resource.error.message } : (resource.data ?? preloaded)
  const loading = resource.loading

  useEffect(() => {
    if (preloaded) sessionStorage.removeItem(cacheKey)
  }, [cacheKey, preloaded])

  // Derived stats
  const topHoldings: PortfolioAnalysisHolding[] = data?.top_holdings || []
  const bottomHoldings: PortfolioAnalysisHolding[] = data?.bottom_holdings || []
  const groupExposure: Record<string, PortfolioGroupExposure> = data?.group_exposure || {}
  const portfolioScore = data?.portfolio_score ?? 0

  // Normalize group bars: find max buy_score, scale all relative to it
  const maxGroupBuy = Math.max(1, ...Object.values(groupExposure).map((g) => g.buy_score || 0))

  return (
    <>
      <div className="flex items-center gap-3">
        <button
          onClick={() => {
            setProgress('加载持仓...')
            setRevision((value) => value + 1)
          }}
          disabled={loading}
          className="inline-flex items-center gap-1.5 h-9 px-4 rounded-xl bg-slate-900 text-white text-xs font-medium hover:bg-slate-800 transition-colors disabled:opacity-60"
        >
          <RefreshCw className={`w-3.5 h-3.5 ${loading ? 'animate-spin' : ''}`} />
          {loading ? '分析中...' : '刷新因子分析'}
        </button>
      </div>

      {loading && (
        <div className="flex flex-col items-center justify-center h-48 gap-2">
          <div className="w-6 h-6 border-2 border-slate-300 border-t-slate-900 rounded-full animate-spin" />
          <span className="text-xs text-slate-500">正在调用多因子引擎分析持仓...</span>
          {progress && <span className="text-xs text-slate-400 font-mono">{progress}</span>}
        </div>
      )}

      {!loading && data?._error && (
        <Card>
          <CardContent className="py-12 text-center">
            <BarChart2 className="w-8 h-8 text-slate-300 mx-auto mb-2" />
            <p className="text-sm text-slate-500">
              {data._error === 'no holdings' ? '暂无持仓数据' : data._error}
            </p>
          </CardContent>
        </Card>
      )}

      {!loading && data && !data._error && (
        <>
          {/* Summary card */}
          <Card>
            <CardContent className="py-4">
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-xs text-slate-500">组合因子评分</p>
                  <p
                    className={`text-lg font-bold ${portfolioScore >= 60 ? 'text-emerald-600' : portfolioScore >= 40 ? 'text-amber-600' : 'text-red-500'}`}
                  >
                    {portfolioScore.toFixed(1)} / 100
                  </p>
                </div>
                <div className="flex items-center gap-6 text-xs text-slate-500">
                  <div className="text-center">
                    <p className="text-2xl font-bold text-slate-900">
                      {data.holdings_scored ?? data.holdings_total}
                    </p>
                    <p>持仓数</p>
                  </div>
                  <div className="text-center">
                    <p
                      className={`text-2xl font-bold ${portfolioScore >= 60 ? 'text-emerald-600' : portfolioScore >= 40 ? 'text-amber-600' : 'text-red-500'}`}
                    >
                      {portfolioScore.toFixed(1)}
                    </p>
                    <p>加权均分</p>
                  </div>
                  <div className="text-center">
                    <p className="text-2xl font-bold text-slate-900">
                      {(data.all_holdings || []).filter((h) => !h.error).length}
                    </p>
                    <p>已评分</p>
                  </div>
                </div>
              </div>
            </CardContent>
          </Card>

          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {/* Factor group exposure */}
            <Card>
              <CardHeader>
                <CardTitle className="text-sm">因子组暴露</CardTitle>
              </CardHeader>
              <CardContent className="space-y-2">
                {Object.entries(groupExposure).map(([group, gdata]) => (
                  <div key={group} className="flex items-center gap-2">
                    <span
                      className="w-2.5 h-2.5 rounded-full"
                      style={{ backgroundColor: GROUP_COLORS[group] || '#94a3b8' }}
                    />
                    <span className="text-xs text-slate-600 w-10">{group}</span>
                    <div className="flex-1 bg-slate-100 rounded-full h-2">
                      <div
                        className="h-full rounded-full bg-emerald-400"
                        style={{
                          width: `${Math.min(((gdata.buy_score ?? 0) / maxGroupBuy) * 100, 100)}%`,
                        }}
                      />
                    </div>
                    <span className="text-xs font-medium text-emerald-600 w-8 text-right">
                      {gdata.buy_score?.toFixed(1)}
                    </span>
                  </div>
                ))}
              </CardContent>
            </Card>

            {/* Top & Bottom holdings */}
            <Card>
              <CardHeader>
                <CardTitle className="text-sm">评分排名</CardTitle>
              </CardHeader>
              <CardContent className="space-y-1.5">
                <p className="text-xs text-slate-400 mb-1">🟢 评分最高</p>
                {topHoldings.map((h) => (
                  <div key={h.symbol || h.code} className="flex items-center gap-2 text-xs">
                    <span className="font-medium text-slate-900 w-16">
                      {displaySymbol(h.symbol || h.code || '', h.market || '')}
                    </span>
                    <span className="text-slate-500 truncate flex-1">{h.name}</span>
                    <span
                      className={`font-medium ${(h.total_score ?? 0) >= 60 ? 'text-emerald-600' : 'text-amber-600'}`}
                    >
                      {(h.total_score ?? 0).toFixed(0)}分
                    </span>
                  </div>
                ))}
                {topHoldings.length === 0 && <p className="text-xs text-slate-400">—</p>}
                <p className="text-xs text-slate-400 mb-1 mt-2">🔴 评分最低</p>
                {bottomHoldings.map((h) => (
                  <div key={h.symbol || h.code} className="flex items-center gap-2 text-xs">
                    <span className="font-medium text-slate-900 w-16">
                      {displaySymbol(h.symbol || h.code || '', h.market || '')}
                    </span>
                    <span className="text-slate-500 truncate flex-1">{h.name}</span>
                    <span className="font-medium text-red-500">
                      {(h.total_score ?? 0).toFixed(0)}分
                    </span>
                  </div>
                ))}
              </CardContent>
            </Card>
          </div>

          {/* All holdings detail */}
          <Card>
            <CardHeader>
              <CardTitle className="text-sm">持仓因子明细</CardTitle>
            </CardHeader>
            <CardContent>
              <div className="overflow-x-auto">
                <table className="w-full text-xs">
                  <thead>
                    <tr className="border-b border-slate-100 text-slate-500">
                      <th className="text-left py-1.5 font-medium">代码</th>
                      <th className="text-left py-1.5 font-medium">名称</th>
                      <th className="text-right py-1.5 font-medium">权重</th>
                      <th className="text-right py-1.5 font-medium">综合分</th>
                    </tr>
                  </thead>
                  <tbody>
                    {(data.all_holdings || []).map((h) => (
                      <tr key={h.symbol || h.code} className="border-b border-slate-50">
                        <td className="py-1.5 font-medium text-slate-900">
                          {displaySymbol(h.symbol || h.code || '', h.market || '')}
                        </td>
                        <td className="py-1.5 text-slate-600">{h.name}</td>
                        <td className="py-1.5 text-right text-slate-500">
                          {(h.weight ?? 0).toFixed(1)}%
                        </td>
                        <td
                          className={`py-1.5 text-right font-bold ${(h.total_score ?? 0) >= 60 ? 'text-emerald-600' : (h.total_score ?? 0) >= 40 ? 'text-amber-600' : 'text-red-500'}`}
                        >
                          {(h.total_score ?? 0).toFixed(1)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </CardContent>
          </Card>
        </>
      )}
    </>
  )
}
