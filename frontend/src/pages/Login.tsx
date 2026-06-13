import { useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '@/hooks/use-auth'
import { useSettings } from '@/hooks/use-settings'
import { useT } from '@/i18n/I18nContext'
import { getFrostIdLoginUrl } from '@/services/api'
import { TrendingUp } from 'lucide-react'

const FROST_ACCENT = '#7176aa'
const FROST_ACCENT_HI = '#8b90c4'

export default function Login() {
  const { authenticated, loading } = useAuth()
  const { positiveHex } = useSettings()
  const { t } = useT()
  const navigate = useNavigate()
  const frostIdLoginUrl = getFrostIdLoginUrl()

  // Auto-redirect if session is still valid
  useEffect(() => {
    if (!loading && authenticated) {
      navigate('/dashboard', { replace: true })
    }
  }, [authenticated, loading, navigate])

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-slate-50">
        <div className="w-5 h-5 border-2 border-slate-300 border-t-slate-600 rounded-full animate-spin" />
      </div>
    )
  }

  if (authenticated) return null

  return (
    <div className="min-h-screen flex items-center justify-center bg-slate-50">
      <div className="w-full max-w-sm px-4">
        <div className="text-center mb-8">
          <div className="w-12 h-12 rounded-2xl flex items-center justify-center mx-auto mb-4" style={{ backgroundColor: positiveHex }}>
            <TrendingUp className="w-6 h-6 text-white" />
          </div>
          <h1 className="text-2xl font-bold text-slate-900 tracking-tight">{t.login.title}</h1>
          <p className="text-sm text-slate-500 mt-1">{t.login.tagline}</p>
        </div>

        <div className="bg-white rounded-2xl border border-slate-200/60 shadow-sm p-6">
          <a
            href={frostIdLoginUrl}
            className="flex items-center justify-center gap-3 w-full h-12 rounded-xl text-sm font-medium transition-all duration-200"
            style={{
              backgroundColor: FROST_ACCENT,
              color: '#fff',
              letterSpacing: '0.04em',
            }}
            onMouseEnter={(e) => { (e.currentTarget as HTMLElement).style.backgroundColor = FROST_ACCENT_HI }}
            onMouseLeave={(e) => { (e.currentTarget as HTMLElement).style.backgroundColor = FROST_ACCENT }}
          >
            <span aria-hidden="true" style={{ fontSize: '1.25rem', lineHeight: 1 }}>❄</span>
            <span>{t.login.frostidLogin}</span>
          </a>
        </div>

        <p className="text-center text-xs text-slate-400 mt-6">
          {t.login.tagline}
        </p>
      </div>
    </div>
  )
}
