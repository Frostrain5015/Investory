import { useEffect, useRef } from 'react'

const COUNT = 260
const RED = '#ef4444'
const GREEN = '#22c55e'

interface Particle {
  x: number; y: number; vx: number; vy: number
  baseX: number; baseY: number
  color: string; size: number; alpha: number
}

export default function HeroParticles() {
  const ref = useRef<HTMLCanvasElement>(null)

  useEffect(() => {
    const c = ref.current; if (!c) return
    const ctx = c.getContext('2d'); if (!ctx) return
    let w = 0, h = 0, particles: Particle[] = [], phase = 0, anim = 0

    const resize = () => {
      w = c.width = c.parentElement!.clientWidth
      h = c.height = c.parentElement!.clientHeight
      const cols = Math.max(16, Math.floor(w / 70))
      const rows = Math.max(10, Math.floor(h / 60))
      const gapX = w / (cols + 1)
      const gapY = h / (rows + 1)
      particles = []
      for (let i = 0; i < COUNT; i++) {
        const col = i % cols
        const row = Math.floor(i / cols) % rows
        particles.push({
          x: Math.random() * w, y: Math.random() * h,
          vx: (Math.random() - 0.5) * 0.5, vy: (Math.random() - 0.5) * 0.3,
          baseX: (col + 1) * gapX + (Math.random() - 0.5) * 12,
          baseY: (row + 1) * gapY + (Math.random() - 0.5) * 50,
          color: Math.random() > 0.5 ? RED : GREEN,
          size: 0.8 + Math.random() * 2.2,
          alpha: 0.25 + Math.random() * 0.45,
        })
      }
    }
    const draw = () => {
      ctx.clearRect(0, 0, w, h)
      phase += 0.0025
      const cluster = Math.sin(phase * 1.6) * 0.5 + 0.5
      ctx.shadowBlur = 0
      for (const p of particles) {
        const rx = Math.sin(phase * 2.3 + p.baseY * 0.008) * 15
        const ry = Math.cos(phase * 1.7 + p.baseX * 0.006) * 6
        const tx = p.baseX + rx, ty = p.baseY + ry
        p.x += (tx - p.x) * 0.018 + p.vx * (1 - cluster)
        p.y += (ty - p.y) * 0.018 + p.vy * (1 - cluster)
        // Glow halo
        ctx.beginPath(); ctx.arc(p.x, p.y, p.size * 2.5, 0, Math.PI * 2)
        ctx.fillStyle = p.color; ctx.globalAlpha = p.alpha * 0.12; ctx.fill()
        // Core dot
        ctx.beginPath(); ctx.arc(p.x, p.y, p.size, 0, Math.PI * 2)
        ctx.globalAlpha = p.alpha; ctx.fill()
      }
      ctx.globalAlpha = 1
      anim = requestAnimationFrame(draw)
    }
    resize(); window.addEventListener('resize', resize)
    draw()
    return () => { cancelAnimationFrame(anim); window.removeEventListener('resize', resize) }
  }, [])

  return <canvas ref={ref} className="absolute inset-0 pointer-events-none" />
}
