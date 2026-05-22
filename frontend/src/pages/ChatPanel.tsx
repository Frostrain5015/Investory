import { useState, useRef, useEffect } from 'react'
import { motion } from 'framer-motion'
import { Sparkles, X, Send, RefreshCw, Trash2, Brain } from 'lucide-react'
import ReactMarkdown from 'react-markdown'
import type { SseEvent } from '@/types'

interface Message { role: 'user' | 'assistant'; content: string }

// Module-level state survives page navigation
let gMessages: Message[] = []
let gListeners: (() => void)[] = []

function notify() { gListeners.forEach(fn => fn()) }

function useChatMessages(): [Message[], (msgs: Message[]) => void] {
  const [, setTick] = useState(0)
  useEffect(() => {
    const fn = () => setTick(t => t + 1)
    gListeners.push(fn)
    return () => { gListeners = gListeners.filter(f => f !== fn) }
  }, [])
  return [gMessages, (msgs: Message[]) => { gMessages = msgs; notify() }]
}

export default function ChatPanel({ onClose }: { onClose: () => void }) {
  const [messages, setMessages] = useChatMessages()
  const [input, setInput] = useState('')
  const [streaming, setStreaming] = useState(false)
  const [streamText, setStreamText] = useState('')
  const [toolMsg, setToolMsg] = useState('')
  const [deepThink, setDeepThink] = useState(false)
  const esRef = useRef<EventSource | null>(null)
  const scrollRef = useRef<HTMLDivElement>(null)

  useEffect(() => { scrollRef.current?.scrollIntoView({ behavior: 'smooth' }) }, [messages, streamText])

  async function send() {
    const text = input.trim()
    if (!text || streaming) return

    // Get settings from server
    let apiKey = ''; let provider = 'openai'; let model = 'gpt-4o-mini'; let baseUrl = ''
    try {
      const r = await fetch('/investory/api/ai/settings', { credentials: 'include' })
      const s = await r.json()
      if (!s.hasKey) { alert('请先在设置页配置 AI API Key'); return }
      provider = s.provider || 'openai'
      model = s.model || 'gpt-4o-mini'
      baseUrl = s.baseUrl || ''
      const kr = await fetch('/investory/api/ai/key', { method: 'POST', credentials: 'include' })
      const kd = await kr.json()
      apiKey = kd.apiKey || ''
    } catch { alert('无法获取 AI 配置'); return }
    if (!apiKey) { alert('请先在设置页配置 AI API Key'); return }

    setInput('')
    const userContent = deepThink ? `[深度思考模式] ${text}\n请给出详细的分析和推理过程，不要省略步骤。` : text
    const newMessages: Message[] = [...messages, { role: 'user', content: userContent }]
    setMessages(newMessages)
    setStreaming(true)
    setStreamText('')

    try {
      const resp = await fetch('/investory/api/ai/chat', {
        method: 'POST', credentials: 'include',
        headers: { 'Content-Type': 'application/json', 'X-AI-Key': apiKey, 'X-AI-Provider': provider, 'X-AI-Model': model, 'X-AI-Base-URL': baseUrl, 'X-AI-Deep-Think': deepThink ? '1' : '0' },
        body: JSON.stringify({ messages: newMessages }),
      })
      if (!resp.ok) { setStreamText(`[错误] HTTP ${resp.status}`); setStreaming(false); return }

      if (esRef.current) esRef.current.close()
      const es = new EventSource('/investory/api/ai/stream')
      esRef.current = es

      es.addEventListener('tool', (e) => {
        const d: SseEvent = JSON.parse(e.data)
        setToolMsg(d.name || '')
      })
      es.addEventListener('token', (e) => {
        const d: SseEvent = JSON.parse(e.data)
        if (toolMsg) setToolMsg('')
        setStreamText(prev => prev + (d.msg || ''))
      })
      es.addEventListener('done', () => {
        setStreamText(prev => { setMessages([...newMessages, { role: 'assistant', content: prev }]); return '' })
        setStreaming(false); es.close(); esRef.current = null
      })
      es.addEventListener('error', (e) => {
        try { const d: SseEvent = JSON.parse((e as MessageEvent).data); setStreamText(d.msg || '未知错误') } catch { setStreamText('连接中断') }
        setStreaming(false); es.close(); esRef.current = null
      })
      es.onerror = () => {}
    } catch (e: any) {
      setStreamText(`[错误] ${e.message}`)
      setStreaming(false)
    }
  }

  function clearChat() { setMessages([]); setStreamText(''); fetch('/investory/api/ai/clear', { method: 'POST', credentials: 'include' }).catch(() => {}) }

  function regenerate() {
    if (messages.length < 2) return
    const trimmed = messages.slice(0, -1)
    setMessages(trimmed)
    const lastUser = trimmed.filter(m => m.role === 'user').pop()
    if (lastUser) { setInput(lastUser.content); setTimeout(() => send(), 100) }
  }

  function handleKeyDown(e: React.KeyboardEvent) { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send() } }

  return (
    <motion.div
      initial={{ x: 400 }} animate={{ x: 0 }} exit={{ x: 400 }}
      transition={{ type: 'spring', stiffness: 300, damping: 30 }}
      className="fixed right-0 top-0 bottom-0 w-[380px] bg-white border-l border-slate-200 shadow-2xl z-50 flex flex-col">
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-3 border-b border-slate-100 shrink-0">
        <div className="flex items-center gap-2">
          <div className="w-7 h-7 rounded-lg bg-slate-900 flex items-center justify-center"><Sparkles className="w-3.5 h-3.5 text-white" /></div>
          <span className="text-sm font-bold text-slate-900">观澜 AI</span>
        </div>
        <div className="flex items-center gap-1">
          <button onClick={() => setDeepThink(!deepThink)} className={`p-1.5 rounded-lg transition-colors ${deepThink ? 'bg-purple-100 text-purple-600' : 'hover:bg-slate-100 text-slate-400'}`} title="深度思考"><Brain className="w-3.5 h-3.5" /></button>
          <button onClick={clearChat} className="p-1.5 rounded-lg hover:bg-slate-100 text-slate-400" title="清空对话"><Trash2 className="w-3.5 h-3.5" /></button>
          {messages.length >= 2 && !streaming && <button onClick={regenerate} className="p-1.5 rounded-lg hover:bg-slate-100 text-slate-400" title="重新生成"><RefreshCw className="w-3.5 h-3.5" /></button>}
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
            <p className="text-sm font-medium text-slate-700">你好，我是观澜</p>
            <p className="text-xs text-slate-400 mt-1">基于价值投资理念的 AI 分析助手</p>
            <div className="mt-4 space-y-2">
              {['我的组合风险怎么样？', '分析一下我的持仓风格', '帮我写一个均线策略'].map(q => (
                <button key={q} onClick={() => { setInput(q); setTimeout(() => send(), 100) }}
                  className="block w-full text-left text-xs text-slate-500 hover:text-slate-900 hover:bg-slate-50 px-3 py-2 rounded-lg transition-colors">"{q}"</button>
              ))}
            </div>
          </div>
        )}
        {messages.map((m, i) => (
          <div key={i} className={`flex ${m.role === 'user' ? 'justify-end' : 'justify-start'}`}>
            <div className={`max-w-[85%] rounded-2xl px-4 py-2.5 text-sm leading-relaxed ${m.role === 'user' ? 'bg-slate-900 text-white' : 'bg-slate-100 text-slate-800'}`}>
              {m.role === 'assistant'
                ? <div className="prose prose-sm prose-slate max-w-none [&_table]:text-xs [&_th]:border [&_th]:border-slate-300 [&_th]:px-2 [&_th]:py-1 [&_td]:border [&_td]:border-slate-200 [&_td]:px-2 [&_td]:py-1 [&_table]:w-full [&_code]:bg-slate-200 [&_code]:px-1 [&_code]:rounded [&_pre]:bg-slate-200 [&_pre]:p-2 [&_pre]:rounded-lg [&_pre]:overflow-auto"><ReactMarkdown>{m.content}</ReactMarkdown></div>
                : <div style={{ whiteSpace: 'pre-wrap' }}>{m.content}</div>
              }
            </div>
          </div>
        ))}
        {streaming && (
          <div className="flex justify-start">
            <div className="max-w-[85%] rounded-2xl px-4 py-2.5 text-sm leading-relaxed bg-slate-100 text-slate-800">
              {toolMsg && <div className="flex items-center gap-2 text-xs text-slate-400 mb-1"><span className="w-3 h-3 border-2 border-slate-300 border-t-slate-600 rounded-full animate-spin" />{toolMsg}</div>}
              {streamText
                ? <div className="prose prose-sm prose-slate max-w-none [&_table]:text-xs [&_th]:border [&_th]:border-slate-300 [&_th]:px-2 [&_th]:py-1 [&_td]:border [&_td]:border-slate-200 [&_td]:px-2 [&_td]:py-1 [&_table]:w-full [&_code]:bg-slate-200 [&_code]:px-1 [&_code]:rounded"><ReactMarkdown>{streamText}</ReactMarkdown></div>
                : (!toolMsg && <span className="inline-flex gap-0.5"><span className="w-1.5 h-1.5 rounded-full bg-slate-400 animate-bounce" /><span className="w-1.5 h-1.5 rounded-full bg-slate-400 animate-bounce" style={{ animationDelay: '0.1s' }} /><span className="w-1.5 h-1.5 rounded-full bg-slate-400 animate-bounce" style={{ animationDelay: '0.2s' }} /></span>)
              }
            </div>
          </div>
        )}
        <div ref={scrollRef} />
      </div>

      {/* Input */}
      <div className="px-4 py-3 border-t border-slate-100 shrink-0">
        <div className="flex items-center gap-2">
          <textarea value={input} onChange={e => setInput(e.target.value)} onKeyDown={handleKeyDown}
            placeholder="向观澜提问..." rows={1}
            disabled={streaming}
            className="flex-1 resize-none h-10 max-h-24 rounded-xl border border-slate-200 px-3.5 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-slate-900/5 disabled:bg-slate-50" />
          <button onClick={send} disabled={!input.trim() || streaming}
            className="w-10 h-10 rounded-xl bg-slate-900 text-white flex items-center justify-center hover:bg-slate-800 disabled:opacity-30 transition-colors shrink-0">
            <Send className="w-4 h-4" />
          </button>
        </div>
      </div>
    </motion.div>
  )
}
