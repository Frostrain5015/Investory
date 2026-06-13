const { app, BrowserWindow, shell, ipcMain, nativeImage, dialog, session } = require('electron')
const { autoUpdater } = require('electron-updater')
const http = require('http')
const fs = require('fs')
const path = require('path')

autoUpdater.logger = require('electron').app.isPackaged ? null : console
autoUpdater.autoDownload = true
autoUpdater.autoInstallOnAppQuit = true

let win = null

const PORT = 18256
const PROTOCOL = 'investory'
const DIST = path.join(__dirname, 'dist')
const ICON = nativeImage.createFromPath(path.join(__dirname, 'assets', 'icon.png'))

app.commandLine.appendSwitch('ignore-certificate-errors')
app.commandLine.appendSwitch('no-proxy-server')

// Windows: associate the running process with the installed app identity so the
// taskbar shows our icon (not the generic Electron one) and groups under the
// NSIS shortcut. Must match the electron-builder `appId`.
if (process.platform === 'win32') app.setAppUserModelId('com.investory.desktop')

// ── Deep-link login (investory://auth?token=…) ─────────────────────
// Frost ID login happens in the system browser; the backend redirects to an
// investory:// deep link carrying a one-time token, which we forward to the
// renderer to exchange for a session inside the app's own cookie jar.

let pendingAuthToken = null

if (process.defaultApp) {
  // Dev (electron .): pass the entry script so OS re-launch resolves correctly.
  if (process.argv.length >= 2) {
    app.setAsDefaultProtocolClient(PROTOCOL, process.execPath, [path.resolve(process.argv[1])])
  }
} else {
  app.setAsDefaultProtocolClient(PROTOCOL)
}

function handleDeepLink(url) {
  let token = null
  try {
    const u = new URL(url)
    if (u.protocol === `${PROTOCOL}:`) token = u.searchParams.get('token')
  } catch { /* invalid URL, ignore */ }
  if (!token) return
  if (win && win.webContents) win.webContents.send('frostid:callback', token)
  else pendingAuthToken = token
}

// Single instance: on Windows a deep link arrives as a fresh launch; hand its
// argv to the already-running instance and exit this one.
if (!app.requestSingleInstanceLock()) {
  app.quit()
  return
}

app.on('second-instance', (_event, argv) => {
  const link = argv.find((a) => typeof a === 'string' && a.startsWith(`${PROTOCOL}://`))
  if (link) handleDeepLink(link)
  if (win) { if (win.isMinimized()) win.restore(); win.focus() }
})

app.on('open-url', (event, url) => { event.preventDefault(); handleDeepLink(url) }) // macOS

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

  // Open external URLs (e.g. Frost ID) in the system browser
  ipcMain.handle('shell:open-external', (_e, url) => {
    try {
      const u = new URL(url)
      if (u.protocol === 'https:' || u.protocol === 'http:') return shell.openExternal(url)
    } catch { /* invalid URL, ignore */ }
  })

  win.loadURL(`http://127.0.0.1:${PORT}/`)

  // Deliver any deep-link token that arrived before the renderer was ready.
  win.webContents.on('did-finish-load', () => {
    if (pendingAuthToken) {
      win.webContents.send('frostid:callback', pendingAuthToken)
      pendingAuthToken = null
    }
  })

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

// The version being downloaded. `autoUpdater.currentVersion` is the *installed*
// version, so progress events must use this instead.
let downloadingVersion = ''

autoUpdater.on('update-available', (info) => {
  console.log('Update available — downloading...')
  downloadingVersion = info.version
  win?.webContents.send('update:status', { type: 'available', version: info.version })
})

autoUpdater.on('update-not-available', () => {
  console.log('App is up to date')
})

autoUpdater.on('download-progress', (progress) => {
  win?.webContents.send('update:status', {
    type: 'downloading',
    version: downloadingVersion,
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
  await session.defaultSession.setProxy({ mode: 'direct' })

  try {
    await startServer()
  } catch (err) {
    console.error('Failed to start server:', err)
    app.quit()
    return
  }
  createWindow()

  // Windows cold-start via deep link: the URL is passed as a launch argument.
  const startLink = process.argv.find((a) => typeof a === 'string' && a.startsWith(`${PROTOCOL}://`))
  if (startLink) handleDeepLink(startLink)

  // Check for updates (only works in packaged app)
  if (app.isPackaged) {
    autoUpdater.checkForUpdatesAndNotify()
  }
})

app.on('window-all-closed', () => {
  app.quit()
})
