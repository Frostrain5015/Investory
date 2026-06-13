import { useEffect, useState } from 'react'
import { motion, AnimatePresence } from 'framer-motion'
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

  // ── Dynamic shell shape per state ──────────────────────────────────
  // The banner morphs between compact pill (available) ↔ wider bar
  // (downloading) ↔ action bar (ready). Each state defines its own
  // width, padding, and gap — the shell spring-animates between them.
  const shell = status.type === 'available'
    ? { width: 'auto' as const, padding: '6px 14px', gap: '10px' }
    : status.type === 'downloading'
      ? { width: '100%' as const, padding: '6px 16px', gap: '12px' }
      : { width: '100%' as const, padding: '6px 16px', gap: '12px' }

  const springShell = { type: 'spring' as const, stiffness: 380, damping: 28 }
  const springSnappy = { type: 'spring' as const, stiffness: 500, damping: 32 }
  const stagger = 0.05

  return (
    <motion.div
      layout
      animate={{
        width: shell.width,
        paddingLeft: shell.padding.split(' ')[1],
        paddingRight: shell.padding.split(' ')[1],
        paddingTop: shell.padding.split(' ')[0],
        paddingBottom: shell.padding.split(' ')[0],
        gap: shell.gap,
      }}
      transition={springShell}
      className="flex items-center bg-slate-900 text-slate-100 text-sm select-none shrink-0 overflow-hidden"
    >
      {/* ── Icon: crossfade between states ─────────────────────────── */}
      <div className="relative w-4 h-4 shrink-0">
        <AnimatePresence mode="wait">
          {status.type === 'available' && (
            <motion.span key="ico-avail"
              initial={{ opacity: 0, scale: 0.7 }} animate={{ opacity: 1, scale: 1 }}
              exit={{ opacity: 0, scale: 0.7 }} transition={springSnappy}
              className="absolute inset-0 flex items-center justify-center text-blue-400">
              <Sparkles className="w-4 h-4" />
            </motion.span>
          )}
          {status.type === 'downloading' && (
            <motion.span key="ico-down"
              initial={{ opacity: 0, scale: 0.7 }} animate={{ opacity: 1, scale: 1 }}
              exit={{ opacity: 0, scale: 0.7 }} transition={springSnappy}
              className="absolute inset-0 flex items-center justify-center text-blue-400">
              <Download className="w-4 h-4 animate-bounce" />
            </motion.span>
          )}
          {status.type === 'ready' && (
            <motion.span key="ico-ready"
              initial={{ opacity: 0, scale: 0.7 }} animate={{ opacity: 1, scale: 1 }}
              exit={{ opacity: 0, scale: 0.7 }} transition={springSnappy}
              className="absolute inset-0 flex items-center justify-center text-green-400">
              <RefreshCw className="w-4 h-4" />
            </motion.span>
          )}
        </AnimatePresence>
      </div>

      {/* ── Version label (always visible, slides as layout changes) ── */}
      <motion.span layout="position" className="font-semibold text-white whitespace-nowrap">
        {status.version}
      </motion.span>

      {/* ── Status-specific content morphs in/out ───────────────────── */}
      <AnimatePresence mode="wait">
        {status.type === 'available' && (
          <motion.span key="lbl-avail"
            initial={{ opacity: 0, x: -8 }} animate={{ opacity: 1, x: 0 }}
            exit={{ opacity: 0, x: -8 }} transition={{ ...springSnappy, delay: stagger }}
            className="whitespace-nowrap">
            正在后台下载…
          </motion.span>
        )}

        {status.type === 'downloading' && (
          <motion.div key="lbl-down" layout="position"
            initial={{ opacity: 0, x: -8 }} animate={{ opacity: 1, x: 0 }}
            exit={{ opacity: 0, x: -8 }} transition={{ ...springSnappy, delay: stagger }}
            className="flex items-center gap-2 min-w-0">
            {/* Progress bar: spring-animated width */}
            <div className="w-28 h-1.5 bg-slate-700 rounded-full overflow-hidden shrink-0">
              <motion.div
                className="h-full bg-blue-500 rounded-full"
                initial={{ width: 0 }}
                animate={{ width: `${status.percent ?? 0}%` }}
                transition={{ type: 'spring', stiffness: 120, damping: 18 }}
              />
            </div>
            <span className="text-slate-400 text-xs tabular-nums">
              {Math.round(status.percent ?? 0)}%
            </span>
            {bps > 0 && (
              <motion.span initial={{ opacity: 0 }} animate={{ opacity: 1 }}
                transition={{ delay: stagger * 2 }}
                className="text-slate-500 text-xs">
                {speed}
              </motion.span>
            )}
          </motion.div>
        )}

        {status.type === 'ready' && (
          <motion.div key="lbl-ready" layout="position"
            initial={{ opacity: 0, x: -8 }} animate={{ opacity: 1, x: 0 }}
            exit={{ opacity: 0, x: -8 }} transition={{ ...springSnappy, delay: stagger }}
            className="flex items-center gap-2 min-w-0 whitespace-nowrap">
            <span>已下载完成，重启后生效</span>
            {/* Restart button blooms in */}
            <motion.button
              onClick={() => window.electronAPI?.restartAndInstall()}
              initial={{ scale: 0.7, opacity: 0 }} animate={{ scale: 1, opacity: 1 }}
              transition={springSnappy}
              className="px-3 py-0.5 rounded-lg bg-green-600 hover:bg-green-500 text-white text-xs font-medium"
            >
              立即重启
            </motion.button>
          </motion.div>
        )}
      </AnimatePresence>

      {/* ── Dismiss (morphs position via layout) ────────────────────── */}
      <motion.button
        layout
        onClick={() => setDismissed(true)}
        className="ml-auto p-0.5 rounded hover:bg-slate-700 shrink-0"
      >
        <X className="w-3.5 h-3.5" />
      </motion.button>
    </motion.div>
  )
}
