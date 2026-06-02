import { useState, useEffect, useCallback } from 'react'
import { useSettings, type BaseCurrency } from '@/hooks/use-settings'
import { useTheme } from '@/hooks/use-theme'
import { useT } from '@/i18n/I18nContext'
import { useToast } from '@/components/Toast'
import {
  Sun, Moon, Monitor, Globe, Bot, Plug,
  ChevronRight, Copy, Download, Trash2, ExternalLink,
  RefreshCw,
} from 'lucide-react'
import Modal, { ModalRow } from '@/components/Modal'
import { BASE, aiListModels, getMcpTokens, revokeMcpToken, type McpTokenInfo } from '@/services/api'
import { motion } from 'framer-motion'

// ── Primitives ──────────────────────────────────────────────────────────

function Toggle({ on, onToggle }: { on: boolean; onToggle: () => void }) {
  return (
    <button onClick={onToggle} role="switch" aria-checked={on}
      className={`relative w-11 h-6 rounded-full transition-colors shrink-0 cursor-pointer
        ${on ? 'bg-slate-800 dark:bg-slate-300' : 'bg-slate-200 dark:bg-slate-700'}`}>
      <span className={`absolute top-0.5 w-5 h-5 rounded-full bg-white shadow-sm transition-all duration-200
        ${on ? 'left-[22px]' : 'left-0.5'}`} />
    </button>
  )
}

function Segments<T extends string>({ value, options, onChange }: {
  value: T | ''; options: { value: T; label: string; icon?: React.ReactNode }[]; onChange: (v: T) => void
}) {
  return (
    <div className="flex gap-1 p-1 bg-slate-100 dark:bg-slate-800 rounded-xl">
      {options.map(o => (
        <button key={o.value} onClick={() => onChange(o.value)}
          className={`flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-all cursor-pointer
            ${value === o.value
              ? 'bg-white dark:bg-slate-600 text-slate-900 dark:text-white shadow-sm'
              : 'text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-300'}`}>
          {o.icon}{o.label}
        </button>
      ))}
    </div>
  )
}

const inputCls = 'w-full h-9 rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 px-3 text-sm text-slate-800 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-slate-300 dark:focus:ring-slate-600 transition placeholder:text-slate-400'
const DEFAULT_OPENAI_COMPAT_BASE_URL = 'https://dashscope.aliyuncs.com/compatible-mode/v1'
const DEFAULT_OPENAI_COMPAT_MODEL = 'qwen-plus-latest'

type ApiFormat = 'openai_compat' | 'anthropic'

function isApiFormat(value: string): value is ApiFormat {
  return value === 'openai_compat' || value === 'anthropic'
}

// ── Cards ───────────────────────────────────────────────────────────────

interface SettingCardProps {
  icon: React.ReactNode
  title: string
  summary: string
  onClick: () => void
  delay: number
}

function SettingCard({ icon, title, summary, onClick, delay }: SettingCardProps) {
  return (
    <motion.button
      onClick={onClick}
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ delay: delay * 0.08, duration: 0.3, ease: 'easeOut' }}
      className="group flex items-center gap-4 p-5 rounded-2xl
                 bg-white dark:bg-slate-900
                 border border-slate-200 dark:border-slate-800
                 hover:border-slate-300 dark:hover:border-slate-700
                 hover:shadow-sm transition-all duration-200
                 text-left cursor-pointer w-full"
    >
      <div className="flex items-center justify-center w-10 h-10 rounded-xl
                      bg-slate-100 dark:bg-slate-800
                      text-slate-600 dark:text-slate-400
                      group-hover:bg-slate-200 dark:group-hover:bg-slate-700
                      transition-colors shrink-0">
        {icon}
      </div>
      <div className="flex-1 min-w-0">
        <p className="text-sm font-semibold text-slate-800 dark:text-slate-200">{title}</p>
        <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5 truncate">{summary}</p>
      </div>
      <ChevronRight className="w-4 h-4 text-slate-300 dark:text-slate-600 group-hover:text-slate-500 transition-colors shrink-0" />
    </motion.button>
  )
}

// ── Page ────────────────────────────────────────────────────────────────

export default function Settings() {
  const { t } = useT()
  const toast = useToast()
  const { pref, setPref } = useTheme()
  const {
    colorScheme, toggleColorScheme, positiveClass, negativeClass,
    baseCurrency, setBaseCurrency, showRiskMetrics, toggleRiskMetrics,
  } = useSettings()

  const [open, setOpen] = useState<string | null>(null)
  const CURRENCY_LABELS: Record<BaseCurrency, string> = {
    CNY: t.settings.currencyCny, HKD: t.settings.currencyHkd, USD: t.settings.currencyUsd,
  }

  // ── AI state ──────────────────────────────────────────────────────────
  const [aiProvider, setAiProvider] = useState<ApiFormat | ''>('openai_compat')
  const [aiLegacyProvider, setAiLegacyProvider] = useState('')
  const [aiKey, setAiKey] = useState('')
  const [aiBaseUrl, setAiBaseUrl] = useState(DEFAULT_OPENAI_COMPAT_BASE_URL)
  const [aiModel, setAiModel] = useState(DEFAULT_OPENAI_COMPAT_MODEL)
  const [aiModels, setAiModels] = useState<string[]>([])
  const [aiModelsLoading, setAiModelsLoading] = useState(false)
  const [aiModelsError, setAiModelsError] = useState('')
  const [aiHasKey, setAiHasKey] = useState(false)
  const [aiMode, setAiMode] = useState<'default' | 'custom'>('default')

  useEffect(() => {
    fetch(`${BASE}/api/ai/settings`, { credentials: 'include' })
      .then(r => r.json()).then(d => {
        if (d.provider) {
          if (isApiFormat(d.provider)) {
            setAiProvider(d.provider)
            setAiLegacyProvider('')
          } else {
            setAiProvider('')
            setAiLegacyProvider(d.provider)
          }
        }
        if (d.model) setAiModel(d.model)
        if (d.baseUrl) setAiBaseUrl(d.baseUrl)
        if (d.hasKey) { setAiHasKey(true); setAiMode('custom') }
      }).catch(() => {})
  }, [])

  const AI_FORMAT_OPTIONS: { value: ApiFormat; label: string }[] = [
    { value: 'openai_compat', label: 'OpenAI兼容' },
    { value: 'anthropic', label: 'Anthropic' },
  ]
  const aiFormatLabel = aiProvider ? AI_FORMAT_OPTIONS.find(o => o.value === aiProvider)?.label ?? aiProvider : '未选择 API 格式'

  function resetAiModelSelection() {
    setAiModel('')
    setAiModels([])
    setAiModelsError('')
  }

  // ── MCP state ─────────────────────────────────────────────────────────
  const mcpUrl = `${window.location.origin}${BASE}/mcp`
  const [mcpToken, setMcpToken] = useState('')
  const [mcpGenerating, setMcpGenerating] = useState(false)
  const [mcpTokens, setMcpTokens] = useState<McpTokenInfo[]>([])
  const [mcpHasActive, setMcpHasActive] = useState(false)

  const refreshMcpTokens = useCallback(() => {
    getMcpTokens().then(d => {
      setMcpTokens(d.tokens)
      setMcpHasActive(d.tokens.some(t => !t.revoked))
    }).catch(() => {})
  }, [])

  // 启动时查询已有 token 状态
  useEffect(() => { refreshMcpTokens() }, [refreshMcpTokens])

  // ── Card status summaries ─────────────────────────────────────────────
  const themeLabel = { system: t.settings.themeSystem, light: t.settings.themeLight, dark: t.settings.themeDark }[pref]
  const schemeLabel = colorScheme === 'cn' ? '红涨绿跌' : '绿涨红跌'
  const isDefaultAi = aiMode === 'default'
  const aiSummary = isDefaultAi ? '默认' : `${aiFormatLabel} · ${aiModel || '未选择模型'}`

  async function fetchAiModels() {
    if (!aiProvider) {
      toast('请选择 API 格式', false)
      return
    }
    setAiModelsLoading(true)
    setAiModelsError('')
    try {
      const data = await aiListModels({ provider: aiProvider, baseUrl: aiBaseUrl, apiKey: aiKey })
      if (data.error) {
        setAiModels([])
        setAiModelsError(data.error)
        toast(data.error, false)
        return
      }
      const models = data.models ?? []
      setAiModels(models)
      if (aiModel && !models.includes(aiModel)) setAiModel('')
      toast(`获取到 ${models.length} 个模型`, true)
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e)
      setAiModelsError(message)
      toast(message, false)
    } finally {
      setAiModelsLoading(false)
    }
  }

  return (
    <div className="overflow-auto h-full bg-slate-50 dark:bg-slate-950" style={{ scrollbarGutter: 'stable' }}>
      <div className="max-w-lg mx-auto px-4 sm:px-6 pb-16">

        {/* ── Header ───────────────────────────────────────────────── */}
        <div className="pt-8 pb-6">
          <h2 className="text-xl font-bold text-slate-900 dark:text-slate-100 tracking-tight">
            {t.settings.title}
          </h2>
          <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">
            管理你的应用偏好与外部接入
          </p>
        </div>

        {/* ── Cards ────────────────────────────────────────────────── */}
        <div className="space-y-3">
          <SettingCard delay={0}
            icon={<Sun className="w-5 h-5" />}
            title="外观"
            summary={`${themeLabel} · ${schemeLabel}`}
            onClick={() => setOpen('appearance')}
          />
          <SettingCard delay={1}
            icon={<Globe className="w-5 h-5" />}
            title="个性化"
            summary={`基准货币 ${baseCurrency} · 量化列${showRiskMetrics ? '开启' : '关闭'}`}
            onClick={() => setOpen('display')}
          />
          <SettingCard delay={2}
            icon={<Bot className="w-5 h-5" />}
            title="观澜"
            summary={aiSummary}
            onClick={() => setOpen('ai')}
          />
          <SettingCard delay={3}
            icon={<Plug className="w-5 h-5" />}
            title="MCP 接入"
            summary={mcpHasActive ? '已连接' : '未配置'}
            onClick={() => setOpen('mcp')}
          />

          <motion.a
            href="https://116.62.179.231:4443/dashboard/account"
            target="_blank" rel="noopener noreferrer"
            initial={{ opacity: 0, y: 12 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: 0.32, duration: 0.3, ease: 'easeOut' }}
            className="group flex items-center gap-4 p-5 rounded-2xl
                       bg-white dark:bg-slate-900
                       border border-slate-200 dark:border-slate-800
                       hover:border-slate-300 dark:hover:border-slate-700
                       hover:shadow-sm transition-all duration-200
                       cursor-pointer w-full no-underline"
          >
            <div className="flex items-center justify-center w-10 h-10 rounded-xl
                            bg-slate-100 dark:bg-slate-800
                            text-slate-600 dark:text-slate-400
                            group-hover:bg-slate-200 dark:group-hover:bg-slate-700
                            transition-colors shrink-0">
              <ExternalLink className="w-5 h-5" />
            </div>
            <div className="flex-1 min-w-0">
              <p className="text-sm font-semibold text-slate-800 dark:text-slate-200">Frost ID</p>
              <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">账户安全管理 · 密码修改 · 设备管理</p>
            </div>
            <ChevronRight className="w-4 h-4 text-slate-300 dark:text-slate-600 group-hover:text-slate-500 transition-colors shrink-0" />
          </motion.a>
        </div>
      </div>

      {/* ═══════════════════════════════════════════════════════════════
          MODALS
          ═══════════════════════════════════════════════════════════════ */}

      {/* ── 外观 ────────────────────────────────────────────────────── */}
      <Modal open={open === 'appearance'} onClose={() => setOpen(null)} title="外观">
        <ModalRow label={t.settings.theme}>
          <Segments value={pref} onChange={setPref} options={[
            { value: 'system', label: t.settings.themeSystem, icon: <Monitor className="w-3.5 h-3.5" /> },
            { value: 'light',  label: t.settings.themeLight,  icon: <Sun className="w-3.5 h-3.5" /> },
            { value: 'dark',   label: t.settings.themeDark,   icon: <Moon className="w-3.5 h-3.5" /> },
          ]} />
        </ModalRow>

        <ModalRow label={t.settings.colorScheme}
          desc={colorScheme === 'cn' ? '红涨绿跌（A 股习惯）' : '绿涨红跌（国际习惯）'}>
          <div className="flex items-center gap-3">
            <span className="text-xs tabular-nums text-slate-500 select-none">
              <span className={positiveClass}>+5.20%</span>
              <span className="text-slate-300 mx-1">/</span>
              <span className={negativeClass}>-3.10%</span>
            </span>
            <Toggle on={colorScheme === 'cn'} onToggle={toggleColorScheme} />
          </div>
        </ModalRow>
      </Modal>

      {/* ── 显示 ────────────────────────────────────────────────────── */}
      <Modal open={open === 'display'} onClose={() => setOpen(null)} title="个性化">
        <ModalRow label={t.settings.currency} desc="影响所有持仓、盈亏金额的显示单位">
          <Segments value={baseCurrency} onChange={setBaseCurrency} options={
            (Object.keys(CURRENCY_LABELS) as BaseCurrency[]).map(c => ({ value: c, label: CURRENCY_LABELS[c] }))
          } />
        </ModalRow>
        <ModalRow label={t.settings.quantColumns} desc={t.settings.quantColumnsDesc}>
          <Toggle on={showRiskMetrics} onToggle={toggleRiskMetrics} />
        </ModalRow>
      </Modal>

      {/* ── 观澜 ─────────────────────────────────────────────────── */}
      <Modal open={open === 'ai'} onClose={() => setOpen(null)} title="观澜">
        {/* Mode toggle */}
        <div className="w-fit pb-3 border-b border-slate-100 dark:border-slate-800 mb-3">
          <Segments value={aiMode} onChange={v => setAiMode(v as 'default' | 'custom')} options={[
            { value: 'default', label: '默认' },
            { value: 'custom', label: '自定义' },
          ]} />
        </div>

        {aiMode === 'custom' && (
          <ModalRow label="API 格式" desc={aiLegacyProvider ? `检测到旧配置「${aiLegacyProvider}」，请重新选择调用格式` : undefined}>
            <Segments<ApiFormat> value={aiProvider} onChange={v => {
              setAiProvider(v)
              setAiLegacyProvider('')
              if (!aiBaseUrl) setAiBaseUrl(v === 'openai_compat' ? DEFAULT_OPENAI_COMPAT_BASE_URL : 'https://api.anthropic.com')
              resetAiModelSelection()
            }} options={AI_FORMAT_OPTIONS} />
          </ModalRow>
        )}

        {aiMode === 'custom' && (
          <><div className="py-3 border-b border-slate-100 dark:border-slate-800">
          <p className="text-sm font-medium text-slate-700 dark:text-slate-300 mb-2">{t.settings.aiApiKey}</p>
          <input type="password" value={aiKey} onChange={e => setAiKey(e.target.value)}
            placeholder={aiHasKey ? t.settings.aiKeyPlaceholder : t.settings.aiApiKey}
            className={inputCls} />
        </div>

        <div className="py-3 border-b border-slate-100 dark:border-slate-800">
          <p className="text-sm font-medium text-slate-700 dark:text-slate-300 mb-2">{t.settings.aiBaseUrl}</p>
          <input type="text" value={aiBaseUrl} onChange={e => {
            setAiBaseUrl(e.target.value)
            resetAiModelSelection()
          }} placeholder={aiProvider === 'anthropic' ? 'https://api.anthropic.com' : t.settings.aiBaseUrl}
            className={inputCls} />
        </div></>
        )}

        {aiMode === 'custom' && (
          <div className="py-3 border-b border-slate-100 dark:border-slate-800">
            <p className="text-sm font-medium text-slate-700 dark:text-slate-300 mb-2">{t.settings.aiModel}</p>
            <div className="flex items-center gap-2">
              <select value={aiModel} onChange={e => setAiModel(e.target.value)}
                disabled={aiModels.length === 0}
                className={`${inputCls} flex-1 disabled:opacity-60`}>
                {aiModels.length === 0 ? (
                  <option value={aiModel}>{aiModel || '请先获取模型列表'}</option>
                ) : (
                  <>
                    <option value="">选择模型</option>
                    {aiModels.map(model => <option key={model} value={model}>{model}</option>)}
                  </>
                )}
              </select>
              <button onClick={fetchAiModels} disabled={aiModelsLoading || !aiProvider || !(aiKey || aiHasKey)}
                className="inline-flex items-center justify-center gap-1.5 h-9 px-3 rounded-lg border border-slate-200 dark:border-slate-700 text-xs font-medium text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-800 disabled:opacity-60 transition-colors cursor-pointer shrink-0">
                <RefreshCw className={`w-3.5 h-3.5 ${aiModelsLoading ? 'animate-spin' : ''}`} />
                获取
              </button>
            </div>
            {aiModelsError && <p className="mt-1 text-[11px] text-red-500">{aiModelsError}</p>}
          </div>
        )}

        <div className="flex gap-2 pt-4">
            <button onClick={async () => {
              if (!aiProvider) { toast('请先选择 API 格式', false); return }
              if (!aiModel) { toast('请先获取并选择模型', false); return }
              const body: Record<string, string> = { provider: aiProvider, model: aiModel, baseUrl: aiBaseUrl }
              if (aiKey) body.apiKey = aiKey
              const res = await fetch(`${BASE}/api/ai/settings`, {
                method: 'POST', credentials: 'include',
                headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
              })
              const data = await res.json()
              if (data.error) { toast(data.error, false); return }
              if (aiKey) setAiHasKey(true)
              toast(t.settings.aiSaveSuccess, true)
            }} className="flex-1 h-10 rounded-xl bg-slate-900 dark:bg-slate-100 text-white dark:text-slate-900 text-sm font-medium hover:bg-slate-800 dark:hover:bg-white transition-colors cursor-pointer">
              {t.settings.aiSaveBtn}
            </button>
          </div>
      </Modal>

      {/* ── MCP 接入 ─────────────────────────────────────────────────── */}
      <Modal open={open === 'mcp'} onClose={() => setOpen(null)} title="MCP 接入">
        <div className="space-y-5">
          {/* Endpoint */}
          <div>
            <p className="text-xs font-medium text-slate-500 dark:text-slate-400 mb-1.5">{t.settings.mcpConnector}</p>
            <div className="flex items-center gap-2">
              <code className="flex-1 px-3 py-2 rounded-lg bg-slate-100 dark:bg-slate-800 text-xs text-slate-600 dark:text-slate-300 break-all font-mono">{mcpUrl}</code>
              <button onClick={() => { navigator.clipboard.writeText(mcpUrl); toast(t.settings.mcpCopied, true) }}
                className="flex items-center justify-center w-9 h-9 rounded-lg border border-slate-200 dark:border-slate-700 text-slate-400 hover:text-slate-600 dark:hover:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-800 transition-colors cursor-pointer shrink-0">
                <Copy className="w-3.5 h-3.5" />
              </button>
            </div>
          </div>

          {/* Active tokens list */}
          {mcpTokens.length > 0 && (
            <div>
              <p className="text-xs font-medium text-slate-500 dark:text-slate-400 mb-2">已生成的令牌</p>
              <div className="space-y-1.5">
                {mcpTokens.filter(tk => !tk.revoked).map(tk => (
                  <div key={tk.id} className="flex items-center gap-2 px-3 py-2 rounded-lg bg-slate-50 dark:bg-slate-800/50 text-xs">
                    <span className="flex-1 text-slate-700 dark:text-slate-300 truncate font-mono">{tk.label}</span>
                    <span className="text-[10px] text-slate-400 shrink-0">
                      {tk.last_used_at
                        ? new Date(tk.last_used_at).toLocaleDateString()
                        : new Date(tk.created_at).toLocaleDateString()}
                    </span>
                    <button onClick={async () => {
                      await revokeMcpToken(tk.id)
                      refreshMcpTokens()
                      toast('令牌已撤销', true)
                    }}
                      className="flex items-center justify-center w-7 h-7 rounded-md text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-950/30 transition-colors cursor-pointer shrink-0"
                      title="撤销令牌">
                      <Trash2 className="w-3.5 h-3.5" />
                    </button>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Generate new token */}
          <div>
            {!mcpToken ? (
              <button disabled={mcpGenerating} onClick={async () => {
                setMcpGenerating(true)
                try {
                  const res = await fetch(`${BASE}/api/mcp/tokens`, {
                    method: 'POST', credentials: 'include',
                    headers: { 'Content-Type': 'application/json' }, body: '{"label":"manual"}',
                  })
                  const data = await res.json()
                  if (data.error) { toast(data.error, false); return }
                  setMcpToken(data.token)
                  refreshMcpTokens()
                } finally { setMcpGenerating(false) }
              }} className="w-full h-10 rounded-xl bg-slate-900 dark:bg-slate-100 text-white dark:text-slate-900 text-sm font-medium hover:bg-slate-800 dark:hover:bg-white transition-colors disabled:opacity-50 cursor-pointer">
                {mcpGenerating ? '生成中...' : t.settings.mcpGenerate}
              </button>
            ) : (
              <div className="space-y-3">
                <p className="text-xs text-amber-600 dark:text-amber-400">{t.settings.mcpTokenOnce}</p>
                <div className="flex items-center gap-2">
                  <code className="flex-1 px-3 py-2 rounded-lg bg-slate-100 dark:bg-slate-800 text-xs text-slate-600 dark:text-slate-300 break-all font-mono">{mcpToken}</code>
                  <button onClick={() => { navigator.clipboard.writeText(mcpToken); toast(t.settings.mcpCopied, true) }}
                    className="flex items-center justify-center w-9 h-9 rounded-lg border border-slate-200 dark:border-slate-700 text-slate-400 hover:text-slate-600 dark:hover:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-800 transition-colors cursor-pointer shrink-0">
                    <Copy className="w-3.5 h-3.5" />
                  </button>
                </div>

                <p className="text-[11px] text-slate-400">{t.settings.mcpJsonHint}</p>
                {(() => {
                  const mcpJson = JSON.stringify({
                    mcpServers: { investory: { type: 'http', url: mcpUrl, headers: { Authorization: `Bearer ${mcpToken}` } } }
                  }, null, 2)
                  return (
                    <div className="relative">
                      <pre className="block p-3 pr-16 rounded-lg bg-slate-100 dark:bg-slate-800 text-[11px] leading-relaxed text-slate-600 dark:text-slate-300 overflow-x-auto font-mono">{mcpJson}</pre>
                      <div className="absolute top-2 right-2 flex gap-1">
                        <button onClick={() => { navigator.clipboard.writeText(mcpJson); toast(t.settings.mcpJsonCopied, true) }}
                          className="px-2 h-7 rounded-md border border-slate-200 dark:border-slate-600 text-[10px] text-slate-500 dark:text-slate-400 bg-white dark:bg-slate-700 hover:bg-slate-50 dark:hover:bg-slate-600 transition-colors cursor-pointer">
                          {t.settings.mcpCopy}
                        </button>
                        <button onClick={() => {
                          const blob = new Blob([mcpJson], { type: 'application/json' })
                          const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = '.mcp.json'; a.click()
                        }} className="px-2 h-7 rounded-md border border-slate-200 dark:border-slate-600 text-[10px] text-slate-500 dark:text-slate-400 bg-white dark:bg-slate-700 hover:bg-slate-50 dark:hover:bg-slate-600 transition-colors cursor-pointer">
                          <Download className="w-3 h-3" />
                        </button>
                      </div>
                    </div>
                  )
                })()}

                <p className="text-[11px] text-slate-400">{t.settings.mcpCliHint}</p>
                <code className="block px-3 py-2 rounded-lg bg-slate-100 dark:bg-slate-800 text-[11px] text-slate-600 dark:text-slate-300 break-all font-mono">
                  claude mcp add --transport http investory {mcpUrl} --header "Authorization: Bearer {mcpToken}"
                </code>
              </div>
            )}
          </div>
        </div>
      </Modal>

    </div>
  )
}
