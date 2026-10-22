import { createContext, useContext } from 'react'
import type { AiModelSettings } from '@/types'
import type { aiSaveSettings } from '@/services/api'

export interface ModelsContextValue {
  settings: AiModelSettings
  loading: boolean
  busy: boolean
  error: string
  save: (model: Parameters<typeof aiSaveSettings>[0]) => Promise<AiModelSettings>
  remove: (id: number) => Promise<AiModelSettings>
  select: (id: number) => Promise<AiModelSettings>
}
export const ModelsContext = createContext<ModelsContextValue | null>(null)
export function useModels() {
  const context = useContext(ModelsContext)
  if (!context) throw new Error('ModelsProvider required')
  return context
}
