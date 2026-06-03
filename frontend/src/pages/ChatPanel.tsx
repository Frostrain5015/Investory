import { useState, useRef, useEffect, useLayoutEffect } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import { Sparkles, X, Send, Trash2, Check, Loader2, Globe, Square, Maximize2, Minimize2, Wrench, Search, Database, BookOpen, MessageSquare, ArrowRight } from 'lucide-react'
import { useToast } from '@/components/Toast'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import type { SseEvent } from '@/types'
import { useT } from '@/i18n/I18nContext'
import { localizeToolName } from '@/i18n/toolNames'
import { getCachedSuggestions, getSuggestionsPromise } from '@/services/aiPreload'
import { BASE } from '@/services/api'

interface PortfolioCard { portfolio_score: number; holdings_scored: number; top_holdings: { symbol: string; name: string; total_score: number }[]; bottom_holdings: { symbol: string; name: string; total_score: number }[]; group_exposure: Record<string, { buy_score: number }> }
interface PicksCard { regime: string; picks: { code: string; name: string; total_score: number; buy_score: number; bullish: string[] }[]; scanned: number }
/** A single step in the agent's reasoning trace. Either a chunk of native
 * reasoning_content, or a tool call (with its eventual completion status). */
export type ToolCategory = 'query' | 'analysis' | 'mutation'
export type TimelineStep =
  | { kind: 'thinking'; text: string; _ts?: number; _elapsed?: number }
  | { kind: 'kb'; topic: string }
  | { kind: 'tool'; name: string; category?: ToolCategory; done: boolean; error?: string; summary?: string; callId?: string }
interface Message { role: 'user' | 'assistant' | 'system'; content: string; thinking?: string; timeline?: TimelineStep[]; hasCode?: boolean; strategyName?: string; strategyDesc?: string; strategyCode?: string; confirm?: ConfirmData; portfolioCard?: PortfolioCard; picksCard?: PicksCard }
interface ConfirmItem { action: string; label: string; endpoint: string; method: string; body: Record<string, any> }
interface ConfirmData { id: string; title: string; items: ConfirmItem[] }
type ConfirmStatus = 'pending' | 'accepted' | 'refused' | 'failed'

const gradientStyle = { background: 'linear-gradient(135deg, #863bff, #47bfff)' }

function normalizeConfirmCopy(text: string) {
  return (text || '')
    .replace(/[「」"“”'’‘\s:：|·,，.。()（）-]/g, '')
    .replace(/列表/g, '')
}

function isDuplicateConfirmCopy(title: string, label: string) {
  const a = normalizeConfirmCopy(title)
  const b = normalizeConfirmCopy(label)
  return !!a && !!b && (a === b || a.includes(b) || b.includes(a))
}

let gMessages: Message[] = []
let gListeners: (() => void)[] = []
function notify() { gListeners.forEach(fn => fn()) }

/** Per-category visual treatment. Three tool families get three palettes:
 *   query     → slate  (read-only, cheap, instant — lowest visual weight)
 *   analysis  → purple (engine / external calls — medium weight; web_search is sky)
 *   mutation  → amber  (writes DB / state — highest, draws the eye)
 * Errors override to red. Done state shifts to emerald.                  */
function toolStyle(step: Extract<TimelineStep, { kind: 'tool' }>) {
  const cat: ToolCategory = step.category || 'query'
  const isWeb = step.name === 'web_search'
  if (step.error) {
    return { label: 'text-red-600', dot: 'bg-red-400', Icon: Wrench }
  }
  if (step.done) {
    return { label: 'text-emerald-600', dot: 'bg-emerald-400',
             Icon: cat === 'mutation' ? Database : cat === 'analysis' ? (isWeb ? Globe : Sparkles) : Search }
  }
  // running
  if (cat === 'mutation') {
    return { label: 'text-amber-600', dot: 'bg-amber-400 animate-pulse', Icon: Database }
  }
  if (cat === 'analysis') {
    return isWeb
      ? { label: 'text-sky-600', dot: 'bg-sky-400 animate-pulse', Icon: Globe }
      : { label: 'text-purple-500', dot: 'bg-purple-400 animate-pulse', Icon: Sparkles }
  }
  // query
  return { label: 'text-slate-500', dot: 'bg-slate-400 animate-pulse', Icon: Search }
}

// ── Peer-level step renderers: each step is a standalone block ──────────
// Like Claude Code's UI: thinking segments and tool calls are peers,
// not nested inside one container. Tools interrupt thinking, then a new
// thinking block picks up after the tool completes.

/** A single reasoning segment: collapsible text block with live elapsed timer.
 *  Claude Code style: "已思考 用时8s" when done, live count-up while streaming. */
function ThinkingSegment({ text, done, _ts, _elapsed }: { text: string; done: boolean; _ts?: number; _elapsed?: number }) {
  const [now, setNow] = useState(Date.now())
  useEffect(() => {
    if (done || _ts == null) return
    const id = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(id)
  }, [done, _ts])
  const elapsedMs = done ? (_elapsed || 0) : (_ts != null ? now - _ts : 0)
  const elapsedSec = Math.max(0, Math.round(elapsedMs / 1000))
  const [userToggled, setUserToggled] = useState(false)
  const [open, setOpen] = useState(true)
  const innerRef = useRef<HTMLDivElement>(null)
  useEffect(() => { if (!userToggled) setOpen(!done) }, [done, userToggled])
  useEffect(() => {
    if (!done && innerRef.current) innerRef.current.scrollTop = innerRef.current.scrollHeight
  })
  if (!text.trim()) return null
  return (
    <div className="mb-1.5">
      <button onClick={() => { setUserToggled(true); setOpen(o => !o) }}
        className="flex items-center gap-1.5 text-[11px] text-slate-400 hover:text-slate-500 transition-colors">
        <span className={`w-1.5 h-1.5 rounded-full ${done ? 'bg-slate-300' : 'bg-amber-400 animate-pulse'}`} />
        {done ? `已思考 用时${elapsedSec}s` : `思考中… ${elapsedSec}s`}
        <span className="text-[9px] ml-0.5 opacity-50">{open ? '▲' : '▼'}</span>
      </button>
      {open && (
        <div ref={innerRef}
          className="mt-1 p-2.5 bg-slate-50 rounded-lg text-[11px] text-slate-500 whitespace-pre-wrap leading-relaxed border-l-2 border-purple-200 max-h-48 overflow-y-auto">
          {text}
        </div>
      )}
    </div>
  )
}

/** A single tool invocation: icon + name + dot (colour-coded by category
 *  and running/done/failed state). Always visible, never collapsed. */
function ToolStepDisplay({ step, lang }: { step: Extract<TimelineStep, { kind: 'tool' }>; lang: 'zh' | 'en' | 'hk' }) {
  const { label, dot, Icon } = toolStyle(step)
  const running = !step.done && !step.error
  // Match the running dot colour to the category palette for the bounce trio.
  const bounce = step.category === 'mutation' ? 'bg-amber-400'
    : step.category === 'analysis' ? (step.name === 'web_search' ? 'bg-sky-400' : 'bg-purple-400')
    : 'bg-slate-400'
  return (
    <motion.div initial={{ opacity: 0, y: 3 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.18, ease: 'easeOut' }}
      className="mb-1.5 flex items-start gap-2 text-[11px]">
      <span className={`mt-1 w-1.5 h-1.5 rounded-full shrink-0 ${dot}`} />
      <div className={`flex-1 flex items-center gap-1.5 ${label}`}>
        <Icon className={`w-3 h-3 ${running ? 'animate-pulse' : ''}`} />
        <span className="font-medium">{localizeToolName(step.name, lang)}</span>
        {running && (
          <>
            <span className="text-[10px] opacity-70">{lang === 'en' ? 'running…' : '调用中…'}</span>
            <span className="inline-flex gap-0.5 ml-0.5">
              <span className={`w-1 h-1 rounded-full animate-bounce ${bounce}`} />
              <span className={`w-1 h-1 rounded-full animate-bounce ${bounce}`} style={{ animationDelay: '0.15s' }} />
              <span className={`w-1 h-1 rounded-full animate-bounce ${bounce}`} style={{ animationDelay: '0.3s' }} />
            </span>
          </>
        )}
        {step.done && !step.error && <Check className="w-3 h-3 opacity-70" />}
        {step.done && !step.error && step.summary && (
          <span className="text-[10px] text-slate-400">· {step.summary}</span>
        )}
        {step.error && (
          <>
            <span className="text-[10px] text-red-500">{lang === 'en' ? 'failed' : '失败'}</span>
            <span className="text-[10px] text-red-400 break-words">{step.error}</span>
          </>
        )}
      </div>
    </motion.div>
  )
}

/** Renders an array of TimelineSteps as a peer-level sequence:
 * each thinking segment gets its own collapse block, each tool gets its
 * own inline indicator — exactly how Claude Code / Codex render. */
function TimelineRenderer({ steps, done, lang }: { steps: TimelineStep[]; done: boolean; lang: 'zh' | 'en' | 'hk' }) {
  if (steps.length === 0) return null
  return (
    <div className="mb-2">
      {steps.map((step, i) => {
        if (step.kind === 'thinking') {
          // This segment is "done" if anything follows it (tool call or newer
          // thinking) OR if the whole generation is complete. Only the very
          // last segment in an active generation stays live.
          const segDone = i < steps.length - 1 || done
          return <ThinkingSegment key={i} text={step.text} done={segDone} _ts={step._ts} _elapsed={step._elapsed} />
        }
        if (step.kind === 'kb') {
          return (
            <motion.div key={i} initial={{ opacity: 0, y: 3 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.18, ease: 'easeOut' }}
              className="mb-1.5 flex items-start gap-2 text-[11px]">
              <span className="mt-1 w-1.5 h-1.5 rounded-full shrink-0 bg-indigo-400" />
              <div className="flex-1 flex items-center gap-1.5 text-indigo-600">
                <BookOpen className="w-3 h-3" />
                <span className="font-medium">{lang === 'en' ? 'Consulting knowledge base' : '查阅知识库'}</span>
                <Check className="w-3 h-3 opacity-70" />
                <span className="text-[10px] text-slate-400">· {step.topic}</span>
              </div>
            </motion.div>
          )
        }
        if (step.kind === 'tool') return <ToolStepDisplay key={i} step={step} lang={lang} />
        return null  // unknown/legacy step kinds (e.g. old 'skill') — skip gracefully
      })}
    </div>
  )
}

function useChatMessages(): [Message[], (msgs: Message[]) => void] {
  const [, setTick] = useState(0)
  useEffect(() => {
    const fn = () => setTick(t => t + 1)
    gListeners.push(fn)
    return () => { gListeners = gListeners.filter(f => f !== fn) }
  }, [])
  return [gMessages, (msgs: Message[]) => { gMessages = msgs; notify() }]
}

export type ChatMode = 'idle' | 'dock' | 'expanded'

interface ChatPanelProps {
  /** Controlled open/closed flag. When false, the shell renders as the idle pill. */
  open?: boolean
  onOpen?: () => void
  onClose: () => void
  initialMessage?: string
  defaultMode?: 'dock' | 'expanded'
}

export default function ChatPanel({ open = true, onOpen, onClose, initialMessage, defaultMode = 'dock' }: ChatPanelProps) {
  const { t, lang } = useT()
  const toast = useToast()
  // 'idle' = collapsed to the floating button; otherwise dock/expanded.
  const [internalMode, setInternalMode] = useState<'dock' | 'expanded'>(defaultMode)
  const mode: ChatMode = open ? internalMode : 'idle'
  const setMode = (m: 'dock' | 'expanded') => { setInternalMode(m); onOpen?.() }
  const [messages, setMessages] = useChatMessages()
  const [input, setInput] = useState('')
  const [streaming, setStreaming] = useState(false)
  const [streamText, setStreamText] = useState('')
  const [streamTimeline, setStreamTimeline] = useState<TimelineStep[]>([])
  const [webSearch, setWebSearch] = useState(false)
  const [dockHeight, setDockHeight] = useState(96)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const pendingStrategy = useRef<{ name: string; desc: string; code: string } | null>(null)
  const pendingCard = useRef<{ type: string; data: any } | null>(null)
  const streamAccum = useRef('')
  // Authoritative timeline ref — frontend builds it from SSE events as the agent runs.
  // We mutate the ref then mirror into state for re-renders so we don't lose ordering.
  const timelineRef = useRef<TimelineStep[]>([])
  /** Stamp _elapsed on every thinking step that isn't the last element (i.e. has
   *  already been closed by a subsequent tool or newer thinking segment). This
   *  ensures previous segments never show "用时0s" when a new step arrives. */
  function stampClosedElapsed(tl: TimelineStep[]) {
    const now = Date.now()
    for (let i = 0; i < tl.length - 1; i++) {
      const s = tl[i]
      if (s.kind === 'thinking' && s._ts != null && s._elapsed == null) {
        tl[i] = { ...s, _elapsed: now - s._ts }
      }
    }
    return tl
  }
  const pushTimeline = () => setStreamTimeline([...timelineRef.current])
  /** Mark a running tool step done/failed. Pairs by callId (exact, survives
   *  parallel same-name calls), falling back to most-recent-by-name. (#3) */
  function finalizeToolStep(callId: string | undefined, name: string, patch: Partial<Extract<TimelineStep, { kind: 'tool' }>>) {
    const tl = timelineRef.current
    let idx = -1
    if (callId) {
      for (let i = tl.length - 1; i >= 0; i--) {
        const s = tl[i]
        if (s.kind === 'tool' && s.callId === callId) { idx = i; break }
      }
    }
    if (idx < 0) {
      for (let i = tl.length - 1; i >= 0; i--) {
        const s = tl[i]
        if (s.kind === 'tool' && s.name === name && !s.done) { idx = i; break }
      }
    }
    if (idx >= 0) { tl[idx] = { ...(tl[idx] as Extract<TimelineStep, { kind: 'tool' }>), ...patch } }
    pushTimeline()
  }
  const [askData, setAskData] = useState<{ question: string; options: string[]; multiSelect: boolean } | null>(null)
  const [askChecked, setAskChecked] = useState<Set<number>>(new Set())
  const [askOther, setAskOther] = useState('')
  const [confirmData, setConfirmData] = useState<ConfirmData | null>(null)
  const [confirmStatus, setConfirmStatus] = useState<ConfirmStatus | null>(null)
  const [confirmResult, setConfirmResult] = useState('')
  const [executing, setExecuting] = useState(false)
  // #7 Session-level "always allow": action types the user chose to auto-confirm
  // for the rest of this session. Lives only in memory (never persisted) and is
  // intentionally scoped per-action-type so a "remember" on a watchlist add does
  // not silently green-light a transaction delete.
  const autoAcceptActionsRef = useRef<Set<string>>(new Set())
  const [rememberConfirm, setRememberConfirm] = useState(false)
  const [suggestions, setSuggestions] = useState<string[]>([])
  const esRef = useRef<EventSource | null>(null)
  const scrollRef = useRef<HTMLDivElement>(null)
  const hairlineRef = useRef<HTMLDivElement>(null)
  const headerRef = useRef<HTMLDivElement>(null)
  const messagesPanelRef = useRef<HTMLDivElement>(null)
  const brandStripRef = useRef<HTMLDivElement>(null)
  const inputWrapRef = useRef<HTMLDivElement>(null)

  useEffect(() => { if (initialMessage) setInput(initialMessage) }, [initialMessage])

  // ESC: collapse from expanded → dock, or close dock if already there
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      if (mode === 'expanded') setMode('dock')
      else if (!streaming && !input) onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [mode, streaming, input, onClose])

  // Auto-grow the textarea up to ~8 lines, then scroll inside it
  useLayoutEffect(() => {
    const ta = textareaRef.current
    if (!ta) return
    ta.style.height = '0px'
    const next = Math.min(ta.scrollHeight, 200)
    ta.style.height = next + 'px'
  }, [input])

  // Replay persisted history on mount, if nothing in memory yet
  useEffect(() => {
    if (gMessages.length > 0) return
    fetch(`${BASE}/api/ai/history`, { credentials: 'include' })
      .then(r => r.json())
      .then(d => {
        if (Array.isArray(d.messages) && d.messages.length > 0) {
          const restored: Message[] = d.messages.map((m: { role: string; content: string; thinking?: string }) => {
            // The backend stores the structured timeline as JSON in the `thinking` field.
            // Older rows contain a raw thinking string — handle both shapes.
            let timeline: TimelineStep[] | undefined
            let thinkingLegacy: string | undefined
            const raw = m.thinking?.trim()
            if (raw) {
              if (raw.startsWith('[')) {
                try {
                  const parsed = JSON.parse(raw)
                  if (Array.isArray(parsed)) timeline = parsed as TimelineStep[]
                } catch { thinkingLegacy = raw }
              } else {
                thinkingLegacy = raw
              }
            }
            return {
              role: m.role === 'user' ? 'user' : m.role === 'assistant' ? 'assistant' : 'system',
              content: m.content,
              ...(timeline ? { timeline } : {}),
              ...(thinkingLegacy ? { thinking: thinkingLegacy } : {}),
            }
          })
          setMessages(restored)
        }
      })
      .catch(() => {})
  }, [])

  useEffect(() => {
    const cached = getCachedSuggestions()
    if (cached) { setSuggestions(cached); return }
    const pending = getSuggestionsPromise()
    if (pending) { pending.then(list => { if (list.length > 0) setSuggestions(list); else setSuggestions([...t.chat.suggestions]) }); return }
    fetch(`${BASE}/api/ai/suggestions`, { credentials: 'include' }).then(r => r.json()).then(d => {
      if (Array.isArray(d.suggestions) && d.suggestions.length > 0) setSuggestions(d.suggestions)
      else setSuggestions([...t.chat.suggestions])
    }).catch(() => { setSuggestions([...t.chat.suggestions]) })
  }, [])

  // Streaming: instant scroll to chase the rapidly-arriving tokens.
  // Use a ref to avoid calling scrollIntoView on every single character
  // (React batches state but the wall-clock is tight).
  const streamTick = useRef(0)
  useEffect(() => {
    if (!streaming) return
    const tick = ++streamTick.current
    requestAnimationFrame(() => {
      if (tick === streamTick.current) scrollRef.current?.scrollIntoView({ behavior: 'instant' as ScrollBehavior })
    })
  }, [streaming, streamText, streamTimeline])

  // When a new message lands (streaming finished), scroll smoothly for a
  // polished landing. Ignore the initial mount replay.
  const prevLen = useRef(messages.length)
  useEffect(() => {
    if (messages.length > prevLen.current && !streaming) {
      scrollRef.current?.scrollIntoView({ behavior: 'smooth' })
    }
    prevLen.current = messages.length
  }, [messages, streaming])

  // Attach all SSE handlers to an EventSource. `baseMessages` is the conversation
  // the final assistant message is appended to. Extracted so both send() and the
  // on-reload resume (#8) share one code path.
  function attachStream(es: EventSource, baseMessages: Message[]) {
      es.addEventListener('strategy', (e) => { const d = JSON.parse(e.data); pendingStrategy.current = { name: d.name, desc: d.description, code: d.code } })
      es.addEventListener('portfolio_card', (e) => { pendingCard.current = { type: 'portfolio', data: JSON.parse(e.data) } })
      es.addEventListener('picks_card', (e) => { pendingCard.current = { type: 'picks', data: JSON.parse(e.data) } })
      es.addEventListener('ask', (e) => { const d = JSON.parse(e.data); setAskData({ question: d.question, options: d.options || [], multiSelect: d.multiSelect || false }); setAskChecked(new Set()); setAskOther('') })
      es.addEventListener('confirm', (e) => {
        try {
          const raw = JSON.parse(e.data); const d = raw.data || raw
          const parsed: ConfirmData = typeof d === 'string' ? JSON.parse(d) : d
          if (parsed?.items?.length > 0) {
            setConfirmData(parsed); setConfirmStatus('pending'); setConfirmResult(''); setRememberConfirm(false)
            // #7 Auto-execute when every action in this card was remembered for the session
            const allRemembered = parsed.items.every(it => autoAcceptActionsRef.current.has(it.action))
            if (allRemembered) { handleConfirmAccept(parsed, false) }
          }
        } catch {}
      })
      es.addEventListener('kb', (e) => {
        const d = JSON.parse(e.data) as { topic?: string }
        if (!d.topic) return
        stampClosedElapsed(timelineRef.current)
        timelineRef.current = [...timelineRef.current, { kind: 'kb', topic: d.topic }]
        pushTimeline()
      })
      es.addEventListener('tool', (e) => {
        const d = JSON.parse(e.data) as { name?: string; category?: ToolCategory; callId?: string }
        const name = d.name || ''
        if (!name) return
        const category: ToolCategory = d.category === 'analysis' || d.category === 'mutation' ? d.category : 'query'
        // A new tool call closes any open thinking segment and starts a tool step
        stampClosedElapsed(timelineRef.current)
        timelineRef.current = [...timelineRef.current, { kind: 'tool', name, category, done: false, ...(d.callId ? { callId: d.callId } : {}) }]
        pushTimeline()
      })
      es.addEventListener('tool_end', (e) => {
        const d = JSON.parse(e.data) as { name?: string; summary?: string; callId?: string }
        finalizeToolStep(d.callId, d.name || '', { done: true, ...(d.summary ? { summary: d.summary } : {}) })
      })
      es.addEventListener('tool_fail', (e) => {
        const d = JSON.parse(e.data) as { name?: string; error?: string; callId?: string }
        finalizeToolStep(d.callId, d.name || '', { done: true, error: d.error || '工具执行失败' })
      })
      es.addEventListener('token', (e) => { const d: SseEvent = JSON.parse(e.data); streamAccum.current += (d.msg || ''); setStreamText(streamAccum.current) })
      es.addEventListener('reasoning', (e) => {
        const d: SseEvent = JSON.parse(e.data)
        const chunk = d.msg || ''
        if (!chunk) return
        // Append to the last thinking step, or open a new one if the last step is a tool
        const last = timelineRef.current[timelineRef.current.length - 1]
        if (last && last.kind === 'thinking') {
          timelineRef.current[timelineRef.current.length - 1] = { ...last, text: last.text + chunk }
        } else {
          stampClosedElapsed(timelineRef.current)
          timelineRef.current = [...timelineRef.current, { kind: 'thinking', text: chunk, _ts: Date.now() }]
        }
        pushTimeline()
      })
      es.addEventListener('done', () => {
        const raw = streamAccum.current
        // Stamp ALL thinking segments (including the last one — gen is done)
        timelineRef.current.forEach((s, i) => {
          if (s.kind === 'thinking' && s._ts != null && s._elapsed == null) {
            timelineRef.current[i] = { ...s, _elapsed: Date.now() - s._ts }
          }
        })
        const finalTimeline = [...timelineRef.current]
        streamAccum.current = ''; timelineRef.current = []
        setStreamText(''); setStreamTimeline([])
        const hasTimeline = finalTimeline.length > 0
        if (!raw.trim() && !hasTimeline) { setStreaming(false); es.close(); esRef.current = null; return }
        // Native reasoning channel takes precedence; fall back to <think> tag for legacy/custom providers
        let content = raw
        let timelineOut: TimelineStep[] | undefined = hasTimeline ? finalTimeline : undefined
        if (!timelineOut) {
          const thinkMatch = raw.match(/<think(?:ing)?>([\s\S]*?)<\/think(?:ing)?>/)
          if (thinkMatch) {
            timelineOut = [{ kind: 'thinking', text: thinkMatch[1].trim() }]
            content = raw.replace(/<think(?:ing)?>[\s\S]*?<\/think(?:ing)?>/g, '').trim()
          }
        }
        const msg: Message = { role: 'assistant', content: content.trim() || raw, timeline: timelineOut }
        const s = pendingStrategy.current
        if (s) { msg.hasCode = true; msg.strategyName = s.name; msg.strategyDesc = s.desc; msg.strategyCode = s.code; pendingStrategy.current = null }
        const card = pendingCard.current
        if (card) { if (card.type === 'portfolio') msg.portfolioCard = card.data; else if (card.type === 'picks') msg.picksCard = card.data; pendingCard.current = null }
        if (!s && !card) {
          const codeMatch = raw.match(/```(?:python)?\s*\n(def decide\(ctx[^)]*\):[\s\S]*?)```/)
          if (codeMatch) { const nameMatch = raw.match(/(?:策略名称|策略)[：:]\s*(.+)/); msg.hasCode = true; msg.strategyName = nameMatch ? nameMatch[1].trim() : ''; msg.strategyCode = codeMatch[1].trim(); msg.strategyDesc = '' }
        }
        setMessages([...baseMessages, msg]); setStreaming(false); es.close(); esRef.current = null
      })
      es.addEventListener('error', (e) => {
        pendingStrategy.current = null
        let errMsg = t.chat.errorNetwork
        try {
          const raw = (e as MessageEvent).data
          if (raw) { const d: SseEvent = JSON.parse(raw); errMsg = d.msg || t.chat.errorUnknown }
        } catch {}
        setMessages([...baseMessages, { role: 'system', content: `⚠ ${errMsg}` }])
        setStreaming(false); es.close(); esRef.current = null
      })
      es.onerror = () => {
        if (!streamAccum.current) {
          setMessages([...baseMessages, { role: 'system', content: `⚠ ${t.chat.errorNetwork}` }])
        }
        setStreaming(false); es.close(); esRef.current = null
      }
  }

  // #8 On mount, if a generation is already running server-side (e.g. the user
  // reloaded mid-answer), resubscribe — the unified event buffer replays the
  // whole turn so the live timeline/text resume instead of being lost.
  useEffect(() => {
    let cancelled = false
    fetch(`${BASE}/api/ai/status`, { credentials: 'include' }).then(r => r.json()).then(d => {
      if (cancelled || !d?.active || streaming || esRef.current) return
      setStreaming(true); setStreamText(''); setStreamTimeline([]); streamAccum.current = ''; timelineRef.current = []
      const es = new EventSource(`${BASE}/api/ai/stream`, { withCredentials: true }); esRef.current = es
      attachStream(es, gMessages)
    }).catch(() => {})
    return () => { cancelled = true }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  async function send(textOverride?: string) {
    const text = (textOverride !== undefined ? textOverride : input).trim()
    if (!text || streaming) return
    setInput('')
    const newMessages: Message[] = [...messages, { role: 'user', content: text }]
    setMessages(newMessages)
    setStreaming(true); setStreamText(''); setStreamTimeline([]); streamAccum.current = ''; timelineRef.current = []; setAskData(null); setAskChecked(new Set()); setAskOther(''); setConfirmData(null); setConfirmStatus(null); setConfirmResult('')
    try {
      const resp = await fetch(`${BASE}/api/ai/chat`, { method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ messages: newMessages, webSearch, conversationId: convIdRef.current || undefined }) })
      if (!resp.ok) { setStreamText(`${t.chat.errorPrefix} HTTP ${resp.status}`); setStreaming(false); return }
      if (esRef.current) esRef.current.close()
      const es = new EventSource(`${BASE}/api/ai/stream`, { withCredentials: true }); esRef.current = es
      attachStream(es, newMessages)
    } catch (e: unknown) { setStreamText(`${t.chat.errorPrefix} ${e instanceof Error ? e.message : String(e)}`); setStreaming(false) }
  }

  const convIdRef = useRef<number>(0)
  const [convList, setConvList] = useState<{ id: number; title: string; createdAt: string; messageCount: number }[]>([])
  const [showConvList, setShowConvList] = useState(false)

  function clearChat() { gMessages = []; setMessages([]); setStreamText(''); convIdRef.current = 0; fetch(`${BASE}/api/ai/clear`, { method: 'POST', credentials: 'include' }).catch(() => {}) }

  function loadConvList() {
    fetch(`${BASE}/api/ai/conversations`, { credentials: 'include' })
      .then(r => r.json()).then((list: any[]) => setConvList(list || [])).catch(() => {})
  }

  function openConv(id: number) {
    fetch(`${BASE}/api/ai/conversations/${id}`, { credentials: 'include' })
      .then(r => r.json()).then((d: any) => {
        const restored = (d.messages || []).map((m: { role: string; content: string; thinking?: string }) => {
          let timeline: TimelineStep[] | undefined
          let thinkingLegacy: string | undefined
          const raw = m.thinking?.trim()
          if (raw) {
            if (raw.startsWith('[')) { try { const p = JSON.parse(raw); if (Array.isArray(p)) timeline = p } catch { thinkingLegacy = raw } }
            else thinkingLegacy = raw
          }
          return { role: m.role === 'user' ? 'user' as const : m.role === 'assistant' ? 'assistant' as const : 'system' as const, content: m.content, ...(timeline ? { timeline } : {}), ...(thinkingLegacy ? { thinking: thinkingLegacy } : {}) }
        })
        gMessages = restored; setMessages(restored); convIdRef.current = id; setShowConvList(false)
      }).catch(() => {})
  }

  function deleteConv(id: number, e: React.MouseEvent) {
    e.stopPropagation()
    if (!confirm('删除此对话？')) return
    fetch(`${BASE}/api/ai/conversations/${id}`, { method: 'DELETE', credentials: 'include' })
      .then(() => { if (convIdRef.current === id) clearChat(); loadConvList() }).catch(() => {})
  }
  function stopGeneration() {
    if (esRef.current) { esRef.current.close(); esRef.current = null }
    fetch(`${BASE}/api/ai/cancel`, { method: 'POST', credentials: 'include' }).catch(() => {})
    // Persist whatever partial text + timeline we have so it doesn't disappear
    const partial = streamAccum.current.trim()
    timelineRef.current.forEach((s, i) => {
      if (s.kind === 'thinking' && s._ts != null && s._elapsed == null) {
        timelineRef.current[i] = { ...s, _elapsed: Date.now() - s._ts }
      }
      // Close out any tool still spinning so reloaded history doesn't show an
      // eternal "调用中…" — mark it done with an aborted note.
      if (s.kind === 'tool' && !s.done) {
        timelineRef.current[i] = { ...s, done: true, error: lang === 'en' ? 'stopped' : '已停止' }
      }
    })
    const partialTimeline = [...timelineRef.current]
    streamAccum.current = ''; timelineRef.current = []
    setStreamText(''); setStreamTimeline([]); setStreaming(false)
    if (partial || partialTimeline.length > 0) {
      setMessages([...messages, {
        role: 'assistant',
        content: partial || '（已停止）',
        timeline: partialTimeline.length > 0 ? partialTimeline : undefined,
      }])
    }
  }
  function handleKeyDown(e: React.KeyboardEvent) { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send() } }

  async function handleConfirmAccept(data?: ConfirmData, remember?: boolean) {
    const cd = data || confirmData
    if (!cd?.items) return; setExecuting(true); const results: string[] = []
    for (const item of cd.items) {
      const itemName = item.label || cd.title
      try {
        const body = item.body || {}; const form = new URLSearchParams()
        for (const [k, v] of Object.entries(body)) { if (v != null && v !== '') form.append(k, String(v)) }
        const res = await fetch(`${BASE}${item.endpoint}`, { method: item.method || 'POST', credentials: 'include', headers: item.method !== 'DELETE' ? { 'Content-Type': 'application/x-www-form-urlencoded' } : undefined, body: item.method !== 'DELETE' ? form.toString() : undefined })
        if (!res.ok) { const err = await res.json().catch(() => ({})); results.push(`✗ ${itemName}: ${err.error || `HTTP ${res.status}`}`) }
        else results.push(`✓ ${itemName}`)
      } catch (e: unknown) {
        const message = e instanceof Error ? e.message : String(e)
        results.push(`✗ ${itemName}: ${message}`)
      }
    }
    // Record opt-in: future cards whose actions are all remembered auto-execute.
    if (remember) { for (const it of cd.items) autoAcceptActionsRef.current.add(it.action) }
    const failed = results.some(r => r.startsWith('✗'))
    setConfirmResult(results.join('\n'))
    setConfirmStatus(failed ? 'failed' : 'accepted'); setExecuting(false)
  }

  const hasContent = messages.length > 0 || streaming

  useLayoutEffect(() => {
    if (mode !== 'dock' || typeof window === 'undefined') return

    let frame = 0
    const viewportHeight = () => window.visualViewport?.height ?? window.innerHeight
    const measure = () => {
      const dockMaxHeight = Math.min(viewportHeight() * 0.6, 540)
      const hairlineHeight = hairlineRef.current?.getBoundingClientRect().height ?? 0
      const headerHeight = hasContent ? (headerRef.current?.getBoundingClientRect().height ?? 0) : 0
      const brandHeight = !hasContent ? (brandStripRef.current?.getBoundingClientRect().height ?? 0) : 0
      const inputHeight = inputWrapRef.current?.getBoundingClientRect().height ?? 0
      const fixedHeight = hairlineHeight + headerHeight + brandHeight + inputHeight
      const messagesHeight = hasContent
        ? Math.min(messagesPanelRef.current?.scrollHeight ?? 0, Math.max(0, dockMaxHeight - fixedHeight))
        : 0
      const measured = Math.min(dockMaxHeight, fixedHeight + messagesHeight)

      if (measured <= 0) return
      setDockHeight(prev => Math.abs(prev - measured) > 1 ? Math.round(measured) : prev)
    }
    const scheduleMeasure = () => {
      cancelAnimationFrame(frame)
      frame = requestAnimationFrame(measure)
    }

    measure()
    const observer = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(scheduleMeasure) : null
    const observedNodes = [hairlineRef.current, headerRef.current, messagesPanelRef.current, brandStripRef.current, inputWrapRef.current]
    observedNodes.forEach(node => { if (node && observer) observer.observe(node) })
    window.addEventListener('resize', scheduleMeasure)
    window.visualViewport?.addEventListener('resize', scheduleMeasure)

    return () => {
      cancelAnimationFrame(frame)
      observer?.disconnect()
      window.removeEventListener('resize', scheduleMeasure)
      window.visualViewport?.removeEventListener('resize', scheduleMeasure)
    }
  }, [mode, hasContent, messages, streaming, streamText, streamTimeline, input, suggestions, askData, confirmData, confirmResult, confirmStatus])

  const headerNode = (
    <div ref={headerRef} className="flex items-center justify-between px-4 py-2.5 shrink-0">
      <div className="flex items-center gap-2">
        <div className="w-6 h-6 rounded-md flex items-center justify-center" style={gradientStyle}>
          <Sparkles className="w-3 h-3 text-white" />
        </div>
        <span className="text-sm font-semibold text-slate-800 tracking-tight">观澜</span>
        <span className="text-[9px] text-slate-400 font-medium bg-slate-100 px-1.5 py-0.5 rounded">AI</span>
      </div>
      <div className="flex items-center gap-0.5">
        <button onClick={() => { loadConvList(); setShowConvList(true) }} className="p-1.5 rounded-md text-slate-400 hover:text-slate-500 transition-colors" title={t.chat.clearChat}><MessageSquare className="w-3.5 h-3.5" /></button>
        <button onClick={() => setMode(mode === 'expanded' ? 'dock' : 'expanded')}
          className="p-1.5 rounded-md text-slate-400 hover:text-slate-500 transition-colors"
          title={mode === 'expanded' ? t.chat.collapse : t.chat.expand}>
          {mode === 'expanded' ? <Minimize2 className="w-3.5 h-3.5" /> : <Maximize2 className="w-3.5 h-3.5" />}
        </button>
        <button onClick={onClose} className="p-1.5 rounded-md text-slate-400 hover:text-slate-500 transition-colors"><X className="w-4 h-4" /></button>
      </div>
    </div>
  )

  const inputBar = (
    <div className="flex items-end gap-2">
      <div className="flex items-center gap-0.5 pb-1">
        {/* Deep-think toggle removed — thinking is now auto-enabled for real
            requests via server-side smart routing, so tools fire reliably
            without a manual switch. */}
        <button onClick={() => setWebSearch(!webSearch)} className={`p-1.5 rounded-md transition-colors ${webSearch ? 'text-sky-500' : 'text-slate-400 hover:text-slate-500'}`} title={t.chat.webSearch}><Globe className="w-4 h-4" /></button>
      </div>
      <textarea ref={textareaRef} value={input} onChange={e => setInput(e.target.value)} onKeyDown={handleKeyDown}
        placeholder={t.chat.placeholder} rows={1} disabled={streaming}
        className="flex-1 resize-none min-h-[40px] max-h-[200px] rounded-xl bg-transparent px-3 py-2.5 text-sm leading-snug focus:outline-none disabled:opacity-60 placeholder:text-slate-400 overflow-y-auto" />
      {streaming ? (
        <button onClick={stopGeneration} title={t.chat.stop}
          className="w-10 h-10 rounded-xl flex items-center justify-center transition-all shrink-0 bg-slate-700 hover:bg-slate-800">
          <Square className="w-3.5 h-3.5 text-white fill-white" />
        </button>
      ) : (
        <button onClick={() => send()} disabled={!input.trim()}
          className="w-10 h-10 rounded-xl flex items-center justify-center transition-all shrink-0 disabled:opacity-30"
          style={input.trim() ? gradientStyle : { background: '#e2e8f0', color: '#94a3b8' }}>
          <Send className="w-4 h-4 text-white" />
        </button>
      )}
    </div>
  )

  const messagesArea = (
    <div ref={messagesPanelRef} className="flex-1 overflow-auto px-4 py-4 space-y-3">
        {messages.length === 0 && !streaming && (
          <div className="text-center py-10">
            <div className="w-10 h-10 rounded-xl flex items-center justify-center mx-auto mb-3" style={{ background: 'linear-gradient(135deg, rgba(134,59,255,0.12), rgba(71,191,255,0.12))' }}>
              <Sparkles className="w-5 h-5" style={{ color: '#863bff' }} />
            </div>
            <p className="text-sm font-medium text-slate-700">{t.chat.greeting}</p>
            <p className="text-xs text-slate-400 mt-1">{t.chat.subtitle}</p>
            <div className="mt-5 space-y-1.5">
              {suggestions.length === 0
                ? [1, 2, 3].map(i => (<div key={i} className="h-8 rounded-lg bg-slate-100 animate-pulse" style={{ width: `${72 + i * 6}%` }} />))
                : suggestions.map((q, i) => (
                    <motion.button key={q} onClick={() => send(q)} initial={{ opacity: 0, y: 4 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: i * 0.06 }}
                      className="block w-full text-left text-xs text-slate-500 hover:text-slate-800 hover:bg-slate-50 px-3 py-2 rounded-lg transition-colors border border-transparent hover:border-slate-200">"{q}"</motion.button>
                  ))
              }
            </div>
          </div>
        )}
        {Array.isArray(messages) && messages.map((m, i) => (
          <div key={i} className={`flex ${m.role === 'user' ? 'justify-end' : 'justify-start'}`}>
            <div className={`max-w-[85%] rounded-2xl px-4 py-2.5 text-sm leading-relaxed ${
              m.role === 'user'
                ? 'text-white' : 'bg-slate-50 text-slate-700 border border-slate-100'
            }`} style={m.role === 'user' ? gradientStyle : undefined}>
              {m.role === 'assistant' ? <>
                {m.timeline && m.timeline.length > 0
                  ? <TimelineRenderer steps={m.timeline} done={true} lang={lang} />
                  : m.thinking && <TimelineRenderer steps={[{ kind: 'thinking', text: m.thinking }]} done={true} lang={lang} />}
                <div className="prose prose-sm prose-slate max-w-none text-[13px] [&_table]:text-[11px] [&_th]:border [&_th]:border-slate-200 [&_th]:px-2 [&_th]:py-1 [&_td]:border [&_td]:border-slate-100 [&_td]:px-2 [&_td]:py-1 [&_table]:w-full [&_code]:bg-slate-100 [&_code]:px-1 [&_code]:rounded [&_pre]:bg-slate-100 [&_pre]:p-2 [&_pre]:rounded-lg [&_pre]:overflow-auto">
                  <ReactMarkdown remarkPlugins={[remarkGfm]} components={{
                    code: ({ children, className, ...props }) => {
                      const text = String(children).trim()
                      if (/^\d{4,6}\.(SH|SZ|HK|US)$/i.test(text) || /^[A-Z]{1,5}\.US$/i.test(text))
                        return <a href={`${BASE}/stock?symbol=${text}`} target="_blank" className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded bg-purple-50 text-purple-700 text-xs font-medium hover:bg-purple-100">{text}</a>
                      return <code className={className} {...props}>{children}</code>
                    }
                  }}>{m.content}</ReactMarkdown>
                </div>
              </> : <div style={{ whiteSpace: 'pre-wrap' }}>{m.content}</div>}
              {m.hasCode && (
                <StrategyCard
                  name={m.strategyName || ''}
                  description={m.strategyDesc || ''}
                  code={m.strategyCode || m.content}
                  onSave={async (name: string) => {
                    const code = m.strategyCode || m.content
                    const res = await fetch(`${BASE}/api/backtest/strategies`, { method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name, strategyType: 'advanced', strategy: { code } }) })
                    const data = await res.json(); if (data.error) toast(data.error, false); else toast(t.chat.strategySaved, true)
                  }}
                />
              )}
              {m.portfolioCard && <DataCard title="组合因子分析" score={m.portfolioCard.portfolio_score ?? 0} top={m.portfolioCard.top_holdings} bottom={m.portfolioCard.bottom_holdings} />}
              {m.picksCard && <PicksCardDisplay card={m.picksCard} />}
            </div>
          </div>
        ))}
        {streaming && (
          <div className="flex justify-start">
            <div className={`max-w-[85%] rounded-2xl px-4 py-2.5 text-sm leading-relaxed ${streamText.startsWith('⚠') ? 'bg-red-50 text-red-700 border border-red-100' : 'bg-slate-50 text-slate-700 border border-slate-100'}`}>
              {(() => {
                // Fold <think> XML fallback into the live timeline so providers
                // that don't emit reasoning_content still get a step-by-step trace.
                let liveSteps = streamTimeline
                let after = streamText
                if (liveSteps.length === 0 && streamText) {
                  const thinkMatch = streamText.match(/<think(?:ing)?>([\s\S]*?)(<\/think(?:ing)?>|$)/)
                  if (thinkMatch) {
                    liveSteps = [{ kind: 'thinking', text: thinkMatch[1] }]
                    after = streamText.replace(/<think(?:ing)?>[\s\S]*?(<\/think(?:ing)?>|$)/, '').trim()
                  }
                }
                const reasoningDone = !!after.trim()
                // The currently-running tool, if any — surfaced as a peer status
                // indicator (sibling to "深度思考中…"), not buried in the timeline.
                const runningTool = (() => {
                  for (let i = liveSteps.length - 1; i >= 0; i--) {
                    const s = liveSteps[i]
                    if (s.kind === 'tool' && !s.done) return s
                  }
                  return null
                })()
                // Idle pulse only when nothing has streamed yet AND no tool is running
                const showGenericPending = liveSteps.length === 0 && !after.trim() && !runningTool
                return (<>
                  {/* The running tool renders inside TimelineRenderer (ToolStepDisplay
                      shows the animated "调用中…" row) — no separate banner, so a tool
                      call is never shown twice. */}
                  {liveSteps.length > 0 && <TimelineRenderer steps={liveSteps} done={reasoningDone} lang={lang} />}
                  {showGenericPending && (
                    <span className="inline-flex gap-1"><span className="w-1.5 h-1.5 rounded-full bg-slate-300 animate-bounce" /><span className="w-1.5 h-1.5 rounded-full bg-slate-300 animate-bounce" style={{ animationDelay: '0.1s' }} /><span className="w-1.5 h-1.5 rounded-full bg-slate-300 animate-bounce" style={{ animationDelay: '0.2s' }} /></span>
                  )}
                  {after && <div className="prose prose-sm prose-slate max-w-none text-[13px] [&_table]:text-[11px] [&_th]:border [&_th]:border-slate-200 [&_th]:px-2 [&_th]:py-1 [&_td]:border [&_td]:border-slate-100 [&_td]:px-2 [&_td]:py-1 [&_table]:w-full [&_code]:bg-slate-100 [&_code]:px-1 [&_code]:rounded [&_pre]:bg-slate-100 [&_pre]:p-2 [&_pre]:rounded-lg [&_pre]:overflow-auto"><ReactMarkdown remarkPlugins={[remarkGfm]}>{after}</ReactMarkdown></div>}
                </>)
              })()}
            </div>
          </div>
        )}
        {askData && (
          <div className="flex justify-start">
            <div className="max-w-[85%] rounded-2xl px-4 py-3 text-sm bg-slate-50 text-slate-700 border border-slate-100 space-y-2">
              <p className="text-xs text-slate-500 font-medium">{askData.question}</p>
              {askData.multiSelect && (
                <p className="text-[10px] text-slate-400 -mt-1">可多选</p>
              )}
              {askData.options.map((o: string, i: number) => {
                const checked = askChecked.has(i)
                const toggle = () => {
                  if (askData.multiSelect) {
                    const next = new Set(askChecked)
                    if (next.has(i)) next.delete(i); else next.add(i)
                    setAskChecked(next)
                  } else {
                    // Single select: submit immediately
                    setAskData(null); setAskChecked(new Set())
                    fetch(`${BASE}/api/ai/answer`, {
                      method: 'POST', credentials: 'include',
                      headers: { 'Content-Type': 'application/json' },
                      body: JSON.stringify({ answer: o }),
                    })
                  }
                }
                return (
                  <button key={i} onClick={toggle}
                    className={`block w-full text-left text-xs px-3 py-2 rounded-lg border transition-colors mb-1 last:mb-0
                      ${checked
                        ? 'bg-purple-50 border-purple-300 text-purple-800'
                        : 'border-slate-200 hover:bg-white hover:border-slate-300 active:bg-purple-50 active:border-purple-200'}`}>
                    {askData.multiSelect && (
                      <span className={`inline-flex items-center justify-center w-4 h-4 rounded border mr-2 text-[10px] align-middle
                        ${checked ? 'bg-purple-500 border-purple-500 text-white' : 'border-slate-300'}`}>
                        {checked ? '✓' : ''}
                      </span>
                    )}
                    {o}
                  </button>
                )
              })}
              {/* "Other" text input — always available */}
              <div className="flex items-center gap-2 pt-1">
                <input type="text" value={askOther} onChange={e => setAskOther(e.target.value)}
                  onKeyDown={e => { if (e.key === 'Enter' && askOther.trim()) {
                    setAskData(null); setAskOther(''); setAskChecked(new Set())
                    fetch(`${BASE}/api/ai/answer`, {
                      method: 'POST', credentials: 'include',
                      headers: { 'Content-Type': 'application/json' },
                      body: JSON.stringify({ answer: askOther.trim() }),
                    })
                  }}}
                  placeholder="其他（自定义回答）…"
                  className="flex-1 h-8 rounded-lg border border-slate-200 bg-white px-2.5 text-xs focus:outline-none focus:ring-2 focus:ring-purple-200 placeholder:text-slate-400" />
                <button disabled={!askOther.trim()} onClick={() => {
                  if (!askOther.trim()) return
                  setAskData(null); setAskOther(''); setAskChecked(new Set())
                  fetch(`${BASE}/api/ai/answer`, {
                    method: 'POST', credentials: 'include',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ answer: askOther.trim() }),
                  })
                }}
                  className="h-8 px-3 rounded-lg bg-slate-200 dark:bg-slate-700 text-xs font-medium text-slate-600 dark:text-slate-300 hover:bg-slate-300 dark:hover:bg-slate-600 disabled:opacity-40 transition-colors shrink-0">
                  发送
                </button>
              </div>
              {/* Multi-select submit button */}
              {askData.multiSelect && askChecked.size > 0 && (
                <button onClick={() => {
                  const selected = askData.options.filter((_, i) => askChecked.has(i)).join('、')
                  setAskData(null); setAskChecked(new Set()); setAskOther('')
                  fetch(`${BASE}/api/ai/answer`, {
                    method: 'POST', credentials: 'include',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ answer: selected }),
                  })
                }}
                  className="w-full h-8 rounded-lg bg-purple-500 text-white text-xs font-medium hover:bg-purple-600 transition-colors">
                  确认选择（{askChecked.size} 项）
                </button>
              )}
            </div>
          </div>
        )}
        {confirmData && Array.isArray(confirmData.items) && (
          <div className="flex justify-start">
            <div className="max-w-[85%] rounded-2xl px-4 py-2.5 text-sm bg-white border-2 border-emerald-500 shadow-lg shadow-emerald-500/10 space-y-2">
              <div className="flex items-center gap-2 text-emerald-700">
                <div className="w-5 h-5 rounded-full bg-emerald-100 flex items-center justify-center"><Check className="w-3 h-3 text-emerald-600" /></div>
                <span className="text-xs font-semibold">{confirmData.title}</span>
              </div>
              {confirmData.items.map((item, i) => {
                const b = item.body || {}
                const isDelete = item.action === 'delete'; const isRemove = item.action === 'remove_watchlist'; const isWatchlist = item.action === 'add_watchlist' || isRemove
                const isTransfer = b.type === 'TRANSFER_IN' || b.type === 'TRANSFER_OUT'; const isDiv = b.type === 'DIV'
                const typeLabels: Record<string, string> = { BUY: '买入', SELL: '卖出', DIV: '分红', TRANSFER_IN: '转入', TRANSFER_OUT: '转出' }
                const cardBg = isDelete || isRemove ? 'bg-red-50 text-red-700' : isWatchlist ? 'bg-purple-50 text-purple-700' : 'bg-slate-50 text-slate-500'
                const showLabel = !!item.label && (confirmData.items.length > 1 || !isDuplicateConfirmCopy(confirmData.title, item.label))
                if (!showLabel && isWatchlist) return null
                return (
                  <div key={i} className={`text-[11px] leading-relaxed rounded-lg px-3 py-2 space-y-0.5 ${cardBg}`}>
                    {showLabel && <div className="text-xs font-medium">{item.label}</div>}
                    {!isWatchlist && <div className="flex flex-wrap gap-x-3 gap-y-0.5">
                      {b.stockName && <span>{b.stockName}</span>}{b.type && <span className="font-semibold">{typeLabels[b.type] || b.type}</span>}
                      {b.shares > 0 && <span>{isDiv ? `每股 ${b.shares}` : isTransfer ? `${b.shares}` : `${b.shares} 股`}</span>}
                      {b.price > 0 && !isTransfer && <span>@ {b.price}</span>}{b.fee > 0 && <span>手续费 {b.fee}</span>}
                      {b.tradeDate && <span>日期 {b.tradeDate}</span>}{b.currency && <span>{b.currency}</span>}
                      {b.amountPerShare != null && b.amountPerShare > 0 && <span>分红/股 {b.amountPerShare}</span>}
                      {b.note && <span className="text-slate-400">备注: {b.note}</span>}
                    </div>}
                  </div>
                )
              })}
              {confirmStatus === 'pending' && (
                <>
                  <div className="flex gap-2 pt-1">
                    <button onClick={() => handleConfirmAccept(undefined, rememberConfirm)} disabled={executing} className="flex items-center gap-1 px-4 py-2 rounded-lg bg-emerald-600 text-white text-xs font-medium hover:bg-emerald-700 transition-colors disabled:opacity-60">
                      {executing ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Check className="w-3.5 h-3.5" />}{t.chat.accept}
                    </button>
                    <button onClick={() => setConfirmStatus('refused')} disabled={executing} className="flex items-center gap-1 px-4 py-2 rounded-lg border border-slate-200 text-slate-600 text-xs font-medium hover:bg-slate-50 transition-colors"><X className="w-3.5 h-3.5" />{t.chat.refuse}</button>
                  </div>
                  <label className="flex items-center gap-1.5 text-[10px] text-slate-400 cursor-pointer select-none pt-0.5">
                    <input type="checkbox" checked={rememberConfirm} onChange={e => setRememberConfirm(e.target.checked)} className="w-3 h-3 accent-emerald-600" />
                    {t.chat.autoConfirmSession}
                  </label>
                </>
              )}
              {confirmStatus === 'accepted' && <p className="text-xs text-emerald-600 font-medium">✓ 已完成{confirmData.items.length > 1 ? ` ${confirmData.items.length} 项` : ''}</p>}
              {confirmStatus === 'failed' && <p className="text-xs text-red-500 whitespace-pre-wrap">{confirmResult || '执行失败'}</p>}
              {confirmStatus === 'refused' && <p className="text-xs text-slate-400">✗ 已取消</p>}
            </div>
          </div>
        )}
        <div ref={scrollRef} />
      </div>
  )

  // ── CSS-driven morph shell: idle ↔ dock ↔ expanded ──────────────────
  // Keep every state on numeric/calc anchors. `auto` cannot interpolate, so it
  // creates the visible jumps that made the shell feel broken.
  const isIdle = mode === 'idle'
  const isExpanded = mode === 'expanded'
  const dockBottom = 'calc(1rem + env(safe-area-inset-bottom, 0px))'
  const morphDuration = '320ms'
  const morphEase = 'cubic-bezier(0.22, 1, 0.36, 1)'

  const shellW = isIdle ? '60px' : 'min(720px, calc(100vw - 32px))'
  const shellH = isIdle ? '60px'
    : isExpanded ? 'min(80svh, 720px)'
    : `${dockHeight}px`
  const shellR = isIdle ? '9999px' : isExpanded ? '24px' : '20px'
  const shellBgVal = isIdle
    ? 'linear-gradient(135deg, #863bff, #47bfff)'
    : 'rgba(255,255,255,0.96)'

  // Bottom-left anchoring stays valid for all three shapes. Expanded mode is
  // centered by moving its bottom edge to half of the remaining viewport height.
  const wrapperClass = 'fixed z-50'
  const wrapperPos: React.CSSProperties = {
    left: isIdle ? 'calc(100vw - 1.5rem - 30px)' : '50vw',
    bottom: isIdle
      ? 'calc(1.5rem + env(safe-area-inset-bottom, 0px))'
      : isExpanded
      ? 'max(10svh, calc((100svh - 720px) / 2))'
      : dockBottom,
    transform: 'translateX(-50%)',
    transition: `left ${morphDuration} ${morphEase}, bottom ${morphDuration} ${morphEase}`,
    willChange: 'left, bottom',
  }

  const showInnerContent = !isIdle

  return (
    <>
      {/* Backdrop only in expanded mode — independent fade, not in morph subtree */}
      <AnimatePresence>
        {isExpanded && (
          <motion.div
            key="guanlan-backdrop"
            initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
            transition={{ duration: 0.2, ease: 'easeOut' }}
            onClick={() => setMode('dock')}
            className="fixed inset-0 z-40 bg-slate-900/30"
            style={{ backdropFilter: 'blur(6px)', WebkitBackdropFilter: 'blur(6px)' }} />
        )}
      </AnimatePresence>

      {/* Shell — width/height change directly, so text is never scale-distorted. */}
      <div className={wrapperClass} style={wrapperPos}>
      <div
        onClick={isIdle ? () => setMode(defaultMode) : undefined}
        style={{
          width: shellW, height: shellH, borderRadius: shellR,
          background: shellBgVal,
          transition: `width ${morphDuration} ${morphEase}, height ${morphDuration} ${morphEase}, border-radius ${morphDuration} ${morphEase}, background ${morphDuration} ease`,
          willChange: 'width, height, border-radius',
        }}
        className={`relative ring-1 shadow-2xl overflow-hidden flex flex-col pb-safe ${
          isIdle
            ? 'ring-white/20 shadow-purple-500/30 cursor-pointer active:brightness-95 items-center justify-center'
            : 'ring-slate-200/70 shadow-purple-500/15'
        }`}>

        {/* Idle state: a single Sparkles centered in the pill.
            Uses AnimatePresence so it fades in only when we land back on idle,
            keeping the shell's layout animation uncluttered. */}
        <AnimatePresence>
          {isIdle && (
            <motion.div key="idle-icon"
              initial={{ opacity: 0, scale: 0.6 }}
              animate={{ opacity: 1, scale: 1 }}
              exit={{ opacity: 0, scale: 0.6 }}
              transition={{ duration: 0.18, ease: 'easeOut' }}
              className="absolute inset-0 flex items-center justify-center text-white pointer-events-none">
              <Sparkles className="w-6 h-6" />
            </motion.div>
          )}
        </AnimatePresence>

        {/* Active state content. Wrapped in a single AnimatePresence so the
            whole inner UI fades in/out as one piece. layout="position" on
            children keeps internal reordering smooth. */}
        <AnimatePresence>
          {showInnerContent && (
            <motion.div key="shell-content"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              transition={{ duration: 0.15, ease: 'easeOut' }}
              className="flex flex-col h-full w-full">

              {/* Gradient hairline */}
              <div ref={hairlineRef} className="h-0.5 shrink-0" style={gradientStyle} />

              {/* Header — only when there's content or expanded */}
              {(hasContent || isExpanded) && headerNode}

              {/* Messages area: when content exists OR we're expanded */}
              {(hasContent || isExpanded) && (
                <div className="flex-1 min-h-0 overflow-hidden flex flex-col">
                  {messagesArea}
                </div>
              )}

              {/* Brand strip — dock idle (empty, but open) only */}
              {!hasContent && !isExpanded && (
                <div ref={brandStripRef} className="flex items-center justify-between px-4 pt-2 pb-1">
                  <div className="flex items-center gap-1.5">
                    <Sparkles className="w-3 h-3" style={{ color: '#863bff' }} />
                    <span className="text-[11px] font-semibold text-slate-700 tracking-tight">观澜</span>
                    <span className="text-[9px] text-slate-400 font-medium bg-slate-100 px-1.5 py-0.5 rounded">AI</span>
                  </div>
                  <div className="flex items-center gap-1">
                    <button onClick={() => setMode('expanded')}
                      className="text-xs text-purple-500 hover:text-purple-600 font-medium transition-colors px-2 py-1 rounded-lg hover:bg-purple-50 inline-flex items-center gap-1"
                      title={t.chat.expand}>
                      <Maximize2 className="w-3.5 h-3.5" />展开
                    </button>
                    <button onClick={onClose}
                      className="text-slate-300 hover:text-slate-500 transition-colors p-1.5 rounded-lg hover:bg-slate-100"
                      title="收起">
                      <X className="w-3.5 h-3.5" />
                    </button>
                  </div>
                </div>
              )}

              {/* Input bar — bottom of every active state */}
              <div ref={inputWrapRef} className={`shrink-0 px-3 py-2 ${isExpanded || hasContent ? 'border-t border-slate-100 bg-white' : ''}`}>
                {inputBar}
                <p className="text-[10px] text-slate-300 text-center">内容由AI生成，不构成投资建议</p>
              </div>
            </motion.div>
          )}
        </AnimatePresence>
        {/* Conversation list modal */}
        <AnimatePresence>
          {showConvList && (
            <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
              className="fixed inset-0 bg-black/30 z-40 flex items-end lg:items-center justify-center lg:p-4"
              onClick={() => setShowConvList(false)}>
              <motion.div initial={{ y: '100%' }} animate={{ y: 0 }} exit={{ y: '100%' }}
                transition={{ type: 'spring', stiffness: 400, damping: 40 }}
                onClick={e => e.stopPropagation()}
                className="w-full lg:max-w-sm bg-white rounded-t-2xl lg:rounded-2xl shadow-xl max-h-[60vh] flex flex-col">
                <div className="flex items-center justify-between px-5 py-3 border-b border-slate-100 shrink-0">
                  <h3 className="text-sm font-bold text-slate-800">对话列表</h3>
                  <button onClick={() => { clearChat(); setShowConvList(false) }} className="text-[11px] text-indigo-600 font-medium hover:text-indigo-700">新对话</button>
                </div>
                <div className="overflow-auto flex-1">
                  {convList.length === 0 ? (
                    <p className="text-xs text-slate-400 text-center py-12">暂无历史对话</p>
                  ) : (
                    convList.map(c => (
                      <div key={c.id} onClick={() => openConv(c.id)}
                        className="flex items-center justify-between px-5 py-3 hover:bg-slate-50 cursor-pointer border-b border-slate-50 transition-colors">
                        <div className="flex-1 min-w-0 mr-2">
                          <p className="text-[13px] text-slate-700 font-medium truncate">{c.title}</p>
                          <p className="text-[10px] text-slate-400 mt-0.5">{c.messageCount ?? 0} 条消息 · {c.createdAt?.substring(0, 10)}</p>
                        </div>
                        <div className="flex items-center gap-1 shrink-0">
                          <button onClick={(e) => { e.stopPropagation(); openConv(c.id) }} className="p-1 rounded hover:bg-slate-100 text-slate-400" title="继续对话"><ArrowRight className="w-3.5 h-3.5" /></button>
                          <button onClick={(e) => deleteConv(c.id, e)} className="p-1 rounded hover:bg-red-50 text-slate-400 hover:text-red-500" title="删除对话"><Trash2 className="w-3.5 h-3.5" /></button>
                        </div>
                      </div>
                    ))
                  )}
                </div>
              </motion.div>
            </motion.div>
          )}
        </AnimatePresence>
      </div>
      </div>
    </>
  )
}

// ── Shared card components ──

function DataCard({ title, score, top, bottom }: { title: string; score: number; top?: { symbol: string; name: string; total_score: number }[]; bottom?: { symbol: string; name: string; total_score: number }[] }) {
  return (
    <div className="mt-3 pt-3 border-t border-slate-200 space-y-2">
      <div className="flex items-center justify-between">
        <span className="text-xs font-semibold text-slate-700">{title}</span>
        <span className={`text-xs font-bold ${score >= 60 ? 'text-emerald-600' : score >= 40 ? 'text-amber-600' : 'text-red-500'}`}>{score.toFixed(0)}分</span>
      </div>
      {top?.length! > 0 && <div className="text-[11px] space-y-1"><span className="text-emerald-600 font-medium">评分最高</span>
        {top!.map((h, i) => (<div key={i} className="flex justify-between text-slate-600"><span>{h.symbol} {h.name}</span><span className="font-medium">{h.total_score?.toFixed(0)}分</span></div>))}
      </div>}
      {bottom?.length! > 0 && <div className="text-[11px] space-y-1"><span className="text-red-500 font-medium">评分最低</span>
        {bottom!.map((h, i) => (<div key={i} className="flex justify-between text-slate-600"><span>{h.symbol} {h.name}</span><span className="font-medium">{h.total_score?.toFixed(0)}分</span></div>))}
      </div>}
    </div>
  )
}

function PicksCardDisplay({ card }: { card: PicksCard }) {
  return (
    <div className="mt-3 pt-3 border-t border-slate-200 space-y-2">
      <div className="flex items-center justify-between">
        <span className="text-xs font-semibold text-slate-700">今日推荐</span>
        <span className="text-[10px] text-slate-400">扫描 {card.scanned} 只 · {card.regime}</span>
      </div>
      {card.picks?.map((p, i) => (
        <div key={i} className="flex items-center gap-2 text-[11px]">
          <span className="font-medium text-slate-900 w-16">{p.code}</span>
          <span className="text-slate-500 flex-1 truncate">{p.name}</span>
          <span className={`font-bold ${(p.total_score ?? 0) >= 60 ? 'text-emerald-600' : 'text-slate-600'}`}>{p.total_score?.toFixed(0)}分</span>
          {p.bullish?.slice(0, 2).map((r, ri) => (<span key={ri} className="text-[10px] px-1 py-0.5 bg-emerald-50 text-emerald-600 rounded">{r}</span>))}
        </div>
      ))}
    </div>
  )
}

function StrategyCard({ name, description, code: _code, onSave }: { name: string; description: string; code: string; onSave: (name: string) => Promise<void> }) {
  const { t } = useT()
  const [saving, setSaving] = useState(false)

  const displayName = name || t.chat.strategyPlaceholder
  const lines = (description || '').split(/\n/).map(l => l.trim()).filter(Boolean)

  // Classify description lines into entry / exit / risk / other buckets
  const entryRx = /(买入|入场|进场|做多|buy|entry|long)/i
  const exitRx  = /(卖出|出场|离场|做空|止盈|sell|exit|short|take.profit)/i
  const riskRx  = /(止损|风控|仓位|回撤|stop|position|risk|capital)/i
  const entryLines = lines.filter(l => entryRx.test(l) && !riskRx.test(l))
  const exitLines  = lines.filter(l => exitRx.test(l) && !riskRx.test(l))
  const riskLines  = lines.filter(l => riskRx.test(l))
  const otherLines = lines.filter(l => !entryRx.test(l) && !exitRx.test(l) && !riskRx.test(l) && l !== displayName)

  function renderRow(label: string, i: number) {
    return (
      <div key={i} className="flex items-start gap-1.5 font-semibold text-slate-900">
        <span className="w-1 h-1 rounded-full bg-purple-400 mt-[5px] shrink-0" />
        <span>{label}</span>
      </div>
    )
  }

  return (
    <div className="mt-3 pt-3 border-t border-slate-200">
      <div className="rounded-xl bg-white border border-purple-200 shadow-sm overflow-hidden">
        {/* Header with gradient strip */}
        <div className="flex items-center gap-2 px-3 py-2" style={gradientStyle}>
          <Sparkles className="w-3.5 h-3.5 text-white" />
          <span className="text-xs font-semibold text-white tracking-tight">{displayName}</span>
        </div>

        <div className="px-3 py-2 space-y-2 text-[11px] leading-relaxed">
          {/* Entry rules */}
          {entryLines.length > 0 && (
            <div>
              <div className="text-[10px] font-semibold text-emerald-600 mb-1 flex items-center gap-1">
                <span className="w-1 h-1 rounded-full bg-emerald-500" />入场条件
              </div>
              {entryLines.map(renderRow)}
            </div>
          )}
          {/* Exit rules */}
          {exitLines.length > 0 && (
            <div>
              <div className="text-[10px] font-semibold text-red-500 mb-1 flex items-center gap-1">
                <span className="w-1 h-1 rounded-full bg-red-400" />出场条件
              </div>
              {exitLines.map(renderRow)}
            </div>
          )}
          {/* Risk management */}
          {riskLines.length > 0 && (
            <div>
              <div className="text-[10px] font-semibold text-amber-600 mb-1 flex items-center gap-1">
                <span className="w-1 h-1 rounded-full bg-amber-400" />风控与仓位
              </div>
              {riskLines.map(renderRow)}
            </div>
          )}
          {/* Unclassified lines */}
          {otherLines.length > 0 && (
            <div className="text-slate-500 space-y-1">
              {otherLines.map((l, i) => <div key={i}>{l}</div>)}
            </div>
          )}
        </div>

        {/* Save button */}
        <div className="px-3 pb-3">
          <button onClick={async () => {
            const savedName = name || prompt(t.chat.promptStrategyName, t.chat.strategyPlaceholder)
            if (!savedName) return
            setSaving(true)
            try { await onSave(savedName) } finally { setSaving(false) }
          }} disabled={saving}
            className="w-full h-9 rounded-lg text-white text-xs font-semibold transition-all flex items-center justify-center gap-1.5 tracking-wide hover:opacity-90 disabled:opacity-60"
            style={gradientStyle}>
            {saving ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Check className="w-3.5 h-3.5 opacity-70" />}
            {saving ? t.chat.saving : t.chat.saveStrategyBtn}
          </button>
        </div>
      </div>
    </div>
  )
}
