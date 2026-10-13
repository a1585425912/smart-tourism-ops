<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { EChartsOption } from 'echarts'
import { flowApi } from '@/api/flow'
import { errorText } from '@/api/http'
import { useAppStore } from '@/stores/app'
import StatePanel from '@/components/common/StatePanel.vue'
import DataMetaBar from '@/components/common/DataMetaBar.vue'
import EChart from '@/components/charts/EChart.vue'
import { axisStyle, baseOption, C } from '@/components/charts/palette'
import { useAsync } from '@/components/admin/composables'
import { levelColor, weatherText } from '@/components/admin/logic'
import { fmtDateTime, fmtNum, fmtPct, fmtTime, label } from '@/utils/format'

const app = useAppStore()
const siteId = computed(() => app.currentSite?.id ?? null)
const days = ref(7)
const date = ref(app.today())
watch(siteId, () => (date.value = app.today()))

const daily = useAsync(async () => (siteId.value ? flowApi.dailyForecast(siteId.value, days.value) : null), [siteId, days])
const hourly = useAsync(async () => (siteId.value && date.value ? flowApi.hourlyForecast(siteId.value, date.value) : null), [siteId, date])

const dailyOption = computed<EChartsOption | null>(() => {
  const d = daily.data.value
  if (!d || !d.points.length) return null
  const x = d.points.map((p) => p.date.slice(5) + (p.holiday ? `\n${p.holiday}` : p.isOffDay ? '\n休' : ''))
  return baseOption({
    grid: { left: 56, right: 24, top: 40, bottom: 44 },
    tooltip: {
      trigger: 'axis', confine: true,
      formatter: (params: any) => {
        const p = d.points[params[0].dataIndex]
        const w = p.weather
        return [
          `<b>${p.date}</b> ${p.holiday ?? (p.isOffDay ? '休息日' : '工作日')}`,
          `预测入园 ${fmtNum(p.predicted)}（区间 ${fmtNum(p.lower)} – ${fmtNum(p.upper)}）`,
          p.actual != null ? `实际入园 ${fmtNum(p.actual)}` : '',
          w ? `${weatherText(w.weatherCode)} ${fmtNum(w.tempMin, 0)}~${fmtNum(w.tempMax, 0)}℃${w.isForecast ? '（预报）' : ''}` : '天气：暂无数据',
        ].filter(Boolean).join('<br/>')
      },
    },
    xAxis: { type: 'category', data: x, ...axisStyle, axisLabel: { color: C.text, lineHeight: 15 } },
    yAxis: { type: 'value', name: '入园人次', ...axisStyle },
    series: [
      // 区间带: 下界透明堆叠 + (上界-下界) 填充
      { name: '下界', type: 'line', stack: 'band', symbol: 'none', lineStyle: { opacity: 0 }, data: d.points.map((p) => p.lower), tooltip: { show: false } },
      { name: '预测区间', type: 'line', stack: 'band', symbol: 'none', lineStyle: { opacity: 0 }, areaStyle: { color: 'rgba(31,111,235,0.15)' }, color: 'rgba(31,111,235,0.3)', data: d.points.map((p) => p.upper - p.lower) },
      { name: '预测入园', type: 'line', smooth: true, symbolSize: 7, lineStyle: { width: 2.5 }, color: C.primary, data: d.points.map((p) => p.predicted) },
      { name: '实际入园', type: 'line', smooth: true, symbolSize: 7, lineStyle: { width: 2, type: 'dashed' }, color: C.ok, connectNulls: false, data: d.points.map((p) => p.actual) },
    ],
    legend: { top: 0, right: 0, data: ['预测入园', '预测区间', '实际入园'] },
  })
})

const hourlyOption = computed<EChartsOption | null>(() => {
  const h = hourly.data.value
  if (!h || !h.points.length) return null
  return baseOption({
    xAxis: { type: 'time', ...axisStyle, splitLine: { show: false } },
    yAxis: [{ type: 'value', name: '在园人数', ...axisStyle }, { type: 'value', name: '入园人次/时', ...axisStyle, splitLine: { show: false } }],
    series: [
      { name: '预测在园', type: 'line', smooth: true, showSymbol: false, areaStyle: { opacity: 0.1 }, color: C.primary, data: h.points.map((p) => [p.t, p.predictedInPark]),
        markPoint: h.peak ? { data: [{ name: '峰值', coord: [h.peak.t, h.peak.value], value: '峰值' }], itemStyle: { color: C.danger } } : undefined },
      { name: '预测入园', type: 'bar', yAxisIndex: 1, barMaxWidth: 14, color: C.ice, data: h.points.map((p) => [p.t, p.predictedEntries]) },
    ],
  })
})

const refreshing = ref(false)
async function refresh() {
  if (!siteId.value) return
  refreshing.value = true
  try {
    const r = await flowApi.refreshForecast(siteId.value)
    ElMessage.success(`已提交重新训练与预测任务（任务号 ${r.jobId}），完成后刷新本页查看`)
  } catch (e) {
    ElMessage.error(errorText(e))
  } finally {
    refreshing.value = false
  }
}

const bt = computed(() => daily.data.value?.backtest ?? null)
const btBetter = computed(() => bt.value && bt.value.mape != null && bt.value.baselineMape != null ? bt.value.mape < bt.value.baselineMape : null)
</script>

<template>
  <div class="sw-page">
    <div class="bar">
      <h2>客流预测与调度</h2>
      <el-radio-group v-model="days" size="small">
        <el-radio-button :value="3">3 天</el-radio-button>
        <el-radio-button :value="7">7 天</el-radio-button>
        <el-radio-button :value="14">14 天</el-radio-button>
      </el-radio-group>
      <span class="grow" />
      <DataMetaBar :meta="daily.data.value?.meta" />
      <el-button v-if="app.has('INTEGRATION_MANAGE')" size="small" :loading="refreshing" :disabled="!siteId" @click="refresh">重新训练并预测</el-button>
    </div>

    <div class="grid2">
      <div class="sw-card">
        <div class="sw-card-title"><span>每日入园人次预测</span><span class="sw-muted">预测为模型估计值，带不确定区间；仅供调度参考</span></div>
        <StatePanel :loading="daily.loading.value" :error="daily.error.value" :empty="!dailyOption" empty-text="暂无预测结果，可点击“重新训练并预测”" min-height="320px" @retry="daily.run">
          <EChart :option="dailyOption" height="320px" />
        </StatePanel>
      </div>
      <div class="sw-card">
        <div class="sw-card-title"><span>模型与回测</span></div>
        <StatePanel :loading="daily.loading.value" :error="daily.error.value" :empty="!daily.data.value" min-height="200px" @retry="daily.run">
          <template v-if="daily.data.value">
            <dl class="model">
              <dt>模型</dt><dd>{{ daily.data.value.model.name }} {{ daily.data.value.model.version }}</dd>
              <dt>训练时间</dt><dd>{{ fmtDateTime(daily.data.value.model.trainedAt) }}</dd>
              <dt>输入特征</dt><dd><span v-for="f in daily.data.value.model.features" :key="f" class="feat">{{ f }}</span></dd>
            </dl>
            <div v-if="bt" class="bt">
              <div class="bt-row">
                <div><span>模型 MAPE</span><b class="num">{{ fmtPct(bt.mape) }}</b></div>
                <div><span>基线 MAPE（{{ bt.baselineName }}）</span><b class="num">{{ fmtPct(bt.baselineMape) }}</b></div>
              </div>
              <div class="bt-row">
                <div><span>模型 MAE</span><b class="num">{{ fmtNum(bt.mae) }}</b></div>
                <div><span>基线 MAE</span><b class="num">{{ fmtNum(bt.baselineMae) }}</b></div>
              </div>
              <p class="sw-muted">按时间顺序回测：{{ bt.testStart }} 至 {{ bt.testEnd }}，共 {{ bt.sampleDays }} 天；训练不使用测试期之后的数据。</p>
              <p v-if="btBetter !== null" class="verdict" :class="{ good: btBetter }">{{ btBetter ? '模型误差低于基线' : '模型误差未优于基线，建议谨慎使用预测结果' }}</p>
            </div>
            <div v-else class="sw-muted">暂无回测记录</div>
          </template>
        </StatePanel>
      </div>
    </div>

    <div class="sw-card">
      <div class="sw-card-title"><span>逐日明细与影响因素</span></div>
      <StatePanel :loading="daily.loading.value" :error="daily.error.value" :empty="!daily.data.value?.points.length" @retry="daily.run">
        <el-table :data="daily.data.value?.points ?? []" size="small">
          <el-table-column prop="date" label="日期" width="110" />
          <el-table-column label="日历" width="100"><template #default="{ row }"><el-tag v-if="row.holiday" size="small" type="danger" effect="plain">{{ row.holiday }}</el-tag><span v-else>{{ row.isOffDay ? '休息日' : '工作日' }}</span></template></el-table-column>
          <el-table-column label="天气" width="150">
            <template #default="{ row }">
              <span v-if="row.weather">{{ weatherText(row.weather.weatherCode) }} {{ fmtNum(row.weather.tempMin, 0) }}~{{ fmtNum(row.weather.tempMax, 0) }}℃<i v-if="row.weather.isForecast" class="fc">预报</i></span>
              <span v-else class="sw-muted">暂无数据</span>
            </template>
          </el-table-column>
          <el-table-column label="预测入园" width="110" align="right"><template #default="{ row }"><b class="num">{{ fmtNum(row.predicted) }}</b></template></el-table-column>
          <el-table-column label="区间" width="150" align="right"><template #default="{ row }"><span class="num sw-muted">{{ fmtNum(row.lower) }} – {{ fmtNum(row.upper) }}</span></template></el-table-column>
          <el-table-column label="实际" width="100" align="right"><template #default="{ row }"><span class="num">{{ fmtNum(row.actual) }}</span></template></el-table-column>
          <el-table-column label="主要影响因素" min-width="220"><template #default="{ row }"><span v-for="d in row.drivers" :key="d" class="feat">{{ d }}</span><span v-if="!row.drivers.length" class="sw-muted">—</span></template></el-table-column>
          <el-table-column label="" width="90"><template #default="{ row }"><el-button link type="primary" size="small" @click="date = row.date">小时预测</el-button></template></el-table-column>
        </el-table>
      </StatePanel>
    </div>

    <div class="sw-card">
      <div class="sw-card-title">
        <span>小时级预测与各点位峰值</span>
        <span class="tr"><el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" size="small" :clearable="false" style="width: 150px" /><DataMetaBar :meta="hourly.data.value?.meta" compact /></span>
      </div>
      <StatePanel :loading="hourly.loading.value" :error="hourly.error.value" :empty="!hourlyOption" empty-text="该日期暂无小时级预测" min-height="280px" @retry="hourly.run">
        <EChart :option="hourlyOption" height="280px" />
        <div v-if="hourly.data.value" class="grid2 lower">
          <div>
            <h4>各区域预测峰值与建议人手</h4>
            <el-table :data="hourly.data.value.zonePeaks" size="small" empty-text="暂无区域峰值预测">
              <el-table-column prop="zoneName" label="区域" min-width="110" />
              <el-table-column label="预测峰值" width="96" align="right"><template #default="{ row }"><span class="num">{{ fmtNum(row.peak) }}</span></template></el-table-column>
              <el-table-column label="峰值时间" width="90"><template #default="{ row }">{{ fmtTime(row.peakAt, 'HH:mm') }}</template></el-table-column>
              <el-table-column label="承载率" width="86" align="right"><template #default="{ row }"><span class="num" :style="{ color: levelColor(row.level) }">{{ fmtPct(row.ratio, 0) }}</span></template></el-table-column>
              <el-table-column label="等级" width="70"><template #default="{ row }"><span :style="{ color: levelColor(row.level) }">{{ row.level === 'UNKNOWN' ? '未知' : label(row.level) }}</span></template></el-table-column>
              <el-table-column label="建议人手" width="90" align="right"><template #default="{ row }"><b class="num">{{ row.staffSuggested }}</b> 人</template></el-table-column>
            </el-table>
          </div>
          <div>
            <h4>资源调度建议</h4>
            <ul v-if="hourly.data.value.recommendations.length" class="recs">
              <li v-for="(r, i) in hourly.data.value.recommendations" :key="i" :class="r.level.toLowerCase()">
                <b>{{ r.title }}</b><p>{{ r.detail }}</p>
              </li>
            </ul>
            <div v-else class="sw-muted">暂无调度建议</div>
            <p class="sw-muted">建议由规则根据预测承载率生成，需结合现场情况由管理人员决策。</p>
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
.grid2 { display: grid; grid-template-columns: minmax(0, 1.7fr) minmax(0, 1fr); gap: 16px; }
.grid2.lower { grid-template-columns: minmax(0, 1.3fr) minmax(0, 1fr); margin-top: 8px; }
.model { display: grid; grid-template-columns: 72px 1fr; gap: 6px 8px; margin: 0 0 12px; font-size: 13px; }
.model dt { color: var(--sw-text-3); }
.model dd { margin: 0; }
.feat { display: inline-block; font-size: 12px; background: var(--sw-bg); border: 1px solid var(--sw-border); border-radius: 3px; padding: 0 6px; margin: 0 4px 4px 0; }
.bt { border-top: 1px dashed var(--sw-border); padding-top: 10px; }
.bt-row { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-bottom: 8px; }
.bt-row span { display: block; font-size: 12px; color: var(--sw-text-3); }
.bt-row b { font-size: 20px; }
.verdict { font-size: 13px; color: #b8740a; margin: 4px 0 0; }
.verdict.good { color: var(--sw-ok); }
.fc { font-style: normal; font-size: 11px; color: var(--sw-primary); margin-left: 4px; }
.tr { display: flex; align-items: center; gap: 10px; font-weight: 400; flex-wrap: wrap; }
.recs { list-style: none; margin: 0 0 8px; padding: 0; }
.recs li { border-left: 3px solid var(--sw-ice); background: var(--sw-bg); padding: 8px 10px; margin-bottom: 6px; border-radius: 0 6px 6px 0; font-size: 13px; }
.recs li.warn { border-left-color: var(--sw-warn); }
.recs li.critical { border-left-color: var(--sw-danger); }
.recs p { margin: 2px 0 0; color: var(--sw-text-2); font-size: 12px; }
@media (max-width: 1000px) { .grid2, .grid2.lower { grid-template-columns: 1fr; } }
</style>
