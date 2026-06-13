import { Component, type ErrorInfo, type ReactNode } from 'react'

interface Props { children: ReactNode; fallback?: ReactNode }

interface State { hasError: boolean; error: Error | null }

export default class ErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false, error: null }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('[ErrorBoundary]', error, info.componentStack)
  }

  render() {
    if (!this.state.hasError) return this.props.children
    return this.props.fallback ?? (
      <div className="flex flex-col items-center justify-center gap-4 h-full bg-slate-50 dark:bg-slate-950 p-8">
        <div className="w-12 h-12 rounded-full bg-red-100 dark:bg-red-900/30 flex items-center justify-center">
          <span className="text-xl">⚠</span>
        </div>
        <div className="text-center">
          <h2 className="text-lg font-semibold text-slate-800 dark:text-slate-200 mb-1">
            页面发生错误
          </h2>
          <p className="text-sm text-slate-500 dark:text-slate-400 max-w-md">
            {this.state.error?.message || '未知错误'}
          </p>
        </div>
        <button onClick={() => { this.setState({ hasError: false, error: null }); window.location.reload() }}
          className="px-4 py-2 rounded-lg bg-slate-900 dark:bg-slate-100 text-white dark:text-slate-900 text-sm font-medium hover:opacity-90 transition-opacity">
          重新加载
        </button>
      </div>
    )
  }
}
