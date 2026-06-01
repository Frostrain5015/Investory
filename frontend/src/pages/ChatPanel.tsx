import { useState, useRef, useEffect } from 'react'
import { motion } from 'framer-motion'
import { Sparkles, X, Send, RefreshCw, Trash2, Brain, Check, Loader2 } from 'lucide-react'
import { useToast } from '@/components/Toast'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import type { SseEvent } from '@/types'
import { useT } from '@/i18n/I18nContext'
import { getCachedSuggestions, getSuggestionsPromise } from '@/services/aiPreload'
import { BASE } from '@/services/api'

interface PortfolioCard { portfolio_score: number; holdings_scored: number; top_holdings: { symbol: string; name: string; total_score: number }[]; bottom_holdings: { symbol: string; name: string; total_score: number }[]; group_exposure: Record<string, { buy_score: number }> }
interface PicksCard { regime: string; picks: { code: string; name: string; total_score: number; buy_score: number; bullish: string[] }[]; scanned: number }
interface Message { role: 'user' | 'assistant' | 'system'; content: string; thinking?: string; hasCode?: boolean; strategyName?: string; strategyDesc?: string; strategyCode?: string; confirm?: ConfirmData; portfolioCard?: PortfolioCard; picksCard?: PicksCard }
interface ConfirmItem { action: string; label: string; endpoint: string; method: string; body: Record<string, any> }
interface ConfirmData { id: string; title: string; items: ConfirmItem[] }
type ConfirmStatus = 'pending' | 'accepted' | 'refused'

// Module-level state survives page navigation
let gMessages: Message[] = []
let gListeners: (() => void)[] = []

function notify() { gListeners.forEach(fn => fn()) }

function ThinkingBlock({ text, done }: { text: string; done: boolean }) {
  const { t } = useT()
  const [open, setOpen] = useState(false)
  return (
    <div className="mb-2">
      <button onClick={() => setOpen(!open)} className="flex items-center gap-1.5 text-xs text-slate-400 hover:text-slate-600">
        <span className={`w-2 h-2 rounded-full ${done ? 'bg-slate-400' : 'bg-amber-400 animate-pulse'}`} />
        {done ? t.chat.thinking : t.chat.thinkingInProgress}
        <span className="text-[10px]">{open ? '▲' : '▼'}</span>
      </button>
      {open && <div className="mt-1.5 p-3 bg-slate-200/50 rounded-lg text-xs text-slate-500 whitespace-pre-wrap leading-relaxed">{text.trim() || '...'}</div>}
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

export default function ChatPanel({ onClose, initialMessage }: { onClose: () => void; initialMessage?: string }) {
  const { t } = useT()
  const toast = useToast()
  const [messages, setMessages] = useChatMessages()
  const [input, setInput] = useState('')
  const [streaming, setStreaming] = useState(false)
  const [streamText, setStreamText] = useState('')
  const [toolMsg, setToolMsg] = useState('')
  const [deepThink, setDeepThink] = useState(false)
  const pendingStrategy = useRef<{ name: string; desc: string; code: string } | null>(null)
  const pendingCard = useRef<{ type: string; data: any } | null>(null)
  const streamAccum = useRef('')  // accumulates raw stream text; read synchronously in done handler
  const [askData, setAskData] = useState<{ question: string; options: string[] } | null>(null)
  const [confirmData, setConfirmData] = useState<ConfirmData | null>(null)
  const [confirmStatus, setConfirmStatus] = useState<ConfirmStatus | null>(null)
  const [executing, setExecuting] = useState(false)
  const [suggestions, setSuggestions] = useState<string[]>([])
  const esRef = useRef<EventSource | null>(null)
  const scrollRef = useRef<HTMLDivElement>(null)

  // Pre-fill input when opened from a metric card
  useEffect(() => {
    if (initialMessage) setInput(initialMessage)
  }, [initialMessage])

  useEffect(() => {
    // Use preloaded cache if available; otherwise fall back to fetching now
    const cached = getCachedSuggestions()
    if (cached) {
      setSuggestions(cached)
      return
    }
    const pending = getSuggestionsPromise()
    if (pending) {
      pending.then(list => { if (list.length > 0) setSuggestions(list); else setSuggestions([...t.chat.suggestions]) })
      return
    }
    // No preload started — fetch now
    fetch(`${BASE}/api/ai/suggestions`, { credentials: 'include' })
      .then(r => r.json())
      .then(d => {
        if (Array.isArray(d.suggestions) && d.suggestions.length > 0) setSuggestions(d.suggestions)
        else setSuggestions([...t.chat.suggestions])
      })
      .catch(() => { setSuggestions([...t.chat.suggestions]) })
  }, [])

  useEffect(() => { scrollRef.current?.scrollIntoView({ behavior: 'smooth' }) }, [messages, streamText])

  // textOverride lets quick-reply/regenerate/askData bypass stale input closure
  async function send(textOverride?: string) {
    const text = (textOverride !== undefined ? textOverride : input).trim()
    if (!text || streaming) return

    setInput('')
    const newMessages: Message[] = [...messages, { role: 'user', content: text }]
    setMessages(newMessages)
    setStreaming(true)
    setStreamText('')
    streamAccum.current = ''
    setToolMsg('')
    setAskData(null)
    setConfirmData(null)
    setConfirmStatus(null)

    try {
      const resp = await fetch(`${BASE}/api/ai/chat`, {
        method: 'POST', credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ messages: newMessages, deepThink }),
      })
      if (!resp.ok) { setStreamText(`${t.chat.errorPrefix} HTTP ${resp.status}`); setStreaming(false); return }

      if (esRef.current) esRef.current.close()
      const es = new EventSource(`${BASE}/api/ai/stream`, { withCredentials: true })
      esRef.current = es

      es.addEventListener('strategy', (e) => {
        const d = JSON.parse(e.data)
        pendingStrategy.current = { name: d.name, desc: d.description, code: d.code }
      })
      es.addEventListener('portfolio_card', (e) => {
        pendingCard.current = { type: 'portfolio', data: JSON.parse(e.data) }
      })
      es.addEventListener('picks_card', (e) => {
        pendingCard.current = { type: 'picks', data: JSON.parse(e.data) }
      })
      es.addEventListener('ask', (e) => {
        const d = JSON.parse(e.data)
        setAskData({ question: d.question, options: d.options || [] })
      })
      es.addEventListener('confirm', (e) => {
        try {
          const raw = JSON.parse(e.data)
          const d = raw.data || raw
          const parsed: ConfirmData = typeof d === 'string' ? JSON.parse(d) : d
          if (parsed && parsed.items && parsed.items.length > 0) {
            setConfirmData(parsed)
            setConfirmStatus('pending')
          }
        } catch {}
      })
      es.addEventListener('tool', (e) => {
        const d: SseEvent = JSON.parse(e.data)
        setToolMsg(d.name || '')
      })
      es.addEventListener('token', (e) => {
        const d: SseEvent = JSON.parse(e.data)
        setToolMsg('')
        streamAccum.current += (d.msg || '')
        setStreamText(streamAccum.current)
      })
      es.addEventListener('done', () => {
        const raw = streamAccum.current
        streamAccum.current = ''
        setToolMsg('')
        setStreamText('')

        if (!raw.trim()) {
          setStreaming(false); es.close(); esRef.current = null; return
        }

        const thinkMatch = raw.match(/<think(?:ing)?>([\s\S]*?)<\/think(?:ing)?>/)
        const thinking = thinkMatch ? thinkMatch[1].trim() : undefined
        const content = raw.replace(/<think(?:ing)?>[\s\S]*?<\/think(?:ing)?>/g, '').trim()
        const msg: Message = { role: 'assistant', content: content || raw, thinking }
        const s = pendingStrategy.current
        if (s) {
          msg.hasCode = true; msg.strategyName = s.name; msg.strategyDesc = s.desc; msg.strategyCode = s.code
          pendingStrategy.current = null
        }
        const card = pendingCard.current
        if (card) {
          if (card.type === 'portfolio') msg.portfolioCard = card.data
          else if (card.type === 'picks') msg.picksCard = card.data
          pendingCard.current = null
        }
        if (!s && !card) {
          // Fallback: detect code directly in the response (AI sometimes writes code inline)
          const codeMatch = raw.match(/```(?:python)?\s*\n(def decide\(ctx[^)]*\):[\s\S]*?)```/)
          if (codeMatch) {
            const nameMatch = raw.match(/(?:策略名称|策略)[：:]\s*(.+)/)
            msg.hasCode = true
            msg.strategyName = nameMatch ? nameMatch[1].trim() : ''
            msg.strategyCode = codeMatch[1].trim()
            msg.strategyDesc = ''
          }
        }
        setMessages([...newMessages, msg])
        setStreaming(false); es.close(); esRef.current = null
      })
      es.addEventListener('error', (e) => {
        pendingStrategy.current = null
        try { const d: SseEvent = JSON.parse((e as MessageEvent).data); setStreamText(d.msg || t.chat.errorUnknown) } catch { setStreamText(t.chat.errorNetwork) }
        setStreaming(false); setToolMsg(''); es.close(); esRef.current = null
      })
      es.onerror = () => {}
    } catch (e: unknown) {
      setStreamText(`${t.chat.errorPrefix} ${e instanceof Error ? e.message : String(e)}`)
      setStreaming(false)
    }
  }

  function clearChat() { gMessages = []; setMessages([]); setStreamText(''); fetch(`${BASE}/api/ai/clear`, { method: 'POST', credentials: 'include' }).catch(() => {}) }

  function regenerate() {
    if (messages.length < 2) return
    const trimmed = messages.slice(0, -1)
    setMessages(trimmed)
    const lastUser = trimmed.filter(m => m.role === 'user').pop()
    if (lastUser) send(lastUser.content)
  }

  async function handleConfirmAccept() {
    if (!confirmData || !confirmData.items) return
    setExecuting(true)
    const results: string[] = []
    for (const item of confirmData.items) {
      try {
        const body = item.body || {}
        const form = new URLSearchParams()
        for (const [k, v] of Object.entries(body)) {
          if (v != null && v !== '') form.append(k, String(v))
        }
        const res = await fetch(`${BASE}${item.endpoint}`, {
          method: item.method || 'POST',
          credentials: 'include',
          headers: item.method !== 'DELETE' ? { 'Content-Type': 'application/x-www-form-urlencoded' } : undefined,
          body: item.method !== 'DELETE' ? form.toString() : undefined,
        })
        if (!res.ok) {
          const err = await res.json().catch(() => ({}))
          results.push(`✗ ${item.label}: ${err.error || `HTTP ${res.status}`}`)
        } else {
          results.push(`✓ ${item.label}`)
        }
      } catch (e: any) {
        results.push(`✗ ${item.label}: ${e.message}`)
      }
    }
    setConfirmStatus('accepted')
    setExecuting(false)
    if (results.length > 0) {
      setMessages([...messages, { role: 'system', content: results.join('\n') }])
    }
  }

  function handleConfirmRefuse() {
    setConfirmStatus('refused')
  }

  function handleKeyDown(e: React.KeyboardEvent) { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send() } }

  return (
    <motion.div
      initial={{ x: '100%' }} animate={{ x: 0 }} exit={{ x: '100%' }}
      transition={{ type: 'spring', stiffness: 300, damping: 30 }}
      className="fixed right-0 top-0 bottom-0 w-[min(380px,100vw)] bg-white border-l border-slate-200 shadow-2xl z-50 flex flex-col pb-safe">
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-3 border-b border-slate-100 shrink-0">
        <div className="flex items-center gap-2">
          <div className="w-7 h-7 rounded-lg bg-slate-900 flex items-center justify-center"><Sparkles className="w-3.5 h-3.5 text-white" /></div>
          <span className="text-sm font-bold text-slate-900">{t.chat.title}</span>
        </div>
        <div className="flex items-center gap-1">
          <button onClick={() => setDeepThink(!deepThink)} className={`p-1.5 rounded-lg transition-colors ${deepThink ? 'bg-purple-100 text-purple-600' : 'hover:bg-slate-100 text-slate-400'}`} title={t.chat.deepThink}><Brain className="w-3.5 h-3.5" /></button>
          <button onClick={clearChat} className="p-1.5 rounded-lg hover:bg-slate-100 text-slate-400" title={t.chat.clearChat}><Trash2 className="w-3.5 h-3.5" /></button>
          {messages.length >= 2 && !streaming && <button onClick={regenerate} className="p-1.5 rounded-lg hover:bg-slate-100 text-slate-400" title={t.chat.regenerate}><RefreshCw className="w-3.5 h-3.5" /></button>}
          <button onClick={onClose} className="p-1.5 rounded-lg hover:bg-slate-100 text-slate-400"><X className="w-4 h-4" /></button>
        </div>
      </div>

      {/* Messages */}
      <div className="flex-1 overflow-auto px-4 py-4 space-y-4">
        {messages.length === 0 && !streaming && (
          <div className="text-center py-12">
            <div className="w-12 h-12 rounded-2xl bg-slate-100 flex items-center justify-center mx-auto mb-3">
              <Sparkles className="w-6 h-6 text-slate-400" />
            </div>
            <p className="text-sm font-medium text-slate-700">{t.chat.greeting}</p>
            <p className="text-xs text-slate-400 mt-1">{t.chat.subtitle}</p>
            <div className="mt-4 space-y-2">
              {suggestions.length === 0
                ? [1, 2, 3].map(i => (
                    <div key={i} className="h-8 rounded-lg bg-slate-100 animate-pulse" style={{ width: `${72 + i * 6}%` }} />
                  ))
                : suggestions.map((q, i) => (
                    <motion.button key={q} onClick={() => send(q)}
                      initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }}
                      transition={{ delay: i * 0.08 }}
                      className="block w-full text-left text-xs text-slate-500 hover:text-slate-900 hover:bg-slate-50 px-3 py-2 rounded-lg transition-colors">"{q}"</motion.button>
                  ))
              }
            </div>
          </div>
        )}
        {Array.isArray(messages) && messages.map((m, i) => (
          <div key={i} className={`flex ${m.role === 'user' ? 'justify-end' : 'justify-start'}`}>
            <div className={`max-w-[85%] rounded-2xl px-4 py-2.5 text-sm leading-relaxed ${m.role === 'user' ? 'bg-slate-900 text-white' : 'bg-slate-100 text-slate-800'}`}>
              {m.role === 'assistant'
                ? <><div>
                  {m.thinking && <ThinkingBlock text={m.thinking} done={true} />}
                </div>
                <div className="prose prose-sm prose-slate max-w-none [&_table]:text-xs [&_th]:border [&_th]:border-slate-300 [&_th]:px-2 [&_th]:py-1 [&_td]:border [&_td]:border-slate-200 [&_td]:px-2 [&_td]:py-1 [&_table]:w-full [&_code]:bg-slate-200 [&_code]:px-1 [&_code]:rounded [&_pre]:bg-slate-200 [&_pre]:p-2 [&_pre]:rounded-lg [&_pre]:overflow-auto">
                  <ReactMarkdown remarkPlugins={[remarkGfm]} components={{
                    code: ({ children, className, ...props }) => {
                      const text = String(children).trim()
                      if (/^\d{4,6}\.(SH|SZ|HK|US)$/i.test(text) || /^[A-Z]{1,5}\.US$/i.test(text)) {
                        return <a href={`${BASE}/stock?symbol=${text}`} target="_blank" className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded bg-blue-50 text-blue-700 text-xs font-medium hover:bg-blue-100">{text}</a>
                      }
                      return <code className={className} {...props}>{children}</code>
                    }
                  }}>{m.content}</ReactMarkdown>
                </div></>
                : <div style={{ whiteSpace: 'pre-wrap' }}>{m.content}</div>
              }
              {m.hasCode && (
                <div className="mt-3 pt-3 border-t border-slate-200">
                  <button onClick={async () => {
                    const name = m.strategyName || prompt(t.chat.promptStrategyName, t.chat.strategyPlaceholder)
                    if (!name) return
                    const code = m.strategyCode || m.content
                    const res = await fetch(`${BASE}/api/backtest/strategies`, {
                      method: 'POST', credentials: 'include',
                      headers: { 'Content-Type': 'application/json' },
                      body: JSON.stringify({ name, strategyType: 'advanced', strategy: { code } })
                    })
                    const data = await res.json()
                    if (data.error) toast(data.error, false)
                    else toast(t.chat.strategySaved, true)
                  }} className="w-full h-9 rounded-lg bg-slate-900 text-white text-xs font-medium hover:bg-slate-800">
                    {t.chat.saveStrategyBtn}
                  </button>
                </div>
              )}
              {/* Portfolio analysis card */}
              {m.portfolioCard && (
                <div className="mt-3 pt-3 border-t border-slate-200 space-y-2">
                  <div className="flex items-center justify-between">
                    <span className="text-xs font-semibold text-slate-700">组合因子分析</span>
                    <span className={`text-xs font-bold ${(m.portfolioCard.portfolio_score ?? 0) >= 60 ? 'text-emerald-600' : (m.portfolioCard.portfolio_score ?? 0) >= 40 ? 'text-amber-600' : 'text-red-500'}`}>
                      {(m.portfolioCard.portfolio_score ?? 0).toFixed(0)}分
                    </span>
                  </div>
                  {m.portfolioCard.top_holdings?.length > 0 && (
                    <div className="text-[11px] space-y-1">
                      <span className="text-emerald-600 font-medium">评分最高</span>
                      {m.portfolioCard.top_holdings.map((h, i) => (
                        <div key={i} className="flex justify-between text-slate-600">
                          <span>{h.symbol} {h.name}</span>
                          <span className="font-medium">{h.total_score?.toFixed(0)}分</span>
                        </div>
                      ))}
                    </div>
                  )}
                  {m.portfolioCard.bottom_holdings?.length > 0 && (
                    <div className="text-[11px] space-y-1">
                      <span className="text-red-500 font-medium">评分最低</span>
                      {m.portfolioCard.bottom_holdings.map((h, i) => (
                        <div key={i} className="flex justify-between text-slate-600">
                          <span>{h.symbol} {h.name}</span>
                          <span className="font-medium">{h.total_score?.toFixed(0)}分</span>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              )}
              {/* Daily picks card */}
              {m.picksCard && (
                <div className="mt-3 pt-3 border-t border-slate-200 space-y-2">
                  <div className="flex items-center justify-between">
                    <span className="text-xs font-semibold text-slate-700">今日推荐</span>
                    <span className="text-[10px] text-slate-400">扫描 {m.picksCard.scanned} 只 · {m.picksCard.regime}</span>
                  </div>
                  {m.picksCard.picks?.map((p, i) => (
                    <div key={i} className="flex items-center gap-2 text-[11px]">
                      <span className="font-medium text-slate-900 w-16">{p.code}</span>
                      <span className="text-slate-500 flex-1 truncate">{p.name}</span>
                      <span className={`font-bold ${(p.total_score ?? 0) >= 60 ? 'text-emerald-600' : 'text-slate-600'}`}>{p.total_score?.toFixed(0)}分</span>
                      {p.bullish?.slice(0, 2).map((r, ri) => (
                        <span key={ri} className="text-[10px] px-1 py-0.5 bg-emerald-50 text-emerald-600 rounded">{r}</span>
                      ))}
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        ))}
        {streaming && (
          <div className="flex justify-start">
            <div className="max-w-[85%] rounded-2xl px-4 py-2.5 text-sm leading-relaxed bg-slate-100 text-slate-800">
              {streamText
                ? (() => {
                    const thinkMatch = streamText.match(/<think(?:ing)?>([\s\S]*?)(<\/think(?:ing)?>|$)/)
                    const thinking = thinkMatch ? thinkMatch[1] : ''
                    const after = streamText.replace(/<think(?:ing)?>[\s\S]*?(<\/think(?:ing)?>|$)/, '').trim()
                    return (<>
                      {thinking && <ThinkingBlock text={thinking} done={/<\/think(?:ing)?>/.test(streamText)} />}
                      {after && <div className="prose prose-sm prose-slate max-w-none [&_table]:text-xs [&_th]:border [&_th]:border-slate-300 [&_th]:px-2 [&_th]:py-1 [&_td]:border [&_td]:border-slate-200 [&_td]:px-2 [&_td]:py-1 [&_table]:w-full [&_code]:bg-slate-200 [&_code]:px-1 [&_code]:rounded"><ReactMarkdown remarkPlugins={[remarkGfm]}>{after}</ReactMarkdown></div>}
                    </>)
                  })()
                : (!toolMsg && <span className="inline-flex gap-0.5"><span className="w-1.5 h-1.5 rounded-full bg-slate-400 animate-bounce" /><span className="w-1.5 h-1.5 rounded-full bg-slate-400 animate-bounce" style={{ animationDelay: '0.1s' }} /><span className="w-1.5 h-1.5 rounded-full bg-slate-400 animate-bounce" style={{ animationDelay: '0.2s' }} /></span>)
              }
              {toolMsg && <div className="flex items-center gap-2 text-sm text-slate-500 mt-2 pt-2 border-t border-slate-200"><span className="w-4 h-4 border-2 border-slate-300 border-t-slate-600 rounded-full animate-spin" />{toolMsg}</div>}
              {askData && (
                <div className="mt-3 pt-3 border-t border-slate-200 space-y-1.5">
                  <p className="text-xs text-slate-500">{askData.question}</p>
                  {askData.options.map((o: string, i: number) => (
                    <button key={i} onClick={() => { setAskData(null); send(o) }}
                      className="block w-full text-left text-xs px-3 py-2 rounded-lg border border-slate-200 hover:bg-slate-50 hover:border-slate-300 transition-colors">{o}</button>
                  ))}
                </div>
              )}
            </div>
          </div>
        )}
        {/* Confirm card — rendered outside streaming block so it survives done event */}
        {confirmData && Array.isArray(confirmData.items) && (
          <div className="flex justify-start">
            <div className="max-w-[85%] rounded-2xl px-4 py-2.5 text-sm bg-white border-2 border-emerald-500 shadow-lg shadow-emerald-500/10 space-y-2">
              <div className="flex items-center gap-2 text-emerald-700">
                <div className="w-5 h-5 rounded-full bg-emerald-100 flex items-center justify-center">
                  <Check className="w-3 h-3 text-emerald-600" />
                </div>
                <span className="text-xs font-semibold">{confirmData.title}</span>
              </div>
              {confirmData.items.map((item, i) => {
                const b = item.body || {}
                const isDelete = item.action === 'delete'
                const isRemove = item.action === 'remove_watchlist'
                const isWatchlist = item.action === 'add_watchlist' || isRemove
                const isTransfer = b.type === 'TRANSFER_IN' || b.type === 'TRANSFER_OUT'
                const isDiv = b.type === 'DIV'
                const typeLabels: Record<string, string> = { BUY: '买入', SELL: '卖出', DIV: '分红', TRANSFER_IN: '转入', TRANSFER_OUT: '转出' }
                const cardBg = isDelete || isRemove ? 'bg-red-50 text-red-700' : isWatchlist ? 'bg-blue-50 text-blue-700' : 'bg-slate-50 text-slate-500'
                return (
                  <div key={i} className={`text-[11px] leading-relaxed rounded-lg px-3 py-2 space-y-0.5 ${cardBg}`}>
                    {item.label && <div className="text-xs font-medium">{item.label}</div>}
                    {!isWatchlist && <div className="flex flex-wrap gap-x-3 gap-y-0.5">
                      {b.stockName && <span>{b.stockName}</span>}
                      {b.type && <span className="font-semibold">{typeLabels[b.type] || b.type}</span>}
                      {b.shares > 0 && <span>{isDiv ? `每股 ${b.shares}` : isTransfer ? `${b.shares}` : `${b.shares} 股`}</span>}
                      {b.price > 0 && !isTransfer && <span>@ {b.price}</span>}
                      {b.fee > 0 && <span>手续费 {b.fee}</span>}
                      {b.tradeDate && <span>日期 {b.tradeDate}</span>}
                      {b.currency && <span>{b.currency}</span>}
                      {b.amountPerShare != null && b.amountPerShare > 0 && <span>分红/股 {b.amountPerShare}</span>}
                      {b.note && <span className="text-slate-400">备注: {b.note}</span>}
                    </div>}
                  </div>
                )
              })}
              {confirmStatus === 'pending' && (
                <div className="flex gap-2 pt-1">
                  <button onClick={handleConfirmAccept} disabled={executing}
                    className="flex items-center gap-1 px-4 py-2 rounded-lg bg-emerald-600 text-white text-xs font-medium hover:bg-emerald-700 transition-colors disabled:opacity-60">
                    {executing ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Check className="w-3.5 h-3.5" />}
                    Accept
                  </button>
                  <button onClick={handleConfirmRefuse} disabled={executing}
                    className="flex items-center gap-1 px-4 py-2 rounded-lg border border-slate-200 text-slate-600 text-xs font-medium hover:bg-slate-50 transition-colors">
                    <X className="w-3.5 h-3.5" />Refuse
                  </button>
                </div>
              )}
              {confirmStatus === 'accepted' && <p className="text-xs text-emerald-600 font-medium">✓ 执行成功</p>}
              {confirmStatus === 'refused' && <p className="text-xs text-slate-400">✗ 已取消</p>}
            </div>
          </div>
        )}
        <div ref={scrollRef} />
      </div>

      {/* Input */}
      <div className="px-4 py-3 border-t border-slate-100 shrink-0">
        <div className="flex items-center gap-2">
          <textarea value={input} onChange={e => setInput(e.target.value)} onKeyDown={handleKeyDown}
            placeholder={t.chat.placeholder} rows={1}
            disabled={streaming}
            className="flex-1 resize-none h-10 max-h-24 rounded-xl border border-slate-200 px-3.5 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-slate-900/5 disabled:bg-slate-50" />
          <button onClick={() => send()} disabled={!input.trim() || streaming}
            className="w-10 h-10 rounded-xl bg-slate-900 text-white flex items-center justify-center hover:bg-slate-800 disabled:opacity-30 transition-colors shrink-0">
            <Send className="w-4 h-4" />
          </button>
        </div>
      </div>
    </motion.div>
  )
}
