import { useT } from '@/i18n/I18nContext'

/** Loading stays inside its section; page navigation and controls remain usable. */
export default function AsyncState({ loading, error, retry, rows = 4 }: {
  loading?: boolean
  error?: Error | null
  retry?: () => void
  rows?: number
}) {
  const { t, lang } = useT()
  if (error) return <div role="alert" className="flex items-center justify-center gap-3 px-4 py-8 text-xs text-slate-500">
    <span>{lang === 'zh' ? '此区域暂时无法加载' : 'This section could not load'}</span>
    {retry && <button onClick={retry} className="underline underline-offset-4 hover:text-slate-900 dark:hover:text-slate-100">{lang === 'zh' ? '重试' : 'Retry'}</button>}
  </div>
  if (!loading) return null
  return <div role="status" aria-label={t.common.loading} className="p-5 space-y-4 motion-safe:animate-pulse">
    {Array.from({ length: rows }, (_, index) => <div key={index} className="h-5 rounded-md bg-slate-100 dark:bg-slate-800" style={{ width: `${94 - index % 3 * 13}%` }} />)}
  </div>
}
