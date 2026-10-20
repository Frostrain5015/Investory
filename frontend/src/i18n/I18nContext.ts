import { createContext, useContext } from 'react'
import { LANGS, type Lang, type Translation } from './translations'

export interface I18nState {
  lang: Lang
  setLang: (lang: Lang) => void
  toggleLang: () => void
}

export const I18nContext = createContext<I18nState | null>(null)

export function useI18n() {
  const ctx = useContext(I18nContext)
  if (!ctx) throw new Error('useI18n must be used within I18nProvider')
  return ctx
}

export function useT(): { t: Translation; lang: Lang } {
  const { lang } = useI18n()
  return { t: LANGS[lang] as Translation, lang }
}
