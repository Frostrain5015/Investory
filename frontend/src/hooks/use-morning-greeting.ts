import { useEffect, useRef } from 'react'
import { useAuth } from './use-auth'
import { useNotificationBubble } from '@/components/NotificationBubble'
import { BASE } from '@/services/api'

/**
 * Show a "观澜·早安" greeting bubble once per calendar day.
 *
 * Independent from the risk-report bubble (different variant styling, separate
 * dedup key) so both can coexist when conditions warrant.
 */
const STORAGE_KEY = 'investory_morning_greeting_date'

export function useMorningGreeting() {
  const { authenticated } = useAuth()
  const bubble = useNotificationBubble()
  const triggered = useRef(false)

  useEffect(() => {
    if (!authenticated || triggered.current) return
    triggered.current = true

    // Skip if already shown today
    const today = new Date().toISOString().slice(0, 10)
    try {
      if (localStorage.getItem(STORAGE_KEY) === today) return
    } catch {}

    // Slight delay so risk-report bubble can settle on top first
    const timer = setTimeout(() => {
      fetch(`${BASE}/api/ai/morning-greeting`, { credentials: 'include' })
        .then(r => r.json())
        .then((d: { show: boolean; title?: string; message?: string }) => {
          if (!d.show || !d.message) return
          bubble.show({
            title: d.title || '观澜 · 早安',
            message: d.message,
            variant: 'morning',
            actionLabel: '现在和观澜聊聊',
            actionHref: '#',
            onAction: () => {
              // Dispatch a custom event the Layout listens for to open ChatPanel
              window.dispatchEvent(new CustomEvent('investory:open-chat'))
            },
          })
          try { localStorage.setItem(STORAGE_KEY, today) } catch {}
        })
        .catch(() => {})
    }, 1500)

    return () => clearTimeout(timer)
  }, [authenticated])
}
