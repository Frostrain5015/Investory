import { useT } from '@/i18n/I18nContext'

/** Shared page-level wait state. Data requests use local loading states instead. */
export default function PageLoading() {
  const { t } = useT()
  return (
    <div role="status" aria-label={t.common.loading} className="page-loading flex h-full min-h-64 items-center justify-center bg-slate-50 dark:bg-slate-950">
      <svg aria-hidden="true" className="page-loading-arc h-11 w-11" viewBox="0 0 48 48" fill="none">
        <circle className="page-loading-track" cx="24" cy="24" r="18" strokeWidth="1" />
        <path d="M24 6a18 18 0 0 1 18 18" stroke="#47bfff" strokeWidth="2" strokeLinecap="round" />
        <path d="M24 42A18 18 0 0 1 6 24" stroke="#863bff" strokeWidth="2" strokeLinecap="round" />
      </svg>
      <span className="sr-only">{t.common.loading}</span>
    </div>
  )
}
