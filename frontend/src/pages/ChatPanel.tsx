import { useState, useRef, useEffect, useLayoutEffect, useMemo } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
import { Sparkles, X, Send, Trash2, Check, Loader2, Globe, Square, Maximize2, Minimize2, Wrench, Search, BookOpen, MessageSquare, ArrowRight, FileText, HelpCircle, Brain, BarChart2 } from 'lucide-react'
import { useToast } from '@/components/Toast'
import { useConfirm } from '@/hooks/use-confirm'
import { usePrompt } from '@/hooks/use-prompt'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import type { SseEvent } from '@/types'
import { useT } from '@/i18n/I18nContext'
import { localizeToolName, MAPS } from '@/i18n/toolNames'
import { getCachedSuggestions, getSuggestionsPromise } from '@/services/aiPreload'
import { BASE } from '@/services/api'

interface PortfolioCard { portfolio_score: number; holdings_scored: number; top_holdings: { symbol: string; name: string; total_score: number }[]; bottom_holdings: { symbol: string; name: string; total_score: number }[]; group_exposure: Record<string, { buy_score: number }> }
interface PicksCard { regime: string; picks: { code: string; name: string; total_score: number; buy_score: number; bullish: string[] }[]; scanned: number }
interface ReportArtifact {
  id?: number
  type: string
  title: string
  summary?: string
  contentJson?: unknown
  content_json?: unknown
  contentMarkdown?: string
  content_markdown?: string
  createdAt?: string
  created_at?: string
}
/** A single step in the agent's reasoning trace. Either a chunk of native
 * reasoning_content, or a tool call (with its eventual completion status). */
export type ToolCategory = 'query' | 'analysis' | 'mutation'
export type TimelineStep =
  | { kind: 'thinking'; text: string; _ts?: number; _elapsed?: number }
  | { kind: 'kb'; topic: string }
  | { kind: 'memory'; count?: string | number }
  | { kind: 'tool'; name: string; category?: ToolCategory; done: boolean; error?: string; summary?: string; callId?: string }
  // The assistant's user-facing answer, interleaved in true emission order with
  // thinking/tool steps so a chunk of answer written *before* a later tool call
  // renders above that tool call — not dumped below the whole timeline.
  | { kind: 'text'; text: string }
interface Message { role: 'user' | 'assistant' | 'system'; content: string; thinking?: string; timeline?: TimelineStep[]; hasCode?: boolean; strategyName?: string; strategyDesc?: string; strategyCode?: string; confirm?: ConfirmData; portfolioCard?: PortfolioCard; picksCard?: PicksCard; artifacts?: ReportArtifact[] }
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

function isStockSageTool(name: string) {
  return name === 'get_stock_report'
    || name === 'get_portfolio_report'
    || name === 'get_daily_picks_report'
}

// ── Tool icon registry ────────────────────────────────────────────────
// One map for all per-tool icon assignments. Tools not listed here fall
// back to category defaults: query→Search, analysis→Wrench, mutation→Wrench.
const TOOL_ICONS: Record<string, typeof Search> = {
  get_stock_report: Sparkles,
  get_portfolio_report: Sparkles,
  get_daily_picks_report: Sparkles,
  ask_user: HelpCircle,
  consult_kb: BookOpen,
}

function getToolIcon(name: string, category: ToolCategory) {
  return TOOL_ICONS[name] || (category === 'query' ? Search : Wrench)
}

/** Per-category visual treatment. Icons come from TOOL_ICONS; label
 * colours are determined by category and done/error state. */
function toolStyle(step: Extract<TimelineStep, { kind: 'tool' }>) {
  const cat: ToolCategory = step.category || 'query'
  const isStockSage = isStockSageTool(step.name)
  const Icon = getToolIcon(step.name, cat)
  if (step.error) {
    return { label: 'text-red-600', dot: 'bg-red-400', Icon }
  }
  if (step.done) {
    return { label: 'text-emerald-600', dot: 'bg-emerald-400', Icon }
  }
  if (isStockSage) {
    return { label: 'text-purple-500', dot: 'bg-purple-400 animate-pulse', Icon }
  }
  if (cat === 'mutation') {
    return { label: 'text-amber-600', dot: 'bg-amber-400 animate-pulse', Icon }
  }
  if (cat === 'query') {
    return { label: 'text-slate-500', dot: 'bg-slate-400 animate-pulse', Icon }
  }
  return { label: 'text-purple-500', dot: 'bg-purple-400 animate-pulse', Icon }
}

// ── Peer-level step renderers: each step is a standalone block ──────────
// Like Claude Code's UI: thinking segments and tool calls are peers,
// not nested inside one container. Tools interrupt thinking, then a new
// thinking block picks up after the tool completes.

/** A single reasoning segment: collapsible text block with live elapsed timer.
 *  Claude Code style: "已思考 用时8s" when done, live count-up while streaming. */
function ThinkingSegment({ text, done, _ts, _elapsed }: { text: string; done: boolean; _ts?: number; _elapsed?: number }) {
  const { lang } = useT()
  // Replace English tool names in thinking text with styled code-block tags
  const localizedText = useMemo(() => {
    if (!text) return ''
    let t = text
    // Build regex from tool label keys (longest first to avoid partial matches)
    const toolNames = Object.keys(MAPS[lang] || {})
      .sort((a, b) => b.length - a.length)
    for (const name of toolNames) {
      const label = MAPS[lang]?.[name]
      if (label) {
        // Replace with inline code block using the localized label
        t = t.replace(new RegExp(`\\b${name}\\b`, 'g'), `\`🛠 ${label}\``)
      }
    }
    return t
  }, [text, lang])
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
          className="mt-1 p-2.5 bg-slate-50 rounded-lg text-[11px] text-slate-500 leading-relaxed border-l-2 border-purple-200 max-h-48 overflow-y-auto">
          <ReactMarkdown remarkPlugins={[remarkGfm]}
            components={{
              p: ({ children }) => <p className="mb-1 last:mb-0">{children}</p>,
              code: ({ className, children }) => {
                const isInline = !className
                const text = String(children)
                if (isInline && text.startsWith('🛠 ')) {
                  // Tool tag: render as a colored chip
                  const toolName = text.replace('🛠 ', '')
                  return (
                    <code className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-indigo-100 text-indigo-700 text-[10px] font-medium border border-indigo-200">
                      <svg className="w-3 h-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"/></svg>
                      {toolName}
                    </code>
                  )
                }
                return isInline
                  ? <code className="px-1 py-0.5 bg-slate-200/70 rounded text-[10px] font-mono">{children}</code>
                  : <pre className="p-2 bg-slate-800 text-slate-100 rounded-lg text-[10px] overflow-x-auto my-1">{children}</pre>
              },
            }}>
            {localizedText}
          </ReactMarkdown>
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
  const stockSage = isStockSageTool(step.name)
  const displayName = stockSage
    ? (lang === 'en' ? 'StockSage engine' : 'StockSage 量化引擎')
    : localizeToolName(step.name, lang)
  const statusText = running
    ? (lang === 'en' ? 'running' : '运行中')
    : (lang === 'en' ? 'completed' : '已完成')
  const bounce = stockSage ? 'bg-purple-400'
    : step.category === 'mutation' ? 'bg-amber-400'
    : step.category === 'analysis' ? 'bg-purple-400'
    : 'bg-slate-400'
  return (
    <motion.div initial={{ opacity: 0, y: 3 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.18, ease: 'easeOut' }}
      className="mb-1.5 flex items-start gap-2 text-[11px]">
      <span className={`mt-1 w-1.5 h-1.5 rounded-full shrink-0 ${dot}`} />
      <div className={`flex-1 flex items-center gap-1.5 ${label}`}>
        <Icon className={`w-3 h-3 ${running ? 'animate-pulse' : ''}`} />
        <span className="font-medium">{displayName}</span>
        {!step.error && !stockSage && <span className="text-[10px] opacity-70">{statusText}</span>}
        {running && (
          <span className="inline-flex gap-0.5 ml-0.5" aria-label={statusText}>
            <span className={`w-1 h-1 rounded-full animate-bounce ${bounce}`} />
            <span className={`w-1 h-1 rounded-full animate-bounce ${bounce}`} style={{ animationDelay: '0.15s' }} />
            <span className={`w-1 h-1 rounded-full animate-bounce ${bounce}`} style={{ animationDelay: '0.3s' }} />
          </span>
        )}
        {step.done && !step.error && <Check className="w-3 h-3 opacity-70" />}
        {step.done && !step.error && step.summary && !stockSage && (
          <span className="text-[10px] text-slate-400">{step.summary}</span>
        )}
        {/* StockSage engine: status shown as summary instead of inline */}
        {!step.error && stockSage && (
          <span className="text-[10px] text-slate-400">{statusText}</span>
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

/** A few providers fold their reasoning into the answer with <think> tags
 * instead of a separate reasoning channel. Strip the bare tags so they never
 * leak into the rendered answer (the content itself is kept). */
function stripThinkTags(text: string): string {
  return text.replace(/<\/?think(?:ing)?>/gi, '')
}

const MARKDOWN_BODY_CLASS = 'prose prose-sm prose-slate max-w-none text-[13px] [&_table]:text-[11px] [&_th]:border [&_th]:border-slate-200 [&_th]:px-2 [&_th]:py-1 [&_td]:border [&_td]:border-slate-100 [&_td]:px-2 [&_td]:py-1 [&_table]:w-full [&_code]:bg-slate-100 [&_code]:px-1 [&_code]:rounded [&_pre]:bg-slate-100 [&_pre]:p-2 [&_pre]:rounded-lg [&_pre]:overflow-auto'

/** Shared renderer for the assistant's answer markdown (stock-code chips +
 * GFM). Used by both the live/historical timeline text steps and the legacy
 * single-block fallback, so all answer text renders identically. */
function MarkdownBody({ text }: { text: string }) {
  return (
    <div className={MARKDOWN_BODY_CLASS}>
      <ReactMarkdown remarkPlugins={[remarkGfm]} components={{
        code: ({ children, className, ...props }) => {
          const txt = String(children).trim()
          if (/^\d{4,6}\.(SH|SZ|HK|US)$/i.test(txt) || /^[A-Z]{1,5}\.US$/i.test(txt))
            return <a href={`${BASE}/stock?symbol=${txt}`} target="_blank" className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded bg-purple-50 text-purple-700 text-xs font-medium hover:bg-purple-100">{txt}</a>
          return <code className={className} {...props}>{children}</code>
        }
      }}>{text}</ReactMarkdown>
    </div>
  )
}

/** Renders an array of TimelineSteps as a peer-level sequence:
 * each thinking segment gets its own collapse block, each tool gets its
 * own inline indicator, and each answer chunk renders inline at its true
 * position — exactly how Claude Code / Codex render. */
function TimelineRenderer({ steps, done, lang }: { steps: TimelineStep[]; done: boolean; lang: 'zh' | 'en' | 'hk' }) {
  if (steps.length === 0) return null
  return (
    <div className="mb-2">
      {steps.map((step, i) => {
        if (step.kind === 'text') {
          const body = stripThinkTags(step.text)
          if (!body.trim()) return null
          return <div key={i} className="mb-2"><MarkdownBody text={body} /></div>
        }
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
        if (step.kind === 'memory') {
          return (
            <motion.div key={i} initial={{ opacity: 0, y: 3 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.18, ease: 'easeOut' }}
              className="mb-1.5 flex items-start gap-2 text-[11px]">
              <span className="mt-1 w-1.5 h-1.5 rounded-full shrink-0 bg-blue-400" />
              <div className="flex-1 flex items-center gap-1.5 text-blue-600">
                <Brain className="w-3 h-3" />
                <span className="font-medium">{lang === 'en' ? 'Recalling memory' : '读取记忆'}</span>
                <Check className="w-3 h-3 opacity-70" />
                {step.count ? <span className="text-[10px] text-slate-400">· {step.count} {lang === 'en' ? 'items' : '条'}</span> : null}
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
  const confirm = useConfirm()
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
  const pendingArtifacts = useRef<ReportArtifact[]>([])
  const streamAccum = useRef('')
  // Authoritative timeline ref — frontend builds it from SSE events as the agent runs.
  // We mutate the ref then mirror into state for re-renders so we don't lose ordering.
  const timelineRef = useRef<TimelineStep[]>([])
  const [selectedArtifact, setSelectedArtifact] = useState<ReportArtifact | null>(null)
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
  const [askData, setAskData] = useState<{ question: string; options: (string | { value: string; label: string })[]; multiSelect: boolean } | null>(null)
  const optionDisplay = (o: string | { value: string; label: string }) => typeof o === 'string' ? o : (o.label || o.value || '')
  const optionValue = (o: string | { value: string; label: string }) => typeof o === 'string' ? o : (o.value || o.label || '')
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
          const restored: Message[] = d.messages.map((m: { role: string; content: string; thinking?: string; artifacts?: ReportArtifact[] }) => {
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
              ...(m.artifacts?.length ? { artifacts: m.artifacts } : {}),
            }
          })
          setMessages(restored)
          // Restore the conversation ID so subsequent operations (e.g. delete) work correctly
          if (d.conversationId && d.conversationId > 0) convIdRef.current = d.conversationId
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
      es.addEventListener('artifact', (e) => {
        try {
          const artifact = JSON.parse(e.data) as ReportArtifact
          if (artifact?.title) pendingArtifacts.current = [...pendingArtifacts.current, artifact]
        } catch {}
      })
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
      es.addEventListener('memory', (e) => {
        const d = JSON.parse(e.data) as { count?: string | number }
        stampClosedElapsed(timelineRef.current)
        timelineRef.current = [...timelineRef.current, { kind: 'memory', count: d.count }]
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
      es.addEventListener('token', (e) => {
        const d: SseEvent = JSON.parse(e.data)
        const chunk = d.msg || ''
        if (!chunk) return
        // Keep the full-text accumulator (used for copy, strategy/code regex, and
        // the legacy <think> fallback at done-time)…
        streamAccum.current += chunk; setStreamText(streamAccum.current)
        // …and also fold the answer into the timeline at its true position, so it
        // interleaves correctly with thinking/tool steps instead of dropping below.
        const last = timelineRef.current[timelineRef.current.length - 1]
        if (last && last.kind === 'text') {
          timelineRef.current[timelineRef.current.length - 1] = { ...last, text: last.text + chunk }
        } else {
          stampClosedElapsed(timelineRef.current)
          timelineRef.current = [...timelineRef.current, { kind: 'text', text: chunk }]
        }
        pushTimeline()
      })
      es.addEventListener('reasoning', (e) => {
        const d: SseEvent = JSON.parse(e.data)
        const chunk = d.msg || ''
        if (!chunk) return
        // Append to the last thinking step, or open a new one if the last step is a
        // tool or answer chunk.
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
        if (!raw.trim() && !hasTimeline && pendingArtifacts.current.length === 0) { setStreaming(false); es.close(); esRef.current = null; return }
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
        const msg: Message = { role: 'assistant', content: content.trim() || raw || 'StockSage 报告已生成。', timeline: timelineOut }
        const s = pendingStrategy.current
        if (s) { msg.hasCode = true; msg.strategyName = s.name; msg.strategyDesc = s.desc; msg.strategyCode = s.code; pendingStrategy.current = null }
        const card = pendingCard.current
        if (card) { if (card.type === 'portfolio') msg.portfolioCard = card.data; else if (card.type === 'picks') msg.picksCard = card.data; pendingCard.current = null }
        const artifacts = pendingArtifacts.current
        if (artifacts.length > 0) { msg.artifacts = artifacts; pendingArtifacts.current = [] }
        if (!s && !card) {
          const codeMatch = raw.match(/```(?:python)?\s*\n(def decide\(ctx[^)]*\):[\s\S]*?)```/)
          if (codeMatch) { const nameMatch = raw.match(/(?:策略名称|策略)[：:]\s*(.+)/); msg.hasCode = true; msg.strategyName = nameMatch ? nameMatch[1].trim() : ''; msg.strategyCode = codeMatch[1].trim(); msg.strategyDesc = '' }
        }
        setMessages([...baseMessages, msg]); setStreaming(false); es.close(); esRef.current = null
      })
      es.addEventListener('error', (e) => {
        pendingStrategy.current = null
        pendingArtifacts.current = []
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
      setStreaming(true); setStreamText(''); setStreamTimeline([]); streamAccum.current = ''; timelineRef.current = []; pendingArtifacts.current = []
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
    setStreaming(true); setStreamText(''); setStreamTimeline([]); streamAccum.current = ''; timelineRef.current = []; pendingArtifacts.current = []; setAskData(null); setAskChecked(new Set()); setAskOther(''); setConfirmData(null); setConfirmStatus(null); setConfirmResult('')
    try {
      const resp = await fetch(`${BASE}/api/ai/chat`, { method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ messages: newMessages, webSearch, conversationId: convIdRef.current || undefined }) })
      if (!resp.ok) { setStreamText(`${t.chat.errorPrefix} HTTP ${resp.status}`); setStreaming(false); return }
      // Capture the server-assigned conversation id (auto-created on the first turn)
      // so deleting the active conversation later correctly clears this window.
      try { const data = await resp.json() as { conversationId?: number }; if (data.conversationId && data.conversationId > 0) convIdRef.current = data.conversationId } catch { /* non-JSON / ignore */ }
      if (esRef.current) esRef.current.close()
      const es = new EventSource(`${BASE}/api/ai/stream`, { withCredentials: true }); esRef.current = es
      attachStream(es, newMessages)
    } catch (e: unknown) { setStreamText(`${t.chat.errorPrefix} ${e instanceof Error ? e.message : String(e)}`); setStreaming(false) }
  }

  const convIdRef = useRef<number>(0)
  const [convList, setConvList] = useState<{ id: number; title: string; createdAt: string; messageCount: number }[]>([])
  const [showConvList, setShowConvList] = useState(false)

  /** Reset local chat state without touching the database.
   *  Does NOT call /api/ai/clear — that endpoint destroys ALL user history. */
  function resetChat() {
    gMessages = []
    setMessages([])
    setStreamText('')
    convIdRef.current = 0
    pendingArtifacts.current = []
    pendingStrategy.current = null
    pendingCard.current = null
  }

  /** Start a brand-new conversation — creates a DB row so the list shows it. */
  function newConversation() {
    resetChat()
    fetch(`${BASE}/api/ai/conversations`, { method: 'POST', credentials: 'include' })
      .then(r => r.json())
      .then(d => { if (d?.id) convIdRef.current = d.id })
      .catch(() => {})
    setShowConvList(false)
  }

  function loadConvList() {
    fetch(`${BASE}/api/ai/conversations`, { credentials: 'include' })
      .then(r => r.json()).then((list: any[]) => setConvList(list || [])).catch(() => {})
  }

  function openConv(id: number) {
    fetch(`${BASE}/api/ai/conversations/${id}`, { credentials: 'include' })
      .then(r => r.json()).then((d: any) => {
        const restored = (d.messages || []).map((m: { role: string; content: string; thinking?: string; artifacts?: ReportArtifact[] }) => {
          let timeline: TimelineStep[] | undefined
          let thinkingLegacy: string | undefined
          const raw = m.thinking?.trim()
          if (raw) {
            if (raw.startsWith('[')) { try { const p = JSON.parse(raw); if (Array.isArray(p)) timeline = p } catch { thinkingLegacy = raw } }
            else thinkingLegacy = raw
          }
          return { role: m.role === 'user' ? 'user' as const : m.role === 'assistant' ? 'assistant' as const : 'system' as const, content: m.content, ...(timeline ? { timeline } : {}), ...(thinkingLegacy ? { thinking: thinkingLegacy } : {}), ...(m.artifacts?.length ? { artifacts: m.artifacts } : {}) }
        })
        gMessages = restored; setMessages(restored); convIdRef.current = id; setShowConvList(false)
      }).catch(() => {})
  }

  async function deleteConv(id: number, e: React.MouseEvent) {
    e.stopPropagation()
    if (!(await confirm(t.chat.confirmDeleteConv))) return
    fetch(`${BASE}/api/ai/conversations/${id}`, { method: 'DELETE', credentials: 'include' })
      .then(() => {
        if (convIdRef.current === id) resetChat()
        loadConvList()
      }).catch(() => {})
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
              {m.role === 'assistant' ? (() => {
                const tl = m.timeline
                // New turns carry the answer as interleaved text steps → render the
                // timeline alone (the answer is inside it, in true order). Legacy
                // turns have no text step → keep the old "timeline then content" layout.
                const hasTextStep = !!tl && tl.some(s => s.kind === 'text')
                return <>
                  {tl && tl.length > 0
                    ? <TimelineRenderer steps={tl} done={true} lang={lang} />
                    : m.thinking && <TimelineRenderer steps={[{ kind: 'thinking', text: m.thinking }]} done={true} lang={lang} />}
                  {!hasTextStep && m.content && <MarkdownBody text={m.content} />}
                </>
              })() : <div style={{ whiteSpace: 'pre-wrap' }}>{m.content}</div>}
              {m.hasCode && (
                <StrategyCard
                  name={m.strategyName || ''}
                  description={m.strategyDesc || ''}
                  code={m.strategyCode || m.content}
                  onSave={async (name: string): Promise<boolean> => {
                    const code = m.strategyCode || m.content
                    try {
                      const res = await fetch(`${BASE}/api/backtest/strategies`, { method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name, strategyType: 'advanced', strategy: { code } }) })
                      if (!res.ok) {
                        // Network-level failure (e.g. server down, CORS blocked, session expired → 401)
                        if (res.status === 401) { toast('登录已过期，请刷新页面后重试', false); return false }
                        let errMsg = `保存失败 (HTTP ${res.status})`
                        try { const d = await res.json() as { error?: string }; if (d.error) errMsg = String(d.error) } catch { /* non-JSON body */ }
                        toast(errMsg, false); return false
                      }
                      const data = await res.json() as { error?: string; id?: number; status?: string }
                      if (data.error) { toast(String(data.error), false); return false }
                      toast(t.chat.strategySaved, true)
                      // Hand the freshly-saved strategy_id back so 观澜 keeps going
                      // (e.g. runs the backtest the user originally asked for) instead
                      // of stalling on the saved card.
                      const sid = typeof data.id === 'number' ? data.id : Number(data.id)
                      if (sid && !streaming) send(`策略「${name}」已保存（strategy_id=${sid}），请继续。`)
                      return true
                    } catch (e: unknown) {
                      // TypeError "Failed to fetch" — request never reached the server
                      toast(e instanceof TypeError ? '网络请求失败，请检查网络连接' : (e instanceof Error ? e.message : '保存失败'), false)
                      return false
                    }
                  }}
                />
              )}
              {m.portfolioCard && <DataCard title="组合因子分析" score={m.portfolioCard.portfolio_score ?? 0} top={m.portfolioCard.top_holdings} bottom={m.portfolioCard.bottom_holdings} />}
              {m.picksCard && <PicksCardDisplay card={m.picksCard} />}
              {m.artifacts?.length ? (
                <div className="mt-3 pt-3 border-t border-slate-200 flex flex-wrap gap-2">
                  {m.artifacts.map((artifact, index) => (
                    <ReportArtifactChip key={`${artifact.id ?? artifact.title}-${index}`} artifact={artifact} onOpen={setSelectedArtifact} />
                  ))}
                </div>
              ) : null}
            </div>
          </div>
        ))}
        {streaming && (
          <div className="flex justify-start">
            <div className={`max-w-[85%] rounded-2xl px-4 py-2.5 text-sm leading-relaxed ${streamText.startsWith('⚠') ? 'bg-red-50 text-red-700 border border-red-100' : 'bg-slate-50 text-slate-700 border border-slate-100'}`}>
              {(() => {
                // The live answer is already interleaved into streamTimeline as
                // text steps, so there's no separate "after" block — order is exact.
                const liveSteps = streamTimeline
                // Idle pulse only when nothing has streamed yet (no thinking, no
                // answer chunk, no tool). A running tool renders its own animated row.
                const showGenericPending = liveSteps.length === 0
                return (<>
                  {liveSteps.length > 0 && <TimelineRenderer steps={liveSteps} done={false} lang={lang} />}
                  {showGenericPending && (
                    <span className="inline-flex gap-1"><span className="w-1.5 h-1.5 rounded-full bg-slate-300 animate-bounce" /><span className="w-1.5 h-1.5 rounded-full bg-slate-300 animate-bounce" style={{ animationDelay: '0.1s' }} /><span className="w-1.5 h-1.5 rounded-full bg-slate-300 animate-bounce" style={{ animationDelay: '0.2s' }} /></span>
                  )}
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
              {askData.options.map((o, i) => {
                const checked = askChecked.has(i)
                const display = optionDisplay(o)
                const val = optionValue(o)
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
                      body: JSON.stringify({ answer: val }),
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
                    {display}
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
                  const selected = askData.options.filter((_, i) => askChecked.has(i)).map(optionValue).join('、')
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

  // ── Two-stage morph choreography ────────────────────────────────────
  // Open  (idle → open):  the orb GLIDES to its anchor first, then the body
  //                       BLOOMS open one beat later, with a soft spring overshoot.
  // Close (open → idle):  reversed AND a touch snappier — the body COLLAPSES
  //                       first, then the orb glides back to the corner.
  // CSS transitions are direction-blind, so we flip the per-property delays on
  // the target state (isIdle): whichever stage leads gets delay 0, the other waits.
  const POS_EASE  = 'cubic-bezier(0.22, 1, 0.36, 1)'     // smooth glide
  const SIZE_EASE = 'cubic-bezier(0.34, 1.28, 0.64, 1)'  // gentle spring overshoot
  const posDur    = isIdle ? '300ms' : '260ms'
  const sizeDur   = isIdle ? '260ms' : '340ms'
  const STAGGER   = '110ms'
  const posDelay  = isIdle ? STAGGER : '0ms'   // open: lead;  close: wait for collapse
  const sizeDelay = isIdle ? '0ms'   : STAGGER // open: wait for glide;  close: lead

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
    // translateZ(0) promotes a compositing layer so the left/bottom glide
    // repaints only this element's layer, not the page.
    transform: 'translateX(-50%) translateZ(0)',
    transition: `left ${posDur} ${POS_EASE} ${posDelay}, bottom ${posDur} ${POS_EASE} ${posDelay}`,
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
          transition: `width ${sizeDur} ${SIZE_EASE} ${sizeDelay}, height ${sizeDur} ${SIZE_EASE} ${sizeDelay}, border-radius ${sizeDur} ${SIZE_EASE} ${sizeDelay}, background 220ms ease ${sizeDelay}`,
          willChange: 'width, height, border-radius',
        }}
        className={`relative ring-1 shadow-2xl overflow-hidden flex flex-col pb-safe ${
          isIdle
            ? 'ring-white/20 shadow-purple-500/30 cursor-pointer active:brightness-95 items-center justify-center'
            : 'ring-slate-200/70 shadow-purple-500/15'
        }`}>

        {/* Bloom + sheen — the "绽放" flourish. One-shot on open, fades on close.
            Both pointer-events-none and clipped by the shell's overflow-hidden. */}
        <AnimatePresence>
          {!isIdle && (
            <motion.div key="guanlan-bloom"
              initial={{ opacity: 0, scale: 0.25 }}
              animate={{ opacity: [0.5, 0], scale: 1.7 }}
              exit={{ opacity: 0 }}
              transition={{ duration: 0.7, ease: 'easeOut', delay: 0.1 }}
              className="pointer-events-none absolute left-1/2 bottom-0 -translate-x-1/2"
              style={{ width: '120%', aspectRatio: '1', borderRadius: '9999px',
                       background: 'radial-gradient(circle, rgba(134,59,255,0.40), transparent 62%)' }} />
          )}
          {!isIdle && (
            <motion.div key="guanlan-sheen"
              initial={{ x: '-130%', opacity: 0 }}
              animate={{ x: '160%', opacity: [0, 0.55, 0] }}
              exit={{ opacity: 0 }}
              transition={{ duration: 0.85, ease: 'easeOut', delay: 0.18 }}
              className="pointer-events-none absolute inset-y-0 w-1/2"
              style={{ background: 'linear-gradient(105deg, transparent, rgba(255,255,255,0.6), transparent)' }} />
          )}
        </AnimatePresence>

        {/* Idle state: a single Sparkles centered in the pill.
            Uses AnimatePresence so it fades in only when we land back on idle,
            keeping the shell's layout animation uncluttered. */}
        <AnimatePresence>
          {isIdle && (
            <motion.div key="idle-icon"
              initial={{ opacity: 0, scale: 0.6 }}
              animate={{ opacity: 1, scale: 1 }}
              exit={{ opacity: 0, scale: 0.6, transition: { duration: 0.1, ease: 'easeIn' } }}
              transition={{ duration: 0.24, ease: [0.34, 1.28, 0.64, 1], delay: 0.12 }}
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
              initial={{ opacity: 0, y: 12 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: 10, transition: { duration: 0.13, ease: 'easeIn' } }}
              transition={{ duration: 0.30, ease: [0.22, 1, 0.36, 1], delay: 0.16 }}
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
                <p className="text-[10px] text-slate-300 text-center">{t.chat.aiDisclaimer}</p>
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
                  <h3 className="text-sm font-bold text-slate-800">{t.chat.conversations}</h3>
                  <button onClick={newConversation} className="text-[11px] text-indigo-600 font-medium hover:text-indigo-700">{t.chat.newConversation}</button>
                </div>
                <div className="overflow-auto flex-1">
                  {convList.length === 0 ? (
                    <p className="text-xs text-slate-400 text-center py-12">{t.chat.noConversations}</p>
                  ) : (
                    convList.map(c => (
                      <div key={c.id} onClick={() => openConv(c.id)}
                        className="flex items-center justify-between px-5 py-3 hover:bg-slate-50 cursor-pointer border-b border-slate-50 transition-colors">
                        <div className="flex-1 min-w-0 mr-2">
                          <p className="text-[13px] text-slate-700 font-medium truncate">{c.title}</p>
                          <p className="text-[10px] text-slate-400 mt-0.5">{t.chat.messageCount.replace('{n}', String(c.messageCount ?? 0))} · {c.createdAt?.substring(0, 10)}</p>
                        </div>
                        <div className="flex items-center gap-1 shrink-0">
                          <button onClick={(e) => { e.stopPropagation(); openConv(c.id) }} className="p-1 rounded hover:bg-slate-100 text-slate-400" title={t.chat.continueConv}><ArrowRight className="w-3.5 h-3.5" /></button>
                          <button onClick={(e) => deleteConv(c.id, e)} className="p-1 rounded hover:bg-red-50 text-slate-400 hover:text-red-500" title={t.chat.deleteConv}><Trash2 className="w-3.5 h-3.5" /></button>
                        </div>
                      </div>
                    ))
                  )}
                </div>
              </motion.div>
            </motion.div>
          )}
        </AnimatePresence>
        <ReportDetailModal artifact={selectedArtifact} onClose={() => setSelectedArtifact(null)} />
      </div>
      </div>
    </>
  )
}

// ── Shared card components ──

function artifactLabel(type: string) {
  if (type === 'stock_report') return 'StockSage 个股报告'
  if (type === 'portfolio_report') return 'StockSage 组合报告'
  if (type === 'daily_picks_report') return 'StockSage 选股报告'
  // Backtests come from the quant backtest engine, not StockSage — label them
  // distinctly so a strategy report isn't mistaken for a stock audit.
  if (type === 'backtest_result') return '量化回测报告'
  return 'StockSage 报告'
}

function artifactContent(artifact: ReportArtifact): Record<string, unknown> {
  const raw = artifact.contentJson ?? artifact.content_json
  if (!raw) return {}
  if (typeof raw === 'string') {
    try {
      const parsed = JSON.parse(raw)
      return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed as Record<string, unknown> : {}
    } catch {
      return {}
    }
  }
  return raw && typeof raw === 'object' && !Array.isArray(raw) ? raw as Record<string, unknown> : {}
}

function stringItems(value: unknown): string[] {
  if (!Array.isArray(value)) return []
  return value.map(item => {
    if (typeof item === 'string') return item
    if (item && typeof item === 'object') {
      const row = item as Record<string, unknown>
      return String(row.text ?? row.signal ?? row.name ?? JSON.stringify(row))
    }
    return String(item)
  }).filter(Boolean)
}

function objectList(value: unknown): Record<string, unknown>[] {
  if (!Array.isArray(value)) return []
  return value.filter(item => item && typeof item === 'object' && !Array.isArray(item)) as Record<string, unknown>[]
}

function objectEntries(value: unknown): string[] {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return []
  const out: string[] = []
  for (const [key, val] of Object.entries(value as Record<string, unknown>)) {
    if (val === null || val === undefined) continue
    if (Array.isArray(val)) {
      // 人类可读的规则/列表用顿号拼接；嵌套对象项跳过。
      const items = val.map(v => (v && typeof v === 'object' ? '' : String(v))).filter(Boolean)
      if (items.length) out.push(`${key}：${items.join('、')}`)
    } else if (typeof val === 'object') {
      // 嵌套对象（如 weights_used 权重字典）是机器调参字段，不向用户展示。
      continue
    } else {
      out.push(`${key}：${String(val)}`)
    }
  }
  return out
}

// 摘要偶尔会被上游误塞成一段 meta JSON；像 JSON 的内容不展示给用户。
function looksLikeJson(text: string): boolean {
  const t = text.trim()
  return (t.startsWith('{') && t.endsWith('}')) || (t.startsWith('[') && t.endsWith(']'))
}

function ReportArtifactChip({ artifact, onOpen }: { artifact: ReportArtifact; onOpen: (artifact: ReportArtifact) => void }) {
  // Backtest results link to the Research page instead of opening the artifact modal
  if (artifact.type === 'backtest_result') {
    const content = (artifact.contentJson ?? artifact.content_json) as Record<string, unknown> | undefined
    const backtestId = content?.backtest_id as number | undefined
    const href = backtestId ? `${import.meta.env.BASE_URL}research?backtest=${backtestId}` : `${import.meta.env.BASE_URL}research`
    return (
      <a href={href}
        className="inline-flex items-center gap-2 rounded-full border border-violet-100 bg-violet-50/70 px-3 py-1.5 text-[11px] font-medium text-violet-700 hover:bg-violet-100 hover:border-violet-200 transition-colors">
        <BarChart2 className="w-3.5 h-3.5" />
        <span>{artifactLabel(artifact.type)}</span>
      </a>
    )
  }
  return (
    <button onClick={() => onOpen(artifact)}
      className="inline-flex items-center gap-2 rounded-full border border-indigo-100 bg-indigo-50/70 px-3 py-1.5 text-[11px] font-medium text-indigo-700 hover:bg-indigo-100 hover:border-indigo-200 transition-colors">
      <FileText className="w-3.5 h-3.5" />
      <span>{artifactLabel(artifact.type)}</span>
    </button>
  )
}

function ReportSection({ title, items, tone = 'slate' }: { title: string; items: string[]; tone?: 'slate' | 'green' | 'red' | 'amber' }) {
  if (items.length === 0) return null
  const color = tone === 'green' ? 'text-emerald-700 bg-emerald-50 border-emerald-100'
    : tone === 'red' ? 'text-red-700 bg-red-50 border-red-100'
    : tone === 'amber' ? 'text-amber-700 bg-amber-50 border-amber-100'
    : 'text-slate-700 bg-slate-50 border-slate-100'
  return (
    <section className="space-y-2">
      <h4 className="text-xs font-semibold text-slate-700">{title}</h4>
      <div className="space-y-1.5">
        {items.map((item, index) => (
          <div key={index} className={`rounded-lg border px-3 py-2 text-[12px] leading-relaxed ${color}`}>{item}</div>
        ))}
      </div>
    </section>
  )
}

function ReportDetailModal({ artifact, onClose }: { artifact: ReportArtifact | null; onClose: () => void }) {
  if (!artifact) return null
  const content = artifactContent(artifact)
  const evidenceFor = stringItems(content.evidence_for)
  const evidenceAgainst = stringItems(content.evidence_against)
  const conflicts = stringItems(content.conflicts)
  const sources = stringItems(content.data_sources)
  const auditTrail = stringItems(content.audit_trail).length > 0 ? stringItems(content.audit_trail) : objectEntries(content.audit_trail)
  const rawFactors = objectList(content.raw_factors).slice(0, 12)
  const markdown = artifact.contentMarkdown ?? artifact.content_markdown
  const regime = content.regime_adjustment && typeof content.regime_adjustment === 'object'
    ? content.regime_adjustment as Record<string, unknown>
    : null
  const dataQuality = content.data_quality && typeof content.data_quality === 'object'
    ? content.data_quality as Record<string, unknown>
    : null

  return (
    <AnimatePresence>
      <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
        className="fixed inset-0 z-50 bg-slate-950/40 flex items-end lg:items-center justify-center lg:p-6"
        onClick={onClose}>
        <motion.div initial={{ y: 32, opacity: 0 }} animate={{ y: 0, opacity: 1 }} exit={{ y: 24, opacity: 0 }}
          transition={{ type: 'spring', stiffness: 360, damping: 34 }}
          onClick={e => e.stopPropagation()}
          className="w-full lg:max-w-3xl max-h-[86vh] bg-white rounded-t-3xl lg:rounded-3xl shadow-2xl border border-slate-100 overflow-hidden flex flex-col">
          <div className="px-5 py-4 border-b border-slate-100 flex items-start justify-between gap-4">
            <div>
              <div className="inline-flex items-center gap-1.5 rounded-full bg-indigo-50 px-2.5 py-1 text-[11px] font-semibold text-indigo-700 mb-2">
                <FileText className="w-3.5 h-3.5" />{artifactLabel(artifact.type)}
              </div>
              <h3 className="text-base font-semibold text-slate-900">{artifact.title}</h3>
              {artifact.summary && !looksLikeJson(artifact.summary) && <p className="text-xs text-slate-500 mt-1 leading-relaxed">{artifact.summary}</p>}
            </div>
            <button onClick={onClose} className="p-2 rounded-full text-slate-400 hover:text-slate-700 hover:bg-slate-100 transition-colors">
              <X className="w-4 h-4" />
            </button>
          </div>
          <div className="overflow-auto px-5 py-4 space-y-5">
            {markdown && (
              <section className="rounded-xl border border-slate-100 bg-white px-3 py-3">
                <div className="prose prose-sm prose-slate max-w-none text-[13px] [&_h1]:text-base [&_h2]:text-sm [&_h2]:mt-4 [&_h2]:mb-2 [&_li]:my-0.5">
                  <ReactMarkdown remarkPlugins={[remarkGfm]}>{markdown}</ReactMarkdown>
                </div>
              </section>
            )}
            {regime && (
              <section className="rounded-xl border border-amber-100 bg-amber-50 px-3 py-2 text-[12px] text-amber-800">
                <div className="font-semibold mb-1">市场环境折扣</div>
                <div>{String(regime.label ?? regime.signal ?? '未知环境')} · {String(regime.message ?? regime.description ?? '报告已记录环境调整。')}</div>
              </section>
            )}
            <ReportSection title="正向证据" items={evidenceFor} tone="green" />
            <ReportSection title="风险 / 反向证据" items={evidenceAgainst} tone="red" />
            <ReportSection title="冲突信号" items={conflicts} tone="amber" />
            {dataQuality && Array.isArray(dataQuality.missing) && dataQuality.missing.length > 0 && (
              <ReportSection title="数据缺失" items={stringItems(dataQuality.missing)} tone="amber" />
            )}
            {rawFactors.length > 0 && (
              <section className="space-y-2">
                <h4 className="text-xs font-semibold text-slate-700">因子审计底稿</h4>
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
                  {rawFactors.map((factor, index) => (
                    <div key={index} className="rounded-lg border border-slate-100 bg-slate-50 px-3 py-2 text-[11px]">
                      <div className="font-medium text-slate-700">{String(factor.label ?? factor.name ?? `因子 ${index + 1}`)}</div>
                      <div className="text-slate-500 mt-0.5">{String(factor.signal ?? factor.group ?? '已记录')}</div>
                    </div>
                  ))}
                </div>
              </section>
            )}
            <ReportSection title="数据来源" items={sources} />
            <ReportSection title="审计轨迹" items={auditTrail} />
          </div>
        </motion.div>
      </motion.div>
    </AnimatePresence>
  )
}

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

function StrategyCard({ name, description, code: _code, onSave }: { name: string; description: string; code: string; onSave: (name: string) => Promise<boolean> }) {
  const { t } = useT()
  const prompt = usePrompt()
  const [saving, setSaving] = useState(false)
  // Once saved, the button is permanently disabled so the same strategy can't be
  // saved twice (which would create duplicate rows and re-trigger continuation).
  const [saved, setSaved] = useState(false)

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
            if (saving || saved) return
            const savedName = name || await prompt({ message: t.chat.promptStrategyName, defaultValue: t.chat.strategyPlaceholder, placeholder: t.chat.strategyPlaceholder })
            if (!savedName) return
            setSaving(true)
            try {
              const ok = await onSave(savedName)
              if (ok) setSaved(true)  // success → lock the button; parent kicks off the continuation
            } catch { /* parent surfaces the error via toast */ } finally { setSaving(false) }
          }} disabled={saving || saved}
            className="w-full h-9 rounded-lg text-white text-xs font-semibold transition-all flex items-center justify-center gap-1.5 tracking-wide hover:opacity-90 disabled:opacity-60"
            style={gradientStyle}>
            {saved ? <Check className="w-3.5 h-3.5" /> : saving ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Check className="w-3.5 h-3.5 opacity-70" />}
            {saved ? t.chat.strategySaved : saving ? t.chat.saving : t.chat.saveStrategyBtn}
          </button>
        </div>
      </div>
    </div>
  )
}
