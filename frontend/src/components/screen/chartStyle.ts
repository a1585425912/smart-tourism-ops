// 大屏图表的统一视觉参数(深色冰雪主题)。
import type { EChartsOption } from 'echarts'

export const SCREEN_COLORS = ['#5ef0ff', '#36e3a0', '#ffb547', '#b48cff', '#2e9bff', '#ff7aa8']

export const axisStyle = {
  axisLine: { lineStyle: { color: 'rgba(127, 166, 201, 0.35)' } },
  axisTick: { show: false },
  axisLabel: { color: '#7fa6c9', fontSize: 11 },
  splitLine: { lineStyle: { color: 'rgba(127, 166, 201, 0.12)', type: 'dashed' as const } },
}

export const tooltipStyle: EChartsOption['tooltip'] = {
  trigger: 'axis',
  backgroundColor: 'rgba(6, 20, 40, 0.92)',
  borderColor: 'rgba(94, 200, 242, 0.4)',
  textStyle: { color: '#d8f1ff', fontSize: 12 },
}

export const legendStyle = {
  textStyle: { color: '#9fc3e0', fontSize: 11 },
  itemWidth: 12,
  itemHeight: 6,
  top: 0,
  right: 0,
}

export function areaGradient(color: string, alpha = 0.35) {
  const toRgba = (hex: string, a: number) => {
    const h = hex.replace('#', '')
    const [r, g, b] = [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16))
    return `rgba(${r}, ${g}, ${b}, ${a})`
  }
  return {
    type: 'linear' as const,
    x: 0,
    y: 0,
    x2: 0,
    y2: 1,
    colorStops: [
      { offset: 0, color: toRgba(color, alpha) },
      { offset: 1, color: toRgba(color, 0) },
    ],
  }
}
