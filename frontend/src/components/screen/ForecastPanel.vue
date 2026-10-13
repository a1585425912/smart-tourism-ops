<script setup lang="ts">
import { computed, watch } from 'vue'
import type { EChartsOption } from 'echarts'
import { screenApi } from '@/api/screen'
import ScreenPanel from './ScreenPanel.vue'
import EChart from './EChart.vue'
import { useScreen } from './screenContext'
import { usePanel } from './usePanel'
import { perSite, pct } from './screenData'
import { mergeMode } from './screenUtils'
import { axisStyle, legendStyle, SCREEN_COLORS, tooltipStyle } from './chartStyle'

/** 未来 7 日入园人次预测(单景区显示预测区间)与回测误差。 */
const ctx = useScreen()
const { data, loading, error, reload } = usePanel(() => perSite(ctx.siteIds.value, (id) => screenApi.dailyForecast(id, 7)), [ctx.siteIds])
watch(ctx.tick, reload, { immediate: true })

const rows = computed(() => (data.value ?? []).filter((r) => r.data.points.length > 0))
const mode = computed(() => mergeMode(rows.value.map((r) => r.data.meta)))
const backtests = computed(() => rows.value.filter((r) => r.data.backtest).map((r) => ({ siteId: r.siteId, b: r.data.backtest! })))

const option = computed<EChartsOption>(() => {
  const list = rows.value
  const dates = list[0]?.data.points.map((p) => p.date) ?? []
  const first = list[0]?.data.points ?? []
  const series: NonNullable<EChartsOption['series']> = []
  if (list.length === 1) {
    // 区间带: 下界(透明)与 上界-下界 堆叠
    series.push(
      { name: '下界', type: 'line', stack: 'band', data: first.map((p) => p.lower), lineStyle: { opacity: 0 }, showSymbol: false, silent: true, tooltip: { show: false } },
      { name: '预测区间', type: 'line', stack: 'band', data: first.map((p) => p.upper - p.lower), lineStyle: { opacity: 0 }, showSymbol: false, silent: true,
        areaStyle: { color: 'rgba(94, 240, 255, 0.16)' }, tooltip: { show: false } },
    )
  }
  list.forEach((r, i) => {
    const color = SCREEN_COLORS[i % SCREEN_COLORS.length]
    series.push({
      name: list.length > 1 ? ctx.siteName(r.siteId) : '预测入园',
      type: 'line', smooth: true, symbolSize: 5, data: r.data.points.map((p) => p.predicted),
      lineStyle: { color, width: 2.2 }, itemStyle: { color },
    })
  })
  return {
    grid: { left: 44, right: 10, top: 26, bottom: 34 },
    tooltip: tooltipStyle,
    legend: { ...legendStyle, data: series.filter((s) => s.name !== '下界' && s.name !== '预测区间').map((s) => String(s.name)) },
    xAxis: {
      type: 'category', data: dates, ...axisStyle,
      axisLabel: {
        ...axisStyle.axisLabel, interval: 0, lineHeight: 13,
        formatter: (v: string, i: number) => `${v.slice(5)}\n${first[i]?.holiday ?? (first[i]?.isOffDay ? '休' : '')}`,
      },
    },
    yAxis: { type: 'value', scale: true, ...axisStyle, axisLine: { show: false } },
    series,
  }
})
</script>

<template>
  <ScreenPanel title="7 日客流预测" sub="入园人次" :mode="mode" :loading="loading" :error="data ? null : error" :empty="!rows.length" empty-text="暂无预测结果" @retry="reload">
    <template #extra>
      <span v-if="backtests.length" class="bt" :title="backtests.map((x) => `${ctx.siteName(x.siteId)} 回测 ${x.b.testStart}~${x.b.testEnd}，基线(${x.b.baselineName}) MAPE ${pct(x.b.baselineMape, 1)}`).join('\n')">
        回测 MAPE
        <template v-for="x in backtests" :key="x.siteId"><b>{{ pct(x.b.mape, 1) }}</b></template>
      </span>
    </template>
    <EChart :option="option" />
  </ScreenPanel>
</template>

<style scoped>
.bt { font-size: 11px; color: #7fa6c9; white-space: nowrap; cursor: help; }
.bt b { margin-left: 4px; color: #5ef0ff; font-variant-numeric: tabular-nums; }
</style>
