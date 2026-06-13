import { createContext, useContext, useState, useEffect, type ReactNode } from 'react'

type ThemePref = 'system' | 'light' | 'dark'
type Theme = 'light' | 'dark'

interface ThemeCtxValue {
  theme: Theme
  pref: ThemePref
  setPref: (p: ThemePref) => void
  toggleTheme: () => void
  isDark: boolean
}

const ThemeCtx = createContext<ThemeCtxValue>({
  theme: 'light', pref: 'system',
  setPref: () => {}, toggleTheme: () => {}, isDark: false,
})

function resolveTheme(pref: ThemePref): Theme {
  if (pref === 'system') {
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
  }
  return pref
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [pref, setPrefState] = useState<ThemePref>(() =>
    (localStorage.getItem('theme-pref') as ThemePref) || 'system'
  )
  const [theme, setTheme] = useState<Theme>(() => resolveTheme(
    (localStorage.getItem('theme-pref') as ThemePref) || 'system'
  ))

  useEffect(() => {
    const apply = () => {
      const resolved = resolveTheme(pref)
      setTheme(resolved)
      document.documentElement.classList.toggle('dark', resolved === 'dark')
    }
    apply()
    if (pref === 'system') {
      const mq = window.matchMedia('(prefers-color-scheme: dark)')
      mq.addEventListener('change', apply)
      return () => mq.removeEventListener('change', apply)
    }
  }, [pref])

  function setPref(p: ThemePref) {
    localStorage.setItem('theme-pref', p)
    setPrefState(p)
  }

  function toggleTheme() {
    setPref(theme === 'light' ? 'dark' : 'light')
  }

  return (
    <ThemeCtx.Provider value={{ theme, pref, setPref, toggleTheme, isDark: theme === 'dark' }}>
      {children}
    </ThemeCtx.Provider>
  )
}

export function useTheme() { return useContext(ThemeCtx) }
