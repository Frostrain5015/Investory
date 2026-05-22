import { useEffect, useRef } from 'react'

const DATA = [
  '茅台 600519 ¥1420.50 +3.2%', '腾讯 00700 HK$385.00 -1.5%',
  '宁德 300750 ¥186.32 +0.8%', '苹果 AAPL $192.40 +1.1%',
  '英伟达 NVDA $952.40 +4.1%', '比亚迪 002594 ¥268.00 -2.3%',
  '标普500 6,032.18', '恒生指数 21,450.00', '上证指数 3,284.32',
  '阿里巴巴 9988 HK$82.50 -0.8%', '台积电 TSM $178.20 +2.5%',
  '微软 MSFT $448.60 +1.8%', '谷歌 GOOGL $195.30 +0.6%',
]

export default function HeroMatrix() {
  const ref = useRef<HTMLCanvasElement>(null)

  useEffect(() => {
    const c = ref.current; if (!c) return
    const ctx = c.getContext('2d'); if (!ctx) return
    let w = 0, h = 0, anim = 0
    const cols: { x: number; y: number; speed: number; text: string; alpha: number }[] = []
    let mx = -100, my = -100

    const resize = () => {
      w = c.width = c.parentElement!.clientWidth
      h = c.height = c.parentElement!.clientHeight
      cols.length = 0
      const count = Math.max(10, Math.floor(w / 120))
      for (let i = 0; i < count; i++) {
        cols.push({
          x: i * (w / count) + Math.random() * 50,
          y: Math.random() * h - h,
          speed: 0.4 + Math.random() * 1.2,
          text: DATA[Math.floor(Math.random() * DATA.length)],
          alpha: 0.1 + Math.random() * 0.2,
        })
      }
    }
    const draw = () => {
      ctx.clearRect(0, 0, w, h)
      ctx.textBaseline = 'top'
      for (const d of cols) {
        const dx = mx - d.x, dy = my - d.y, dist = Math.sqrt(dx * dx + dy * dy)
        const ripple = dist < 100 ? (1 - dist / 100) * 25 : 0
        d.y += d.speed + (dist < 60 ? -2 : 0)
        if (d.y > h + 30) { d.y = -30; d.text = DATA[Math.floor(Math.random() * DATA.length)] }
        ctx.font = '11px "Inter", ui-monospace'
        const hasUp = d.text.includes('+')
        const color = hasUp ? '209, 65, 65' : d.text.includes('-') ? '34, 197, 94' : '148, 163, 184'
        ctx.fillStyle = `rgba(${color}, ${d.alpha})`
        ctx.fillText(d.text, d.x + ripple * 0.2, d.y)
      }
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

  return <canvas ref={ref} className="absolute inset-0 pointer-events-none" style={{ zIndex: 3 }} />
}
