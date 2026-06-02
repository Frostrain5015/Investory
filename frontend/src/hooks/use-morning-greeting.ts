import { useEffect, useRef } from 'react'
import { useAuth } from './use-auth'
import { useNotificationBubble } from '@/components/NotificationBubble'

/**
 * Show a local, time-aware Guanlan greeting once per Beijing calendar day.
 * This intentionally does not call AI or the backend greeting endpoint.
 */
const STORAGE_KEY = 'investory_greeting_date_v2'

type GreetingSlot = 'morning' | 'forenoon' | 'noon' | 'afternoon' | 'evening' | 'welcome'

const GREETINGS: Record<GreetingSlot, { title: string; message: string }> = {
  morning: {
    title: '观澜 · 早上好',
    message: '早盘前可以先看世界市场动向，再检查一下持仓风险和今日关注标的。',
  },
  forenoon: {
    title: '观澜 · 上午好',
    message: '可以快速扫一眼全球市场、组合风险，或者让我帮你分析一只持仓。',
  },
  noon: {
    title: '观澜 · 中午好',
    message: '午间适合复盘上午波动，看看组合风险有没有变化，也可以整理交易计划。',
  },
  afternoon: {
    title: '观澜 · 下午好',
    message: '可以检查持仓表现、关注尾盘机会，或者让我帮你做一段持仓分析。',
  },
  evening: {
    title: '观澜 · 晚上好',
    message: '适合复盘今天的组合表现，看看风控报告，或者准备明天的观察清单。',
  },
  welcome: {
    title: '观澜 · 欢迎回来',
    message: '可以从世界市场、组合风控或个股深度分析开始，我会帮你把重点先拎出来。',
  },
}

function beijingParts() {
  const parts = new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(new Date())
  const get = (type: Intl.DateTimeFormatPartTypes) => parts.find(p => p.type === type)?.value || ''
  return {
    dateKey: `${get('year')}-${get('month')}-${get('day')}`,
    hour: Number(get('hour') || 0),
  }
}

function greetingSlot(hour: number): GreetingSlot {
  if (hour >= 5 && hour < 9) return 'morning'
  if (hour >= 9 && hour < 11) return 'forenoon'
  if (hour >= 11 && hour < 14) return 'noon'
  if (hour >= 14 && hour < 18) return 'afternoon'
  if (hour >= 18 && hour < 23) return 'evening'
  return 'welcome'
}

export function useMorningGreeting() {
  const { authenticated } = useAuth()
  const bubble = useNotificationBubble()
  const triggered = useRef(false)

  useEffect(() => {
    if (!authenticated || triggered.current) return
    triggered.current = true

    const { dateKey, hour } = beijingParts()
    try {
      if (localStorage.getItem(STORAGE_KEY) === dateKey) return
    } catch {}

    const timer = setTimeout(() => {
      const greeting = GREETINGS[greetingSlot(hour)]
      bubble.show({
        title: greeting.title,
        message: greeting.message,
        variant: 'morning',
        actionLabel: '和观澜聊聊',
        actionHref: '#',
        onAction: () => {
          window.dispatchEvent(new CustomEvent('investory:open-chat'))
        },
      })
      try { localStorage.setItem(STORAGE_KEY, dateKey) } catch {}
    }, 700)

    return () => clearTimeout(timer)
  }, [authenticated, bubble])
}
