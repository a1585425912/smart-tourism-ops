import type { EChartsOption } from 'echarts'

/** 统一配色: 主色、冰蓝、预警、危险、正常, 以及类别色。 */
export const C = {
  primary: '#1f6feb',
  ice: '#5ec8f2',
  warn: '#e6a23c',
  danger: '#e5484d',
  ok: '#2fa86b',
  grey: '#a0aec0',
  text: '#4a5568',
  axis: '#cbd5e1',
  split: '#edf1f6',
}

export const SERIES_COLORS = ['#1f6feb', '#5ec8f2', '#2fa86b', '#e6a23c', '#8b5cf6', '#e5484d', '#14b8a6', '#f472b6', '#64748b']

export const CATEGORY_COLORS: Record<string, string> = {
  HOTEL: '#1f6feb',
  CATERING: '#e6a23c',
  RETAIL: '#2fa86b',
  TICKET: '#5ec8f2',
  ENTERTAINMENT: '#8b5cf6',
}

export const SENTIMENT_COLORS: Record<string, string> = {
  POSITIVE: '#2fa86b',
  NEUTRAL: '#5ec8f2',
  NEGATIVE: '#e5484d',
  MIXED: '#e6a23c',
  UNKNOWN: '#a0aec0',
}

/** 通用坐标轴图表基础配置。 */
export function baseOption(extra: EChartsOption = {}): EChartsOption {
  return {
    color: SERIES_COLORS,
    textStyle: { fontFamily: 'inherit', color: C.text },
    grid: { left: 48, right: 24, top: 36, bottom: 32, containLabel: false },
    tooltip: { trigger: 'axis', confine: true },
    legend: { top: 0, right: 0, icon: 'roundRect', itemWidth: 12, itemHeight: 8, textStyle: { color: C.text } },
    ...extra,
  }
}

export const axisStyle = {
  axisLine: { lineStyle: { color: C.axis } },
  axisTick: { show: false },
  axisLabel: { color: C.text },
  splitLine: { lineStyle: { color: C.split } },
}
