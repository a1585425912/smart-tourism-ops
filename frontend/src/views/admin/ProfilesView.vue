<script setup lang="ts">
import { computed, ref } from 'vue'
import type { EChartsOption } from 'echarts'
import { opsApi, type ProfileDimension } from '@/api/ops'
import { useAppStore } from '@/stores/app'
import StatePanel from '@/components/common/StatePanel.vue'
import DataMetaBar from '@/components/common/DataMetaBar.vue'
import KpiTile from '@/components/admin/KpiTile.vue'
import EChart from '@/components/charts/EChart.vue'
import { axisStyle, baseOption, C, SERIES_COLORS } from '@/components/charts/palette'
import { useAsync } from '@/components/admin/composables'
import { fmtNum, fmtPct } from '@/utils/format'

const app = useAppStore()
const siteId = computed(() => app.currentSite?.id ?? null)
const month = ref(app.now().subtract(1, 'month').format('YYYY-MM'))

const profile = useAsync(async () => (siteId.value && month.value ? opsApi.profile(siteId.value, month.value) : null), [siteId, month])

const DIMS: { key: ProfileDimension; name: string; chart: 'bar' | 'pie' }[] = [
  { key: 'ORIGIN', name: '客源地', chart: 'bar' },
  { key: 'AGE', name: '年龄结构', chart: 'pie' },
  { key: 'GENDER', name: '性别', chart: 'pie' },
  { key: 'CHANNEL', name: '购票渠道', chart: 'pie' },
  { key: 'STAY', name: '停留时长', chart: 'bar' },
]

const isUnknown = (b: string) => b.includes('未知') || b.includes('未识别')

function option(key: ProfileDimension, chart: 'bar' | 'pie'): EChartsOption | null {
  const rows = profile.data.value?.dimensions[key]
  if (!rows?.length) return null
  if (chart === 'pie') {
    return {
      color: SERIES_COLORS,
      tooltip: { trigger: 'item', confine: true, formatter: (p: any) => `${p.name}<br/>${fmtNum(p.value)} 人（${p.percent}%）` },
      legend: { bottom: 0, icon: 'circle', itemWidth: 8, textStyle: { color: C.text } },
      series: [{
        type: 'pie', radius: ['42%', '68%'], center: ['50%', '44%'], label: { formatter: '{b}\n{d}%', color: C.text },
        data: rows.map((r) => ({ name: r.bucket, value: r.visitors, itemStyle: isUnknown(r.bucket) ? { color: C.grey } : undefined })),
      }],
    }
  }
  const sorted = key === 'ORIGIN' ? [...rows].sort((a, b) => a.visitors - b.visitors) : [...rows].reverse()
  return baseOption({
    legend: { show: false },
    grid: { left: 84, right: 56, top: 8, bottom: 24 },
    tooltip: { trigger: 'axis', confine: true, axisPointer: { type: 'shadow' } },
    xAxis: { type: 'value', ...axisStyle },
    yAxis: { type: 'category', data: sorted.map((r) => r.bucket), ...axisStyle, splitLine: { show: false } },
    series: [{
      name: '游客数', type: 'bar', barMaxWidth: 16,
      data: sorted.map((r) => ({ value: r.visitors, itemStyle: { color: isUnknown(r.bucket) ? C.grey : C.primary, borderRadius: [0, 3, 3, 0] } })),
      label: { show: true, position: 'right', color: C.text, formatter: (p: any) => fmtPct(sorted[p.dataIndex].share) },
    }],
  })
}
</script>

<template>
  <div class="sw-page">
    <div class="bar">
      <h2>游客画像</h2>
      <el-date-picker v-model="month" type="month" value-format="YYYY-MM" size="small" :clearable="false" style="width: 140px" />
      <span class="grow" />
      <DataMetaBar :meta="profile.data.value?.meta" />
    </div>

    <StatePanel :loading="profile.loading.value" :error="profile.error.value" :empty="!profile.data.value || !Object.keys(profile.data.value.dimensions).length" empty-text="该月份暂无画像数据" min-height="240px" @retry="profile.run">
      <template v-if="profile.data.value">
        <div class="sw-grid kpis">
          <KpiTile label="样本量" :value="fmtNum(profile.data.value.sampleSize)" unit="人" sub="画像统计基于该样本，非全量游客" />
          <KpiTile label="样本覆盖率" :value="fmtPct(profile.data.value.coverage)" :accent="profile.data.value.coverage < 0.5 ? C.warn : C.ok" sub="样本量 ÷ 当月入园人次" />
          <div class="sw-card notes">
            <b>口径说明</b>
            <ul>
              <li v-for="n in profile.data.value.notes" :key="n">{{ n }}</li>
              <li>“未知”为未能识别的样本，原样保留并以灰色展示，不做推断分摊。</li>
            </ul>
          </div>
        </div>
        <div class="charts">
          <div v-for="d in DIMS" :key="d.key" class="sw-card">
            <div class="sw-card-title"><span>{{ d.name }}</span></div>
            <EChart v-if="option(d.key, d.chart)" :option="option(d.key, d.chart)" height="300px" />
            <div v-else class="none">暂无数据</div>
          </div>
        </div>
      </template>
    </StatePanel>
  </div>
</template>

<style scoped>
.bar { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
h2 { margin: 0; font-size: 20px; }
.grow { flex: 1; }
.kpis { grid-template-columns: minmax(180px, 1fr) minmax(180px, 1fr) minmax(0, 2fr); margin-bottom: 16px; }
.notes { font-size: 13px; }
.notes ul { margin: 6px 0 0; padding-left: 18px; color: var(--sw-text-2); }
.charts { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(100%, 380px), 1fr)); gap: 16px; }
.none { height: 300px; display: flex; align-items: center; justify-content: center; color: var(--sw-text-3); }
@media (max-width: 900px) { .kpis { grid-template-columns: 1fr 1fr; } .notes { grid-column: 1 / -1; } }
</style>
