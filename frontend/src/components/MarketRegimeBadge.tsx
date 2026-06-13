import { cn } from '@/lib/utils'

interface Props {
  regime: string
  className?: string
}

const REGIME_CONFIG: Record<string, { label: string; bg: string; text: string }> = {
  NORMAL:       { label: '正常',  bg: 'bg-emerald-100',  text: 'text-emerald-700' },
  CAUTION:      { label: '谨慎',  bg: 'bg-amber-100',    text: 'text-amber-700' },
  CRISIS:       { label: '危机',  bg: 'bg-red-100',      text: 'text-red-700' },
  BULL:         { label: '牛市',  bg: 'bg-green-100',    text: 'text-green-700' },
  EXTREME_BULL: { label: '极端牛', bg: 'bg-yellow-100',  text: 'text-yellow-800' },
  BEAR:         { label: '熊市',  bg: 'bg-rose-100',     text: 'text-rose-700' },
}

export default function MarketRegimeBadge({ regime, className }: Props) {
  const cfg = REGIME_CONFIG[regime] ?? { label: regime || '未知', bg: 'bg-slate-100', text: 'text-slate-600' }
  return (
    <span className={cn('inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium', cfg.bg, cfg.text, className)}>
      {cfg.label}
    </span>
  )
}
