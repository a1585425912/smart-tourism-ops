// 大屏跨景区取数辅助。

export interface SiteResult<T> {
  siteId: number
  data: T
}

/**
 * 对范围内每个景区各调用一次接口。单个景区失败不影响其它景区;
 * 全部失败时抛出第一个错误, 让面板显示失败状态。
 */
export async function perSite<T>(ids: number[], fn: (siteId: number) => Promise<T>): Promise<SiteResult<T>[]> {
  const settled = await Promise.allSettled(ids.map((id) => fn(id)))
  const ok: SiteResult<T>[] = []
  let firstError: unknown = null
  settled.forEach((r, i) => {
    if (r.status === 'fulfilled') ok.push({ siteId: ids[i], data: r.value })
    else if (firstError === null) firstError = r.reason
  })
  if (ok.length === 0 && firstError !== null) throw firstError
  return ok
}

/** 排队时长取值: 有实测用实测, 否则用估算; 返回值与口径标签。 */
export function waitOf(q: { measuredWaitMin: number | null; estimatedWaitMin: number | null }): {
  value: number | null
  kind: '实测' | '估算' | null
} {
  if (q.measuredWaitMin !== null && q.measuredWaitMin !== undefined) return { value: q.measuredWaitMin, kind: '实测' }
  if (q.estimatedWaitMin !== null && q.estimatedWaitMin !== undefined) return { value: q.estimatedWaitMin, kind: '估算' }
  return { value: null, kind: null }
}

export function waitColor(min: number | null): string {
  if (min === null) return '#6b7a90'
  if (min >= 60) return '#ff4d6d'
  if (min >= 30) return '#ffb547'
  return '#36e3a0'
}

export function pct(v: number | null | undefined, digits = 0): string {
  if (v === null || v === undefined || !Number.isFinite(v)) return '—'
  return (v * 100).toFixed(digits) + '%'
}

/** 带正负号的变化率, 如 +12.3%。 */
export function signedPct(v: number | null | undefined): string {
  if (v === null || v === undefined || !Number.isFinite(v)) return '—'
  return (v > 0 ? '+' : '') + (v * 100).toFixed(1) + '%'
}

export function money(v: number | null | undefined): string {
  if (v === null || v === undefined || !Number.isFinite(v)) return '—'
  if (Math.abs(v) >= 1e8) return '¥' + (v / 1e8).toFixed(2) + '亿'
  if (Math.abs(v) >= 1e4) return '¥' + (v / 1e4).toFixed(1) + '万'
  return '¥' + v.toFixed(0)
}
