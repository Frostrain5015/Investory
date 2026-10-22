import { createContext, useContext } from 'react'

export type ColorScheme = 'cn' | 'western'

export type BaseCurrency = 'CNY' | 'HKD' | 'USD'

export interface Settings {
  colorScheme: ColorScheme
  toggleColorScheme: () => void
  positiveClass: string
  negativeClass: string
  positiveBgClass: string
  negativeBgClass: string
  positiveHex: string
  negativeHex: string
  baseCurrency: BaseCurrency
  setBaseCurrency: (c: BaseCurrency) => void
  formatCurrency: (value: number) => string
  convertCurrency: (cnyValue: number) => number
}

export const SettingsContext = createContext<Settings | null>(null)

export function useSettings() {
  const ctx = useContext(SettingsContext)
  if (!ctx) throw new Error('useSettings must be inside SettingsProvider')
  return ctx
}
