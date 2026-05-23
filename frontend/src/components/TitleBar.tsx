import { Minus, Square, X } from 'lucide-react'
import { useState } from 'react'

const api = (window as any).electronAPI

export default function TitleBar() {
  const [maxed, setMaxed] = useState(false)

  if (!api?.isDesktop) return null

  function onMaximize() {
    api.maximize()
    setMaxed(!maxed)
  }

  return (
    <div
      className="flex items-center justify-between h-9 bg-slate-900 text-slate-400 select-none shrink-0"
      style={{ WebkitAppRegion: 'drag' } as React.CSSProperties}
    >
      {/* Spacer */}<div />

      {/* Window controls */}
      <div className="flex h-full" style={{ WebkitAppRegion: 'no-drag' } as React.CSSProperties}>
        <button
          onClick={() => api.minimize()}
          className="w-11 h-full flex items-center justify-center hover:bg-slate-700/60 transition-colors"
          aria-label="Minimize"
        >
          <Minus className="w-3.5 h-3.5" />
        </button>
        <button
          onClick={onMaximize}
          className="w-11 h-full flex items-center justify-center hover:bg-slate-700/60 transition-colors"
          aria-label="Maximize"
        >
          <Square className="w-3 h-3" />
        </button>
        <button
          onClick={() => api.close()}
          className="w-11 h-full flex items-center justify-center hover:bg-red-600 hover:text-white transition-colors"
          aria-label="Close"
        >
          <X className="w-3.5 h-3.5" />
        </button>
      </div>
    </div>
  )
}
