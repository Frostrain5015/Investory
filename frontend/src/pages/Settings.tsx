import { useState, useRef, useEffect } from 'react'
import { useAuth } from '@/hooks/use-auth'
import { useToast } from '@/components/Toast'
import { useConfirm } from '@/hooks/use-confirm'
import { useSettings, type BaseCurrency } from '@/hooks/use-settings'
import { useTheme } from '@/hooks/use-theme'
import { useT } from '@/i18n/I18nContext'
import { Sun, Moon, Monitor, Camera } from 'lucide-react'
import { BASE } from '@/services/api'

// ── Primitives ────────────────────────────────────────────────────

function SectionLabel({ children }: { children: React.ReactNode }) {
  return (
    <p className="text-[11px] font-semibold uppercase tracking-widest text-slate-400 dark:text-slate-500 mb-1 pt-6 pb-1">
      {children}
    </p>
  )
}

/** Responsive row: side-by-side on sm+, stacked on mobile */
function Row({ label, desc, children }: { label: string; desc?: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between
                    py-3.5 border-b border-slate-100 dark:border-slate-800 last:border-0 gap-2 sm:gap-8">
      <div className="min-w-0 shrink">
        <p className="text-sm font-medium text-slate-800 dark:text-slate-200 leading-snug">{label}</p>
        {desc && <p className="text-xs text-slate-400 dark:text-slate-500 mt-0.5 leading-relaxed">{desc}</p>}
      </div>
      <div className="shrink-0">{children}</div>
    </div>
  )
}

function Toggle({ on, onToggle }: { on: boolean; onToggle: () => void }) {
  return (
    <button onClick={onToggle} aria-checked={on} role="switch"
      className={`relative w-11 h-6 rounded-full transition-colors shrink-0
        ${on ? 'bg-slate-800 dark:bg-slate-300' : 'bg-slate-200 dark:bg-slate-700'}`}>
      <span className={`absolute top-0.5 w-5 h-5 rounded-full bg-white shadow-sm transition-all duration-200
        ${on ? 'left-[22px]' : 'left-0.5'}`} />
    </button>
  )
}

function Segments<T extends string>({
  value, options, onChange, fullWidthMobile = false,
}: {
  value: T
  options: { value: T; label: string; icon?: React.ReactNode }[]
  onChange: (v: T) => void
  fullWidthMobile?: boolean
}) {
  return (
    <div className={`flex gap-1 p-1 bg-slate-100 dark:bg-slate-800 rounded-xl
      ${fullWidthMobile ? 'w-full sm:w-auto' : ''}`}>
      {options.map(o => (
        <button key={o.value} onClick={() => onChange(o.value)}
          className={`flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-all
            ${fullWidthMobile ? 'flex-1 sm:flex-none' : ''}
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

// ── Page ──────────────────────────────────────────────────────────

export default function Settings() {
  const { username, logout } = useAuth()
  const confirm = useConfirm()
  const toast = useToast()
  const {
    colorScheme, toggleColorScheme, positiveClass, negativeClass,
    baseCurrency, setBaseCurrency, showRiskMetrics, toggleRiskMetrics,
  } = useSettings()
  const { pref, setPref } = useTheme()
  const { t } = useT()

  const fileRef = useRef<HTMLInputElement>(null)
  const [avatar, setAvatar] = useState(() => localStorage.getItem('investory_avatar') || '')
  const [oldPw, setOldPw] = useState('')
  const [newPw, setNewPw] = useState('')
  const [pwMsg, setPwMsg] = useState('')
  const [deleting, setDeleting] = useState(false)

  const [aiProvider, setAiProvider] = useState('bailian')
  const [aiKey, setAiKey] = useState('')
  const [aiBaseUrl, setAiBaseUrl] = useState('')
  const [aiModel, setAiModel] = useState('')
  const [aiHasKey, setAiHasKey] = useState(false)

  // MCP 接入
  const mcpUrl = `${window.location.origin}${BASE}/mcp`
  const [mcpToken, setMcpToken] = useState('')
  const [mcpGenerating, setMcpGenerating] = useState(false)

  useEffect(() => {
    fetch(`${BASE}/api/ai/settings`, { credentials: 'include' })
      .then(r => r.json()).then(d => {
        if (d.provider) setAiProvider(d.provider)
        if (d.model)    setAiModel(d.model)
        if (d.baseUrl)  setAiBaseUrl(d.baseUrl)
        if (d.hasKey)   setAiHasKey(true)
      }).catch(() => {})
  }, [])

  const AI_PRESETS: Record<string, { label: string; baseUrl: string; model: string }> = {
    bailian:   { label: t.settings.presetBailian, baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen-plus' },
    openai:    { label: 'OpenAI',                  baseUrl: '',                                                  model: 'gpt-4o-mini' },
    deepseek:  { label: 'DeepSeek',                baseUrl: 'https://api.deepseek.com/v1',                       model: 'deepseek-v4-flash' },
    moonshot:  { label: 'Moonshot',                baseUrl: 'https://api.moonshot.cn/v1',                        model: 'moonshot-v1-8k' },
    zhipu:     { label: t.settings.presetZhipu,    baseUrl: 'https://open.bigmodel.cn/api/paas/v4',              model: 'glm-4-flash' },
    anthropic: { label: 'Anthropic',               baseUrl: '',                                                  model: 'claude-haiku-4-5' },
    custom:    { label: t.settings.presetCustom,    baseUrl: '',                                                  model: '' },
  }

  const CURRENCY_LABELS: Record<BaseCurrency, string> = {
    CNY: t.settings.currencyCny,
    HKD: t.settings.currencyHkd,
    USD: t.settings.currencyUsd,
  }

  function handleAvatarUpload(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]; if (!file) return
    const reader = new FileReader()
    reader.onload = () => {
      const d = reader.result as string
      setAvatar(d)
      localStorage.setItem('investory_avatar', d)
    }
    reader.readAsDataURL(file)
  }

  async function handleChangePassword() {
    if (!oldPw || !newPw) { setPwMsg(t.settings.pwFillBoth); return }
    if (newPw.length < 6) { setPwMsg(t.settings.pwTooShort); return }
    const form = new URLSearchParams({ oldPassword: oldPw, newPassword: newPw })
    const res = await fetch(`${BASE}/api/password`, {
      method: 'POST', credentials: 'include',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: form.toString(),
    })
    const data = await res.json()
    if (data.error) {
      setPwMsg(data.error)
    } else if (data.status === 'ok') {
      setPwMsg(t.settings.pwChanged)
      setOldPw(''); setNewPw('')
    }
  }

  async function handleDeleteAccount() {
    if (!(await confirm(t.settings.confirmDeleteAccount))) return
    setDeleting(true)
    try {
      const res = await fetch(`${BASE}/api/account`, { method: 'DELETE', credentials: 'include' })
      if (res.ok) { toast(t.settings.accountDeleted, true); setTimeout(logout, 1500) }
      else toast(t.settings.deleteFailed, false)
    } catch { toast(t.settings.networkError, false) }
    setDeleting(false)
  }

  const modelPlaceholder = aiProvider === 'openai'    ? 'e.g. gpt-4o-mini / gpt-4o'
    : aiProvider === 'anthropic' ? 'e.g. claude-haiku-4-5'
    : aiProvider === 'deepseek'  ? 'e.g. deepseek-v4-flash / deepseek-v4-pro'
    : t.settings.aiModel

  return (
    <div className="overflow-auto h-full bg-white dark:bg-slate-950">
      <div className="max-w-2xl mx-auto px-4 sm:px-8 pb-16">

        {/* ── Page title ──────────────────────────────────────── */}
        <div className="pt-6 pb-2">
          <h2 className="text-xl font-bold text-slate-900 dark:text-slate-100 tracking-tight">
            {t.settings.title}
          </h2>
        </div>

        {/* ── 外观 ─────────────────────────────────────────────── */}
        <SectionLabel>外观</SectionLabel>

        <Row label={t.settings.theme}>
          <Segments value={pref} onChange={setPref} fullWidthMobile options={[
            { value: 'system', label: t.settings.themeSystem, icon: <Monitor className="w-3.5 h-3.5" /> },
            { value: 'light',  label: t.settings.themeLight,  icon: <Sun className="w-3.5 h-3.5" /> },
            { value: 'dark',   label: t.settings.themeDark,   icon: <Moon className="w-3.5 h-3.5" /> },
          ]} />
        </Row>

        <Row label={t.settings.colorScheme}
          desc={colorScheme === 'cn' ? '红涨绿跌（A 股习惯）' : '绿涨红跌（国际习惯）'}>
          <div className="flex items-center gap-3">
            <span className="text-xs tabular-nums text-slate-500">
              <span className={positiveClass}>+5.20%</span>
              <span className="text-slate-300 mx-1">/</span>
              <span className={negativeClass}>-3.10%</span>
            </span>
            <Toggle on={colorScheme === 'cn'} onToggle={toggleColorScheme} />
          </div>
        </Row>

        {/* ── 显示 ─────────────────────────────────────────────── */}
        <SectionLabel>显示</SectionLabel>

        <Row label={t.settings.currency} desc="影响所有持仓、盈亏金额的显示单位">
          <Segments value={baseCurrency} onChange={setBaseCurrency} fullWidthMobile options={
            (Object.keys(CURRENCY_LABELS) as BaseCurrency[]).map(c => ({ value: c, label: CURRENCY_LABELS[c] }))
          } />
        </Row>

        <Row label={t.settings.quantColumns} desc={t.settings.quantColumnsDesc}>
          <Toggle on={showRiskMetrics} onToggle={toggleRiskMetrics} />
        </Row>

        {/* ── AI 助手 ───────────────────────────────────────────── */}
        <SectionLabel>AI 助手</SectionLabel>

        <Row label={t.settings.aiProvider}>
          <select value={aiProvider} onChange={e => {
            const p = e.target.value; setAiProvider(p)
            const preset = AI_PRESETS[p]
            if (preset && p !== 'custom') { setAiBaseUrl(preset.baseUrl); setAiModel(preset.model) }
          }} className={`${inputCls} sm:w-52`}>
            {Object.entries(AI_PRESETS).map(([key, p]) => (
              <option key={key} value={key}>{p.label}</option>
            ))}
          </select>
        </Row>

        <Row label={t.settings.aiApiKey}>
          <input type="password" value={aiKey} onChange={e => setAiKey(e.target.value)}
            placeholder={aiHasKey ? t.settings.aiKeyPlaceholder : t.settings.aiApiKey}
            className={`${inputCls} sm:w-64`} />
        </Row>

        {aiProvider === 'custom' && (
          <Row label={t.settings.aiBaseUrl}>
            <input type="text" value={aiBaseUrl} onChange={e => setAiBaseUrl(e.target.value)}
              placeholder={t.settings.aiBaseUrl}
              className={`${inputCls} sm:w-64`} />
          </Row>
        )}

        <Row label={t.settings.aiModel}>
          <input type="text" value={aiModel} onChange={e => setAiModel(e.target.value)}
            placeholder={modelPlaceholder}
            className={`${inputCls} sm:w-64`} />
        </Row>

        <div className="flex gap-2 pt-3 pb-1">
          <button onClick={async () => {
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
          }} className="px-4 h-9 rounded-lg bg-slate-900 dark:bg-slate-100 text-white dark:text-slate-900 text-sm font-medium hover:bg-slate-700 dark:hover:bg-white transition-colors">
            {t.settings.aiSaveBtn}
          </button>
          <button onClick={async () => {
            const res = await fetch(`${BASE}/api/ai/settings`, { method: 'DELETE', credentials: 'include' })
            const data = await res.json()
            if (data.error) { toast(data.error, false); return }
            setAiProvider('bailian'); setAiKey(''); setAiBaseUrl(''); setAiModel('qwen-plus'); setAiHasKey(false)
            toast(t.settings.aiResetSuccess, true)
          }} className="px-4 h-9 rounded-lg border border-slate-200 dark:border-slate-700 text-slate-500 dark:text-slate-400 text-sm hover:bg-slate-50 dark:hover:bg-slate-800 transition-colors">
            {t.settings.aiResetBtn}
          </button>
        </div>

        {/* ── MCP 接入 ──────────────────────────────────────────── */}
        <SectionLabel>{t.settings.mcpSection}</SectionLabel>

        <Row label={t.settings.mcpConnector} desc={t.settings.mcpConnectorDesc}>
          <div className="flex items-center gap-2">
            <code className="px-2 py-1 rounded bg-slate-100 dark:bg-slate-800 text-xs text-slate-600 dark:text-slate-300 break-all">{mcpUrl}</code>
            <button onClick={() => { navigator.clipboard.writeText(mcpUrl); toast(t.settings.mcpCopied, true) }}
              className="px-3 h-8 rounded-lg border border-slate-200 dark:border-slate-700 text-xs text-slate-500 dark:text-slate-400 hover:bg-slate-50 dark:hover:bg-slate-800 transition-colors shrink-0">
              {t.settings.mcpCopy}
            </button>
          </div>
        </Row>

        <Row label={t.settings.mcpToken} desc={t.settings.mcpTokenDesc}>
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
            } finally { setMcpGenerating(false) }
          }} className="px-4 h-9 rounded-lg bg-slate-900 dark:bg-slate-100 text-white dark:text-slate-900 text-sm font-medium hover:bg-slate-700 dark:hover:bg-white transition-colors disabled:opacity-50">
            {t.settings.mcpGenerate}
          </button>
        </Row>

        {mcpToken && (
          <div className="py-3 border-b border-slate-100 dark:border-slate-800">
            <p className="text-xs text-amber-600 dark:text-amber-400 mb-2">{t.settings.mcpTokenOnce}</p>
            <div className="flex items-center gap-2 mb-3">
              <code className="flex-1 px-2 py-1.5 rounded bg-slate-100 dark:bg-slate-800 text-xs text-slate-700 dark:text-slate-200 break-all">{mcpToken}</code>
              <button onClick={() => { navigator.clipboard.writeText(mcpToken); toast(t.settings.mcpCopied, true) }}
                className="px-3 h-8 rounded-lg border border-slate-200 dark:border-slate-700 text-xs text-slate-500 dark:text-slate-400 hover:bg-slate-50 dark:hover:bg-slate-800 transition-colors shrink-0">
                {t.settings.mcpCopy}
              </button>
            </div>
            <p className="text-[11px] text-slate-400 mb-1">{t.settings.mcpCliHint}</p>
            <code className="block px-2 py-1.5 rounded bg-slate-100 dark:bg-slate-800 text-[11px] text-slate-600 dark:text-slate-300 break-all">
              claude mcp add --transport http investory {mcpUrl} --header "Authorization: Bearer {mcpToken}"
            </code>
          </div>
        )}

        {/* ── 账户安全 ──────────────────────────────────────────── */}
        <SectionLabel>账户安全</SectionLabel>

        <Row label="头像与用户名" desc={t.settings.avatarHint}>
          <button className="flex items-center gap-3" onClick={() => fileRef.current?.click()}>
            <div className="relative">
              {avatar
                ? <img src={avatar} className="w-10 h-10 rounded-full object-cover border border-slate-200 dark:border-slate-700" />
                : <div className="w-10 h-10 rounded-full bg-slate-100 dark:bg-slate-800 flex items-center justify-center">
                    <span className="text-base font-bold text-slate-400">{username?.charAt(0)?.toUpperCase() || '?'}</span>
                  </div>
              }
              <div className="absolute -bottom-0.5 -right-0.5 w-4 h-4 rounded-full bg-slate-700 border-2 border-white dark:border-slate-950 flex items-center justify-center">
                <Camera className="w-2 h-2 text-white" />
              </div>
            </div>
            <span className="text-sm font-medium text-slate-700 dark:text-slate-300">{username}</span>
          </button>
          <input ref={fileRef} type="file" accept="image/*" className="hidden" onChange={handleAvatarUpload} />
        </Row>

        {/* Password */}
        <div className="py-3.5 border-b border-slate-100 dark:border-slate-800">
          <p className="text-sm font-medium text-slate-800 dark:text-slate-200 mb-3">{t.settings.changePassword}</p>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-2.5 sm:max-w-md">
            <input type="password" placeholder={t.settings.oldPassword} value={oldPw}
              onChange={e => setOldPw(e.target.value)} className={inputCls} />
            <input type="password"
              placeholder={`${t.settings.newPassword}（${t.settings.newPasswordHint}）`}
              value={newPw} onChange={e => setNewPw(e.target.value)} className={inputCls} />
          </div>
          {pwMsg && (
            <p className={`text-xs mt-2 ${
              pwMsg === t.settings.pwChanged || pwMsg === t.settings.passwordSuccess
                ? 'text-emerald-600' : 'text-red-500'
            }`}>{pwMsg}</p>
          )}
          <button onClick={handleChangePassword}
            className="mt-3 px-4 h-9 rounded-lg bg-slate-900 dark:bg-slate-100 text-white dark:text-slate-900 text-sm font-medium hover:bg-slate-700 transition-colors">
            {t.settings.passwordBtn}
          </button>
        </div>

        {/* Danger zone */}
        <div className="pt-4">
          <p className="text-xs font-semibold uppercase tracking-widest text-red-400 mb-3">{t.settings.dangerZone}</p>
          <button onClick={handleDeleteAccount} disabled={deleting}
            className="px-4 h-9 rounded-lg border border-red-200 dark:border-red-900 text-sm font-medium text-red-600 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-950/30 transition-colors disabled:opacity-50">
            {deleting ? t.settings.deleting : t.settings.deleteAccount}
          </button>
        </div>

      </div>
    </div>
  )
}
