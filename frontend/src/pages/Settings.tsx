import { useRef, useState, type FormEvent, type ReactNode } from 'react'
import { useSettings, type BaseCurrency } from '@/hooks/use-settings'
import { useTheme } from '@/hooks/use-theme'
import { useT } from '@/i18n/I18nContext'
import { useToast } from '@/hooks/use-toast'
import { Sun, Moon, Monitor, Globe, Bot, ChevronRight, ExternalLink, RefreshCw, Eye, EyeOff, Plus, Trash2, Check } from 'lucide-react'
import Modal, { ModalRow } from '@/components/Modal'
import InfoTip from '@/components/InfoTip'
import { aiListModels } from '@/services/api'
import { useModels } from '@/hooks/use-models'
import { useConfirm } from '@/hooks/use-confirm'
import type { AiSettings } from '@/types'

type ApiFormat = 'openai_compat' | 'anthropic'
type SettingsPanel = 'appearance' | 'display' | 'ai' | null
const FROST_ID_URL = 'https://frostrain.tech/dashboard/account'
const inputCls = 'w-full min-h-11 rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 px-3 text-sm text-slate-800 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-slate-400 placeholder:text-slate-400'
const secondaryCls = 'inline-flex items-center justify-center gap-1.5 min-h-11 px-3 rounded-lg border border-slate-200 dark:border-slate-700 text-xs font-medium text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-800 disabled:opacity-50 cursor-pointer focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-slate-500'
function normalizeEndpoint(value: string) { return value.trim().replace(/\/+$/, '') }
function Segments<T extends string>({ value, options, onChange }: {
  value: T; options: { value: T; label: string; icon?: ReactNode }[]; onChange: (value: T) => void
}) {
  return <div className="inline-flex flex-wrap gap-1 p-1 bg-slate-100 dark:bg-slate-800 rounded-xl">
    {options.map(option => <button type="button" key={option.value} aria-pressed={value === option.value}
      onClick={() => onChange(option.value)}
      className={`inline-flex items-center justify-center gap-1.5 min-h-11 px-3 rounded-lg text-xs font-medium cursor-pointer focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-slate-500 ${value === option.value ? 'bg-white dark:bg-slate-600 text-slate-900 dark:text-white shadow-sm' : 'text-slate-500 dark:text-slate-400'}`}>
      {option.icon}{option.label}
    </button>)}
  </div>
}
function SettingCard({ icon, title, summary, onClick }: { icon: ReactNode; title: string; summary: string; onClick: () => void }) {
  return <button type="button" onClick={onClick} className="group flex items-center gap-4 p-5 rounded-2xl bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 hover:border-slate-400 dark:hover:border-slate-600 text-left cursor-pointer w-full focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-slate-500">
    <span className="flex items-center justify-center w-10 h-10 rounded-xl bg-slate-100 dark:bg-slate-800 text-slate-600 dark:text-slate-400 shrink-0">{icon}</span>
    <span className="flex-1 min-w-0">
      <span className="block text-sm font-semibold text-slate-800 dark:text-slate-200">{title}</span>
      <span className="block text-xs text-slate-500 dark:text-slate-400 mt-0.5 truncate">{summary}</span>
    </span>
    <ChevronRight className="w-4 h-4 text-slate-400 shrink-0" />
  </button>
}
export default function Settings() {
  const { t } = useT()
  const toast = useToast()
  const { pref, setPref } = useTheme()
  const { colorScheme, toggleColorScheme, positiveClass, negativeClass, baseCurrency, setBaseCurrency } = useSettings()
  const [open, setOpen] = useState<SettingsPanel>(null)
  const modelStore = useModels()
  const confirm = useConfirm()
  const [slotId, setSlotId] = useState(1)
  const saved = modelStore.settings.models.find(item => item.id === slotId) ?? null
  const [provider, setProvider] = useState<ApiFormat>('openai_compat')
  const [baseUrl, setBaseUrl] = useState('')
  const [model, setModel] = useState('')
  const [contextWindow, setContextWindow] = useState('')
  const [apiKey, setApiKey] = useState('')
  const [clearKey, setClearKey] = useState(false)
  const [showKey, setShowKey] = useState(false)
  const [models, setModels] = useState<string[]>([])
  const [listing, setListing] = useState(false)
  const [saving, setSaving] = useState(false)
  const loading = modelStore.loading
  const [error, setError] = useState('')
  const [modelsError, setModelsError] = useState('')
  const listVersion = useRef(0)
  const sameEndpoint = saved?.provider === provider && normalizeEndpoint(saved.baseUrl) === normalizeEndpoint(baseUrl)
  const retainsKey = !!saved?.hasKey && sameEndpoint && !clearKey
  const currencyLabels: Record<BaseCurrency, string> = { CNY: t.settings.currencyCny, HKD: t.settings.currencyHkd, USD: t.settings.currencyUsd }
  const themeLabel = { system: t.settings.themeSystem, light: t.settings.themeLight, dark: t.settings.themeDark }[pref]
  function applySettings(settings: AiSettings) {
    setSlotId(settings.id)
    setProvider(settings.provider === 'anthropic' ? 'anthropic' : 'openai_compat')
    setBaseUrl(settings.baseUrl)
    setModel(settings.model)
    setContextWindow(settings.contextWindow === null ? '' : String(settings.contextWindow))
    setApiKey('')
    setClearKey(false)
  }
  function editProfile(profile: AiSettings | null) {
    invalidateModels()
    setError('')
    setShowKey(false)
    if (profile) applySettings(profile)
    else {
      setSlotId([1, 2, 3].find(id => !modelStore.settings.models.some(item => item.id === id)) ?? 1)
      setProvider('openai_compat'); setBaseUrl(''); setModel(''); setContextWindow(''); setApiKey(''); setClearKey(false)
    }
  }
  function openModels() {
    const selected = modelStore.settings.models.find(item => item.id === modelStore.settings.activeModelId)
    editProfile(selected ?? modelStore.settings.models[0] ?? null)
    setOpen('ai')
  }
  async function removeProfile() {
    if (!saved || !await confirm('删除这个模型配置？密钥和上下文设置也会一并移除。')) return
    try {
      const result = await modelStore.remove(saved.id)
      editProfile(result.models[0] ?? null)
      toast('模型配置已删除', true)
    } catch { setError('删除失败，请重试') }
  }
  function invalidateModels() {
    listVersion.current += 1
    setModels([])
    setListing(false)
    setModelsError('')
  }
  function keyPayload(): string | undefined {
    return apiKey.trim() || (retainsKey ? undefined : '')
  }
  async function fetchModels() {
    const version = ++listVersion.current
    setListing(true)
    setModelsError('')
    try {
      const result = await aiListModels({ id: slotId, provider, baseUrl: baseUrl.trim(), apiKey: keyPayload() })
      if (version !== listVersion.current) return
      if (result.error) setModelsError(result.error)
      else setModels(result.models ?? [])
    } catch {
      if (version === listVersion.current) setModelsError('无法获取模型列表，也可直接填写 Model ID')
    } finally {
      if (version === listVersion.current) setListing(false)
    }
  }
  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const context = Number(contextWindow)
    if (!Number.isInteger(context) || context < 1024 || context > 1048576) {
      setError('上下文窗口须为 1024 至 1048576 的整数')
      return
    }
    setSaving(true)
    setError('')
    try {
      const settings = await modelStore.save({ id: slotId, provider, baseUrl: baseUrl.trim(), model: model.trim(), contextWindow: context, apiKey: keyPayload() })
      const updated = settings.models.find(item => item.id === slotId)
      if (updated) applySettings(updated)
      toast('模型配置已保存', true)
    } catch {
      setError('保存失败，请检查配置后重试')
    } finally { setSaving(false) }
  }
  return <div className="overflow-auto h-full bg-slate-50 dark:bg-slate-950" style={{ scrollbarGutter: 'stable' }}>
    <div className="max-w-lg mx-auto px-4 sm:px-6 pb-16">
      <div className="pt-8 pb-6">
        <h2 className="text-xl font-bold text-slate-900 dark:text-slate-100 tracking-tight">{t.settings.title}</h2>
        <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">外观、显示和模型配置</p>
      </div>
      <div className="space-y-3">
        <SettingCard icon={<Sun className="w-5 h-5" />} title="外观" summary={`${themeLabel}，${colorScheme === 'cn' ? '红涨绿跌' : '绿涨红跌'}`} onClick={() => setOpen('appearance')} />
        <SettingCard icon={<Globe className="w-5 h-5" />} title="个性化" summary={`基准货币 ${baseCurrency}`} onClick={() => setOpen('display')} />
        <SettingCard icon={<Bot className="w-5 h-5" />} title="观澜" summary={loading ? '正在读取配置' : modelStore.settings.models.length ? `已配置 ${modelStore.settings.models.length} 个模型` : '请配置模型'} onClick={openModels} />
        <a href={FROST_ID_URL} target="_blank" rel="noopener noreferrer"
          onClick={event => { if (window.electronAPI?.isDesktop) { event.preventDefault(); window.electronAPI.openExternal(FROST_ID_URL) } }}
          className="flex items-center gap-4 p-5 rounded-2xl bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 hover:border-slate-400 dark:hover:border-slate-600 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-slate-500">
          <span className="flex items-center justify-center w-10 h-10 rounded-xl bg-slate-100 dark:bg-slate-800 text-slate-600 dark:text-slate-400"><ExternalLink className="w-5 h-5" /></span>
          <span className="flex-1"><span className="block text-sm font-semibold text-slate-800 dark:text-slate-200">Frost ID</span><span className="block text-xs text-slate-500 dark:text-slate-400 mt-0.5">管理密码和登录设备</span></span>
          <ChevronRight className="w-4 h-4 text-slate-400" />
        </a>
      </div>
    </div>
    <Modal open={open === 'appearance'} onClose={() => setOpen(null)} title="外观">
      <ModalRow label={t.settings.theme}>
        <Segments value={pref} onChange={setPref} options={[
          { value: 'system', label: t.settings.themeSystem, icon: <Monitor className="w-3.5 h-3.5" /> },
          { value: 'light', label: t.settings.themeLight, icon: <Sun className="w-3.5 h-3.5" /> },
          { value: 'dark', label: t.settings.themeDark, icon: <Moon className="w-3.5 h-3.5" /> },
        ]} />
      </ModalRow>
      <ModalRow label={t.settings.colorScheme} desc={colorScheme === 'cn' ? '红涨绿跌（A 股习惯）' : '绿涨红跌（国际习惯）'}>
        <div className="flex items-center gap-3">
          <span className="text-xs tabular-nums"><span className={positiveClass}>+5.20%</span><span className="text-slate-400 mx-1">/</span><span className={negativeClass}>-3.10%</span></span>
          <button type="button" role="switch" aria-label="红涨绿跌" aria-checked={colorScheme === 'cn'} onClick={toggleColorScheme} className="min-w-11 min-h-11 inline-flex items-center justify-center focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-slate-500">
            <span className={`relative w-11 h-6 rounded-full ${colorScheme === 'cn' ? 'bg-[#1e293b] dark:bg-[#c7c7d0]' : 'bg-[#e2e8f0] dark:bg-[#333339]'}`}><span className={`absolute top-0.5 w-5 h-5 rounded-full bg-[#fff] dark:bg-[#19191d] shadow-sm ${colorScheme === 'cn' ? 'right-0.5' : 'left-0.5'}`} /></span>
          </button>
        </div>
      </ModalRow>
    </Modal>
    <Modal open={open === 'display'} onClose={() => setOpen(null)} title="个性化">
      <ModalRow label={t.settings.currency} desc="影响持仓和盈亏金额的显示单位">
        <Segments value={baseCurrency} onChange={setBaseCurrency} options={(Object.keys(currencyLabels) as BaseCurrency[]).map(value => ({ value, label: currencyLabels[value] }))} />
      </ModalRow>
    </Modal>
    <Modal open={open === 'ai'} onClose={() => { if (!saving) setOpen(null) }} title="观澜模型">
      <div className="mb-5 space-y-2">
        <div className="flex items-center justify-between text-xs text-slate-500 dark:text-slate-400"><span>模型配置</span><span>{modelStore.settings.models.length} / 3</span></div>
        {modelStore.settings.models.map(profile => <button key={profile.id} type="button" disabled={saving || modelStore.busy}
          onClick={() => editProfile(profile)} aria-pressed={profile.id === slotId}
          className={`w-full flex items-center gap-2 min-h-11 rounded-lg border px-3 text-left ${profile.id === slotId ? 'border-slate-500 dark:border-slate-400 bg-slate-50 dark:bg-slate-800' : 'border-slate-200 dark:border-slate-700'}`}>
          <span className="flex-1 min-w-0"><span className="block text-sm text-slate-800 dark:text-slate-200 truncate">{profile.model}</span>{!profile.configured && <span className="text-xs text-amber-700 dark:text-amber-400">需完善配置</span>}</span>
          {profile.id === modelStore.settings.activeModelId && <Check className="w-4 h-4 text-slate-500 dark:text-slate-400" aria-label="当前使用" />}
        </button>)}
        <button type="button" disabled={loading || saving || modelStore.busy || modelStore.settings.models.length >= 3} onClick={() => editProfile(null)} className={`${secondaryCls} w-full`}><Plus className="w-4 h-4" />添加模型</button>
      </div>
      <form onSubmit={save} className="space-y-4">
        <fieldset disabled={loading || saving || modelStore.busy} className="space-y-4 disabled:opacity-60">
          <div><div className="flex items-center mb-1"><label htmlFor="ai-protocol" className="text-sm font-medium text-slate-700 dark:text-slate-300">API 协议</label><InfoTip label="API 协议">云端 API 和本地模型使用同一配置。请选择服务支持的协议；本地模型需先启动提供兼容接口的推理服务。</InfoTip></div>
            <select id="ai-protocol" value={provider} onChange={event => { setProvider(event.target.value === 'anthropic' ? 'anthropic' : 'openai_compat'); invalidateModels() }} className={inputCls}>
              <option value="openai_compat">OpenAI 兼容（Chat Completions）</option><option value="anthropic">Anthropic Messages</option>
            </select>
          </div>
          <div><div className="flex items-center mb-1"><label htmlFor="ai-base" className="text-sm font-medium text-slate-700 dark:text-slate-300">接口地址（Base URL）</label><InfoTip label="接口地址">使用服务提供的基础地址，不要附加 /chat/completions 或 /messages。地址由 Investory 后端访问；如果使用远程后端，localhost 指向后端所在电脑。</InfoTip></div>
            <input id="ai-base" type="url" required value={baseUrl} onChange={event => { setBaseUrl(event.target.value); invalidateModels() }} placeholder="例如 http://localhost:11434/v1" className={inputCls} autoCapitalize="none" spellCheck={false} />
          </div>
          <div><div className="flex items-center mb-1"><label htmlFor="ai-key" className="text-sm font-medium text-slate-700 dark:text-slate-300">API Key</label><InfoTip label="API Key">本地服务无需鉴权时可留空。已保存的密钥仅在协议和地址不变时保留，更换接口后需重新填写。</InfoTip></div>
            <div className="relative"><input id="ai-key" type={showKey ? 'text' : 'password'} value={apiKey} onChange={event => { setApiKey(event.target.value); invalidateModels() }}
              placeholder={retainsKey ? '已保存，留空保留原密钥' : '本地服务无需鉴权时可留空'} autoComplete="off" spellCheck={false} className={`${inputCls} pr-12`} />
              <button type="button" onClick={() => setShowKey(value => !value)} className="absolute right-0 top-0 w-11 h-11 flex items-center justify-center text-slate-500 dark:text-slate-400" aria-label={showKey ? '隐藏密钥' : '显示密钥'}>{showKey ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}</button>
            </div>
            {clearKey && <p className="text-xs text-amber-700 dark:text-amber-400 mt-1.5" role="status">保存后将移除原密钥。</p>}
            {retainsKey && <button type="button" onClick={() => { setClearKey(true); setApiKey(''); invalidateModels() }} className="text-xs min-h-11 text-slate-600 dark:text-slate-300 underline underline-offset-4">清除已保存的密钥</button>}
          </div>
          <div><label htmlFor="ai-model" className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-2">Model ID</label>
            <div className="flex gap-2"><input id="ai-model" required maxLength={255} list="ai-model-options" value={model} onChange={event => setModel(event.target.value)} placeholder="填写服务中的完整模型名称" className={`${inputCls} min-w-0 flex-1`} autoCapitalize="none" spellCheck={false} />
              <button type="button" onClick={fetchModels} disabled={listing || !baseUrl.trim()} className={`${secondaryCls} shrink-0`}><RefreshCw className={`w-3.5 h-3.5 ${listing ? 'animate-spin motion-reduce:animate-none' : ''}`} />{listing ? '获取中' : '获取列表'}</button>
            </div>
            <datalist id="ai-model-options">{models.map(id => <option key={id} value={id} />)}</datalist>
            {models.length > 0 && <p className="text-xs mt-1.5 text-slate-500 dark:text-slate-400" role="status">已获取 {models.length} 个模型，可在输入框中选择。</p>}
            {modelsError && <p className="text-xs mt-1.5 text-red-600 dark:text-red-400" role="alert">{modelsError}</p>}
          </div>
          <div><div className="flex items-center mb-1"><label htmlFor="ai-context" className="text-sm font-medium text-slate-700 dark:text-slate-300">上下文窗口（tokens）</label><InfoTip label="上下文窗口">按模型和硬件能力填写。系统会保守估算输入、预留回复空间并裁剪较早对话；本轮内容仍过长时停止请求。本地服务的上下文和显存分配需在推理服务中另行设置，这里不会自动修改。</InfoTip></div>
            <input id="ai-context" type="number" min={1024} max={1048576} step={1} required value={contextWindow} onChange={event => setContextWindow(event.target.value)} placeholder="例如 8192" className={inputCls} />
          </div>
        </fieldset>
        {(error || modelStore.error) && <p className="text-xs text-red-600 dark:text-red-400" role="alert">{error || modelStore.error}</p>}
        <div className="flex items-center gap-2 pt-3 border-t border-slate-100 dark:border-slate-800">
          {saved && <button type="button" onClick={removeProfile} disabled={saving || modelStore.busy} className="min-h-11 px-2 text-slate-500 hover:text-red-600 dark:text-slate-400" aria-label="删除模型"><Trash2 className="w-4 h-4" /></button>}
          <span className="flex-1" />
          <button type="button" disabled={saving || modelStore.busy} onClick={() => setOpen(null)} className={secondaryCls}>取消</button>
          <button type="submit" disabled={loading || saving || modelStore.busy} className="min-h-11 px-5 rounded-lg bg-slate-900 dark:bg-slate-100 text-white dark:text-slate-900 text-sm font-medium disabled:opacity-50 cursor-pointer focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-slate-500">{saving ? '保存中' : '保存配置'}</button>
        </div>
      </form>
    </Modal>
  </div>
}
