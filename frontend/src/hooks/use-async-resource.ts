import { useCallback, useEffect, useState } from 'react'

type Loader<T> = (signal: AbortSignal) => Promise<T>
interface Snapshot<T> {
  key: string
  loader: Loader<T>
  data?: T
  error?: Error
  revision: number
}

/** A response belongs to its request key; cancelled requests cannot overwrite newer data. */
export function useAsyncResource<T>(key: string | null, loader: Loader<T>) {
  const [snapshot, setSnapshot] = useState<Snapshot<T> | null>(null)
  const [revision, setRevision] = useState(0)
  const matching = key !== null && snapshot?.key === key && snapshot.loader === loader
  const current = matching && snapshot.revision === revision
  const reload = useCallback(() => setRevision(value => value + 1), [])

  useEffect(() => {
    if (key === null) return
    const controller = new AbortController()
    loader(controller.signal).then(
      (data) => {
        if (!controller.signal.aborted) setSnapshot({ key, loader, data, revision })
      },
      (reason) => {
        if (!controller.signal.aborted) {
          setSnapshot(previous => ({
            key,
            loader,
            revision,
            data: previous?.key === key && previous.loader === loader ? previous.data : undefined,
            error: reason instanceof Error ? reason : new Error(String(reason)),
          }))
        }
      },
    )
    return () => controller.abort()
  }, [key, loader, revision])

  return {
    data: matching ? snapshot.data : undefined,
    error: current ? snapshot.error : undefined,
    loading: key !== null && !current,
    reload,
  }
}
