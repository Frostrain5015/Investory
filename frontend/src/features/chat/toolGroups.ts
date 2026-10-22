import type { TimelineStep } from './types'

export type ToolStep = Extract<TimelineStep, { kind: 'tool' }>
export type TimelineEntry =
  | { kind: 'step'; index: number; step: TimelineStep }
  | { kind: 'tools'; index: number; tools: ToolStep[] }

/** Text and thinking are hard boundaries; never reorder or hide either. */
export function groupTimeline(steps: TimelineStep[]): TimelineEntry[] {
  const entries: TimelineEntry[] = []
  for (const [index, step] of steps.entries()) {
    if ((step.kind === 'text' || step.kind === 'thinking') && !step.text.replace(/<\/?think(?:ing)?>/gi, '').trim()) continue
    const previous = entries.at(-1)
    if (step.kind === 'tool' && previous?.kind === 'tools') previous.tools.push(step)
    else if (step.kind === 'tool') entries.push({ kind: 'tools', index, tools: [step] })
    else entries.push({ kind: 'step', index, step })
  }
  return entries
}

export function toolGroupState(tools: ToolStep[]) {
  const running = tools.some(tool => !tool.done && !tool.error)
  const errors = tools.filter(tool => !!tool.error).length
  return { running, errors, state: errors ? 'warning' as const : running ? 'running' as const : 'complete' as const }
}
