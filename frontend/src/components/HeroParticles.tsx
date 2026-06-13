import { useEffect, useRef } from 'react'

const COUNT = 350
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
    let mx = -999, my = -999  // mouse position

    const resize = () => {
      w = c.width = c.parentElement!.clientWidth
      h = c.height = c.parentElement!.clientHeight
      const cols = Math.max(18, Math.floor(w / 65))
      const rows = Math.max(12, Math.floor(h / 55))
      const gapX = w / cols
      const gapY = h / rows
      particles = []
      for (let i = 0; i < COUNT; i++) {
        const col = i % cols
        const row = Math.floor(i / cols) % rows
        particles.push({
          x: Math.random() * w, y: Math.random() * h,
          vx: (Math.random() - 0.5) * 0.5, vy: (Math.random() - 0.5) * 0.3,
          baseX: col * gapX + gapX * 0.5 + (Math.random() - 0.5) * 8,
          baseY: row * gapY + gapY * 0.5 + (Math.random() - 0.5) * 8,
          color: Math.random() > 0.5 ? RED : GREEN,
          size: 0.6 + Math.random() * 1.8,
          alpha: 0.2 + Math.random() * 0.35,
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
        // Mouse repulsion
        const dxm = p.x - mx, dym = p.y - my
        const dist = Math.sqrt(dxm * dxm + dym * dym)
        if (dist < 100 && dist > 0) {
          const force = (1 - dist / 100) * 3.5
          p.x += (dxm / dist) * force
          p.y += (dym / dist) * force
        }
        // Mouse proximity boosts glow
        const near = dist < 100 ? (1 - dist / 100) * 0.35 : 0
        // Glow halo
        ctx.beginPath(); ctx.arc(p.x, p.y, p.size * 2.5, 0, Math.PI * 2)
        ctx.fillStyle = p.color; ctx.globalAlpha = p.alpha * 0.12 + near; ctx.fill()
        // Core dot
        ctx.beginPath(); ctx.arc(p.x, p.y, p.size, 0, Math.PI * 2)
        ctx.globalAlpha = p.alpha + near; ctx.fill()
      }
      ctx.globalAlpha = 1
      anim = requestAnimationFrame(draw)
    }
    const onMove = (e: MouseEvent) => { mx = e.clientX; my = e.clientY }
    resize(); window.addEventListener('resize', resize)
    window.addEventListener('mousemove', onMove)
    draw()
    return () => {
      cancelAnimationFrame(anim)
      window.removeEventListener('resize', resize)
      window.removeEventListener('mousemove', onMove)
    }
  }, [])

  return <canvas ref={ref} className="absolute inset-0 pointer-events-none" />
}
