<script setup lang="ts">
import { computed } from 'vue'
import type { EChartsOption } from 'echarts'
import { screenApi, type FlowTrend, type HourlyForecast } from '@/api/screen'
import ScreenPanel from './ScreenPanel.vue'
import EChart from './EChart.vue'
import { useScreen } from './screenContext'
import { usePanel } from './usePanel'
import { perSite } from './screenData'
import { mergeMode, shanghaiHour } from './screenUtils'
import { areaGradient, axisStyle, legendStyle, SCREEN_COLORS, tooltipStyle } from './chartStyle'
import { watch } from 'vue'

/** 24 小时在园人数趋势 + 当日逐小时预测叠加。 */
const ctx = useScreen()

interface Row {
  trend: FlowTrend | null
  forecast: HourlyForecast | null
}

const { data, loading, error, reload } = usePanel(
  () =>
    perSite<Row>(ctx.siteIds.value, async (siteId) => {
      const r = ctx.range.value
      const [t, f] = await Promise.allSettled([
        screenApi.flowTrend({ siteId, metric: 'IN_PARK', granularity: 'HOUR', start: r.start, end: r.end }),
        screenApi.hourlyForecast(siteId, r.date),
      ])
      if (t.status === 'rejected' && f.status === 'rejected') throw t.reason
      return { trend: t.status === 'fulfilled' ? t.value : null, forecast: f.status === 'fulfilled' ? f.value : null }
    }),
  [ctx.siteIds],
)
watch(ctx.tick, reload, { immediate: true })

const hours = Array.from({ length: 24 }, (_, i) => i)
const mode = computed(() => mergeMode((data.value ?? []).flatMap((r) => [r.data.trend?.meta, r.data.forecast?.meta])))
const empty = computed(
  () => !!data.value && data.value.every((r) => !(r.data.trend?.points.length || r.data.forecast?.points.length)),
)
const hasGaps = computed(() => (data.value ?? []).some((r) => (r.data.trend?.gaps.length ?? 0) > 0))

const option = computed<EChartsOption>(() => {
  const rows = data.value ?? []
  const multi = rows.length > 1
  const series: NonNullable<EChartsOption['series']> = []
  rows.forEach((r, i) => {
    const color = SCREEN_COLORS[i % SCREEN_COLORS.length]
    const name = ctx.siteName(r.siteId)
    // 缺失小时保持 null, 折线断开, 不补 0
    const actual: (number | null)[] = hours.map(() => null)
    r.data.trend?.points.forEach((p) => (actual[shanghaiHour(p.t)] = p.value))
    const predicted: (number | null)[] = hours.map(() => null)
    r.data.forecast?.points.forEach((p) => (predicted[shanghaiHour(p.t)] = p.predictedInPark))
    series.push({
      name: multi ? name : '在园人数',
      type: 'line', smooth: true, showSymbol: false, data: actual, connectNulls: false,
      lineStyle: { color, width: 2.5, shadowColor: color, shadowBlur: 8 },
      itemStyle: { color },
      areaStyle: multi ? undefined : { color: areaGradient(color) },
    })
    if (r.data.forecast?.points.length) {
      series.push({
        name: multi ? `${name}·预测` : '预测',
        type: 'line', smooth: true, showSymbol: false, data: predicted,
        lineStyle: { color: multi ? color : '#ffb547', width: 1.5, type: 'dashed' },
        itemStyle: { color: multi ? color : '#ffb547' },
      })
    }
  })
  return {
    color: SCREEN_COLORS,
    grid: { left: 46, right: 12, top: 28, bottom: 22 },
    tooltip: tooltipStyle,
    legend: { ...legendStyle, type: 'scroll' },
    xAxis: { type: 'category', data: hours.map((h) => `${h}时`), boundaryGap: false, ...axisStyle, axisLabel: { ...axisStyle.axisLabel, interval: 2 } },
    yAxis: { type: 'value', ...axisStyle, axisLine: { show: false } },
    series,
  }
})
</script>

<template>
  <ScreenPanel title="在园人数趋势" sub="今日逐小时 · 虚线为预测" :mode="mode" :loading="loading" :error="data ? null : error" :empty="empty" @retry="reload">
    <template #extra><span v-if="hasGaps" class="gap">存在数据缺口</span></template>
    <EChart :option="option" />
  </ScreenPanel>
</template>

<style scoped>
.gap { font-size: 11px; color: #ffb547; }
</style>
