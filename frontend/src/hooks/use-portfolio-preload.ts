import { useEffect, useRef } from 'react'
import { useLocation } from 'react-router-dom'
import { useAuth } from './use-auth'
import { useNotificationBubble } from '@/components/NotificationBubble'
import { BASE } from '@/services/api'

/**
 * On login, trigger portfolio factor analysis in the background.
 * When complete, show a notification bubble if the user is not already
 * viewing the Research page.
 */
export function usePortfolioPreload() {
  const { authenticated, portfolioId } = useAuth()
  const location = useLocation()
  const bubble = useNotificationBubble()
  const triggered = useRef(false)

  useEffect(() => {
    if (!authenticated || !portfolioId || triggered.current) return
    triggered.current = true

    // Run analysis in background
    ;(async () => {
      try {
        // 1. Get holdings
        const holdRes = await fetch(`${BASE}/api/holdings`, { credentials: 'include' })
        const holdData = await holdRes.json()
        const snaps = holdData.snapshots || []
        if (snaps.length === 0) return

        // 2. Build payload
        const totalVal = snaps.reduce((s: number, h: any) => s + (h.marketValue ?? h.marketValueCny ?? h.totalInvested ?? 0), 0)
        const holdings = snaps.map((h: any) => ({
          symbol: h.stockSymbol,
          name: h.stockName || h.stockSymbol,
          weight: totalVal > 0 ? ((h.marketValue ?? h.marketValueCny ?? h.totalInvested ?? 0) / totalVal * 100) : (100 / snaps.length),
        }))

        // 3. Call analysis API
        const res = await fetch(`${BASE}/api/stocksage/portfolio-analysis`, {
          method: 'POST',
          credentials: 'include',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ holdings }),
        })
        const result = await res.json()
        if (result.error) return

        // 4. Show bubble if user is NOT already on research page
        if (!location.pathname.startsWith('/research')) {
          const score = result.portfolio_score ?? 0
          const emoji = score >= 60 ? '不错' : score >= 40 ? '还行' : '注意'
          const topName = result.top_holdings?.[0]?.name || '—'
          bubble.show({
            title: '观澜 · 风控简报',
            message: `今日组合评分 ${score.toFixed(0)} 分，${emoji}！评分最高的是 ${topName}。点击查看完整分析 →`,
            actionLabel: '查看风控报告',
            actionHref: '/research',
          })
        }

        // Store in sessionStorage so RiskSection can use it
        try { sessionStorage.setItem('investory_preloaded_analysis', JSON.stringify(result)) } catch {}
      } catch { /* silent background failure */ }
    })()
  }, [authenticated, portfolioId])
}
