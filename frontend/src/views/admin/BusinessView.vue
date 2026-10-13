<script setup lang="ts">
import GlobalScopeView from '@/components/admin/GlobalScopeView.vue'
import { computed, ref } from 'vue'
import type { EChartsOption } from 'echarts'
import { opsApi, type BusinessCategory } from '@/api/ops'
import { useAppStore } from '@/stores/app'
import StatePanel from '@/components/common/StatePanel.vue'
import DataMetaBar from '@/components/common/DataMetaBar.vue'
import KpiTile from '@/components/admin/KpiTile.vue'
import EChart from '@/components/charts/EChart.vue'
import { axisStyle, baseOption, C, CATEGORY_COLORS } from '@/components/charts/palette'
import { useAsync } from '@/components/admin/composables'
import { fmtMoney, fmtNum, fmtPct } from '@/utils/format'

const app = useAppStore()
const siteId = computed(() => app.currentSite?.id ?? null)
const days = ref(30)

const CAT: Record<BusinessCategory, string> = { HOTEL: '酒店住宿', CATERING: '餐饮', RETAIL: '零售门店', TICKET: '门票', ENTERTAINMENT: '娱乐项目' }
const BASIS: Record<string, string> = { PAID: '按支付金额', VERIFIED: '按核销金额', BOOKED: '按入账金额' }

// 日期区间 [start, end): 截至昨日(当日经营数据通常未结算)
const range = computed(() => {
  const end = app.now().startOf('day')
  return { start: end.subtract(days.value, 'day').format('YYYY-MM-DD'), end: end.format('YYYY-MM-DD') }
})

const summary = useAsync(async () => (siteId.value ? opsApi.businessSummary(siteId.value, range.value.start, range.value.end) : null), [siteId, range])
const linkage = useAsync(async () => (siteId.value ? opsApi.businessLinkage(siteId.value, days.value) : null), [siteId, days])

const total = computed(() => {
  const rows = summary.data.value?.byCategory ?? []
  const vals = rows.map((r) => r.revenue).filter((v): v is number => v != null)
  return vals.length ? vals.reduce((a, b) => a + b, 0) : null
})

const dailyOption = computed<EChartsOption | null>(() => {
  const rows = summary.data.value?.daily ?? []
  if (!rows.length) return null
  const dates = [...new Set(rows.map((r) => r.date))].sort()
  const cats = [...new Set(rows.map((r) => r.category))]
  return baseOption({
    tooltip: { trigger: 'axis', confine: true, axisPointer: { type: 'shadow' }, valueFormatter: (v) => (v == null ? '暂无数据' : fmtMoney(Number(v))) },
    xAxis: { type: 'category', data: dates.map((d) => d.slice(5)), ...axisStyle, splitLine: { show: false } },
    yAxis: { type: 'value', name: '营收（元）', ...axisStyle },
    series: cats.map((c) => ({
      name: CAT[c] ?? c, type: 'bar', stack: 'rev', barMaxWidth: 22, color: CATEGORY_COLORS[c],
      data: dates.map((d) => rows.find((r) => r.date === d && r.category === c)?.revenue ?? null),
    })),
  })
})

const linkOption = computed<EChartsOption | null>(() => {
  const pts = linkage.data.value?.points ?? []
  if (!pts.length) return null
  return baseOption({
    grid: { left: 56, right: 110, top: 40, bottom: 32 },
    xAxis: { type: 'category', data: pts.map((p) => p.date.slice(5)), ...axisStyle, splitLine: { show: false } },
    yAxis: [
      { type: 'value', name: '入园人次', ...axisStyle },
      { type: 'value', name: '入住率', min: 0, max: 1, ...axisStyle, splitLine: { show: false }, axisLabel: { color: C.text, formatter: (v: number) => `${Math.round(v * 100)}%` } },
      { type: 'value', name: '餐饮营收', offset: 56, ...axisStyle, splitLine: { show: false } },
    ],
    series: [
      { name: '入园人次', type: 'bar', barMaxWidth: 14, color: 'rgba(94,200,242,0.6)', data: pts.map((p) => p.entries) },
      { name: '酒店入住率', type: 'line', yAxisIndex: 1, smooth: true, showSymbol: false, color: C.primary, data: pts.map((p) => p.hotelOccupancy) },
      { name: '餐饮营收', type: 'line', yAxisIndex: 2, smooth: true, showSymbol: false, color: C.warn, data: pts.map((p) => p.cateringRevenue) },
    ],
  })
})

function rText(r: number | null) {
  if (r == null) return '不可计算'
  const a = Math.abs(r)
  return `${r.toFixed(2)}（${a >= 0.7 ? '强' : a >= 0.4 ? '中等' : '弱'}${r >= 0 ? '正' : '负'}相关）`
}
</script>

<template>
  <GlobalScopeView v-if="app.isGlobal" kind="business" />
  <div v-else class="sw-page">
    <div class="bar">
      <h2>多业态经营</h2>
      <el-radio-group v-model="days" size="small">
        <el-radio-button :value="7">近 7 天</el-radio-button>
        <el-radio-button :value="30">近 30 天</el-radio-button>
        <el-radio-button :value="90">近 90 天</el-radio-button>
      </el-radio-group>
      <span class="sw-muted">{{ range.start }} 至 {{ range.end }}（不含当日）</span>
      <span class="grow" />
      <DataMetaBar :meta="summary.data.value?.meta" />
    </div>

    <StatePanel :loading="summary.loading.value && !summary.data.value" :error="summary.error.value" :empty="!summary.data.value?.byCategory.length" empty-text="暂无业态经营数据（待接入酒店、餐饮、门店系统）" min-height="160px" @retry="summary.run">
      <template v-if="summary.data.value">
        <div class="sw-grid kpis">
          <KpiTile label="业态营收合计" :value="fmtMoney(total, summary.data.value.currency)" :sub="`${summary.data.value.currency} · ${BASIS[summary.data.value.amountBasis] ?? summary.data.value.amountBasis}`" />
          <KpiTile v-for="c in summary.data.value.byCategory" :key="c.category" :label="CAT[c.category] ?? c.category" :value="fmtMoney(c.revenue, summary.data.value.currency)" :accent="CATEGORY_COLORS[c.category]">
            <span>{{ c.units }} 家</span>
            <span v-if="c.orders != null">订单 {{ fmtNum(c.orders) }}</span>
            <span v-if="c.avgOccupancy != null">平均入住率 {{ fmtPct(c.avgOccupancy) }}</span>
          </KpiTile>
        </div>
        <div class="grid2">
          <div class="sw-card">
            <div class="sw-card-title"><span>每日营收构成</span><span class="sw-muted">金额口径：{{ BASIS[summary.data.value.amountBasis] ?? summary.data.value.amountBasis }}，币种 {{ summary.data.value.currency }}</span></div>
            <EChart v-if="dailyOption" :option="dailyOption" height="300px" />
            <div v-else class="none">暂无数据</div>
          </div>
          <div class="sw-card">
            <div class="sw-card-title"><span>营收前列单元</span></div>
            <el-table :data="summary.data.value.topUnits" size="small" max-height="300" empty-text="暂无数据">
              <el-table-column type="index" width="44" />
              <el-table-column prop="name" label="名称" min-width="140" />
              <el-table-column label="业态" width="90"><template #default="{ row }">{{ CAT[row.category as BusinessCategory] ?? row.category }}</template></el-table-column>
              <el-table-column label="营收" width="110" align="right"><template #default="{ row }"><span class="num">{{ fmtMoney(row.revenue, summary.data.value!.currency) }}</span></template></el-table-column>
            </el-table>
          </div>
        </div>
      </template>
    </StatePanel>

    <div class="sw-card">
      <div class="sw-card-title"><span>客流与业态联动</span><DataMetaBar :meta="linkage.data.value?.meta" compact /></div>
      <StatePanel :loading="linkage.loading.value" :error="linkage.error.value" :empty="!linkOption" empty-text="暂无联动分析数据" min-height="300px" @retry="linkage.run">
        <EChart :option="linkOption" height="300px" />
        <div v-if="linkage.data.value" class="grid2 lower">
          <div>
            <h4>相关性（皮尔逊 r）</h4>
            <ul class="corr">
              <li v-for="c in linkage.data.value.correlations" :key="c.pair"><span>{{ c.pair }}</span><b class="num">{{ rText(c.r) }}</b><i>样本 {{ c.n }} 天</i></li>
            </ul>
            <p class="sw-muted">相关性不代表因果关系，仅用于发现联动线索。</p>
          </div>
          <div>
            <h4>联动洞察</h4>
            <ul v-if="linkage.data.value.insights.length" class="ins"><li v-for="s in linkage.data.value.insights" :key="s">{{ s }}</li></ul>
            <div v-else class="sw-muted">暂无洞察</div>
          </div>
        </div>
      </StatePanel>
    </div>
  </div>
</template>

<style scoped>
.bar { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
h2 { margin: 0; font-size: 20px; }
h4 { margin: 12px 0 8px; font-size: 14px; }
.grow { flex: 1; }
.kpis { grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); margin-bottom: 16px; }
.grid2 { display: grid; grid-template-columns: minmax(0, 1.6fr) minmax(0, 1fr); gap: 16px; }
.grid2.lower { grid-template-columns: 1fr 1fr; }
.none { height: 300px; display: flex; align-items: center; justify-content: center; color: var(--sw-text-3); }
.corr { list-style: none; margin: 0; padding: 0; font-size: 13px; }
.corr li { display: flex; gap: 10px; padding: 6px 0; border-bottom: 1px dashed var(--sw-border); flex-wrap: wrap; }
.corr span { flex: 1; min-width: 120px; color: var(--sw-text-2); }
.corr i { font-style: normal; color: var(--sw-text-3); font-size: 12px; }
.ins { margin: 0; padding-left: 18px; font-size: 13px; color: var(--sw-text-2); line-height: 1.8; }
@media (max-width: 1000px) { .grid2, .grid2.lower { grid-template-columns: 1fr; } }
</style>
