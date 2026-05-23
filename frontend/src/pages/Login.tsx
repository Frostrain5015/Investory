import { useState, useEffect, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '@/hooks/use-auth'
import { useSettings } from '@/hooks/use-settings'
import { useT } from '@/i18n/I18nContext'
import { TrendingUp } from 'lucide-react'

export default function Login() {
  const { login } = useAuth()
  const { positiveHex } = useSettings()
  const { t } = useT()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [autoLogin, setAutoLogin] = useState(false)

  // Auto-login: if saved credentials exist, auto-fill and submit
  useEffect(() => {
    const saved = localStorage.getItem('investory_creds')
    if (saved) {
      try {
        const creds = JSON.parse(saved) as { u: string; p: string }
        if (creds.u && creds.p) {
          setAutoLogin(true)
          setUsername(creds.u)
          setPassword(atob(creds.p))
          setLoading(true)
        }
      } catch { localStorage.removeItem('investory_creds') }
    }
  }, [])

  useEffect(() => {
    if (autoLogin && username && password && loading) {
      login(username, password).then(result => {
        if (!result.success) {
          setError(result.error || t.login.errorLoginFailed)
          setLoading(false)
          setAutoLogin(false)
        }
      })
    }
  }, [autoLogin, username, password])

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
    setLoading(true)
    const result = await login(username, password)
    setLoading(false)
    if (!result.success) {
      setError(result.error || t.login.errorLoginFailed)
    } else {
      // Always save credentials — cleared on explicit logout in use-auth
      localStorage.setItem('investory_creds', JSON.stringify({ u: username, p: btoa(password) }))
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-slate-50">
      <div className="w-full max-w-sm">
        <div className="text-center mb-8">
          <div className="w-12 h-12 rounded-2xl flex items-center justify-center mx-auto mb-4" style={{ backgroundColor: positiveHex }}>
            <TrendingUp className="w-6 h-6 text-white" />
          </div>
          <h1 className="text-2xl font-bold text-slate-900 tracking-tight">{t.login.title}</h1>
          <p className="text-sm text-slate-500 mt-1">{t.login.tagline}</p>
        </div>

        <form onSubmit={handleSubmit} className="bg-white rounded-2xl border border-slate-200/60 shadow-sm p-6 space-y-4">
          {autoLogin && loading && !error && (
            <div className="flex items-center gap-2 text-slate-500 text-sm justify-center">
              <div className="w-4 h-4 border-2 border-slate-300 border-t-slate-600 rounded-full animate-spin" />
              {t.login.autoLoggingIn}
            </div>
          )}
          {error && (
            <div className="bg-red-50 text-red-600 text-sm rounded-xl px-4 py-3">{error}</div>
          )}
          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1.5">{t.login.username}</label>
            <input
              type="text"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              required
              className="w-full h-10 rounded-xl border border-slate-200 px-3.5 text-sm focus:outline-none focus:ring-2 focus:ring-slate-900/10 focus:border-slate-300 transition-colors"
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1.5">{t.login.password}</label>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              className="w-full h-10 rounded-xl border border-slate-200 px-3.5 text-sm focus:outline-none focus:ring-2 focus:ring-slate-900/10 focus:border-slate-300 transition-colors"
            />
          </div>
          <button
            type="submit"
            disabled={loading}
            className="w-full h-10 rounded-xl bg-slate-900 text-white text-sm font-medium hover:bg-slate-800 transition-colors disabled:opacity-50"
          >
            {loading ? t.login.loggingIn : t.login.loginBtn}
          </button>
        </form>

        <p className="text-center text-sm text-slate-500 mt-4">
          {t.login.noAccount}<Link to="/register" className="text-slate-900 font-medium hover:underline">{t.login.createAccount}</Link>
        </p>
      </div>
    </div>
  )
}
