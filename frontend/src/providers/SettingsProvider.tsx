import { SettingsContext, type ColorScheme, type BaseCurrency } from '@/hooks/use-settings'
import { useState, useEffect, useCallback, type ReactNode } from 'react'
import { getExchangeRates } from '@/services/api'
import { useAuth } from '@/hooks/use-auth'

const FALLBACK_RATES: Record<BaseCurrency, number> = {
  CNY: 1,
  HKD: 1.08,
  USD: 0.138,
}

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
  const { authenticated } = useAuth()
  const [colorScheme, setColorScheme] = useState<ColorScheme>(() => {
    return (localStorage.getItem('colorScheme') as ColorScheme) || 'cn'
  })
  const [baseCurrency, setBaseCurrency] = useState<BaseCurrency>(() => {
    return (localStorage.getItem('baseCurrency') as BaseCurrency) || 'CNY'
  })
  const [showRiskMetrics, setShowRiskMetrics] = useState<boolean>(() => {
    return localStorage.getItem('showRiskMetrics') === 'true'
  })
  const [rates, setRates] = useState<Record<BaseCurrency, number>>(FALLBACK_RATES)

  const fetchRates = useCallback(() => {
    getExchangeRates()
      .then((data) => {
        setRates({
          CNY: 1,
          HKD: Number(data.rates.HKD) || FALLBACK_RATES.HKD,
          USD: Number(data.rates.USD) || FALLBACK_RATES.USD,
        })
      })
      .catch(() => {})
  }, [])

  useEffect(() => {
    localStorage.setItem('colorScheme', colorScheme)
  }, [colorScheme])
  useEffect(() => {
    localStorage.setItem('baseCurrency', baseCurrency)
  }, [baseCurrency])
  useEffect(() => {
    localStorage.setItem('showRiskMetrics', String(showRiskMetrics))
  }, [showRiskMetrics])
  useEffect(() => {
    if (authenticated) fetchRates()
  }, [fetchRates, authenticated])

  function toggleColorScheme() {
    setColorScheme((prev) => (prev === 'cn' ? 'western' : 'cn'))
  }

  function toggleRiskMetrics() {
    setShowRiskMetrics((prev) => !prev)
  }

  function convertCurrency(cnyValue: number): number {
    return cnyValue * rates[baseCurrency]
  }

  function formatCurrency(value: number): string {
    const converted = convertCurrency(value)
    return (
      CURRENCY_SYMBOLS[baseCurrency] +
      converted.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
    )
  }

  const classes = colorScheme === 'cn' ? getCnClasses() : getWesternClasses()

  return (
    <SettingsContext.Provider
      value={{
        colorScheme,
        toggleColorScheme,
        ...classes,
        baseCurrency,
        setBaseCurrency,
        formatCurrency,
        convertCurrency,
        showRiskMetrics,
        toggleRiskMetrics,
      }}
    >
      {children}
    </SettingsContext.Provider>
  )
}
