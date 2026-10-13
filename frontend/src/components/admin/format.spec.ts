import { describe, expect, it } from 'vitest'
import { fmtCompact, fmtMoney, fmtNum, fmtPct, label } from '@/utils/format'

// 缺失值必须显示占位符而不是 0
describe('shared formatters', () => {
  it('render null as placeholder, not zero', () => {
    expect(fmtNum(null)).toBe('—')
    expect(fmtNum(undefined)).toBe('—')
    expect(fmtPct(null)).toBe('—')
    expect(fmtMoney(null)).toBe('—')
    expect(fmtCompact(null)).toBe('—')
  })

  it('format real zero as zero', () => {
    expect(fmtNum(0)).toBe('0')
    expect(fmtPct(0)).toBe('0.0%')
  })

  it('format money with units', () => {
    expect(fmtMoney(123456)).toBe('¥12.3万')
    expect(fmtMoney(250000000)).toBe('¥2.50亿')
  })

  it('labels known codes and passes through unknown', () => {
    expect(label('PENDING_REVIEW')).toBe('待复核')
    expect(label('SOMETHING')).toBe('SOMETHING')
    expect(label(null)).toBe('—')
  })
})
