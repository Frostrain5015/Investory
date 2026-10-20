import { createContext, useContext } from 'react'

export const ConfirmCtx = createContext<(msg: string) => Promise<boolean>>(() =>
  Promise.resolve(false),
)

export function useConfirm() {
  return useContext(ConfirmCtx)
}
