import { describe, expect, it } from 'vitest'
import {
  addDays, bboxOf, buildingHeight, dayStartIso, fitScale, heightColor, levelColor, LEVEL_COLORS, mergeMode, mixHex,
  shanghaiDate, shanghaiHour, sumNullable, weatherText, zoneExtrusionHeight,
} from './screenUtils'
import { perSite, pct, signedPct, waitColor, waitOf } from './screenData'
import type { DataMeta } from '@/api/types'

const meta = (dataMode: DataMeta['dataMode']): DataMeta => ({ dataAsOf: null, sourceIds: [], dataMode, quality: 'GOOD' })

describe('fitScale', () => {
  it('fills an exact 16:9 window', () => {
    expect(fitScale(1920, 1080)).toEqual({ scale: 1, offsetX: 0, offsetY: 0 })
    expect(fitScale(3840, 2160).scale).toBe(2)
  })
  it('letterboxes wider and taller windows', () => {
    const wide = fitScale(2560, 1080)
    expect(wide.scale).toBe(1)
    expect(wide.offsetX).toBe(320)
    expect(wide.offsetY).toBe(0)
    const tall = fitScale(960, 1080)
    expect(tall.scale).toBe(0.5)
    expect(tall.offsetX).toBe(0)
    expect(tall.offsetY).toBe(270)
  })
  it('is safe for a zero-size window', () => {
    expect(fitScale(0, 0).scale).toBe(1)
  })
})

describe('buildingHeight', () => {
  it('prefers the height tag, including units', () => {
    expect(buildingHeight({ height: '24.5 m', 'building:levels': '2' })).toBe(24.5)
    expect(buildingHeight({ height: 30 })).toBe(30)
  })
  it('derives from levels at 3.2 m per level', () => {
    expect(buildingHeight({ 'building:levels': '5' })).toBe(16)
    expect(buildingHeight({ 'building:levels': 10 })).toBe(32)
  })
  it('falls back to 9 m and clamps extremes', () => {
    expect(buildingHeight({})).toBe(9)
    expect(buildingHeight(null)).toBe(9)
    expect(buildingHeight({ height: 'tall' })).toBe(9)
    expect(buildingHeight({ height: '1' })).toBe(3)
    expect(buildingHeight({ height: '900' })).toBe(300)
  })
})

describe('colour scales', () => {
  it('maps load levels, unknown included', () => {
    expect(levelColor('FULL')).toBe(LEVEL_COLORS.FULL)
    expect(levelColor(null)).toBe(LEVEL_COLORS.UNKNOWN)
    expect(levelColor('BOGUS')).toBe(LEVEL_COLORS.UNKNOWN)
  })
  it('interpolates building colour by height', () => {
    expect(heightColor(0)).toBe('#16304f')
    expect(heightColor(1000)).toBe('#bff0ff')
    expect(heightColor(7.5)).toBe(mixHex('#16304f', '#1d4f7a', 0.5))
    expect(mixHex('#000000', '#ffffff', 0.5)).toBe('#808080')
  })
  it('grows zone extrusion with ratio and keeps missing data low', () => {
    expect(zoneExtrusionHeight(null)).toBe(12)
    expect(zoneExtrusionHeight(0)).toBe(20)
    expect(zoneExtrusionHeight(1)).toBe(180)
    expect(zoneExtrusionHeight(9)).toBe(zoneExtrusionHeight(1.5))
    expect(zoneExtrusionHeight(0.9)).toBeGreaterThan(zoneExtrusionHeight(0.3))
  })
})

describe('data helpers', () => {
  it('merges data modes', () => {
    expect(mergeMode([])).toBeNull()
    expect(mergeMode([null, undefined])).toBeNull()
    expect(mergeMode([meta('DEMO'), meta('DEMO')])).toBe('DEMO')
    expect(mergeMode([meta('REAL'), meta('DEMO')])).toBe('MIXED')
    expect(mergeMode([meta('REAL'), meta('MIXED')])).toBe('MIXED')
  })
  it('never turns missing values into zero', () => {
    expect(sumNullable([null, undefined])).toBeNull()
    expect(sumNullable([null, 3, 4])).toBe(7)
    expect(sumNullable([0])).toBe(0)
    expect(pct(null)).toBe('—')
    expect(pct(0.256, 1)).toBe('25.6%')
    expect(signedPct(0.1)).toBe('+10.0%')
    expect(signedPct(-0.052)).toBe('-5.2%')
    expect(signedPct(null)).toBe('—')
  })
  it('labels measured vs estimated queue waits', () => {
    expect(waitOf({ measuredWaitMin: 12, estimatedWaitMin: 30 })).toEqual({ value: 12, kind: '实测' })
    expect(waitOf({ measuredWaitMin: null, estimatedWaitMin: 30 })).toEqual({ value: 30, kind: '估算' })
    expect(waitOf({ measuredWaitMin: null, estimatedWaitMin: null })).toEqual({ value: null, kind: null })
    expect(waitColor(null)).toBe('#6b7a90')
    expect(waitColor(75)).toBe('#ff4d6d')
  })
  it('computes a bounding box across geometries', () => {
    expect(bboxOf([
      { coordinates: [126.6, 45.78] },
      { coordinates: [[[[126.55, 45.77], [126.57, 45.79], [126.55, 45.77]]]] },
      null,
    ])).toEqual([126.55, 45.77, 126.6, 45.79])
    expect(bboxOf([])).toBeNull()
  })
  it('maps WMO weather codes', () => {
    expect(weatherText(0)).toBe('晴')
    expect(weatherText(73)).toBe('雪')
    expect(weatherText(null)).toBe('—')
  })
})

describe('business-day helpers (Asia/Shanghai)', () => {
  it('derives the business date independent of the browser zone', () => {
    expect(shanghaiDate(Date.parse('2026-02-17T16:30:00Z'))).toBe('2026-02-18')
    expect(shanghaiDate(Date.parse('2026-02-17T15:59:00Z'))).toBe('2026-02-17')
  })
  it('builds half-open day ranges', () => {
    expect(dayStartIso('2026-02-18')).toBe('2026-02-18T00:00:00+08:00')
    expect(addDays('2026-02-28', 1)).toBe('2026-03-01')
    expect(addDays('2026-01-01', -1)).toBe('2025-12-31')
    expect(shanghaiHour('2026-02-18T14:00:00+08:00')).toBe(14)
    expect(shanghaiHour('2026-02-17T16:00:00Z')).toBe(0)
  })
})

describe('perSite', () => {
  it('keeps successful sites when one fails', async () => {
    const out = await perSite([1, 2, 3], async (id) => {
      if (id === 2) throw new Error('boom')
      return id * 10
    })
    expect(out).toEqual([{ siteId: 1, data: 10 }, { siteId: 3, data: 30 }])
  })
  it('throws when every site fails and is empty for no sites', async () => {
    await expect(perSite([1], async () => { throw new Error('all down') })).rejects.toThrow('all down')
    expect(await perSite([], async () => 1)).toEqual([])
  })
})
