import { useEffect, useRef, useState } from 'react'

/** Poll only the mounted, foreground page; never overlap slow refreshes. */
export function useTimedRefresh(fn: () => void | Promise<unknown>, scope = ''): [Date | null, () => void] {
  const fnRef = useRef(fn)
  useEffect(() => { fnRef.current = fn }, [fn])
  const [lastRefresh, setLastRefresh] = useState<Date | null>(null)
  const markRefreshed = () => setLastRefresh(new Date())
  useEffect(() => {
    let pending = false
    let disposed = false
    let lastAttempt = Date.now()
    let timer: ReturnType<typeof setInterval> | undefined
    async function refresh() {
      if (disposed || pending || document.visibilityState === 'hidden') return
      pending = true
      lastAttempt = Date.now()
      try { await fnRef.current(); if (!disposed) setLastRefresh(new Date()) }
      catch { /* Keep the last result; the page owns its error presentation. */ }
      finally { pending = false }
    }
    function start() { if (timer) clearInterval(timer); timer = setInterval(() => { void refresh() }, 30_000) }
    function visibility() {
      if (document.visibilityState === 'hidden') { if (timer) clearInterval(timer); timer = undefined }
      else { if (Date.now() - lastAttempt >= 30_000) void refresh(); start() }
    }
    if (document.visibilityState !== 'hidden') start()
    document.addEventListener('visibilitychange', visibility)
    return () => { disposed = true; if (timer) clearInterval(timer); document.removeEventListener('visibilitychange', visibility) }
  }, [scope])
  return [lastRefresh, markRefreshed]
}

export function timeAgo(date: Date | null): string {
  if (!date) return ''
  const s = Math.floor((Date.now() - date.getTime()) / 1000)
  if (s < 60) return '刚刚'
  if (s < 3600) return `${Math.floor(s / 60)} 分钟前`
  return `${Math.floor(s / 3600)} 小时前`
}
