import { useReducedMotion } from 'framer-motion'
import { useCountUp } from '@/hooks/use-count-up'
import DataReveal from '@/components/DataReveal'

interface Props {
  value: number | null | undefined
  format?: (value: number) => string
  signed?: boolean
  suffix?: string
}
const decimal = (value: number) => value.toFixed(2)

function TransitionValue({ value, format }: { value: number; format: (value: number) => string }) {
  const current = useCountUp(value, 320)
  return <span className="tabular-nums">{format(current)}</span>
}

/** Dissolve missing/available states; interpolate only real numeric updates. */
export default function QuoteNumber({ value, format = decimal, signed = false, suffix = '' }: Props) {
  const reducedMotion = useReducedMotion()
  const available = value != null && Number.isFinite(value)
  const display = (number: number) => `${signed ? (number >= 0 ? '+' : '-') : ''}${format(signed ? Math.abs(number) : number)}${suffix}`
  return <span aria-label={available ? display(value) : '—'}>
    <DataReveal contentKey={available ? 'available' : 'missing'}>
      {available ? (reducedMotion ? display(value) : <TransitionValue value={value} format={display} />) : '—'}
    </DataReveal>
  </span>
}
