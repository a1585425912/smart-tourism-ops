import { describe, expect, it } from 'vitest'
import { buildMenu, dayRange, maskPhone, ratioColor, safeMarkdown, splitByEvidence, trendText } from './logic'

describe('buildMenu', () => {
  const routes = [
    { path: 'overview', component: {}, meta: { title: '运营总览', group: '综合态势' } },
    { path: 'sites', component: {}, meta: { title: '景区', group: '综合态势', permission: 'SITE_READ' } },
    { path: 'reviews/:id', component: {}, meta: { title: '详情', hidden: true, permission: 'REVIEW_READ' } },
    { path: 'users', component: {}, meta: { title: '用户', group: '系统管理', permission: 'USER_MANAGE' } },
  ]

  it('filters by permission and groups in order', () => {
    const menu = buildMenu(routes as never, (p) => p === 'SITE_READ')
    expect(menu).toEqual([
      { group: '综合态势', items: [
        { path: '/admin/overview', title: '运营总览', icon: undefined },
        { path: '/admin/sites', title: '景区', icon: undefined },
      ] },
    ])
  })

  it('drops hidden routes and empty groups', () => {
    const menu = buildMenu(routes as never, () => true)
    expect(menu.map((g) => g.group)).toEqual(['综合态势', '系统管理'])
    expect(menu.flatMap((g) => g.items).some((i) => i.path.includes(':id'))).toBe(false)
  })
})

describe('splitByEvidence', () => {
  it('rebuilds the original text exactly', () => {
    const text = '排队两个小时，冰雕很美'
    const segs = splitByEvidence(text, [{ start: 0, end: 6, topic: 'QUEUE' }, { start: 7, end: 11, topic: 'SCENERY' }])
    expect(segs.map((s) => s.text).join('')).toBe(text)
    expect(segs.filter((s) => s.topic).map((s) => s.topic)).toEqual(['QUEUE', 'SCENERY'])
  })

  it('clips out-of-range and overlapping spans', () => {
    const text = 'abcdef'
    const segs = splitByEvidence(text, [{ start: 4, end: 99, topic: 'B' }, { start: 1, end: 5, topic: 'A' }, { start: 3, end: 3, topic: 'X' }])
    expect(segs.map((s) => s.text).join('')).toBe(text)
    expect(segs).toEqual([
      { text: 'a', topic: null },
      { text: 'bcde', topic: 'A' },
      { text: 'f', topic: 'B' },
    ])
  })
})

describe('safeMarkdown', () => {
  it('keeps basic formatting', () => {
    expect(safeMarkdown('**重点** 内容')).toContain('<strong>重点</strong>')
  })

  it('removes scripts, links, images and attributes', () => {
    const html = safeMarkdown('<script>alert(1)</script>[链接](http://x.y) ![i](http://x/i.png) <b onclick="x">b</b>')
    expect(html).not.toContain('<script')
    expect(html).not.toContain('<a')
    expect(html).not.toContain('<img')
    expect(html).not.toContain('onclick')
  })

  it('handles empty input', () => {
    expect(safeMarkdown(null)).toBe('')
  })
})

describe('trendText', () => {
  it('reports null as not computable rather than zero', () => {
    expect(trendText(null)).toEqual({ text: '不可计算', dir: 'na' })
    expect(trendText(0.123).text).toBe('+12.3%')
    expect(trendText(-0.05).dir).toBe('down')
  })
})

describe('misc', () => {
  it('ratioColor follows thresholds', () => {
    expect(ratioColor(null)).toBe('#a0aec0')
    expect(ratioColor(1.2)).toBe('#e5484d')
    expect(ratioColor(0.85)).toBe('#e6a23c')
  })

  it('dayRange returns half-open business-day range', () => {
    expect(dayRange('2026-02-18', 1)).toEqual({ start: '2026-02-18T00:00:00+08:00', end: '2026-02-19T00:00:00+08:00' })
    expect(dayRange('2026-03-01', 7).start).toBe('2026-02-23T00:00:00+08:00')
  })

  it('maskPhone masks middle digits', () => {
    expect(maskPhone('13812345678')).toBe('138****5678')
    expect(maskPhone(null)).toBe('—')
  })
})
