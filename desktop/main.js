const { app, BrowserWindow, shell, ipcMain, nativeImage, dialog } = require('electron')
const { autoUpdater } = require('electron-updater')
const http = require('http')
const fs = require('fs')
const path = require('path')

autoUpdater.logger = require('electron').app.isPackaged ? null : console
autoUpdater.autoDownload = true
autoUpdater.autoInstallOnAppQuit = true

let win = null

const PORT = 18256
const DIST = path.join(__dirname, 'dist')
const ICON = nativeImage.createFromPath(path.join(__dirname, 'assets', 'icon.png'))

app.commandLine.appendSwitch('ignore-certificate-errors')

// ── Local static server with SPA fallback ──────────────────────────

function startServer() {
  const mime = {
    '.html': 'text/html', '.js': 'application/javascript', '.css': 'text/css',
    '.svg': 'image/svg+xml', '.png': 'image/png', '.json': 'application/json',
    '.woff2': 'font/woff2', '.ico': 'image/x-icon',
  }

  return new Promise((resolve, reject) => {
    const server = http.createServer((req, res) => {
      const url = new URL(req.url, `http://localhost:${PORT}`)
      let filePath = path.join(DIST, url.pathname === '/' ? 'index.html' : url.pathname)

      if (!path.extname(filePath) || !mime[path.extname(filePath)]) {
        filePath = path.join(DIST, 'index.html')
      }

      const ext = path.extname(filePath)
      const contentType = mime[ext] || 'application/octet-stream'

      fs.readFile(filePath, (err, data) => {
        if (err) {
          res.writeHead(404)
          res.end('Not Found')
          return
        }
        res.writeHead(200, { 'Content-Type': contentType })
        res.end(data)
      })
    })

    server.listen(PORT, '127.0.0.1', () => {
      console.log(`Server ready: http://127.0.0.1:${PORT}`)
      resolve(server)
    })
    server.on('error', reject)
  })
}

// ── Window ─────────────────────────────────────────────────────────

function createWindow() {
  win = new BrowserWindow({
    width: 1280,
    height: 800,
    minWidth: 800,
    minHeight: 600,
    title: 'Investory',
    icon: ICON,
    frame: false,
    backgroundColor: '#0f172a',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      nodeIntegration: false,
      contextIsolation: true,
    },
  })

  // Window control IPC
  ipcMain.on('window:minimize', () => win.minimize())
  ipcMain.on('window:maximize', () => {
    if (win.isMaximized()) win.unmaximize()
    else win.maximize()
  })
  ipcMain.on('window:close', () => win.close())

  win.loadURL(`http://127.0.0.1:${PORT}/`)

  win.webContents.on('did-fail-load', (_e, code, desc, url) => {
    console.error(`FAIL: ${url} — ${desc} (${code})`)
  })

  win.webContents.setWindowOpenHandler(({ url }) => {
    try { const u = new URL(url); if (u.protocol === 'https:' || u.protocol === 'http:') shell.openExternal(url) }
    catch { /* invalid URL, ignore */ }
    return { action: 'deny' }
  })
}

// ── Auto-update ────────────────────────────────────────────────────

autoUpdater.on('update-available', (info) => {
  console.log('Update available — downloading...')
  win?.webContents.send('update:status', { type: 'available', version: info.version })
})

autoUpdater.on('update-not-available', () => {
  console.log('App is up to date')
})

autoUpdater.on('download-progress', (progress) => {
  win?.webContents.send('update:status', {
    type: 'downloading',
    version: autoUpdater.currentVersion?.version ?? '',
    percent: progress.percent,
    bytesPerSecond: progress.bytesPerSecond,
  })
})

autoUpdater.on('update-downloaded', (info) => {
  console.log('Update downloaded — will install on quit')
  win?.webContents.send('update:status', { type: 'ready', version: info.version })
})

ipcMain.on('app:restart-and-install', () => {
  autoUpdater.quitAndInstall()
})

// ── App lifecycle ──────────────────────────────────────────────────

app.whenReady().then(async () => {
  try {
    await startServer()
  } catch (err) {
    console.error('Failed to start server:', err)
    app.quit()
    return
  }
  createWindow()

  // Check for updates (only works in packaged app)
  if (app.isPackaged) {
    autoUpdater.checkForUpdatesAndNotify()
  }
})

app.on('window-all-closed', () => {
  app.quit()
})
