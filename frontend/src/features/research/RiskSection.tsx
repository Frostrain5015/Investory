import { useCallback } from 'react'
import { useAuth } from '@/hooks/use-auth'
import { useAsyncResource } from '@/hooks/use-async-resource'
import { useSettings } from '@/hooks/use-settings'
import { BASE } from '@/services/api'
import type { QuantData } from '@/types'
import AsyncState from '@/components/AsyncState'
import InfoTip from '@/components/InfoTip'
import QuoteNumber from '@/components/QuoteNumber'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'

/** Existing historical statistics; no factor engine or model call. */
export function RiskSection() {
  const { portfolioId } = useAuth()
  const { positiveClass, negativeClass } = useSettings()
  const loader = useCallback(async (signal: AbortSignal): Promise<QuantData> => {
    const response = await fetch(`${BASE}/api/quant/portfolio-scenario`, { credentials: 'include', signal })
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    return response.json() as Promise<QuantData>
  }, [])
  const resource = useAsyncResource(portfolioId ? String(portfolioId) : null, loader)
  const risk = resource.data?.risk
  const stats = [
    { label: '组合 Beta', value: risk?.weighted_beta, suffix: '', help: '现有持仓相对基准的加权波动敏感度。只显示实际计算结果，缺失时不补零。' },
    { label: '日收益下沿', value: risk?.var_95_pct, suffix: '%', help: '历史日收益的5%分位。组合净值样本不足时，现有计算程序使用持仓等权历史模拟。' },
    { label: '历史最大回撤', value: risk?.portfolio_maxdd, suffix: '%', help: '现有计算区间内组合净值从峰值回落的最大幅度。净值样本不足时不显示估算值。' },
  ]
  return <div className="space-y-6">
    <AsyncState loading={!resource.data && resource.loading} error={resource.error} retry={resource.reload} />
    {resource.data && <>
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4 data-enter">
        {stats.map(stat => <Card key={stat.label}><CardContent className="pt-5">
          <div className="flex items-center gap-1 text-xs text-slate-500">{stat.label}<InfoTip label={stat.label}>{stat.help}</InfoTip></div>
          <p className="text-2xl font-semibold text-slate-900 tabular-nums mt-1"><QuoteNumber value={stat.value} suffix={stat.suffix} /></p>
        </CardContent></Card>)}
      </div>
      <Card><CardHeader className="flex-row items-center gap-1">
        <CardTitle className="text-base">历史情景</CardTitle>
        <InfoTip label="历史情景">按现有计算记录展示持仓在历史情景中的收益估计；不生成多因子评分或推荐名单。</InfoTip>
      </CardHeader><CardContent>
        {(resource.data.scenarios ?? []).length === 0 ? <p className="py-8 text-center text-sm text-slate-400">暂无已计算的历史情景</p>
          : <div className="overflow-x-auto"><table className="w-full text-sm whitespace-nowrap">
            <thead><tr className="border-b border-slate-100 text-xs text-slate-500">
              <th className="text-left py-3 font-medium">情景</th><th className="text-left px-3 py-3 font-medium">区间</th><th className="text-right py-3 font-medium">收益估计</th>
            </tr></thead><tbody>{resource.data.scenarios.map(item => <tr key={item.scenario_key} className="border-b border-slate-50">
              <td className="py-3 text-slate-700">{item.scenario_name}</td><td className="px-3 text-slate-400 text-xs">{item.start_date} — {item.end_date}</td>
              <td className={`text-right tabular-nums ${item.total_pnl_pct == null ? 'text-slate-400' : item.total_pnl_pct >= 0 ? positiveClass : negativeClass}`}><QuoteNumber value={item.total_pnl_pct} signed suffix="%" /></td>
            </tr>)}</tbody>
          </table></div>}
      </CardContent></Card>
    </>}
  </div>
}
