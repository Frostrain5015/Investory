import { createContext, useContext } from 'react'

export const ToastCtx = createContext<(msg: string, ok: boolean) => void>(() => {})

export function useToast() {
  return useContext(ToastCtx)
}
