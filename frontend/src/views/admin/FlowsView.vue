<script setup lang="ts">
import { computed, ref } from 'vue'
import dayjs from 'dayjs'
import type { EChartsOption } from 'echarts'
import { flowApi, type FlowMetric } from '@/api/flow'
import { useAppStore } from '@/stores/app'
import StatePanel from '@/components/common/StatePanel.vue'
import DataMetaBar from '@/components/common/DataMetaBar.vue'
import KpiTile from '@/components/admin/KpiTile.vue'
import CsvImportDialog from '@/components/admin/CsvImportDialog.vue'
import EChart from '@/components/charts/EChart.vue'
import { axisStyle, baseOption, C } from '@/components/charts/palette'
import { useAsync } from '@/components/admin/composables'
import { dayRange, levelColor, ratioColor, trendText } from '@/components/admin/logic'
import { fmtNum, fmtPct, fmtTime, label } from '@/utils/format'

const app = useAppStore()
const siteId = computed(() => app.currentSite?.id ?? null)

type Preset = 'today' | '7d' | '30d' | 'custom'
const preset = ref<Preset>('today')
const custom = ref<[string, string] | null>(null)
const metric = ref<FlowMetric>('ENTRY')
const METRICS: { v: FlowMetric; n: string; unit: string }[] = [
  { v: 'ENTRY', n: '入园人次', unit: '人次' },
  { v: 'EXIT', n: '出园人次', unit: '人次' },
  { v: 'IN_PARK', n: '在园人数', unit: '人' },
]

const rangeError = ref('')
const range = computed(() => {
  rangeError.value = ''
  if (preset.value === 'custom' && custom.value) {
    const start = dayjs(custom.value[0])
    const end = dayjs(custom.value[1]).add(1, 'day')
    const days = end.diff(start, 'day')
    if (days > 400) {
      rangeError.value = '自定义范围不能超过 400 天'
      return null
    }
    return { start: `${custom.value[0]}T00:00:00+08:00`, end: `${end.format('YYYY-MM-DD')}T00:00:00+08:00`, days }
  }
  const days = preset.value === '7d' ? 7 : preset.value === '30d' ? 30 : 1
  return { ...dayRange(app.today(), days), days }
})
// 小时粒度最多 31 天, 这里 7 天以内用小时, 其余用日
const granularity = computed(() => ((range.value?.days ?? 1) <= 7 ? 'HOUR' : 'DAY'))

const summary = useAsync(async () => (siteId.value && range.value ? flowApi.summary(siteId.value, range.value.start, range.value.end) : null), [siteId, range])
const trend = useAsync(async () => (siteId.value && range.value
  ? flowApi.trend({ siteId: siteId.value, metric: metric.value, granularity: granularity.value, start: range.value.start, end: range.value.end })
  : null), [siteId, range, metric])
const zones = useAsync(async () => (siteId.value ? flowApi.zones(siteId.value) : []), [siteId])

const AGG: Record<string, string> = { sum: '时段合计', max: '时段峰值', last: '时段末值' }

const trendOption = computed<EChartsOption | null>(() => {
  const t = trend.data.value
  if (!t || !t.points.length) return null
  const m = METRICS.find((x) => x.v === metric.value)!
  const bar = t.aggregation === 'sum'
  return baseOption({
    legend: { show: false },
    xAxis: { type: 'time', ...axisStyle, splitLine: { show: false } },
    yAxis: { type: 'value', name: m.unit, ...axisStyle },
    series: [{
      name: m.n, type: bar ? 'bar' : 'line', smooth: true, showSymbol: false, connectNulls: false, color: C.primary,
      areaStyle: bar ? undefined : { opacity: 0.1 }, barMaxWidth: 18,
      data: t.points.map((p) => [p.t, p.value]),
      // 数据缺口用灰色区域标出, 不以 0 填充
      markArea: t.gaps.length ? {
        silent: true, itemStyle: { color: 'rgba(160,174,192,0.25)' }, label: { show: true, color: '#718096', formatter: '数据缺失' },
        data: t.gaps.map((g) => [{ xAxis: g.start }, { xAxis: g.end }]) as never,
      } : undefined,
    }],
  })
})

const importOpen = ref(false)
function doImport(file: File, onProgress: (p: number) => void) {
  return flowApi.importCsv(siteId.value as number, file, onProgress)
}
function reload() {
  summary.run()
  trend.run()
  zones.run()
}
</script>

<template>
  <div class="sw-page">
    <div class="bar">
      <el-radio-group v-model="preset" size="small">
        <el-radio-button value="today">今日</el-radio-button>
        <el-radio-button value="7d">近 7 天</el-radio-button>
        <el-radio-button value="30d">近 30 天</el-radio-button>
        <el-radio-button value="custom">自定义</el-radio-button>
      </el-radio-group>
      <el-date-picker v-if="preset === 'custom'" v-model="custom" type="daterange" value-format="YYYY-MM-DD" size="small" range-separator="至" start-placeholder="开始" end-placeholder="结束" style="max-width: 260px" />
      <span v-if="rangeError" class="err">{{ rangeError }}</span>
      <span class="grow" />
      <DataMetaBar :meta="summary.data.value?.meta" />
      <el-button v-if="app.has('FLOW_IMPORT')" size="small" :disabled="!siteId" @click="importOpen = true">导入客流 CSV</el-button>
    </div>

    <StatePanel :loading="summary.loading.value && !summary.data.value" :error="summary.error.value" :empty="!summary.data.value" empty-text="请选择景区和时间范围" min-height="100px" @retry="summary.run">
      <div v-if="summary.data.value" class="sw-grid kpis">
        <KpiTile label="入园人次" :value="fmtNum(summary.data.value.entries)" unit="人次" :trend="trendText(summary.data.value.entriesDod)" trend-label="环比">
          <span>同比 {{ trendText(summary.data.value.entriesYoy).text }}</span>
        </KpiTile>
        <KpiTile label="出园人次" :value="fmtNum(summary.data.value.exits)" unit="人次" :accent="C.ice" />
        <KpiTile label="当前在园人数" :value="fmtNum(summary.data.value.inParkCurrent)" unit="人" :accent="levelColor(summary.data.value.loadLevel)"
          :sub="`承载率 ${fmtPct(summary.data.value.utilization)} · 承载量 ${fmtNum(summary.data.value.capacity)}`" />
        <KpiTile label="在园峰值" :value="fmtNum(summary.data.value.inParkPeak)" unit="人" :accent="C.warn"
          :sub="summary.data.value.inParkPeakAt ? '出现于 ' + fmtTime(summary.data.value.inParkPeakAt) : '暂无峰值时间'" />
      </div>
      <ul v-if="summary.data.value?.notes.length" class="notes">
        <li v-for="n in summary.data.value.notes" :key="n">{{ n }}</li>
      </ul>
      <p class="sw-muted caliber">口径：入园/出园为时段内闸机人次（未去重，不等于独立游客数）；在园人数取来源上报的存量值；区域人数来自区域绑定的计数设备，不可相加得到景区人数。</p>
    </StatePanel>

    <div class="sw-card">
      <div class="sw-card-title">
        <el-radio-group v-model="metric" size="small">
          <el-radio-button v-for="m in METRICS" :key="m.v" :value="m.v">{{ m.n }}</el-radio-button>
        </el-radio-group>
        <span class="tr">
          <span v-if="trend.data.value" class="sw-muted">{{ granularity === 'HOUR' ? '按小时' : '按日' }} · {{ AGG[trend.data.value.aggregation] }}<template v-if="trend.data.value.gaps.length"> · {{ trend.data.value.gaps.length }} 段缺口</template></span>
          <DataMetaBar :meta="trend.data.value?.meta" compact />
        </span>
      </div>
      <StatePanel :loading="trend.loading.value" :error="trend.error.value" :empty="!trendOption" empty-text="该时间范围暂无数据" min-height="320px" @retry="trend.run">
        <EChart :option="trendOption" height="320px" />
      </StatePanel>
    </div>

    <div class="sw-card">
      <div class="sw-card-title"><span>区域人数与热度</span><span class="sw-muted">热度 = 区域人数 ÷ 区域承载量；低于 30% 为低，达到预警比例为预警，达到 100% 为饱和</span></div>
      <StatePanel :loading="zones.loading.value" :error="zones.error.value" :empty="!zones.data.value?.length" empty-text="该景区尚未划定区域" @retry="zones.run">
        <el-table :data="zones.data.value ?? []" size="small">
          <el-table-column prop="zoneName" label="区域" min-width="120" />
          <el-table-column label="当前人数" width="100" align="right"><template #default="{ row }"><span class="num">{{ row.count == null ? '暂无数据' : fmtNum(row.count) }}</span></template></el-table-column>
          <el-table-column label="承载量" width="90" align="right"><template #default="{ row }"><span class="num">{{ fmtNum(row.capacity) }}</span></template></el-table-column>
          <el-table-column label="热度" min-width="180">
            <template #default="{ row }">
              <div class="heat">
                <div class="track"><div class="fill" :style="{ width: Math.min(100, (row.ratio ?? 0) * 100) + '%', background: ratioColor(row.ratio) }" /></div>
                <span class="num">{{ fmtPct(row.ratio, 0) }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="等级" width="80"><template #default="{ row }"><span class="lv" :style="{ color: levelColor(row.level) }">{{ row.level === 'UNKNOWN' ? '未知' : label(row.level) }}</span></template></el-table-column>
          <el-table-column label="更新时间" width="110"><template #default="{ row }">{{ fmtTime(row.at) }}</template></el-table-column>
          <el-table-column label="数据来源" min-width="160"><template #default="{ row }">{{ row.countSource ?? '未绑定计数设备' }}</template></el-table-column>
          <el-table-column label="" width="150"><template #default="{ row }"><DataMetaBar :meta="row.meta" compact /></template></el-table-column>
        </el-table>
      </StatePanel>
    </div>

    <CsvImportDialog
      v-model="importOpen"
      title="导入客流数据"
      header="source_record_id,biz_time,zone_code,metric,value"
      :notes="[
        'biz_time 为带时区的 ISO-8601 时间，如 2026-02-18T14:00:00+08:00',
        'zone_code 可为空（表示景区整体）；metric 取 ENTRY / EXIT / IN_PARK / ZONE_COUNT / QUEUE_WAIT',
        '相同 source_record_id 重复导入不会重复计数；迟到数据会触发相关时间桶重算',
        `数据将导入当前景区：${app.currentSite?.name ?? '—'}`,
      ]"
      :uploader="doImport"
      @done="reload"
    />
  </div>
</template>

<style scoped>
.bar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.grow { flex: 1; }
.err { color: var(--sw-danger); font-size: 13px; }
.kpis { grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); }
.notes { margin: 10px 0 0; padding-left: 18px; font-size: 12px; color: #b8740a; }
.caliber { margin: 8px 0 0; }
.tr { display: flex; align-items: center; gap: 10px; font-weight: 400; flex-wrap: wrap; }
.heat { display: flex; align-items: center; gap: 8px; }
.track { flex: 1; height: 8px; background: #edf1f6; border-radius: 4px; overflow: hidden; }
.fill { height: 100%; border-radius: 4px; }
.lv { font-weight: 600; }
</style>
