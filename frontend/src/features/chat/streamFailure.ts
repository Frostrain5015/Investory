import type { Message, ReportArtifact, TimelineStep } from './types'

export interface StoredMessage {
  role: string
  content: string
  thinking?: string
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
  let timeline: TimelineStep[] | undefined
  let thinking: string | undefined
  if (stored.thinking?.trim()) {
    try {
      const parsed: unknown = JSON.parse(stored.thinking)
      if (Array.isArray(parsed)) timeline = parsed as TimelineStep[]
      else thinking = stored.thinking
    } catch { thinking = stored.thinking }
  }
  const text = timeline?.filter((step): step is Extract<TimelineStep, { kind: 'text' }> => step.kind === 'text').map(step => step.text).join('')
  return {
    role: stored.role === 'user' ? 'user' : stored.role === 'assistant' ? 'assistant' : 'system',
    content: text || stored.content,
    timeline, thinking, artifacts: stored.artifacts,
  }
}

/** Keep received evidence visible; interruption never proves a report completed. */
export function interruptedTurn(
  content: string,
  timeline: TimelineStep[],
  artifacts: ReportArtifact[],
  error: string,
  now = Date.now(),
  kind: 'error' | 'stopped' = 'error',
  extra: Partial<Pick<Message, 'hasCode' | 'strategyName' | 'strategyDesc' | 'strategyCode' | 'portfolioCard' | 'picksCard'>> = {},
): Message[] {
  const steps = timeline.map((step): TimelineStep => {
    if (step.kind === 'tool' && !step.done) return { ...step, done: true, error, interrupted: true }
    if (step.kind === 'thinking' && step._ts != null && step._elapsed == null) {
      return { ...step, _elapsed: Math.max(0, now - step._ts) }
    }
    return step
  })
  const messages: Message[] = []
  if (content.trim() || steps.length || artifacts.length || Object.keys(extra).length) {
    messages.push({ ...extra, role: 'assistant', content, timeline: steps, artifacts: [...artifacts] })
  }
  messages.push({ role: 'system', content: error, notice: { kind } })
  return messages
}
