const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('electronAPI', {
  isDesktop: true,
  platform: process.platform,
  minimize: () => ipcRenderer.send('window:minimize'),
  maximize: () => ipcRenderer.send('window:maximize'),
  close: () => ipcRenderer.send('window:close'),
  onUpdateStatus: (callback) => {
    const listener = (_event, status) => callback(status)
    ipcRenderer.on('update:status', listener)
    return () => ipcRenderer.removeListener('update:status', listener)
  },
  restartAndInstall: () => ipcRenderer.send('app:restart-and-install'),
  openExternal: (url) => ipcRenderer.invoke('shell:open-external', url),
  onFrostIdCallback: (callback) => {
    const listener = (_event, token) => callback(token)
    ipcRenderer.on('frostid:callback', listener)
    return () => ipcRenderer.removeListener('frostid:callback', listener)
  },
})
