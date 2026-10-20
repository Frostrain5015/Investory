import { createContext, useContext } from 'react'

export type ThemePref = 'system' | 'light' | 'dark'

export type Theme = 'light' | 'dark'

export interface ThemeCtxValue {
  theme: Theme
  pref: ThemePref
  setPref: (p: ThemePref) => void
  toggleTheme: () => void
  isDark: boolean
}

export const ThemeCtx = createContext<ThemeCtxValue>({
  theme: 'light',
  pref: 'system',
  setPref: () => {},
  toggleTheme: () => {},
  isDark: false,
})

export function useTheme() {
  return useContext(ThemeCtx)
}
