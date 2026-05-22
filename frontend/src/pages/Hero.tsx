import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '@/hooks/use-auth'
import { motion, AnimatePresence } from 'framer-motion'
import HeroParticles from '@/components/HeroParticles'
import HeroMatrix from '@/components/HeroMatrix'

export default function Hero() {
  const { login: doLogin, register: doRegister, authenticated } = useAuth()
  const nav = useNavigate()
  const [mode, setMode] = useState<'login' | 'register' | null>(null)
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [email, setEmail] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  useEffect(() => { if (authenticated) nav('/dashboard', { replace: true }) }, [authenticated, nav])

  const submit = async (e: React.FormEvent) => {
    e.preventDefault(); setError(''); setLoading(true)
    const result = mode === 'login'
      ? await doLogin(username, password)
      : await doRegister(username, password, email || undefined)
    setLoading(false)
    if (result.success) { nav('/dashboard', { replace: true }) }
    else setError(result.error || `${mode === 'login' ? '登录' : '注册'}失败`)
  }

  const switchMode = () => {
    setMode(mode === 'login' ? 'register' : 'login')
    setError(''); setUsername(''); setPassword(''); setEmail('')
  }

  return (
    <div className="relative w-full h-screen overflow-hidden bg-[#080c14]">
      {/* Radial glow background */}
      <div className="absolute inset-0" style={{
        background: 'radial-gradient(ellipse 60% 50% at 50% 45%, rgba(14, 165, 233, 0.06) 0%, transparent 70%), radial-gradient(ellipse 80% 60% at 50% 50%, rgba(8, 12, 20, 1) 0%, rgba(2, 6, 14, 1) 100%)',
      }} />

      {/* Visual layers */}
      <HeroParticles />
      <HeroMatrix />

      {/* UI layer */}
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
            和时间为友 与价值同行
          </motion.p>

          {!mode ? (
            <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: 0.5, duration: 0.5 }}
              className="flex items-center justify-center gap-4">
              <button onClick={() => setMode('login')}
                className="h-11 px-8 rounded-xl bg-white text-slate-900 text-sm font-medium hover:bg-slate-200 transition-colors shadow-lg shadow-white/10">
                登录
              </button>
              <button onClick={() => setMode('register')}
                className="h-11 px-8 rounded-xl border border-slate-600 text-slate-300 text-sm font-medium hover:border-slate-400 hover:text-white transition-colors">
                注册
              </button>
            </motion.div>
          ) : (
            <AnimatePresence mode="wait">
              <motion.form key={mode} initial={{ opacity: 0, y: 12, scale: 0.97 }} animate={{ opacity: 1, y: 0, scale: 1 }}
                exit={{ opacity: 0, y: -12, scale: 0.97 }} transition={{ duration: 0.25 }} onSubmit={submit}
                className="mx-auto max-w-sm bg-slate-900/70 backdrop-blur-xl border border-slate-700/60 rounded-2xl p-6 shadow-2xl">
                <h2 className="text-lg font-semibold text-white mb-4">{mode === 'login' ? '登录' : '注册'}</h2>
                <input type="text" value={username} onChange={e => setUsername(e.target.value)} required
                  placeholder="用户名" className="w-full h-10 px-3.5 rounded-xl bg-slate-800 border border-slate-600 text-sm text-white placeholder-slate-500 focus:outline-none focus:border-sky-500 mb-3" />
                <input type="password" value={password} onChange={e => setPassword(e.target.value)} required minLength={6}
                  placeholder="密码" className="w-full h-10 px-3.5 rounded-xl bg-slate-800 border border-slate-600 text-sm text-white placeholder-slate-500 focus:outline-none focus:border-sky-500 mb-3" />
                {mode === 'register' && (
                  <input type="email" value={email} onChange={e => setEmail(e.target.value)}
                    placeholder="邮箱（选填）" className="w-full h-10 px-3.5 rounded-xl bg-slate-800 border border-slate-600 text-sm text-white placeholder-slate-500 focus:outline-none focus:border-sky-500 mb-3" />
                )}
                {error && <p className="text-xs text-red-400 mb-3">{error}</p>}
                <button type="submit" disabled={loading}
                  className="w-full h-10 rounded-xl bg-white text-slate-900 text-sm font-medium hover:bg-slate-200 transition-colors disabled:opacity-60">
                  {loading ? '...' : mode === 'login' ? '登录' : '注册'}
                </button>
                <p className="text-xs text-slate-500 text-center mt-4">
                  {mode === 'login' ? '还没有账号？' : '已有账号？'}
                  <button type="button" onClick={switchMode} className="text-sky-400 hover:text-sky-300 ml-1">
                    {mode === 'login' ? '注册' : '登录'}
                  </button>
                </p>
              </motion.form>
            </AnimatePresence>
          )}
        </motion.div>
      </div>
    </div>
  )
}
