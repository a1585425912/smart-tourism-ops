// 演示模拟画面: 未接入真实视频平台时, 在 canvas 上绘制带"演示画面"水印的程序化场景。
// 不是任何点位的真实画面, 水印始终可见。

interface Walker {
  x: number
  y: number
  vx: number
  vy: number
  size: number
}

export interface FeedOptions {
  seed: number
  title: string
  clock: () => string
  /** 人群密度 0-1 */
  density?: number
  animate?: boolean
}

function rng(seed: number) {
  let s = seed % 2147483647
  if (s <= 0) s += 2147483646
  return () => (s = (s * 16807) % 2147483647) / 2147483647
}

export class SyntheticFeed {
  private ctx: CanvasRenderingContext2D
  private walkers: Walker[] = []
  private sculptures: { x: number; w: number; h: number; hue: number }[] = []
  private raf = 0
  private last = 0
  private frame = 0

  constructor(private canvas: HTMLCanvasElement, private opts: FeedOptions) {
    this.ctx = canvas.getContext('2d')!
    const r = rng(opts.seed * 9973 + 17)
    const n = Math.round(30 + (opts.density ?? 0.5) * 90)
    for (let i = 0; i < n; i++) {
      this.walkers.push({ x: r(), y: 0.45 + r() * 0.55, vx: (r() - 0.5) * 0.04, vy: (r() - 0.5) * 0.012, size: 0.6 + r() * 0.8 })
    }
    const count = 3 + Math.floor(r() * 3)
    for (let i = 0; i < count; i++) {
      this.sculptures.push({ x: 0.08 + (i / count) * 0.9 + r() * 0.05, w: 0.08 + r() * 0.1, h: 0.18 + r() * 0.25, hue: 185 + r() * 30 })
    }
  }

  start() {
    this.stop()
    if (this.opts.animate === false) {
      this.draw(0)
      return
    }
    const loop = (t: number) => {
      // 约 20fps, 节省大屏多路渲染开销
      if (t - this.last > 50) {
        this.draw(Math.min(0.2, (t - this.last) / 1000))
        this.last = t
      }
      this.raf = requestAnimationFrame(loop)
    }
    this.raf = requestAnimationFrame(loop)
  }

  stop() {
    cancelAnimationFrame(this.raf)
  }

  private draw(dt: number) {
    const { canvas, ctx } = this
    const w = canvas.width
    const h = canvas.height
    this.frame++

    const sky = ctx.createLinearGradient(0, 0, 0, h)
    sky.addColorStop(0, '#071528')
    sky.addColorStop(0.45, '#0f2c4a')
    sky.addColorStop(0.46, '#1b3550')
    sky.addColorStop(1, '#2b4a66')
    ctx.fillStyle = sky
    ctx.fillRect(0, 0, w, h)

    // 冰雕轮廓(程序生成的发光几何体)
    for (const s of this.sculptures) {
      const baseY = h * 0.46
      const x = s.x * w
      const sw = s.w * w
      const sh = s.h * h
      const g = ctx.createLinearGradient(0, baseY - sh, 0, baseY)
      g.addColorStop(0, `hsla(${s.hue}, 90%, 75%, 0.85)`)
      g.addColorStop(1, `hsla(${s.hue + 20}, 80%, 45%, 0.35)`)
      ctx.fillStyle = g
      ctx.beginPath()
      ctx.moveTo(x - sw / 2, baseY)
      ctx.lineTo(x - sw / 3, baseY - sh * 0.7)
      ctx.lineTo(x, baseY - sh)
      ctx.lineTo(x + sw / 3, baseY - sh * 0.7)
      ctx.lineTo(x + sw / 2, baseY)
      ctx.closePath()
      ctx.fill()
      ctx.shadowColor = `hsl(${s.hue}, 100%, 70%)`
      ctx.shadowBlur = 12
      ctx.strokeStyle = `hsla(${s.hue}, 100%, 85%, 0.6)`
      ctx.stroke()
      ctx.shadowBlur = 0
    }

    // 地面透视网格
    ctx.strokeStyle = 'rgba(140, 200, 240, 0.12)'
    ctx.lineWidth = 1
    for (let i = 0; i <= 12; i++) {
      const x = (i / 12) * w
      ctx.beginPath()
      ctx.moveTo(w / 2 + (x - w / 2) * 0.15, h * 0.46)
      ctx.lineTo(x, h)
      ctx.stroke()
    }
    for (let i = 1; i <= 6; i++) {
      const y = h * 0.46 + (h * 0.54 * (i * i)) / 36
      ctx.beginPath()
      ctx.moveTo(0, y)
      ctx.lineTo(w, y)
      ctx.stroke()
    }

    // 人群
    for (const p of this.walkers) {
      p.x += p.vx * dt
      p.y += p.vy * dt
      if (p.x < 0) p.x += 1
      if (p.x > 1) p.x -= 1
      if (p.y < 0.47 || p.y > 1) p.vy = -p.vy
      const depth = (p.y - 0.46) / 0.54
      const r = Math.max(1.2, (1.5 + depth * 4) * p.size * (w / 480))
      ctx.fillStyle = `rgba(20, 30, 45, ${0.55 + depth * 0.4})`
      ctx.beginPath()
      ctx.ellipse(p.x * w, p.y * h, r * 0.7, r * 1.6, 0, 0, Math.PI * 2)
      ctx.fill()
      ctx.fillStyle = `rgba(230, 120, 90, ${0.4 + depth * 0.4})`
      ctx.beginPath()
      ctx.arc(p.x * w, p.y * h - r * 1.8, r * 0.55, 0, Math.PI * 2)
      ctx.fill()
    }

    // 扫描线与噪点
    ctx.fillStyle = 'rgba(255,255,255,0.025)'
    for (let y = this.frame % 4; y < h; y += 4) ctx.fillRect(0, y, w, 1)
    const band = ((this.frame * 3) % (h + 60)) - 30
    ctx.fillStyle = 'rgba(160, 220, 255, 0.05)'
    ctx.fillRect(0, band, w, 30)

    // 叠加信息
    const fs = Math.max(10, Math.round(w / 36))
    ctx.font = `${fs}px "Microsoft YaHei", sans-serif`
    ctx.textBaseline = 'top'
    ctx.fillStyle = this.frame % 20 < 12 ? '#ff4d6d' : 'rgba(255,77,109,0.3)'
    ctx.beginPath()
    ctx.arc(fs * 0.9, fs * 1.0, fs * 0.32, 0, Math.PI * 2)
    ctx.fill()
    ctx.fillStyle = 'rgba(230, 246, 255, 0.92)'
    ctx.fillText(this.opts.title, fs * 1.6, fs * 0.45)
    const ts = this.opts.clock()
    ctx.textAlign = 'right'
    ctx.fillText(ts, w - fs * 0.6, fs * 0.45)
    ctx.textAlign = 'center'
    ctx.font = `bold ${Math.round(fs * 1.25)}px "Microsoft YaHei", sans-serif`
    ctx.fillStyle = 'rgba(255, 200, 110, 0.75)'
    ctx.fillText('演示画面 · 非实时视频', w / 2, h - fs * 2.2)
    ctx.textAlign = 'left'
  }
}
