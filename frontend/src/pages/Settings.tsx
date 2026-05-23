import { useState, useRef, useEffect } from 'react'
import { useAuth } from '@/hooks/use-auth'
import { useToast } from '@/components/Toast'
import { useConfirm } from '@/hooks/use-confirm'
import { useSettings, type BaseCurrency } from '@/hooks/use-settings'
import { useTheme } from '@/hooks/use-theme'
import { useT } from '@/i18n/I18nContext'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Sun, Moon, Camera, Sparkles } from 'lucide-react'

export default function Settings() {
  const { username, logout } = useAuth()
  const confirm = useConfirm()
  const toast = useToast()
  const { colorScheme, toggleColorScheme, positiveClass, negativeClass, baseCurrency, setBaseCurrency, showRiskMetrics, toggleRiskMetrics } = useSettings()
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

  // Load AI settings from server on mount
  useEffect(() => {
    fetch('/investory/api/ai/settings', { credentials: 'include' })
      .then(r => r.json()).then(d => {
        if (d.provider) setAiProvider(d.provider)
        if (d.model) setAiModel(d.model)
        if (d.baseUrl) setAiBaseUrl(d.baseUrl)
        if (d.hasKey) setAiHasKey(true)
      }).catch(() => {})
  }, [])

  const AI_PRESETS: Record<string, { label: string; baseUrl: string; model: string }> = {
    bailian:    { label: t.settings.presetBailian, baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen-plus' },
    openai:     { label: 'OpenAI',                   baseUrl: '',                                           model: 'gpt-4o-mini' },
    deepseek:   { label: 'DeepSeek',                 baseUrl: 'https://api.deepseek.com/v1',                model: 'deepseek-chat' },
    moonshot:   { label: 'Moonshot',                 baseUrl: 'https://api.moonshot.cn/v1',                 model: 'moonshot-v1-8k' },
    zhipu:      { label: t.settings.presetZhipu,     baseUrl: 'https://open.bigmodel.cn/api/paas/v4',       model: 'glm-4-flash' },
    anthropic:  { label: 'Anthropic',                baseUrl: '',                                           model: 'claude-haiku-4-5' },
    custom:     { label: t.settings.presetCustom,     baseUrl: '',                                           model: '' },
  }

  const CURRENCY_LABELS: Record<BaseCurrency, string> = {
    CNY: t.settings.currencyCny,
    HKD: t.settings.currencyHkd,
    USD: t.settings.currencyUsd,
  }

  const THEME_LABELS: Record<string, string> = {
    system: t.settings.themeSystem,
    light: t.settings.themeLight,
    dark: t.settings.themeDark,
  }

  function handleAvatarUpload(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    if (!file) return
    const reader = new FileReader()
    reader.onload = () => {
      const dataUrl = reader.result as string
      setAvatar(dataUrl)
      localStorage.setItem('investory_avatar', dataUrl)
    }
    reader.readAsDataURL(file)
  }

  async function handleChangePassword() {
    if (!oldPw || !newPw) { setPwMsg(t.settings.pwFillBoth); return }
    if (newPw.length < 6) { setPwMsg(t.settings.pwTooShort); return }
    const form = new URLSearchParams({ oldPassword: oldPw, newPassword: newPw })
    const res = await fetch('/investory/api/password', { method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: form.toString() })
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
      const res = await fetch('/investory/api/account', { method: 'DELETE', credentials: 'include' })
      if (res.ok) { toast(t.settings.accountDeleted, true); setTimeout(logout, 1500) }
      else toast(t.settings.deleteFailed, false)
    } catch { toast(t.settings.networkError, false) }
    setDeleting(false)
  }

  const modelPlaceholder = aiProvider === 'openai'
    ? 'e.g. gpt-4o-mini / gpt-4o'
    : aiProvider === 'anthropic'
      ? 'e.g. claude-haiku-4-5 / claude-sonnet-4-20250514'
      : aiProvider === 'deepseek'
        ? 'e.g. deepseek-chat'
        : t.settings.aiModel

  return (
    <div className="p-6 max-w-2xl mx-auto space-y-6">
      <h2 className="text-xl font-bold text-slate-900 dark:text-slate-100 tracking-tight">{t.settings.title}</h2>
      <div className="grid grid-cols-1 md:grid-cols-2 gap-6">

      {/* Avatar */}
      <Card>
        <CardContent className="pt-6 flex items-center gap-4">
          <div className="relative cursor-pointer" onClick={() => fileRef.current?.click()}>
            {avatar ? (
              <img src={avatar} className="w-16 h-16 rounded-full object-cover border-2 border-slate-200" />
            ) : (
              <div className="w-16 h-16 rounded-full bg-slate-200 flex items-center justify-center">
                <span className="text-2xl font-bold text-slate-500">{username?.charAt(0)?.toUpperCase() || '?'}</span>
              </div>
            )}
            <div className="absolute bottom-0 right-0 w-5 h-5 rounded-full bg-slate-800 flex items-center justify-center">
              <Camera className="w-3 h-3 text-white" />
            </div>
          </div>
          <div>
            <p className="font-semibold text-slate-900 dark:text-slate-100">{username}</p>
            <p className="text-xs text-slate-400 mt-0.5">{t.settings.avatarHint}</p>
          </div>
          <input ref={fileRef} type="file" accept="image/*" className="hidden" onChange={handleAvatarUpload} />
        </CardContent>
      </Card>

      {/* Theme */}
      <Card>
        <CardHeader><CardTitle className="text-base">{t.settings.theme}</CardTitle></CardHeader>
        <CardContent>
          <div className="flex gap-2">
            {(['system', 'light', 'dark'] as const).map(val => (
              <button key={val} onClick={() => setPref(val)}
                className={`flex-1 h-10 rounded-xl text-sm font-medium transition-colors flex items-center justify-center gap-1.5
                  ${pref === val ? 'bg-slate-900 text-white' : 'bg-slate-100 text-slate-600 hover:bg-slate-200'}`}>
                {val === 'light' && <Sun className="w-3.5 h-3.5" />}
                {val === 'dark' && <Moon className="w-3.5 h-3.5" />}
                {THEME_LABELS[val]}
              </button>
            ))}
          </div>
        </CardContent>
      </Card>

      {/* Color scheme */}
      <Card>
        <CardHeader><CardTitle className="text-base">{t.settings.colorScheme}</CardTitle></CardHeader>
        <CardContent>
          <div className="flex items-center justify-between">
            <div className="space-y-1">
              <p className="text-sm font-medium text-slate-700 dark:text-slate-300">{colorScheme === 'cn' ? t.settings.cnColorScheme : t.settings.enColorScheme}</p>
              <div className="flex items-center gap-3 text-sm">
                <span className={positiveClass}>+5.20%</span><span className="text-slate-300">/</span><span className={negativeClass}>-3.10%</span>
              </div>
            </div>
            <button onClick={toggleColorScheme} className="relative w-12 h-7 rounded-full bg-slate-200 hover:bg-slate-300 transition-colors">
              <span className="absolute top-0.5 w-6 h-6 rounded-full bg-white shadow-sm transition-all duration-200" style={{ left: colorScheme === 'cn' ? '2px' : '22px' }} />
            </button>
          </div>
        </CardContent>
      </Card>

      {/* Base currency */}
      <Card>
        <CardHeader><CardTitle className="text-base">{t.settings.currency}</CardTitle></CardHeader>
        <CardContent>
          <div className="flex gap-2">
            {(Object.keys(CURRENCY_LABELS) as BaseCurrency[]).map(c => (
              <button key={c} onClick={() => setBaseCurrency(c)}
                className={`flex-1 h-10 rounded-xl text-sm font-medium transition-colors ${baseCurrency === c ? 'bg-slate-900 text-white' : 'bg-slate-100 text-slate-600 hover:bg-slate-200 dark:bg-slate-800 dark:text-slate-300'}`}>{CURRENCY_LABELS[c]}</button>
            ))}
          </div>
        </CardContent>
      </Card>

      {/* Quant metrics toggle */}
      <Card>
        <CardHeader><CardTitle className="text-base">{t.settings.quantColumns}</CardTitle></CardHeader>
        <CardContent>
          <div className="flex items-center justify-between">
            <div className="space-y-1">
              <p className="text-sm font-medium text-slate-700 dark:text-slate-300">
                {showRiskMetrics ? t.settings.quantColumnsOn : t.settings.quantColumnsOff}
              </p>
              <p className="text-xs text-slate-400">{t.settings.quantColumnsDesc}</p>
            </div>
            <button onClick={toggleRiskMetrics}
              className="relative w-12 h-7 rounded-full bg-slate-200 hover:bg-slate-300 transition-colors">
              <span className="absolute top-0.5 w-6 h-6 rounded-full bg-white shadow-sm transition-all duration-200"
                style={{ left: showRiskMetrics ? '22px' : '2px' }} />
            </button>
          </div>
        </CardContent>
      </Card>

      {/* AI Assistant */}
      <Card>
        <CardHeader>
          <div className="flex items-center justify-between">
            <CardTitle className="text-base flex items-center gap-2"><Sparkles className="w-4 h-4" />{t.settings.aiSettings}</CardTitle>
            <span className="text-[10px] text-slate-400">{t.settings.aiDefaultNote}</span>
          </div>
        </CardHeader>
        <CardContent className="space-y-3">
          <select value={aiProvider} onChange={e => {
            const p = e.target.value; setAiProvider(p)
            const preset = AI_PRESETS[p]
            if (preset && p !== 'custom') { setAiBaseUrl(preset.baseUrl); setAiModel(preset.model) }
          }} className="w-full h-10 rounded-xl border border-slate-200 px-3 text-sm">
            {Object.entries(AI_PRESETS).map(([key, p]) => (
              <option key={key} value={key}>{p.label}</option>
            ))}
          </select>
          <input type="password" value={aiKey} onChange={e => setAiKey(e.target.value)}
            placeholder={aiHasKey ? t.settings.aiKeyPlaceholder : t.settings.aiApiKey}
            className="w-full h-10 rounded-xl border border-slate-200 px-3.5 text-sm" />
          {aiProvider === 'custom' && (
            <input type="text" value={aiBaseUrl} onChange={e => setAiBaseUrl(e.target.value)}
              placeholder={t.settings.aiBaseUrl}
              className="w-full h-10 rounded-xl border border-slate-200 px-3.5 text-sm" />
          )}
          <input type="text" value={aiModel} onChange={e => setAiModel(e.target.value)}
            placeholder={modelPlaceholder}
            className="w-full h-10 rounded-xl border border-slate-200 px-3.5 text-sm" />
          <div className="flex gap-2">
            <button onClick={async () => {
              const body: Record<string, string> = { provider: aiProvider, model: aiModel, baseUrl: aiBaseUrl }
              if (aiKey) body.apiKey = aiKey
              const res = await fetch('/investory/api/ai/settings', {
                method: 'POST', credentials: 'include',
                headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
              })
              const data = await res.json()
              if (data.error) { toast(data.error, false); return }
              if (aiKey) setAiHasKey(true)
              toast(t.settings.aiSaveSuccess, true)
            }} className="flex-1 h-10 rounded-xl bg-slate-900 text-white text-sm font-medium hover:bg-slate-800 transition-colors">
              {t.settings.aiSaveBtn}
            </button>
            <button onClick={async () => {
              const res = await fetch('/investory/api/ai/settings', { method: 'DELETE', credentials: 'include' })
              const data = await res.json()
              if (data.error) { toast(data.error, false); return }
              setAiProvider('bailian'); setAiKey(''); setAiBaseUrl(''); setAiModel('qwen-plus'); setAiHasKey(false)
              toast(t.settings.aiResetSuccess, true)
            }} className="h-10 px-3 rounded-xl border border-slate-200 text-slate-500 text-sm hover:bg-slate-50 transition-colors whitespace-nowrap">
              {t.settings.aiResetBtn}
            </button>
          </div>
        </CardContent>
      </Card>

      {/* Password */}
      <Card>
        <CardHeader><CardTitle className="text-base">{t.common.warn}</CardTitle></CardHeader>
        <CardContent className="space-y-3">
          <input type="password" placeholder={t.settings.oldPassword} value={oldPw} onChange={e => setOldPw(e.target.value)}
            className="w-full h-10 rounded-xl border border-slate-200 px-3.5 text-sm dark:bg-slate-800 dark:border-slate-700 dark:text-slate-200" />
          <input type="password" placeholder={`${t.settings.newPassword}（${t.settings.newPasswordHint}）`} value={newPw} onChange={e => setNewPw(e.target.value)}
            className="w-full h-10 rounded-xl border border-slate-200 px-3.5 text-sm dark:bg-slate-800 dark:border-slate-700 dark:text-slate-200" />
          {pwMsg && <p className={`text-xs ${pwMsg === t.settings.pwChanged || pwMsg === t.settings.passwordSuccess ? 'text-emerald-600' : 'text-red-500'}`}>{pwMsg}</p>}
          <button onClick={handleChangePassword} className="w-full h-10 rounded-xl bg-slate-900 text-white text-sm font-medium hover:bg-slate-800 transition-colors">{t.settings.passwordBtn}</button>
        </CardContent>
      </Card>

      {/* Danger zone */}
      <Card className="border-red-200">
        <CardHeader><CardTitle className="text-base text-red-600">{t.settings.dangerZone}</CardTitle></CardHeader>
        <CardContent>
          <button onClick={handleDeleteAccount} disabled={deleting}
            className="w-full h-10 rounded-xl border border-red-200 text-sm font-medium text-red-600 hover:bg-red-50 transition-colors disabled:opacity-50">{deleting ? t.settings.deleting : t.settings.deleteAccount}</button>
        </CardContent>
      </Card>
      </div>
    </div>
  )
}
