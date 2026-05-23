import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '@/hooks/use-auth'
import { motion, AnimatePresence } from 'framer-motion'
import HeroParticles from '@/components/HeroParticles'
import HeroMatrix from '@/components/HeroMatrix'
import { useT } from '@/i18n/I18nContext'
import LangSwitcher from '@/components/LangSwitcher'
import { User } from 'lucide-react'

interface SavedCreds { u: string; p: string }

function loadSavedCreds(): SavedCreds | null {
  try {
    const raw = localStorage.getItem('investory_creds')
    if (!raw) return null
    const creds = JSON.parse(raw)
    if (creds.u && creds.p) return creds
  } catch {}
  return null
}

export default function Hero() {
  const { t } = useT()
  const { login: doLogin, register: doRegister, authenticated } = useAuth()
  const nav = useNavigate()
  const [savedCreds] = useState<SavedCreds | null>(loadSavedCreds)
  const [showSaved, setShowSaved] = useState(!!savedCreds)
  const [mode, setMode] = useState<'login' | 'register' | null>(null)
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [email, setEmail] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [quickLoginLoading, setQuickLoginLoading] = useState(false)

  useEffect(() => { if (authenticated) nav('/dashboard', { replace: true }) }, [authenticated, nav])

  const submit = async (e: React.FormEvent) => {
    e.preventDefault(); setError(''); setLoading(true)
    const result = mode === 'login'
      ? await doLogin(username, password)
      : await doRegister(username, password, email || undefined)
    setLoading(false)
    if (result.success) {
      localStorage.setItem('investory_creds', JSON.stringify({ u: username, p: btoa(password) }))
      nav('/dashboard', { replace: true })
    } else setError(result.error || (mode === 'login' ? t.login.errorLoginFailed : t.register.errorRegisterFailed))
  }

  const switchMode = () => {
    setMode(mode === 'login' ? 'register' : 'login')
    setError(''); setUsername(''); setPassword(''); setEmail('')
  }

  const quickLogin = async () => {
    if (!savedCreds) return
    setQuickLoginLoading(true)
    const result = await doLogin(savedCreds.u, atob(savedCreds.p))
    setQuickLoginLoading(false)
    if (result.success) { nav('/dashboard', { replace: true }) }
    else {
      setError(t.login.errorLoginFailed)
      setShowSaved(false)
    }
  }

  const switchAccount = () => {
    localStorage.removeItem('investory_creds')
    setShowSaved(false)
    setError('')
  }

  return (
    <div className="relative w-full h-dvh overflow-hidden bg-[#080c14]">
      <div className="absolute inset-0" style={{
        background: 'radial-gradient(ellipse 60% 50% at 50% 45%, rgba(14, 165, 233, 0.06) 0%, transparent 70%), radial-gradient(ellipse 80% 60% at 50% 50%, rgba(8, 12, 20, 1) 0%, rgba(2, 6, 14, 1) 100%)',
      }} />

      <HeroParticles />
      <HeroMatrix />

      <div className="absolute top-4 right-4 z-20">
        <LangSwitcher dark />
      </div>

      <div className="relative z-10 flex flex-col items-center justify-center h-full px-4">
        <motion.div initial={{ opacity: 0, y: 20 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.8 }}
          className="text-center max-w-lg">
          <motion.h1 initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: 0.2, duration: 0.6 }}
            className="text-5xl sm:text-6xl font-bold tracking-tight text-white mb-3"
            style={{ textShadow: '0 0 80px rgba(14, 165, 233, 0.3)' }}>
            Investory
          </motion.h1>
          <motion.p initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: 0.35, duration: 0.6 }}
            className="text-base text-slate-400 mb-10 tracking-wide">
            {t.login.tagline}
          </motion.p>

          <AnimatePresence mode="wait">
            {showSaved && savedCreds ? (
              <motion.div key="saved"
                initial={{ opacity: 0, y: 12, scale: 0.97 }} animate={{ opacity: 1, y: 0, scale: 1 }}
                exit={{ opacity: 0, y: -12, scale: 0.97 }} transition={{ duration: 0.25 }}
                className="mx-auto max-w-sm">
                <div className="bg-slate-900/70 backdrop-blur-xl border border-slate-700/60 rounded-2xl p-8 shadow-2xl">
                  <motion.div initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: 0.1 }}>
                    <div className="w-16 h-16 rounded-full bg-sky-500/20 flex items-center justify-center mx-auto mb-4">
                      <User className="w-8 h-8 text-sky-400" />
                    </div>
                    <p className="text-sm text-slate-400 mb-1">{t.login.welcomeBack}</p>
                    <p className="text-xl font-semibold text-white mb-6">{savedCreds.u}</p>
                  </motion.div>

                  {error && (
                    <motion.p initial={{ opacity: 0 }} animate={{ opacity: 1 }}
                      className="text-xs text-red-400 mb-4">{error}</motion.p>
                  )}

                  <motion.button
                    initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: 0.2 }}
                    onClick={quickLogin} disabled={quickLoginLoading}
                    className="w-full h-11 rounded-xl bg-white text-slate-900 text-sm font-medium hover:bg-slate-200 transition-colors disabled:opacity-60 shadow-lg shadow-white/10 mb-3">
                    {quickLoginLoading ? '...' : t.login.oneClickLogin}
                  </motion.button>

                  <motion.button
                    initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ delay: 0.3 }}
                    onClick={switchAccount}
                    className="text-xs text-sky-400 hover:text-sky-300 transition-colors">
                    {t.login.switchAccount}
                  </motion.button>
                </div>
              </motion.div>
            ) : !mode ? (
              <motion.div key="cta"
                initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -10 }}
                transition={{ duration: 0.4 }}
                className="flex items-center justify-center gap-4">
                <button onClick={() => setMode('login')}
                  className="h-11 px-8 rounded-xl bg-white text-slate-900 text-sm font-medium hover:bg-slate-200 transition-colors shadow-lg shadow-white/10">
                  {t.login.loginBtn}
                </button>
                <button onClick={() => setMode('register')}
                  className="h-11 px-8 rounded-xl border border-slate-600 text-slate-300 text-sm font-medium hover:border-slate-400 hover:text-white transition-colors">
                  {t.register.registerBtn}
                </button>
              </motion.div>
            ) : (
              <motion.form key={mode} noValidate
                initial={{ opacity: 0, y: 12, scale: 0.97 }} animate={{ opacity: 1, y: 0, scale: 1 }}
                exit={{ opacity: 0, y: -12, scale: 0.97 }} transition={{ duration: 0.25 }} onSubmit={submit}
                className="mx-auto max-w-sm bg-slate-900/70 backdrop-blur-xl border border-slate-700/60 rounded-2xl p-6 shadow-2xl">
                <h2 className="text-lg font-semibold text-white mb-4">{mode === 'login' ? t.login.loginBtn : t.register.registerBtn}</h2>
                <input type="text" value={username} onChange={e => setUsername(e.target.value)}
                  autoComplete="username" placeholder={t.login.username}
                  className="w-full h-10 px-3.5 rounded-xl bg-slate-800 border border-slate-600 text-sm text-white placeholder-slate-500 focus:outline-none focus:border-sky-500 mb-3
                  [&:-webkit-autofill]:bg-slate-800 [&:-webkit-autofill]:text-white [&:-webkit-autofill]:[transition-delay:99999s]" />
                <input type="password" value={password} onChange={e => setPassword(e.target.value)}
                  autoComplete={mode === 'login' ? 'current-password' : 'new-password'} placeholder={t.login.password}
                  className="w-full h-10 px-3.5 rounded-xl bg-slate-800 border border-slate-600 text-sm text-white placeholder-slate-500 focus:outline-none focus:border-sky-500 mb-3
                  [&:-webkit-autofill]:bg-slate-800 [&:-webkit-autofill]:text-white [&:-webkit-autofill]:[transition-delay:99999s]" />
                {mode === 'register' && (
                  <input type="email" value={email} onChange={e => setEmail(e.target.value)}
                    placeholder={t.register.email} className="w-full h-10 px-3.5 rounded-xl bg-slate-800 border border-slate-600 text-sm text-white placeholder-slate-500 focus:outline-none focus:border-sky-500 mb-3" />
                )}
                {error && <p className="text-xs text-red-400 mb-3">{error}</p>}
                <button type="submit" disabled={loading}
                  className="w-full h-10 rounded-xl bg-white text-slate-900 text-sm font-medium hover:bg-slate-200 transition-colors disabled:opacity-60">
                  {loading ? '...' : mode === 'login' ? t.login.loginBtn : t.register.registerBtn}
                </button>
                <p className="text-xs text-slate-500 text-center mt-4">
                  {mode === 'login' ? t.login.noAccount : t.register.haveAccount}
                  <button type="button" onClick={switchMode} className="text-sky-400 hover:text-sky-300 ml-1">
                    {mode === 'login' ? t.login.createAccount : t.register.loginLink}
                  </button>
                </p>
              </motion.form>
            )}
          </AnimatePresence>
        </motion.div>
      </div>
    </div>
  )
}
