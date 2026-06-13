import { BASE } from './api'

// Preload AI suggestions once during app init so the chat panel opens instantly.
let cache: string[] | null = null
let pending: Promise<string[]> | null = null

export function preloadSuggestions(): void {
  if (cache || pending) return
  pending = fetch(`${BASE}/api/ai/suggestions`, { credentials: 'include' })
    .then(r => r.json())
    .then(d => {
      if (Array.isArray(d.suggestions) && d.suggestions.length > 0) {
        cache = d.suggestions
      }
      return cache || []
    })
    .catch(() => [])
    .finally(() => { pending = null })
}

export function getCachedSuggestions(): string[] | null {
  return cache
}

export function getSuggestionsPromise(): Promise<string[]> | null {
  return pending
}
