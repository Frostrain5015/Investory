import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '@/hooks/use-auth'
import { useSettings, type BaseCurrency } from '@/hooks/use-settings'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'

const CURRENCY_LABELS: Record<BaseCurrency, string> = { CNY: '人民币 (¥)', HKD: '港币 (HK$)', USD: '美元 ($)' }

export default function Settings() {
  const { logout } = useAuth()
  const { colorScheme, toggleColorScheme, positiveClass, negativeClass, baseCurrency, setBaseCurrency } = useSettings()
  const navigate = useNavigate()
  const [deleting, setDeleting] = useState(false)

  async function handleDeleteAccount() {
    if (!confirm('确认注销账户？此操作不可撤销，所有数据将被永久删除。')) return
    setDeleting(true)
    try {
      const res = await fetch('/investory/api/account', { method: 'DELETE', credentials: 'include' })
      if (res.ok) {
        alert('账户已注销')
        logout()
      } else {
        alert('注销失败，请稍后重试')
      }
    } catch {
      alert('网络错误')
    }
    setDeleting(false)
  }

  return (
    <div className="p-6 max-w-lg mx-auto space-y-6">
      <h2 className="text-xl font-bold text-slate-900 tracking-tight">设置</h2>

      {/* Color scheme */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">涨跌颜色</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="flex items-center justify-between">
            <div className="space-y-1">
              <p className="text-sm font-medium text-slate-700">
                {colorScheme === 'cn' ? '红涨绿跌（A 股习惯）' : '绿涨红跌（海外习惯）'}
              </p>
              <div className="flex items-center gap-3 text-sm">
                <span className={positiveClass}>+5.20%</span>
                <span className="text-slate-300">/</span>
                <span className={negativeClass}>-3.10%</span>
              </div>
            </div>
            <button
              onClick={toggleColorScheme}
              className="relative w-12 h-7 rounded-full transition-colors duration-200 bg-slate-200 hover:bg-slate-300"
            >
              <span className="absolute top-0.5 w-6 h-6 rounded-full bg-white shadow-sm transition-all duration-200"
                style={{ left: colorScheme === 'cn' ? '2px' : '22px' }} />
            </button>
          </div>
        </CardContent>
      </Card>

      {/* Base currency */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">本位币</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-sm text-slate-500 mb-3">选择用于计算总资产和盈亏的货币</p>
          <div className="flex gap-2">
            {(Object.keys(CURRENCY_LABELS) as BaseCurrency[]).map((c) => (
              <button
                key={c}
                onClick={() => setBaseCurrency(c)}
                className={`flex-1 h-10 rounded-xl text-sm font-medium transition-colors ${
                  baseCurrency === c
                    ? 'bg-slate-900 text-white'
                    : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
                }`}
              >
                {CURRENCY_LABELS[c]}
              </button>
            ))}
          </div>
        </CardContent>
      </Card>

      {/* Account actions */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">账户</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <button
            onClick={() => { logout(); navigate('/login') }}
            className="w-full h-10 rounded-xl border border-slate-200 text-sm font-medium text-slate-700 hover:bg-slate-50 transition-colors"
          >
            退出登录
          </button>
          <button
            onClick={handleDeleteAccount}
            disabled={deleting}
            className="w-full h-10 rounded-xl border border-red-200 text-sm font-medium text-red-600 hover:bg-red-50 transition-colors disabled:opacity-50"
          >
            {deleting ? '注销中...' : '注销账户'}
          </button>
        </CardContent>
      </Card>
    </div>
  )
}
