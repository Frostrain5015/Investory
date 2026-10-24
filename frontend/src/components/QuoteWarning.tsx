import InfoTip from '@/components/InfoTip'
import { quoteDescription, quoteNeedsWarning, type QuoteContext } from '@/lib/quotePresentation'

export default function QuoteWarning(quote: QuoteContext) {
  if (!quoteNeedsWarning(quote)) return null
  return <InfoTip warning label="报价异常">{quoteDescription(quote)}</InfoTip>
}
