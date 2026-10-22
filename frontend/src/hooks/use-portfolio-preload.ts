import { useEffect, useEffectEvent } from 'react'
import { useLocation } from 'react-router-dom'
import { useAuth } from './use-auth'
import { useNotificationBubble } from '@/hooks/use-notification-bubble'
import { BASE } from '@/services/api'

interface HoldingSnapshot {
  stockSymbol?: string
  stockName?: string
  marketValue?: number
  marketValueCny?: number
  totalInvested?: number
}

interface HoldingsResponse {
  snapshots?: HoldingSnapshot[]
}

interface PortfolioAnalysisResult {
  error?: string
  portfolio_score?: number
  top_holdings?: { name?: string }[]
}

function holdingValue(h: HoldingSnapshot) {
  return h.marketValue ?? h.marketValueCny ?? h.totalInvested ?? 0
}

/**
 * On login, trigger portfolio factor analysis in the background.
 * The "analysing" bubble is shown only after holdings exist, and delayed so it
 * queues behind the greeting bubble. Results are cached for instant RiskSection load.
 */
export function usePortfolioPreload() {
  const { authenticated, portfolioId } = useAuth()
  const location = useLocation()
  const { show } = useNotificationBubble()
  const notifyOutsideResearch = useEffectEvent((notification: Parameters<typeof show>[0]) => {
    if (!location.pathname.startsWith('/research')) show(notification)
  })

  useEffect(() => {
    if (!authenticated || !portfolioId) return

    let cancelled = false
    let analysisDone = false
    let analysingBubbleTimer: ReturnType<typeof setTimeout> | null = null
    const controller = new AbortController()
    let timeout: ReturnType<typeof setTimeout> | null = null

    ;(async () => {
      try {
        const holdRes = await fetch(`${BASE}/api/holdings`, { credentials: 'include', signal: controller.signal })
        if (!holdRes.ok) { console.error('[preload] holdings fetch failed:', holdRes.status); return }
        const holdData = (await holdRes.json()) as HoldingsResponse
        const snaps = holdData.snapshots || []
        if (snaps.length === 0) { console.log('[preload] no holdings, skipping'); return }

        if (!cancelled) {
          analysingBubbleTimer = setTimeout(() => {
            if (cancelled || analysisDone) return
            notifyOutsideResearch({
              title: '观澜，分析中',
              message: '正在生成你的风控报告，稍后会把组合风险、优势持仓和薄弱点整理好。',
            })
          }, 2600)
        }

        const totalVal = snaps.reduce((sum, h) => sum + holdingValue(h), 0)
        const holdings = snaps.map(h => ({
          symbol: h.stockSymbol,
          name: h.stockName || h.stockSymbol,
          weight: totalVal > 0 ? (holdingValue(h) / totalVal * 100) : (100 / snaps.length),
        }))

        if (cancelled) return
        timeout = setTimeout(() => controller.abort(), 180000)
        const res = await fetch(`${BASE}/api/stocksage/portfolio-analysis`, {
          method: 'POST',
          credentials: 'include',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ holdings }),
          signal: controller.signal,
        })
        analysisDone = true
        if (analysingBubbleTimer) clearTimeout(analysingBubbleTimer)
        console.log('[preload] analysis response:', res.status)

        if (!res.ok) { console.error('[preload] analysis failed:', res.status); return }
        const result = (await res.json()) as PortfolioAnalysisResult
        if (cancelled) return
        if (result.error) { console.error('[preload] analysis error:', result.error); return }

        try { sessionStorage.setItem(`investory_preloaded_analysis:${portfolioId}`, JSON.stringify(result)) } catch { /* Ignore malformed or unavailable optional state. */ }

        if (!cancelled) {
          const score = result.portfolio_score ?? 0
          const tone = score >= 60 ? '状态不错' : score >= 40 ? '还算稳' : '需要留意'
          const topName = result.top_holdings?.[0]?.name || '暂无'
          notifyOutsideResearch({
            title: '观澜，风控简报',
            message: `组合评分 ${score.toFixed(0)} 分，${tone}。评分最高的是 ${topName}，可以查看完整分析。`,
            actionLabel: '查看风控报告',
            actionHref: '/research',
          })
        }
      } catch (e: unknown) {
        analysisDone = true
        if (analysingBubbleTimer) clearTimeout(analysingBubbleTimer)
        if (e instanceof Error && e.name !== 'AbortError') console.error('[preload] failed:', e.message)
      } finally {
        if (timeout) clearTimeout(timeout)
      }
    })()

    return () => {
      cancelled = true
      controller.abort()
      if (timeout) clearTimeout(timeout)
      if (analysingBubbleTimer) clearTimeout(analysingBubbleTimer)
    }
  }, [authenticated, portfolioId])
}
