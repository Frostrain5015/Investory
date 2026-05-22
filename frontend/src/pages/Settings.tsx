import { useState, useRef, useEffect } from 'react'
import { useAuth } from '@/hooks/use-auth'
import { useToast } from '@/components/Toast'
import { useSettings, type BaseCurrency } from '@/hooks/use-settings'
import { useTheme } from '@/hooks/use-theme'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Sun, Moon, Camera, Sparkles } from 'lucide-react'

const CURRENCY_LABELS: Record<BaseCurrency, string> = { CNY: '人民币 (¥)', HKD: '港币 (HK$)', USD: '美元 ($)' }

export default function Settings() {
  const { username, logout } = useAuth()
  const toast = useToast()
  const { colorScheme, toggleColorScheme, positiveClass, negativeClass, baseCurrency, setBaseCurrency, showRiskMetrics, toggleRiskMetrics } = useSettings()
  const { pref, setPref } = useTheme()
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
    bailian:    { label: '阿里云百炼',   baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen-plus' },
    openai:     { label: 'OpenAI',       baseUrl: '',                                           model: 'gpt-4o-mini' },
    deepseek:   { label: 'DeepSeek',     baseUrl: 'https://api.deepseek.com/v1',                model: 'deepseek-chat' },
    moonshot:   { label: 'Moonshot',     baseUrl: 'https://api.moonshot.cn/v1',                 model: 'moonshot-v1-8k' },
    zhipu:      { label: '智谱 GLM',     baseUrl: 'https://open.bigmodel.cn/api/paas/v4',       model: 'glm-4-flash' },
    anthropic:  { label: 'Anthropic',    baseUrl: '',                                           model: 'claude-haiku-4-5' },
    custom:     { label: '自定义',       baseUrl: '',                                           model: '' },
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
    if (!oldPw || !newPw) { setPwMsg('请填写原密码和新密码'); return }
    if (newPw.length < 6) { setPwMsg('新密码至少6位'); return }
    const form = new URLSearchParams({ oldPassword: oldPw, newPassword: newPw })
    const res = await fetch('/investory/api/password', { method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: form.toString() })
    const data = await res.json()
    setPwMsg(data.error || '密码已修改')
    if (data.status === 'ok') { setOldPw(''); setNewPw('') }
  }

  async function handleDeleteAccount() {
    if (!confirm('确认注销账户？此操作不可撤销。')) return
    setDeleting(true)
    try {
      const res = await fetch('/investory/api/account', { method: 'DELETE', credentials: 'include' })
      if (res.ok) { toast('账户已注销', true); setTimeout(logout, 1500) }
      else toast('注销失败', false)
    } catch { toast('网络错误', false) }
    setDeleting(false)
  }

  return (
    <div className="p-6 max-w-lg mx-auto space-y-6">
      <h2 className="text-xl font-bold text-slate-900 dark:text-slate-100 tracking-tight">账户设置</h2>

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
            <p className="text-xs text-slate-400 mt-0.5">点击头像上传照片</p>
          </div>
          <input ref={fileRef} type="file" accept="image/*" className="hidden" onChange={handleAvatarUpload} />
        </CardContent>
      </Card>

      {/* Theme */}
      <Card>
        <CardHeader><CardTitle className="text-base">主题</CardTitle></CardHeader>
        <CardContent>
          <div className="flex gap-2">
            {([['system', '跟随系统'], ['light', '亮色'], ['dark', '暗色']] as const).map(([val, label]) => (
              <button key={val} onClick={() => setPref(val)}
                className={`flex-1 h-10 rounded-xl text-sm font-medium transition-colors flex items-center justify-center gap-1.5
                  ${pref === val ? 'bg-slate-900 text-white' : 'bg-slate-100 text-slate-600 hover:bg-slate-200'}`}>
                {val === 'light' && <Sun className="w-3.5 h-3.5" />}
                {val === 'dark' && <Moon className="w-3.5 h-3.5" />}
                {label}
              </button>
            ))}
          </div>
        </CardContent>
      </Card>

      {/* Color scheme */}
      <Card>
        <CardHeader><CardTitle className="text-base">涨跌颜色</CardTitle></CardHeader>
        <CardContent>
          <div className="flex items-center justify-between">
            <div className="space-y-1">
              <p className="text-sm font-medium text-slate-700 dark:text-slate-300">{colorScheme === 'cn' ? '红涨绿跌（A股习惯）' : '绿涨红跌（海外习惯）'}</p>
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
        <CardHeader><CardTitle className="text-base">本位币</CardTitle></CardHeader>
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
        <CardHeader><CardTitle className="text-base">量化分析列</CardTitle></CardHeader>
        <CardContent>
          <div className="flex items-center justify-between">
            <div className="space-y-1">
              <p className="text-sm font-medium text-slate-700 dark:text-slate-300">
                {showRiskMetrics ? '显示历史分位数和风险指标' : '隐藏量化指标列'}
              </p>
              <p className="text-xs text-slate-400">在自选页面显示 Beta、波动率、历史分位数等列</p>
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
            <CardTitle className="text-base flex items-center gap-2"><Sparkles className="w-4 h-4" />观澜 AI</CardTitle>
            <span className="text-[10px] text-slate-400">默认服务商：阿里云百炼</span>
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
            placeholder={aiHasKey ? '已保存自定义Key' : '自定义 API Key'}
            className="w-full h-10 rounded-xl border border-slate-200 px-3.5 text-sm" />
          {aiProvider === 'custom' && (
            <input type="text" value={aiBaseUrl} onChange={e => setAiBaseUrl(e.target.value)}
              placeholder="API 端点地址，例如 https://api.deepseek.com/v1"
              className="w-full h-10 rounded-xl border border-slate-200 px-3.5 text-sm" />
          )}
          <input type="text" value={aiModel} onChange={e => setAiModel(e.target.value)}
            placeholder={aiProvider === 'openai' ? '例如 gpt-4o-mini / gpt-4o' : aiProvider === 'anthropic' ? '例如 claude-haiku-4-5' : aiProvider === 'deepseek' ? '例如 deepseek-chat' : '模型名称，服务商文档中可查'}
            className="w-full h-10 rounded-xl border border-slate-200 px-3.5 text-sm" />
          <button onClick={async () => {
            const body: any = { provider: aiProvider, model: aiModel, baseUrl: aiBaseUrl }
            if (aiKey) body.apiKey = aiKey
            const res = await fetch('/investory/api/ai/settings', {
              method: 'POST', credentials: 'include',
              headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
            })
            const data = await res.json()
            if (data.error) { toast(data.error, false); return }
            if (aiKey) setAiHasKey(true)
            toast('AI 设置已保存', true)
          }} className="w-full h-10 rounded-xl bg-slate-900 text-white text-sm font-medium hover:bg-slate-800 transition-colors">
            保存设置
          </button>
        </CardContent>
      </Card>

      {/* Password */}
      <Card>
        <CardHeader><CardTitle className="text-base">修改密码</CardTitle></CardHeader>
        <CardContent className="space-y-3">
          <input type="password" placeholder="原密码" value={oldPw} onChange={e => setOldPw(e.target.value)}
            className="w-full h-10 rounded-xl border border-slate-200 px-3.5 text-sm dark:bg-slate-800 dark:border-slate-700 dark:text-slate-200" />
          <input type="password" placeholder="新密码（至少6位）" value={newPw} onChange={e => setNewPw(e.target.value)}
            className="w-full h-10 rounded-xl border border-slate-200 px-3.5 text-sm dark:bg-slate-800 dark:border-slate-700 dark:text-slate-200" />
          {pwMsg && <p className={`text-xs ${pwMsg.includes('错误') || pwMsg.includes('请') ? 'text-red-500' : 'text-emerald-600'}`}>{pwMsg}</p>}
          <button onClick={handleChangePassword} className="w-full h-10 rounded-xl bg-slate-900 text-white text-sm font-medium hover:bg-slate-800 transition-colors">修改密码</button>
        </CardContent>
      </Card>

      {/* Danger zone */}
      <Card className="border-red-200">
        <CardHeader><CardTitle className="text-base text-red-600">危险操作</CardTitle></CardHeader>
        <CardContent>
          <button onClick={handleDeleteAccount} disabled={deleting}
            className="w-full h-10 rounded-xl border border-red-200 text-sm font-medium text-red-600 hover:bg-red-50 transition-colors disabled:opacity-50">{deleting ? '注销中...' : '注销账户'}</button>
        </CardContent>
      </Card>
    </div>
  )
}
