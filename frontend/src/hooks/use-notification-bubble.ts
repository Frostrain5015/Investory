import { createContext, useContext } from 'react'

export type Variant = 'default' | 'morning'

export interface Bubble {
  id: number
  title: string
  message: string
  actionLabel?: string
  actionHref?: string
  variant?: Variant
  onAction?: () => void
}

export interface Ctx {
  show: (b: Omit<Bubble, 'id'>) => void
  dismiss: (id: number) => void
}

export const ctx = createContext<Ctx>({ show: () => {}, dismiss: () => {} })

export const useNotificationBubble = () => useContext(ctx)
