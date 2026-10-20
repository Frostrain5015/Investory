import { I18nContext } from '@/i18n/I18nContext'
import { useState, useCallback, type ReactNode } from 'react'
import { type Lang } from '@/i18n/translations'

const STORAGE_KEY = 'investory_lang'

function detectLang(): Lang {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    if (stored === 'en' || stored === 'zh' || stored === 'hk') return stored
  } catch {
    /* Ignore malformed or unavailable optional state. */
  }
  // Detect from browser
  const nav = navigator.language || ''
  return nav.startsWith('zh') ? 'zh' : 'en'
}

export function I18nProvider({ children }: { children: ReactNode }) {
  const [lang, setLangState] = useState<Lang>(detectLang)

  const setLang = useCallback((l: Lang) => {
    setLangState(l)
    try {
      localStorage.setItem(STORAGE_KEY, l)
    } catch {
      /* Ignore malformed or unavailable optional state. */
    }
  }, [])

  const toggleLang = useCallback(() => {
    setLang(lang === 'zh' ? 'en' : 'zh')
  }, [lang, setLang])

  return (
    <I18nContext.Provider value={{ lang, setLang, toggleLang }}>{children}</I18nContext.Provider>
  )
}

/** Translation hook — usage: const { t } = useT(); t.nav.dashboard */
