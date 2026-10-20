import { createContext, useContext } from 'react'

export interface PromptOptions {
  message: string
  defaultValue?: string
  placeholder?: string
  confirmText?: string
  cancelText?: string
}

export const PromptCtx = createContext<(opts: PromptOptions) => Promise<string | null>>(() =>
  Promise.resolve(null),
)

export function usePrompt() {
  return useContext(PromptCtx)
}
