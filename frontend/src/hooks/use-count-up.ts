import { useState, useEffect, useRef } from 'react'

export function useCountUp(target: number, duration = 600): number {
  const [current, setCurrent] = useState(target)
  const startRef = useRef(target)
  const startTimeRef = useRef(0)
  const rafRef = useRef(0)

  useEffect(() => {
    if (target === current) return
    startRef.current = current
    startTimeRef.current = performance.now()
    cancelAnimationFrame(rafRef.current)

    const animate = (now: number) => {
      const elapsed = now - startTimeRef.current
      const progress = Math.min(elapsed / duration, 1)
      const eased = 1 - Math.pow(1 - progress, 3) // ease-out cubic
      setCurrent(startRef.current + (target - startRef.current) * eased)
      if (progress < 1) rafRef.current = requestAnimationFrame(animate)
    }

    rafRef.current = requestAnimationFrame(animate)
    return () => cancelAnimationFrame(rafRef.current)
  }, [target, duration])

  return current
}
