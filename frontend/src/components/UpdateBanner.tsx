import { useEffect, useState } from 'react'
import { Download, RefreshCw, X, Sparkles } from 'lucide-react'

export default function UpdateBanner() {
  const [status, setStatus] = useState<UpdateStatus | null>(null)
  const [dismissed, setDismissed] = useState(false)

  useEffect(() => {
    if (!window.electronAPI?.onUpdateStatus) return
    const unsub = window.electronAPI.onUpdateStatus((s) => {
      setStatus(s)
      setDismissed(false)
    })
    return unsub
  }, [])

  if (!window.electronAPI?.isDesktop || !status || dismissed) return null

  const bps = status.bytesPerSecond ?? 0
  const speed = bps > 1_000_000
    ? `${(bps / 1_000_000).toFixed(1)} MB/s`
    : `${Math.round(bps / 1024)} KB/s`

  return (
    <div className="flex items-center gap-3 px-4 py-2 bg-slate-900 text-slate-100 text-sm select-none shrink-0">
      {status.type === 'available' && (
        <>
          <Sparkles className="w-4 h-4 text-blue-400 shrink-0" />
          <span>发现新版本 <span className="font-semibold text-white">{status.version}</span>，正在后台下载…</span>
          <button onClick={() => setDismissed(true)} className="ml-auto p-0.5 rounded hover:bg-slate-700">
            <X className="w-3.5 h-3.5" />
          </button>
        </>
      )}

      {status.type === 'downloading' && (
        <>
          <Download className="w-4 h-4 text-blue-400 shrink-0 animate-bounce" />
          <span>正在下载 <span className="font-semibold text-white">{status.version}</span></span>
          <div className="flex items-center gap-2 ml-2">
            <div className="w-28 h-1.5 bg-slate-700 rounded-full overflow-hidden">
              <div
                className="h-full bg-blue-500 rounded-full transition-all duration-300"
                style={{ width: `${status.percent ?? 0}%` }}
              />
            </div>
            <span className="text-slate-400 text-xs tabular-nums">
              {Math.round(status.percent ?? 0)}%
            </span>
            {bps > 0 && <span className="text-slate-500 text-xs">{speed}</span>}
          </div>
        </>
      )}

      {status.type === 'ready' && (
        <>
          <RefreshCw className="w-4 h-4 text-green-400 shrink-0" />
          <span>
            <span className="font-semibold text-white">{status.version}</span> 已下载完成，重启后生效
          </span>
          <button
            onClick={() => window.electronAPI?.restartAndInstall()}
            className="ml-2 px-3 py-0.5 rounded-lg bg-green-600 hover:bg-green-500 text-white text-xs font-medium transition-colors"
          >
            立即重启
          </button>
          <button onClick={() => setDismissed(true)} className="ml-auto p-0.5 rounded hover:bg-slate-700">
            <X className="w-3.5 h-3.5" />
          </button>
        </>
      )}
    </div>
  )
}
