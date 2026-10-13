import dayjs from 'dayjs'

export function fmtTime(v: string | null | undefined, pattern = 'MM-DD HH:mm'): string {
  return v ? dayjs(v).format(pattern) : '—'
}

export function fmtDateTime(v: string | null | undefined): string {
  return fmtTime(v, 'YYYY-MM-DD HH:mm')
}

/** 数字格式化; null/undefined 显示占位符而不是 0。 */
export function fmtNum(v: number | null | undefined, digits = 0): string {
  if (v === null || v === undefined || Number.isNaN(v)) return '—'
  return v.toLocaleString('zh-CN', { maximumFractionDigits: digits, minimumFractionDigits: digits })
}

export function fmtPct(v: number | null | undefined, digits = 1): string {
  if (v === null || v === undefined || Number.isNaN(v)) return '—'
  return `${(v * 100).toFixed(digits)}%`
}

export function fmtMoney(v: number | null | undefined, currency = 'CNY'): string {
  if (v === null || v === undefined) return '—'
  const symbol = currency === 'CNY' ? '¥' : currency + ' '
  if (Math.abs(v) >= 1e8) return `${symbol}${(v / 1e8).toFixed(2)}亿`
  if (Math.abs(v) >= 1e4) return `${symbol}${(v / 1e4).toFixed(1)}万`
  return `${symbol}${v.toFixed(2)}`
}

/** 大数字缩写: 12345 -> 1.2万 */
export function fmtCompact(v: number | null | undefined): string {
  if (v === null || v === undefined) return '—'
  if (Math.abs(v) >= 1e4) return `${(v / 1e4).toFixed(1)}万`
  return v.toLocaleString('zh-CN')
}

export const STATUS_LABELS: Record<string, string> = {
  ONLINE: '在线', OFFLINE: '离线', FAULT: '故障', UNKNOWN: '未知',
  OPEN: '开放', CLOSED: '关闭', SEASONAL: '季节性开放',
  QUEUED: '排队中', RUNNING: '执行中', SUCCEEDED: '成功', FAILED: '失败', CANCELLED: '已取消',
  DRAFT: '草稿', ASSIGNED: '已派发', IN_PROGRESS: '执行中', SUBMITTED: '已提交', RECTIFYING: '整改中',
  PENDING_REVIEW: '待复核', COMPLETED: '已完成',
  POSITIVE: '正面', NEUTRAL: '中性', NEGATIVE: '负面', MIXED: '褒贬兼有',
  LOW: '低', NORMAL: '正常', WARN: '预警', FULL: '饱和', HIGH: '高', URGENT: '紧急', MEDIUM: '中',
}

export function label(code: string | null | undefined): string {
  return code ? STATUS_LABELS[code] ?? code : '—'
}
