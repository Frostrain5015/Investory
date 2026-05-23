import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '@/hooks/use-auth'
import { useT } from '@/i18n/I18nContext'
import { TrendingUp } from 'lucide-react'

export default function Register() {
  const { t } = useT()
  const { register } = useAuth()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [email, setEmail] = useState('')
  const [error, setError] = useState('')
  const [success, setSuccess] = useState(false)
  const [loading, setLoading] = useState(false)

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
    setLoading(true)
    const result = await register(username, password, email || undefined)
    setLoading(false)
    if (result.success) {
      setSuccess(true)
    } else {
      setError(result.error || t.register.errorRegisterFailed)
    }
  }

  if (success) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-slate-50">
        <div className="w-full max-w-sm text-center">
          <div className="w-12 h-12 rounded-full bg-emerald-100 flex items-center justify-center mx-auto mb-4">
            <svg className="w-6 h-6 text-emerald-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
            </svg>
          </div>
          <h1 className="text-2xl font-bold text-slate-900">{t.register.successMsg}</h1>
          <Link to="/login" className="inline-flex items-center justify-center h-10 px-6 rounded-xl bg-slate-900 text-white text-sm font-medium hover:bg-slate-800 transition-colors mt-6">
            {t.register.loginLink}
          </Link>
        </div>
      </div>
    )
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-slate-50">
      <div className="w-full max-w-sm">
        <div className="text-center mb-8">
          <div className="w-12 h-12 rounded-2xl bg-slate-900 flex items-center justify-center mx-auto mb-4">
            <TrendingUp className="w-6 h-6 text-emerald-400" />
          </div>
          <h1 className="text-2xl font-bold text-slate-900 tracking-tight">{t.register.title}</h1>
          <p className="text-sm text-slate-500 mt-1">{t.register.subtitle}</p>
        </div>

        <form onSubmit={handleSubmit} className="bg-white rounded-2xl border border-slate-200/60 shadow-sm p-6 space-y-4">
          {error && (
            <div className="bg-red-50 text-red-600 text-sm rounded-xl px-4 py-3">{error}</div>
          )}
          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1.5">{t.register.username}</label>
            <input type="text" value={username} onChange={(e) => setUsername(e.target.value)} required
              className="w-full h-10 rounded-xl border border-slate-200 px-3.5 text-sm focus:outline-none focus:ring-2 focus:ring-slate-900/10 focus:border-slate-300 transition-colors" />
          </div>
          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1.5">{t.register.password}</label>
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required minLength={6}
              className="w-full h-10 rounded-xl border border-slate-200 px-3.5 text-sm focus:outline-none focus:ring-2 focus:ring-slate-900/10 focus:border-slate-300 transition-colors" />
          </div>
          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1.5">{t.register.email}</label>
            <input type="email" value={email} onChange={(e) => setEmail(e.target.value)}
              className="w-full h-10 rounded-xl border border-slate-200 px-3.5 text-sm focus:outline-none focus:ring-2 focus:ring-slate-900/10 focus:border-slate-300 transition-colors" />
          </div>
          <button type="submit" disabled={loading}
            className="w-full h-10 rounded-xl bg-slate-900 text-white text-sm font-medium hover:bg-slate-800 transition-colors disabled:opacity-50">
            {loading ? t.register.registering : t.register.registerBtn}
          </button>
        </form>

        <p className="text-center text-sm text-slate-500 mt-4">
          {t.register.haveAccount}<Link to="/login" className="text-slate-900 font-medium hover:underline">{t.register.loginLink}</Link>
        </p>
      </div>
    </div>
  )
}
