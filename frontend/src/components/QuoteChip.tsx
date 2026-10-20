// 行情芯片：把 `名称|现价|±x%` token 渲染成"完整标的名称 现价（涨跌幅）"，
// 涨跌幅按用户涨跌配色上色，整体可点击跳转到该标的行情详情页。
// 解析不到 symbol（非真实标的，如"同比-15%"）时降级为纯文本，绝不上色/加链接。

import { useEffect, useState } from 'react'
import { BASE } from '@/services/api'
import { useSettings } from '@/hooks/use-settings'
import { resolveQuote, type QuoteToken, type ResolvedQuote } from '@/lib/quoteLinks'

export default function QuoteChip({ name, price, chg, symbol: symbolHint, original }: QuoteToken) {
  const { positiveClass, negativeClass } = useSettings()
  const [resolution, setResolution] = useState<{ name: string; quote: ResolvedQuote | null }>()
  const resolved = symbolHint
    ? { symbol: symbolHint, name }
    : resolution?.name === name ? resolution.quote : undefined

  useEffect(() => {
    if (symbolHint) return
    let alive = true
    resolveQuote(name).then(quote => { if (alive) setResolution({ name, quote }) })
    return () => { alive = false }
  }, [name, symbolHint])

  // 解析中 / 解析不到 → 原样回显（散文来源用原始文本不改写；模型 token 用规范回显），不上色不加链接
  if (!resolved) {
    return <span>{original ?? `${name}${price ? ' ' + price : ''}${chg ? `（${chg}）` : ''}`}</span>
  }

  const down = !!chg && chg.trim().startsWith('-')
  const colorClass = chg ? (down ? negativeClass : positiveClass) : 'text-slate-700'
  const dispName = resolved.name || name
  const showPrice = price ?? resolved.price
  return (
    <a
      href={`${BASE}/stock?symbol=${encodeURIComponent(resolved.symbol)}`}
      target="_blank"
      rel="noreferrer"
      title={`${dispName} 行情详情`}
      className={`font-medium tabular-nums underline decoration-dotted decoration-slate-300 underline-offset-2 hover:decoration-current ${colorClass}`}
    >
      {dispName}{showPrice ? ` ${showPrice}` : ''}{chg ? `（${chg}）` : ''}
    </a>
  )
}
