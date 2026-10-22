import { useReducedMotion } from 'framer-motion'
import { useCountUp } from '@/hooks/use-count-up'

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

/** Animate updates only; missing values never enter the numeric animation. */
export default function QuoteNumber({ value, format = decimal, signed = false, suffix = '' }: Props) {
  const reducedMotion = useReducedMotion()
  if (value == null || !Number.isFinite(value)) return <>—</>
  const display = (number: number) => `${signed ? (number >= 0 ? '+' : '-') : ''}${format(signed ? Math.abs(number) : number)}${suffix}`
  return <span aria-label={display(value)}>{reducedMotion ? display(value) : <TransitionValue value={value} format={display} />}</span>
}
