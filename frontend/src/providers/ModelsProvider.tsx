import { useEffect, useRef, useState, type ReactNode } from 'react'
import { useAuth } from '@/hooks/use-auth'
import { ModelsContext } from '@/hooks/use-models'
import { aiGetSettings, aiSaveSettings, aiRemoveModel, aiSelectModel } from '@/services/api'
import type { AiModelSettings } from '@/types'

const empty: AiModelSettings = { models: [], activeModelId: null, maxModels: 3 }
export function ModelsProvider({ children }: { children: ReactNode }) {
  const { userId } = useAuth()
  return <ModelsForUser key={userId ?? 'anonymous'} userId={userId}>{children}</ModelsForUser>
}
function ModelsForUser({ children, userId }: { children: ReactNode; userId: number | null }) {
  const [settings, setSettings] = useState<AiModelSettings>(empty)
  const [loading, setLoading] = useState(!!userId)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const generation = useRef(0)
  useEffect(() => {
    const current = ++generation.current
    if (!userId) return
    aiGetSettings().then(result => { if (generation.current === current) setSettings(result) })
      .catch(() => { if (generation.current === current) setError('无法读取模型配置，请刷新重试') })
      .finally(() => { if (generation.current === current) setLoading(false) })
    return () => { generation.current += 1 }
  }, [userId])
  async function update(operation: () => Promise<AiModelSettings>) {
    const current = generation.current
    setBusy(true)
    try {
      const result = await operation()
      if (generation.current === current) { setSettings(result); setError('') }
      return result
    } finally { if (generation.current === current) setBusy(false) }
  }
  return <ModelsContext.Provider value={{ settings, loading, busy, error,
    save: model => update(() => aiSaveSettings(model)),
    remove: id => update(() => aiRemoveModel(id)),
    select: id => update(() => aiSelectModel(id)),
  }}>{children}</ModelsContext.Provider>
}
