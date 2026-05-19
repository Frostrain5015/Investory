import { createContext, useContext, useState, useEffect, type ReactNode } from 'react'

export type ColorScheme = 'cn' | 'western'
export type BaseCurrency = 'CNY' | 'HKD' | 'USD'

const EXCHANGE_RATES: Record<BaseCurrency, number> = {
  CNY: 1,
  HKD: 1.08,
  USD: 0.138,
}

interface Settings {
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

const SettingsContext = createContext<Settings | null>(null)

function getCnClasses() {
  return {
    positiveClass: 'text-red-500',
    negativeClass: 'text-emerald-600',
    positiveBgClass: 'bg-red-50 text-red-600',
    negativeBgClass: 'bg-emerald-50 text-emerald-600',
    positiveHex: '#ef4444',
    negativeHex: '#10b981',
  }
}

function getWesternClasses() {
  return {
    positiveClass: 'text-emerald-600',
    negativeClass: 'text-red-500',
    positiveBgClass: 'bg-emerald-50 text-emerald-600',
    negativeBgClass: 'bg-red-50 text-red-600',
    positiveHex: '#10b981',
    negativeHex: '#ef4444',
  }
}

const CURRENCY_SYMBOLS: Record<BaseCurrency, string> = {
  CNY: '¥',
  HKD: 'HK$',
  USD: '$',
}

export function SettingsProvider({ children }: { children: ReactNode }) {
  const [colorScheme, setColorScheme] = useState<ColorScheme>(() => {
    return (localStorage.getItem('colorScheme') as ColorScheme) || 'cn'
  })
  const [baseCurrency, setBaseCurrency] = useState<BaseCurrency>(() => {
    return (localStorage.getItem('baseCurrency') as BaseCurrency) || 'CNY'
  })

  useEffect(() => { localStorage.setItem('colorScheme', colorScheme) }, [colorScheme])
  useEffect(() => { localStorage.setItem('baseCurrency', baseCurrency) }, [baseCurrency])

  function toggleColorScheme() {
    setColorScheme(prev => prev === 'cn' ? 'western' : 'cn')
  }

  function convertCurrency(cnyValue: number): number {
    return cnyValue * EXCHANGE_RATES[baseCurrency]
  }

  function formatCurrency(value: number): string {
    const converted = convertCurrency(value)
    return CURRENCY_SYMBOLS[baseCurrency] + converted.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  }

  const classes = colorScheme === 'cn' ? getCnClasses() : getWesternClasses()

  return (
    <SettingsContext.Provider value={{ colorScheme, toggleColorScheme, ...classes, baseCurrency, setBaseCurrency, formatCurrency, convertCurrency }}>
      {children}
    </SettingsContext.Provider>
  )
}

export function useSettings() {
  const ctx = useContext(SettingsContext)
  if (!ctx) throw new Error('useSettings must be inside SettingsProvider')
  return ctx
}
