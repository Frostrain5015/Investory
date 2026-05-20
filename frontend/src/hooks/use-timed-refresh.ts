import { useEffect, useRef, useState } from 'react'

export function useTimedRefresh(fn: () => void): [Date | null, () => void] {
  const fnRef = useRef(fn)
  fnRef.current = fn
  const [lastRefresh, setLastRefresh] = useState<Date | null>(null)

  const markRefreshed = () => setLastRefresh(new Date())

  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | null = null
    let interval: ReturnType<typeof setInterval> | null = null

    function schedule() {
      const now = new Date()
      const m = now.getMinutes()
      const s = now.getSeconds()
      const ms = now.getMilliseconds()
      const mod = m % 5
      const secondsToNext = (4 - mod) * 60 + (60 - s) - ms / 1000
      const delayMs = Math.max(200, Math.round(secondsToNext * 1000))

      timer = setTimeout(() => {
        fnRef.current()
        setLastRefresh(new Date())
        interval = setInterval(() => { fnRef.current(); setLastRefresh(new Date()) }, 5 * 60 * 1000)
      }, delayMs)
    }

    schedule()

    return () => {
      if (timer) clearTimeout(timer)
      if (interval) clearInterval(interval)
    }
  }, [])

  return [lastRefresh, markRefreshed]
}

export function timeAgo(date: Date | null): string {
  if (!date) return ''
  const s = Math.floor((Date.now() - date.getTime()) / 1000)
  if (s < 60) return '刚刚'
  if (s < 3600) return `${Math.floor(s / 60)} 分钟前`
  return `${Math.floor(s / 3600)} 小时前`
}
