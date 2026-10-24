import type { Message, ReportArtifact } from './types'

export interface StoredMessage {
  role: string
  content: string
  artifacts?: ReportArtifact[]
}

export function restoreMessage(stored: StoredMessage): Message {
  if (stored.role === 'notice') {
    try {
      const notice: unknown = JSON.parse(stored.content)
      if (typeof notice === 'object' && notice !== null && 'message' in notice && typeof notice.message === 'string') {
        return { role: 'system', content: notice.message, notice: { kind: 'kind' in notice && notice.kind === 'stopped' ? 'stopped' : 'error' } }
      }
    } catch { /* Older plain-text status rows remain readable. */ }
    return { role: 'system', content: stored.content, notice: { kind: 'error' } }
  }
  return {
    role: stored.role === 'user' ? 'user' : stored.role === 'assistant' ? 'assistant' : 'system',
    content: stored.content,
    artifacts: stored.artifacts,
  }
}

/** Keep received answer and interaction artifacts visible; interruption never proves a report completed. */
export function interruptedTurn(
  content: string,
  artifacts: ReportArtifact[],
  error: string,
  kind: 'error' | 'stopped' = 'error',
  extra: Partial<Pick<Message, 'hasCode' | 'strategyName' | 'strategyDesc' | 'strategyCode' | 'portfolioCard' | 'picksCard'>> = {},
): Message[] {
  const messages: Message[] = []
  if (content.trim() || artifacts.length || Object.keys(extra).length) {
    messages.push({ ...extra, role: 'assistant', content, artifacts: [...artifacts] })
  }
  messages.push({ role: 'system', content: error, notice: { kind } })
  return messages
}
