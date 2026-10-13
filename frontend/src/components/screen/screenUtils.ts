// 指挥大屏的纯函数工具(无 DOM 依赖, 便于单元测试)。
import type { DataMeta } from '@/api/types'

export const DESIGN_W = 1920
export const DESIGN_H = 1080

/** 把固定设计画布等比缩放进窗口, 居中留黑边。 */
export function fitScale(winW: number, winH: number, designW = DESIGN_W, designH = DESIGN_H) {
  if (winW <= 0 || winH <= 0) return { scale: 1, offsetX: 0, offsetY: 0 }
  const scale = Math.min(winW / designW, winH / designH)
  return {
    scale,
    offsetX: Math.round((winW - designW * scale) / 2),
    offsetY: Math.round((winH - designH * scale) / 2),
  }
}

export const DEFAULT_BUILDING_HEIGHT = 9
export const METERS_PER_LEVEL = 3.2

/** OSM 建筑高度: 优先 height 标签, 其次 building:levels × 3.2 m, 缺省 9 m; 限制在 3–300 m。 */
export function buildingHeight(props: Record<string, unknown> | null | undefined): number {
  const raw = props?.['height']
  let h: number | null = null
  if (typeof raw === 'number') h = raw
  else if (typeof raw === 'string') {
    const m = raw.trim().match(/^(\d+(?:\.\d+)?)/)
    if (m) h = Number(m[1])
  }
  if (h === null || !Number.isFinite(h) || h <= 0) {
    const levelsRaw = props?.['building:levels']
    const levels = typeof levelsRaw === 'number' ? levelsRaw : Number(String(levelsRaw ?? '').trim())
    h = Number.isFinite(levels) && levels > 0 ? levels * METERS_PER_LEVEL : DEFAULT_BUILDING_HEIGHT
  }
  return Math.min(300, Math.max(3, Math.round(h * 10) / 10))
}

export type Level = 'LOW' | 'NORMAL' | 'WARN' | 'FULL' | 'UNKNOWN'

export const LEVEL_COLORS: Record<Level, string> = {
  LOW: '#2ec5ff',
  NORMAL: '#36e3a0',
  WARN: '#ffb547',
  FULL: '#ff4d6d',
  UNKNOWN: '#6b7a90',
}

export const LEVEL_LABELS: Record<Level, string> = {
  LOW: '舒适', NORMAL: '正常', WARN: '预警', FULL: '饱和', UNKNOWN: '无数据',
}

export function levelColor(level: string | null | undefined): string {
  return LEVEL_COLORS[(level ?? 'UNKNOWN') as Level] ?? LEVEL_COLORS.UNKNOWN
}

/** 区域热力柱高度(米): 无数据时为低矮占位, 承载率越高越高。 */
export function zoneExtrusionHeight(ratio: number | null | undefined): number {
  if (ratio === null || ratio === undefined || !Number.isFinite(ratio)) return 12
  const r = Math.min(1.5, Math.max(0, ratio))
  return Math.round(20 + r * 160)
}

/** 冰蓝渐变: 建筑高度 → 颜色。 */
export const HEIGHT_COLOR_STOPS: [number, string][] = [
  [0, '#16304f'],
  [15, '#1d4f7a'],
  [40, '#2a7fb0'],
  [80, '#59c2e6'],
  [150, '#bff0ff'],
]

export function heightColor(h: number): string {
  const stops = HEIGHT_COLOR_STOPS
  if (h <= stops[0][0]) return stops[0][1]
  for (let i = 1; i < stops.length; i++) {
    const [h1, c1] = stops[i]
    const [h0, c0] = stops[i - 1]
    if (h <= h1) return mixHex(c0, c1, (h - h0) / (h1 - h0))
  }
  return stops[stops.length - 1][1]
}

export function mixHex(a: string, b: string, t: number): string {
  const pa = parseHex(a)
  const pb = parseHex(b)
  const k = Math.min(1, Math.max(0, t))
  const c = pa.map((v, i) => Math.round(v + (pb[i] - v) * k))
  return '#' + c.map((v) => v.toString(16).padStart(2, '0')).join('')
}

function parseHex(hex: string): number[] {
  const h = hex.replace('#', '')
  return [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16))
}

/** 多个来源元信息合并后的数据模式, 用于面板角标。 */
export function mergeMode(metas: (DataMeta | null | undefined)[]): 'REAL' | 'DEMO' | 'MIXED' | null {
  const modes = metas.filter((m): m is DataMeta => !!m).map((m) => m.dataMode)
  if (modes.length === 0) return null
  if (modes.includes('MIXED')) return 'MIXED'
  const set = new Set(modes)
  if (set.size > 1) return 'MIXED'
  return modes[0]
}

/** 全部为 null 时返回 null(不把缺失当 0), 否则对非空值求和。 */
export function sumNullable(values: (number | null | undefined)[]): number | null {
  let seen = false
  let total = 0
  for (const v of values) {
    if (v !== null && v !== undefined && Number.isFinite(v)) {
      seen = true
      total += v
    }
  }
  return seen ? total : null
}

type Coords = number | Coords[]

/** 计算 GeoJSON 几何集合的经纬度包围盒 [minLon, minLat, maxLon, maxLat]。 */
export function bboxOf(geoms: ({ coordinates: unknown } | null | undefined)[]): [number, number, number, number] | null {
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity
  const walk = (c: Coords) => {
    if (Array.isArray(c) && typeof c[0] === 'number') {
      const [x, y] = c as number[]
      if (x < minX) minX = x
      if (y < minY) minY = y
      if (x > maxX) maxX = x
      if (y > maxY) maxY = y
    } else if (Array.isArray(c)) {
      c.forEach((n) => walk(n))
    }
  }
  geoms.forEach((g) => g && walk(g.coordinates as Coords))
  return Number.isFinite(minX) ? [minX, minY, maxX, maxY] : null
}

/** WMO 天气代码 → 中文。 */
export function weatherText(code: number | null | undefined): string {
  if (code === null || code === undefined) return '—'
  if (code === 0) return '晴'
  if (code <= 2) return '少云'
  if (code === 3) return '阴'
  if (code === 45 || code === 48) return '雾'
  if (code >= 51 && code <= 57) return '毛毛雨'
  if (code >= 61 && code <= 67) return '雨'
  if (code >= 71 && code <= 77) return '雪'
  if (code >= 80 && code <= 82) return '阵雨'
  if (code >= 85 && code <= 86) return '阵雪'
  if (code >= 95) return '雷暴'
  return '—'
}

export function easeOutCubic(t: number): number {
  const k = Math.min(1, Math.max(0, t))
  return 1 - Math.pow(1 - k, 3)
}

export function prefersReducedMotion(): boolean {
  try {
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches
  } catch {
    return false
  }
}

const SHANGHAI_OFFSET_MS = 8 * 3600 * 1000

/** 业务日(Asia/Shanghai)日期字符串, 与浏览器所在时区无关。 */
export function shanghaiDate(ms: number): string {
  return new Date(ms + SHANGHAI_OFFSET_MS).toISOString().slice(0, 10)
}

/** 某业务日 00:00 的带时区时间, 如 2026-02-18T00:00:00+08:00。 */
export function dayStartIso(date: string): string {
  return `${date}T00:00:00+08:00`
}

export function addDays(date: string, days: number): string {
  const d = new Date(`${date}T00:00:00Z`)
  d.setUTCDate(d.getUTCDate() + days)
  return d.toISOString().slice(0, 10)
}

/** 业务时区下的小时(0-23)。 */
export function shanghaiHour(iso: string): number {
  return new Date(new Date(iso).getTime() + SHANGHAI_OFFSET_MS).getUTCHours()
}
