import { useEffect, useRef } from 'react'
import { useLocation } from 'react-router-dom'
import { useAuth } from './use-auth'
import { useNotificationBubble } from '@/components/NotificationBubble'
import { BASE } from '@/services/api'

/**
 * On login, trigger portfolio factor analysis in the background.
 * Shows an immediate "analysing" bubble, then updates with results
 * when complete. Stores results in sessionStorage for instant RiskSection load.
 */
export function usePortfolioPreload() {
  const { authenticated, portfolioId } = useAuth()
  const location = useLocation()
  const bubble = useNotificationBubble()
  const triggered = useRef(false)

  useEffect(() => {
    if (!authenticated || !portfolioId || triggered.current) return
    triggered.current = true

    // Show immediate feedback
    if (!location.pathname.startsWith('/research')) {
      bubble.show({
        title: '观澜 · 分析中',
        message: '正在调用多因子引擎分析你的持仓，稍后为你呈现风控简报…',
      })
    }

    ;(async () => {
      try {
        // 1. Get holdings
        const holdRes = await fetch(`${BASE}/api/holdings`, { credentials: 'include' })
        if (!holdRes.ok) { console.error('[preload] holdings fetch failed:', holdRes.status); return }
        const holdData = await holdRes.json()
        const snaps = holdData.snapshots || []
        if (snaps.length === 0) { console.log('[preload] no holdings, skipping'); return }

        // 2. Build payload
        const totalVal = snaps.reduce((s: number, h: any) => s + (h.marketValue ?? h.marketValueCny ?? h.totalInvested ?? 0), 0)
        const holdings = snaps.map((h: any) => ({
          symbol: h.stockSymbol,
          name: h.stockName || h.stockSymbol,
          weight: totalVal > 0 ? ((h.marketValue ?? h.marketValueCny ?? h.totalInvested ?? 0) / totalVal * 100) : (100 / snaps.length),
        }))

        // 3. Call analysis API with timeout
        const controller = new AbortController()
        const timeout = setTimeout(() => controller.abort(), 180000) // 3 min max
        const res = await fetch(`${BASE}/api/stocksage/portfolio-analysis`, {
          method: 'POST', credentials: 'include',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ holdings }),
          signal: controller.signal,
        })
        clearTimeout(timeout)
        console.log('[preload] analysis response:', res.status)

        if (!res.ok) { console.error('[preload] analysis failed:', res.status); return }
        const result = await res.json()
        if (result.error) { console.error('[preload] analysis error:', result.error); return }

        // 4. Store in sessionStorage
        try { sessionStorage.setItem('investory_preloaded_analysis', JSON.stringify(result)) } catch {}

        // 5. Show result bubble (only if user hasn't navigated to research)
        if (!location.pathname.startsWith('/research')) {
          const score = result.portfolio_score ?? 0
          const emoji = score >= 60 ? '不错' : score >= 40 ? '还行' : '注意'
          const topName = result.top_holdings?.[0]?.name || '—'
          bubble.show({
            title: '观澜 · 风控简报',
            message: `组合评分 ${score.toFixed(0)} 分，${emoji}！评分最高 ${topName}。点击查看完整分析 →`,
            actionLabel: '查看风控报告',
            actionHref: '/research',
          })
          // Dismiss the "analysing" bubble by replacing it (show replaces)
        }
      } catch (e: any) {
        if (e.name !== 'AbortError') console.error('[preload] failed:', e.message || e)
      }
    })()
  }, [authenticated, portfolioId])
}
