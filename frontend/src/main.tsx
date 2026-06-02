import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import App from './App'
import './index.css'

// Recover gracefully from a lazy-loaded chunk that can't be fetched — e.g. an
// open tab navigating during the brief service-restart window of a deploy, or a
// chunk hash that changed after a redeploy. Vite fires `vite:preloadError` when
// a dynamic import() fails; a one-shot reload pulls the fresh index + chunks so
// the user sees a flicker instead of a hard "Failed to fetch module" crash.
window.addEventListener('vite:preloadError', (e) => {
  const KEY = 'vitePreloadReloadedAt'
  const last = Number(sessionStorage.getItem(KEY) || 0)
  // Guard against reload loops (e.g. server genuinely down): at most once / 10s.
  if (Date.now() - last > 10_000) {
    sessionStorage.setItem(KEY, String(Date.now()))
    e.preventDefault()
    window.location.reload()
  }
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter basename={import.meta.env.VITE_BASE || '/investory'}>
      <App />
    </BrowserRouter>
  </StrictMode>,
)
