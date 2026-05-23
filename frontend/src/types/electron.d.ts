interface ElectronAPI {
  isDesktop: boolean
  platform: string
  minimize: () => void
  maximize: () => void
  close: () => void
}

interface Window {
  electronAPI?: ElectronAPI
}
