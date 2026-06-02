import { useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '@/hooks/use-auth'
import { motion, AnimatePresence } from 'framer-motion'
import HeroParticles from '@/components/HeroParticles'
import HeroMatrix from '@/components/HeroMatrix'
import { useT } from '@/i18n/I18nContext'
import LangSwitcher from '@/components/LangSwitcher'
import { getFrostIdLoginUrl } from '@/services/api'

export default function Hero() {
  const { t } = useT()
  const { authenticated, isAdmin } = useAuth()
  const nav = useNavigate()
  const frostIdLoginUrl = getFrostIdLoginUrl()

  // Auto-redirect if already authenticated (session still valid = "auto-login")
  useEffect(() => {
    if (authenticated) nav(isAdmin ? '/admin' : '/dashboard', { replace: true })
  }, [authenticated, isAdmin, nav])

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
      <div className="absolute bottom-5 left-5 z-20">
        <span className="text-[10px] text-slate-600 font-light tracking-widest">❄ Frost Tech</span>
      </div>

      <div className="relative z-10 flex flex-col items-center justify-center h-full px-4">
        <motion.div
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.8 }}
          className="text-center max-w-lg"
        >
          <motion.h1
            initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }}
            transition={{ delay: 0.2, duration: 0.6 }}
            className="text-5xl sm:text-6xl font-bold tracking-tight text-white mb-3"
            style={{ textShadow: '0 0 80px rgba(14, 165, 233, 0.3)' }}
          >
            Investory
          </motion.h1>
          <motion.p
            initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }}
            transition={{ delay: 0.35, duration: 0.6 }}
            className="text-base text-slate-400 mb-10 tracking-wide"
          >
            {t.login.tagline}
          </motion.p>

          <AnimatePresence mode="wait">
            <motion.div
              key="frostid"
              initial={{ opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -10 }}
              transition={{ delay: 0.5, duration: 0.4 }}
              className="flex flex-col items-center gap-3"
            >
              <a
                href={frostIdLoginUrl}
                className="flex items-center justify-center gap-2.5 h-11 px-8 rounded-xl text-sm font-medium transition-all duration-200 shadow-lg"
                style={{
                  background: 'rgba(113, 118, 170, 0.15)',
                  border: '1px solid rgba(113, 118, 170, 0.45)',
                  color: '#c4c7e8',
                  boxShadow: '0 0 24px rgba(113, 118, 170, 0.12)',
                }}
                onMouseEnter={(e) => {
                  const el = e.currentTarget as HTMLElement
                  el.style.background = 'rgba(113, 118, 170, 0.28)'
                  el.style.borderColor = 'rgba(113, 118, 170, 0.7)'
                  el.style.color = '#e0e2f4'
                  el.style.boxShadow = '0 0 36px rgba(113, 118, 170, 0.2)'
                }}
                onMouseLeave={(e) => {
                  const el = e.currentTarget as HTMLElement
                  el.style.background = 'rgba(113, 118, 170, 0.15)'
                  el.style.borderColor = 'rgba(113, 118, 170, 0.45)'
                  el.style.color = '#c4c7e8'
                  el.style.boxShadow = '0 0 24px rgba(113, 118, 170, 0.12)'
                }}
              >
                <span style={{ fontSize: '1.1rem', lineHeight: 1 }} aria-hidden="true">❄</span>
                <span>{t.login.frostidLogin}</span>
              </a>
              <p className="text-xs text-slate-600">{t.login.frostidDesc}</p>
            </motion.div>
          </AnimatePresence>
        </motion.div>
      </div>
    </div>
  )
}
