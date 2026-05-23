import { useState, useRef, useEffect } from 'react'
import { useI18n } from '@/i18n/I18nContext'
import { LANG_LABELS, type Lang } from '@/i18n/translations'

const FLAG_CDN = 'https://flagcdn.com'
const FLAG_CODE: Record<Lang, string> = { zh: 'cn', hk: 'hk', en: 'us' }
const FLAG_STYLE = { width: 20, height: 14, borderRadius: 2 }

export default function LangSwitcher({ dark }: { dark?: boolean }) {
  const { lang, setLang } = useI18n()
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    function handleClick(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', handleClick)
    return () => document.removeEventListener('mousedown', handleClick)
  }, [])

  const langs: Lang[] = ['zh', 'hk', 'en']

  return (
    <div ref={ref} className="relative">
      <button
        onClick={() => setOpen(!open)}
        className={`flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-xs font-medium transition-colors ${
          dark ? 'text-slate-400 hover:text-white hover:bg-white/10' : 'text-slate-500 hover:text-slate-700 hover:bg-slate-100'
        }`}
      >
        <img src={`${FLAG_CDN}/${FLAG_CODE[lang]}.svg`} style={FLAG_STYLE} alt="" />
        <span className="hidden sm:inline">{LANG_LABELS[lang]}</span>
        <svg className="w-3 h-3 opacity-50" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
          <polyline points="6 9 12 15 18 9" />
        </svg>
      </button>
      {open && (
        <div className="absolute right-0 top-full mt-1 bg-white rounded-xl border border-slate-200 shadow-lg overflow-hidden z-50 min-w-[120px]">
          {langs.map(l => (
            <button
              key={l}
              onClick={() => { setLang(l); setOpen(false) }}
              className={`flex items-center gap-2.5 w-full px-3.5 py-2.5 text-sm transition-colors ${
                l === lang ? 'bg-slate-50 text-slate-900 font-medium' : 'text-slate-500 hover:bg-slate-50 hover:text-slate-700'
              }`}
            >
              <img src={`${FLAG_CDN}/${FLAG_CODE[l]}.svg`} style={FLAG_STYLE} alt="" />
              {LANG_LABELS[l]}
              {l === lang && (
                <svg className="w-4 h-4 ml-auto text-slate-900" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
                  <polyline points="20 6 9 17 4 12" />
                </svg>
              )}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
