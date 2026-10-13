import DOMPurify from 'dompurify'
import { marked } from 'marked'
import type { RouteRecordRaw } from 'vue-router'

// 后台通用纯函数, 有对应单元测试(logic.spec.ts)。

export interface MenuItem {
  path: string
  title: string
  icon?: string
}

export interface MenuGroup {
  group: string
  items: MenuItem[]
}

/** 根据权限过滤菜单并按 meta.group 分组, 保持路由声明顺序。隐藏路由不显示。 */
export function buildMenu(routes: RouteRecordRaw[], has: (p: string) => boolean, base = '/admin'): MenuGroup[] {
  const groups: MenuGroup[] = []
  for (const r of routes) {
    const meta = r.meta ?? {}
    if (meta.hidden || !meta.group || !meta.title) continue
    if (meta.permission && !has(meta.permission)) continue
    let g = groups.find((x) => x.group === meta.group)
    if (!g) {
      g = { group: meta.group, items: [] }
      groups.push(g)
    }
    g.items.push({ path: `${base}/${r.path}`, title: meta.title, icon: meta.icon })
  }
  return groups
}

export interface TextSegment {
  text: string
  topic: string | null
}

/**
 * 按证据偏移把原文切成片段, 用于安全高亮(不使用 v-html)。
 * 越界、重叠或空的区间会被裁剪/忽略, 保证片段拼接后与原文一致。
 */
export function splitByEvidence(text: string, spans: { start: number; end: number; topic: string }[]): TextSegment[] {
  const sorted = spans
    .map((s) => ({ start: Math.max(0, Math.min(s.start, text.length)), end: Math.max(0, Math.min(s.end, text.length)), topic: s.topic }))
    .filter((s) => s.end > s.start)
    .sort((a, b) => a.start - b.start)
  const out: TextSegment[] = []
  let cursor = 0
  for (const s of sorted) {
    const start = Math.max(s.start, cursor)
    if (start >= s.end) continue
    if (start > cursor) out.push({ text: text.slice(cursor, start), topic: null })
    out.push({ text: text.slice(start, s.end), topic: s.topic })
    cursor = s.end
  }
  if (cursor < text.length) out.push({ text: text.slice(cursor), topic: null })
  return out
}

const ALLOWED_TAGS = ['p', 'br', 'strong', 'em', 'ul', 'ol', 'li', 'code', 'pre', 'blockquote', 'h3', 'h4', 'h5', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'hr']

/** 模型输出 -> 受限 Markdown HTML。仅保留基本排版标签, 去除链接、图片、脚本与所有属性。 */
export function safeMarkdown(md: string | null | undefined): string {
  if (!md) return ''
  const html = marked.parse(md, { async: false, gfm: true, breaks: true }) as string
  return DOMPurify.sanitize(html, { ALLOWED_TAGS, ALLOWED_ATTR: [] })
}

/** 两个数之比的变化率展示: null 表示不可计算。 */
export function trendText(v: number | null | undefined): { text: string; dir: 'up' | 'down' | 'flat' | 'na' } {
  if (v === null || v === undefined || Number.isNaN(v)) return { text: '不可计算', dir: 'na' }
  const pct = (v * 100).toFixed(1)
  if (Math.abs(v) < 0.0005) return { text: '持平', dir: 'flat' }
  return v > 0 ? { text: `+${pct}%`, dir: 'up' } : { text: `${pct}%`, dir: 'down' }
}

/** 承载率等级 -> 颜色。 */
export function levelColor(level: string | null | undefined): string {
  switch (level) {
    case 'LOW': return '#5ec8f2'
    case 'NORMAL': return '#2fa86b'
    case 'WARN': return '#e6a23c'
    case 'FULL':
    case 'CRITICAL': return '#e5484d'
    default: return '#a0aec0'
  }
}

/** 按等级给出比例颜色(用于热度条), ratio 为 null 时返回灰色。 */
export function ratioColor(ratio: number | null | undefined, warnRatio = 0.8): string {
  if (ratio === null || ratio === undefined) return '#a0aec0'
  if (ratio >= 1) return '#e5484d'
  if (ratio >= warnRatio) return '#e6a23c'
  if (ratio < 0.3) return '#5ec8f2'
  return '#2fa86b'
}

/** 根据 WMO 天气代码返回中文描述。 */
export function weatherText(code: number | null | undefined): string {
  if (code === null || code === undefined) return '—'
  if (code === 0) return '晴'
  if (code <= 3) return '多云'
  if (code <= 48) return '雾'
  if (code <= 57) return '毛毛雨'
  if (code <= 67) return '雨'
  if (code <= 77) return '雪'
  if (code <= 82) return '阵雨'
  if (code <= 86) return '阵雪'
  return '雷暴'
}

/** 计算日期区间预设: 返回 [start, end) 的 ISO 字符串(按业务时钟"今天")。 */
export function dayRange(todayIso: string, days: number, tz = '+08:00'): { start: string; end: string } {
  const end = new Date(`${todayIso}T00:00:00${tz}`)
  end.setUTCDate(end.getUTCDate() + 1)
  const start = new Date(end)
  start.setUTCDate(start.getUTCDate() - days)
  const iso = (d: Date) => {
    const local = new Date(d.getTime() + 8 * 3600 * 1000)
    return `${local.toISOString().slice(0, 10)}T00:00:00${tz}`
  }
  return { start: iso(start), end: iso(end) }
}

/** 遮蔽手机号(兜底, 后端已脱敏)。 */
export function maskPhone(v: string | null | undefined): string {
  if (!v) return '—'
  return v.replace(/(\d{3})\d{4}(\d{4})/, '$1****$2')
}
