interface UpdateStatus {
  type: 'available' | 'downloading' | 'ready'
  version: string
  percent?: number
  bytesPerSecond?: number
}

interface ElectronAPI {
  isDesktop: boolean
  platform: string
  minimize: () => void
  maximize: () => void
  close: () => void
  onUpdateStatus: (callback: (status: UpdateStatus) => void) => () => void
  restartAndInstall: () => void
  openExternal: (url: string) => Promise<void>
  onFrostIdCallback: (callback: (token: string) => void) => () => void
}

interface Window {
  electronAPI?: ElectronAPI
}
