<script setup lang="ts">
import GlobalScopeView from '@/components/admin/GlobalScopeView.vue'
import { computed, reactive, ref, watch } from 'vue'
import type { EChartsOption } from 'echarts'
import { opsApi, type TourGroup } from '@/api/ops'
import { useAppStore } from '@/stores/app'
import StatePanel from '@/components/common/StatePanel.vue'
import DataMetaBar from '@/components/common/DataMetaBar.vue'
import KpiTile from '@/components/admin/KpiTile.vue'
import EChart from '@/components/charts/EChart.vue'
import { axisStyle, baseOption, C } from '@/components/charts/palette'
import { useAsync } from '@/components/admin/composables'
import { maskPhone } from '@/components/admin/logic'
import { fmtNum, fmtTime } from '@/utils/format'

const app = useAppStore()
const siteId = computed(() => app.currentSite?.id ?? null)
const days = ref(30)
const range = computed(() => {
  const end = app.now().startOf('day').add(1, 'day')
  return { start: end.subtract(days.value, 'day').format('YYYY-MM-DD'), end: end.format('YYYY-MM-DD') }
})

const stats = useAsync(async () => (siteId.value ? opsApi.groupStats(siteId.value, range.value.start, range.value.end) : null), [siteId, range])
const agencies = useAsync(() => opsApi.agencies())

const q = reactive<{ date: string | null; agencyId: number | null; status: string; page: number; size: number }>({ date: app.today(), agencyId: null, status: '', page: 1, size: 20 })
const list = useAsync(async () => (siteId.value
  ? opsApi.groups({ siteId: siteId.value, date: q.date ?? undefined, agencyId: q.agencyId ?? undefined, status: q.status || undefined, page: q.page, size: q.size })
  : null), [siteId, () => q.page, () => q.size])
watch(() => [q.date, q.agencyId, q.status], () => {
  if (q.page !== 1) q.page = 1
  else list.run()
})

const STATUS: Record<TourGroup['status'], { n: string; type: string }> = {
  BOOKED: { n: '已预约', type: 'info' }, ENTERED: { n: '已入园', type: 'success' }, LEFT: { n: '已离园', type: 'primary' }, CANCELLED: { n: '已取消', type: 'danger' },
}

function hbar(rows: { name: string; visitors: number; groups: number }[], color: string): EChartsOption | null {
  if (!rows.length) return null
  const top = [...rows].sort((a, b) => b.visitors - a.visitors).slice(0, 10).reverse()
  return baseOption({
    legend: { show: false },
    grid: { left: 120, right: 40, top: 8, bottom: 24 },
    tooltip: { trigger: 'axis', confine: true, axisPointer: { type: 'shadow' }, formatter: (p: any) => `${p[0].name}<br/>${fmtNum(p[0].value)} 人 · ${top[p[0].dataIndex].groups} 团` },
    xAxis: { type: 'value', ...axisStyle },
    yAxis: { type: 'category', data: top.map((r) => r.name), ...axisStyle, splitLine: { show: false }, axisLabel: { color: C.text, width: 110, overflow: 'truncate' } },
    series: [{ type: 'bar', barMaxWidth: 14, color, data: top.map((r) => r.visitors), itemStyle: { borderRadius: [0, 3, 3, 0] } }],
  })
}
const agencyOption = computed(() => hbar(stats.data.value?.byAgency ?? [], C.primary))
const originOption = computed(() => hbar((stats.data.value?.byOrigin ?? []).map((o) => ({ name: o.region, visitors: o.visitors, groups: o.groups })), C.ice))
const dailyOption = computed<EChartsOption | null>(() => {
  const rows = stats.data.value?.daily ?? []
  if (!rows.length) return null
  return baseOption({
    xAxis: { type: 'category', data: rows.map((r) => r.date.slice(5)), ...axisStyle, splitLine: { show: false } },
    yAxis: [{ type: 'value', name: '团队人数', ...axisStyle }, { type: 'value', name: '团数', ...axisStyle, splitLine: { show: false }, minInterval: 1 }],
    series: [
      { name: '团队人数', type: 'bar', barMaxWidth: 16, color: C.primary, data: rows.map((r) => r.visitors) },
      { name: '团数', type: 'line', yAxisIndex: 1, smooth: true, showSymbol: false, color: C.warn, data: rows.map((r) => r.groups) },
    ],
  })
})
</script>

<template>
  <GlobalScopeView v-if="app.isGlobal" kind="groups" />
  <div v-else class="sw-page">
    <div class="bar">
      <h2>旅行社与团队</h2>
      <el-radio-group v-model="days" size="small">
        <el-radio-button :value="7">近 7 天</el-radio-button>
        <el-radio-button :value="30">近 30 天</el-radio-button>
        <el-radio-button :value="90">近 90 天</el-radio-button>
      </el-radio-group>
      <span class="grow" />
      <DataMetaBar :meta="stats.data.value?.meta" />
    </div>

    <StatePanel :loading="stats.loading.value && !stats.data.value" :error="stats.error.value" :empty="!stats.data.value || !stats.data.value.totalGroups" empty-text="该时间范围暂无团队数据" min-height="160px" @retry="stats.run">
      <template v-if="stats.data.value">
        <div class="sw-grid kpis">
          <KpiTile label="入园团队" :value="fmtNum(stats.data.value.totalGroups)" unit="团" />
          <KpiTile label="团队游客" :value="fmtNum(stats.data.value.totalVisitors)" unit="人" :accent="C.ice" />
          <KpiTile label="合作旅行社" :value="fmtNum(stats.data.value.byAgency.length)" unit="家" :accent="C.ok" />
          <KpiTile label="客源地区" :value="fmtNum(stats.data.value.byOrigin.length)" unit="个" :accent="C.warn" />
        </div>
        <div class="charts">
          <div class="sw-card"><div class="sw-card-title"><span>旅行社送客 Top 10（人数）</span></div><EChart v-if="agencyOption" :option="agencyOption" height="280px" /><div v-else class="none">暂无数据</div></div>
          <div class="sw-card"><div class="sw-card-title"><span>团队客源地 Top 10</span></div><EChart v-if="originOption" :option="originOption" height="280px" /><div v-else class="none">暂无数据</div></div>
          <div class="sw-card"><div class="sw-card-title"><span>每日团队量</span></div><EChart v-if="dailyOption" :option="dailyOption" height="280px" /><div v-else class="none">暂无数据</div></div>
        </div>
      </template>
    </StatePanel>

    <div class="sw-card">
      <div class="sw-card-title">
        <span>团队明细</span>
        <span class="filters">
          <el-date-picker v-model="q.date" type="date" value-format="YYYY-MM-DD" size="small" placeholder="参观日期" style="width: 140px" />
          <el-select v-model="q.agencyId" placeholder="旅行社" clearable filterable size="small" style="width: 180px">
            <el-option v-for="a in agencies.data.value ?? []" :key="a.id" :label="a.name" :value="a.id" />
          </el-select>
          <el-select v-model="q.status" placeholder="状态" clearable size="small" style="width: 100px">
            <el-option v-for="(s, k) in STATUS" :key="k" :label="s.n" :value="k" />
          </el-select>
        </span>
      </div>
      <StatePanel :loading="list.loading.value && !list.data.value" :error="list.error.value" :empty="!list.data.value?.items.length" empty-text="没有符合条件的团队" @retry="list.run">
        <el-table :data="list.data.value?.items ?? []" size="small">
          <el-table-column prop="groupCode" label="团号" width="150" />
          <el-table-column prop="agencyName" label="旅行社" min-width="160" />
          <el-table-column prop="visitDate" label="参观日期" width="104" />
          <el-table-column label="入园 / 预约人数" width="120" align="right"><template #default="{ row }"><span class="num">{{ fmtNum(row.enteredCount) }} / {{ fmtNum(row.plannedSize) }}</span></template></el-table-column>
          <el-table-column label="带队导游" min-width="170">
            <template #default="{ row }">{{ row.guideName ?? '—' }} <span class="sw-muted">{{ maskPhone(row.guidePhoneMasked) }}</span><div v-if="row.guideCertNo" class="sw-muted">导游证 {{ row.guideCertNo }}</div></template>
          </el-table-column>
          <el-table-column label="客源地" width="90"><template #default="{ row }">{{ row.originRegion ?? '未知' }}</template></el-table-column>
          <el-table-column label="入园时间" width="110"><template #default="{ row }">{{ fmtTime(row.enteredAt) }}</template></el-table-column>
          <el-table-column label="状态" width="92">
            <template #default="{ row }">
              <el-tag size="small" :type="STATUS[row.status as TourGroup['status']].type">{{ STATUS[row.status as TourGroup['status']].n }}</el-tag>
              <el-tag v-if="row.dataMode === 'DEMO'" size="small" type="info" effect="plain" style="margin-left: 4px">演示</el-tag>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination v-model:current-page="q.page" v-model:page-size="q.size" class="pager" small background layout="total, sizes, prev, pager, next" :page-sizes="[20, 50, 100]" :total="list.data.value?.total ?? 0" />
      </StatePanel>
    </div>
  </div>
</template>

<style scoped>
.bar { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
h2 { margin: 0; font-size: 20px; }
.grow { flex: 1; }
.kpis { grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); margin-bottom: 16px; }
.charts { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 340px), 1fr)); gap: 16px; }
.none { height: 280px; display: flex; align-items: center; justify-content: center; color: var(--sw-text-3); }
.filters { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; font-weight: 400; }
.pager { margin-top: 12px; justify-content: flex-end; }
</style>
