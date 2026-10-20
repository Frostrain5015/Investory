import { useEffect, useState } from 'react'

type Loader<T> = (signal: AbortSignal) => Promise<T>
interface Snapshot<T> {
  key: string
  loader: Loader<T>
  data?: T
  error?: Error
}

/** A response belongs to its request key; cancelled requests cannot overwrite newer data. */
export function useAsyncResource<T>(key: string | null, loader: Loader<T>) {
  const [snapshot, setSnapshot] = useState<Snapshot<T> | null>(null)
  const current = key !== null && snapshot?.key === key && snapshot.loader === loader

  useEffect(() => {
    if (key === null) return
    const controller = new AbortController()
    loader(controller.signal).then(
      (data) => {
        if (!controller.signal.aborted) setSnapshot({ key, loader, data })
      },
      (reason) => {
        if (!controller.signal.aborted) {
          setSnapshot({
            key,
            loader,
            error: reason instanceof Error ? reason : new Error(String(reason)),
          })
        }
      },
    )
    return () => controller.abort()
  }, [key, loader])

  return {
    data: current ? snapshot.data : undefined,
    error: current ? snapshot.error : undefined,
    loading: key !== null && !current,
  }
}
