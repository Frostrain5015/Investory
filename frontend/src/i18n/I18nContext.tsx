import { createContext, useContext, useState, useCallback, type ReactNode } from 'react'
import { LANGS, type Lang, type Translation } from './translations'

interface I18nState {
  lang: Lang
  setLang: (lang: Lang) => void
  toggleLang: () => void
}

const I18nContext = createContext<I18nState | null>(null)

const STORAGE_KEY = 'investory_lang'

function detectLang(): Lang {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    if (stored === 'en' || stored === 'zh' || stored === 'hk') return stored
  } catch {}
  // Detect from browser
  const nav = navigator.language || ''
  return nav.startsWith('zh') ? 'zh' : 'en'
}

export function I18nProvider({ children }: { children: ReactNode }) {
  const [lang, setLangState] = useState<Lang>(detectLang)

  const setLang = useCallback((l: Lang) => {
    setLangState(l)
    try { localStorage.setItem(STORAGE_KEY, l) } catch {}
  }, [])

  const toggleLang = useCallback(() => {
    setLang(lang === 'zh' ? 'en' : 'zh')
  }, [lang, setLang])

  return (
    <I18nContext.Provider value={{ lang, setLang, toggleLang }}>
      {children}
    </I18nContext.Provider>
  )
}

export function useI18n() {
  const ctx = useContext(I18nContext)
  if (!ctx) throw new Error('useI18n must be used within I18nProvider')
  return ctx
}

/** Translation hook — usage: const { t } = useT(); t.nav.dashboard */
export function useT(): { t: Translation; lang: Lang } {
  const { lang } = useI18n()
  return { t: LANGS[lang] as Translation, lang }
}
