import InfoTip from '@/components/InfoTip'
export default function QuoteWarning({ live, timestamp, source }: { live?: boolean; timestamp?: string | null; source?: string | null }) {
  if (live) return null
  if (source === 'cost') return <InfoTip warning label="成本估值">暂时无法获取报价，当前按持仓成本估值，不能代表市场价格。</InfoTip>
  return <InfoTip warning label="非实时报价">{`当前价格不是已确认的实时报价${timestamp ? `，行情时间：${timestamp}` : ''}。可能为收盘价、保留报价或估算值。`}</InfoTip>
}
