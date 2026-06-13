import { Link } from 'react-router-dom'
import { useT } from '@/i18n/I18nContext'
import { TrendingUp } from 'lucide-react'

export default function Register() {
  const { t } = useT()

  return (
    <div className="min-h-screen flex items-center justify-center bg-slate-50">
      <div className="w-full max-w-sm px-4">
        <div className="text-center mb-8">
          <div className="w-12 h-12 rounded-2xl flex items-center justify-center mx-auto mb-4 bg-slate-900">
            <TrendingUp className="w-6 h-6 text-white" />
          </div>
          <h1 className="text-2xl font-bold text-slate-900 tracking-tight">{t.register.title}</h1>
          <p className="text-sm text-slate-500 mt-1">{t.register.subtitle}</p>
        </div>

        <div className="bg-white rounded-2xl border border-slate-200/60 shadow-sm p-8 text-center">
          <span className="text-4xl mb-4 block" aria-hidden="true">❄</span>
          <p className="text-sm text-slate-600 mb-2">
            {t.register.frostidOnly}
          </p>
          <p className="text-xs text-slate-400 mb-6">
            {t.register.frostidDesc}
          </p>
          <Link
            to="/login"
            className="inline-block w-full h-12 leading-[3rem] rounded-xl bg-slate-900 text-white text-sm font-medium hover:bg-slate-800 transition-colors"
          >
            {t.register.backToLogin}
          </Link>
        </div>

        <p className="text-center text-xs text-slate-400 mt-6">
          {t.register.frostidDesc}
        </p>
      </div>
    </div>
  )
}
