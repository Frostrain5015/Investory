import InfoTip from '@/components/InfoTip'
export default function QuoteWarning({ live, timestamp }: { live?: boolean; timestamp?: string | null }) {
  if (live) return null
  return <InfoTip warning label="非实时报价">{`当前价格不是已确认的实时报价${timestamp ? `，行情时间：${timestamp}` : ''}。可能为收盘价、保留报价或估算值。`}</InfoTip>
}
