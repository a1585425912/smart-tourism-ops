<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import type { EChartsOption } from 'echarts'
import { opsApi, type Alert } from '@/api/ops'
import { flowApi, type FlowTrend } from '@/api/flow'
import { useAppStore } from '@/stores/app'
import StatePanel from '@/components/common/StatePanel.vue'
import DataMetaBar from '@/components/common/DataMetaBar.vue'
import KpiTile from '@/components/admin/KpiTile.vue'
import EChart from '@/components/charts/EChart.vue'
import { axisStyle, baseOption, C, SERIES_COLORS } from '@/components/charts/palette'
import { useAsync } from '@/components/admin/composables'
import { levelColor, trendText } from '@/components/admin/logic'
import { fmtNum, fmtPct, fmtTime, label } from '@/utils/format'

const app = useAppStore()
const router = useRouter()

const overview = useAsync(() => opsApi.overview())

// 各景区近 24 小时在园人数趋势
const trends = useAsync(async () => {
  if (!app.has('FLOW_READ')) return [] as { name: string; trend: FlowTrend }[]
  const end = app.now().startOf('hour').add(1, 'hour')
  const start = end.subtract(24, 'hour')
  const results = await Promise.all(app.sites.map(async (s) => ({
    name: s.shortName,
    trend: await flowApi.trend({ siteId: s.id, metric: 'IN_PARK', granularity: 'HOUR', start: start.format(), end: end.format() }),
  })))
  return results
})

let timer: ReturnType<typeof setInterval>
onMounted(() => {
  timer = setInterval(() => { overview.run(); trends.run() }, 60000)
})
onBeforeUnmount(() => clearInterval(timer))

const totals = computed(() => overview.data.value?.totals)
const totalUtil = computed(() => {
  const t = totals.value
  return t && t.inPark != null && t.capacity > 0 ? t.inPark / t.capacity : null
})
const alerts = computed(() => overview.data.value?.alerts ?? [])
const criticalCount = computed(() => alerts.value.filter((a) => a.level !== 'INFO').length)

const gaugeOption = computed<EChartsOption | null>(() => {
  if (totalUtil.value == null) return null
  return {
    series: [{
      type: 'gauge', startAngle: 200, endAngle: -20, min: 0, max: 100, radius: '100%', center: ['50%', '62%'],
      progress: { show: true, width: 12, itemStyle: { color: totalUtil.value >= 1 ? C.danger : totalUtil.value >= 0.8 ? C.warn : C.primary } },
      axisLine: { lineStyle: { width: 12, color: [[1, '#e8edf4']] } },
      axisTick: { show: false }, splitLine: { show: false }, axisLabel: { show: false }, pointer: { show: false },
      detail: { valueAnimation: true, formatter: '{value}%', fontSize: 22, offsetCenter: [0, '-5%'], color: C.text },
      data: [{ value: Math.round(totalUtil.value * 1000) / 10 }],
    }],
  }
})

const trendOption = computed<EChartsOption | null>(() => {
  const list = trends.data.value ?? []
  if (!list.some((x) => x.trend.points.length)) return null
  return baseOption({
    xAxis: { type: 'time', ...axisStyle, splitLine: { show: false } },
    yAxis: { type: 'value', name: '在园人数', ...axisStyle },
    series: list.map((x, i) => ({
      name: x.name, type: 'line', smooth: true, showSymbol: false, connectNulls: false,
      areaStyle: { opacity: 0.08 }, lineStyle: { width: 2 }, color: SERIES_COLORS[i % SERIES_COLORS.length],
      data: x.trend.points.map((p) => [p.t, p.value]),
    })),
  })
})
const trendMeta = computed(() => trends.data.value?.[0]?.trend.meta ?? null)

const ALERT_TYPES: Record<Alert['type'], string> = {
  ZONE_LOAD: '区域承载', DEVICE_OFFLINE: '设备离线', DATA_STALE: '数据过期', QUEUE: '排队', REVIEW: '网评', INSPECTION: '巡检', FORECAST: '预测',
}

const quick = computed(() => [
  { t: 'AI 运营助手', p: '/admin/assistant', perm: 'AI_USE', icon: 'MagicStick' },
  { t: '客流预测与调度', p: '/admin/forecast', perm: 'FLOW_READ', icon: 'Histogram' },
  { t: '网评舆情', p: '/admin/reviews', perm: 'REVIEW_READ', icon: 'ChatDotRound' },
  { t: '日常巡检', p: '/admin/inspections', perm: 'INSPECTION_READ', icon: 'Checked' },
  { t: '运营日报', p: '/admin/reports', perm: 'REPORT_READ', icon: 'Document' },
  { t: '点位监控', p: '/admin/devices', perm: 'DEVICE_READ', icon: 'VideoCamera' },
].filter((q) => app.has(q.perm)))

function openSite(id: number, path: string) {
  app.selectSite(id)
  router.push(path)
}
</script>

<template>
  <div class="sw-page">
    <div class="head">
      <h2>运营总览</h2>
      <DataMetaBar :meta="overview.data.value?.meta" />
      <span class="sw-muted">业务日 {{ app.today() }} · 每分钟自动刷新</span>
    </div>

    <StatePanel :loading="overview.loading.value && !overview.data.value" :error="overview.error.value" :empty="!overview.data.value?.sites.length" empty-text="暂无授权景区数据" @retry="overview.run">
      <div class="sw-grid kpis">
        <KpiTile label="今日入园人次（授权景区合计）" :value="fmtNum(totals?.entries)" unit="人次" sub="闸机计次，未去重" />
        <KpiTile label="当前在园人数" :value="fmtNum(totals?.inPark)" unit="人" :sub="`最大承载量 ${fmtNum(totals?.capacity)}`" :accent="C.ice" />
        <div class="sw-card gauge">
          <div class="sw-muted">综合承载率</div>
          <EChart v-if="gaugeOption" :option="gaugeOption" height="104px" />
          <div v-else class="none">暂无数据</div>
        </div>
        <KpiTile label="待处理告警" :value="String(criticalCount)" unit="条" :sub="`共 ${alerts.length} 条提示`" :accent="criticalCount ? C.danger : C.ok" />
      </div>

      <div class="sw-grid sites">
        <div v-for="s in overview.data.value?.sites" :key="s.siteId" class="sw-card site">
          <div class="sw-card-title">
            <span>{{ s.name }} <span class="st">{{ label(s.status) }}</span></span>
            <span class="lv" :style="{ background: levelColor(s.today.loadLevel) }">{{ s.today.loadLevel === 'UNKNOWN' ? '承载未知' : label(s.today.loadLevel) }}</span>
          </div>
          <div class="row4 num">
            <div><b>{{ fmtNum(s.today.entries) }}</b><span>今日入园</span></div>
            <div><b>{{ fmtNum(s.today.inParkCurrent) }}</b><span>当前在园</span></div>
            <div><b>{{ fmtPct(s.today.utilization) }}</b><span>承载率</span></div>
            <div><b>{{ fmtNum(s.today.inParkPeak) }}</b><span>峰值 {{ s.today.inParkPeakAt ? fmtTime(s.today.inParkPeakAt, 'HH:mm') : '' }}</span></div>
          </div>
          <div class="yoy sw-muted">
            较昨日 <em :class="trendText(s.today.entriesDod).dir">{{ trendText(s.today.entriesDod).text }}</em>
            · 同比 <em :class="trendText(s.today.entriesYoy).dir">{{ trendText(s.today.entriesYoy).text }}</em>
          </div>
          <ul class="facts">
            <li @click="openSite(s.siteId, '/admin/flows')"><span>区域预警 / 饱和</span><b class="num" :class="{ bad: s.zones.full > 0, warn: s.zones.warn > 0 }">{{ s.zones.warn }} / {{ s.zones.full }}<i> 共 {{ s.zones.total }} 个</i></b></li>
            <li @click="openSite(s.siteId, '/admin/devices')"><span>设备在线</span><b class="num" :class="{ warn: s.devices.offline > 0 }">{{ s.devices.online }} / {{ s.devices.total }}<i v-if="s.devices.offline"> 离线 {{ s.devices.offline }}</i></b></li>
            <li @click="openSite(s.siteId, '/admin/reviews')"><span>今日网评 / 负面</span><b class="num" :class="{ warn: s.reviews.negative > 0 }">{{ s.reviews.today }} / {{ s.reviews.negative }}<i> 均分 {{ fmtNum(s.reviews.avgRating, 1) }}</i></b></li>
            <li @click="openSite(s.siteId, '/admin/inspections')"><span>巡检进行 / 逾期</span><b class="num" :class="{ bad: s.inspections.overdue > 0 }">{{ s.inspections.open }} / {{ s.inspections.overdue }}<i> 未闭环异常 {{ s.inspections.openIssues }}</i></b></li>
            <li @click="openSite(s.siteId, '/admin/groups')"><span>今日团队</span><b class="num">{{ s.groupsToday.groups }} 团<i> {{ fmtNum(s.groupsToday.visitors) }} 人</i></b></li>
          </ul>
          <div v-if="s.queues.length" class="queues">
            <span class="sw-muted">排队最长</span>
            <span v-for="q in s.queues.slice(0, 3)" :key="q.name" class="q">{{ q.name }} <b class="num">{{ q.waitMin == null ? '暂无数据' : fmtNum(q.waitMin) + ' 分钟' }}</b></span>
          </div>
          <div class="note">
            <DataMetaBar :meta="s.today.meta" />
            <span v-for="n in s.today.notes" :key="n" class="sw-muted">{{ n }}</span>
          </div>
        </div>
      </div>
    </StatePanel>

    <div class="sw-grid two">
      <div class="sw-card">
        <div class="sw-card-title"><span>近 24 小时在园人数</span><DataMetaBar :meta="trendMeta" compact /></div>
        <StatePanel :loading="trends.loading.value && !trends.data.value" :error="trends.error.value" :empty="!trendOption" min-height="280px" @retry="trends.run">
          <EChart :option="trendOption" />
        </StatePanel>
      </div>
      <div class="sw-card">
        <div class="sw-card-title"><span>告警与提示</span></div>
        <StatePanel :loading="overview.loading.value && !overview.data.value" :error="overview.error.value" :empty="!alerts.length" empty-text="当前没有告警" min-height="280px" @retry="overview.run">
          <ul class="alerts">
            <li v-for="(a, i) in alerts" :key="i" :class="a.level.toLowerCase()">
              <span class="ty">{{ ALERT_TYPES[a.type] ?? a.type }}</span>
              <div class="body"><b>{{ a.title }}</b><p>{{ a.detail }}</p></div>
              <span class="at num">{{ fmtTime(a.at) }}</span>
            </li>
          </ul>
        </StatePanel>
      </div>
    </div>

    <div v-if="quick.length" class="quick">
      <a v-for="q in quick" :key="q.p" class="sw-card" @click="router.push(q.p)">
        <el-icon :size="20"><component :is="q.icon" /></el-icon>{{ q.t }}
      </a>
    </div>
  </div>
</template>

<style scoped>
.head { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
h2 { margin: 0; font-size: 20px; }
.kpis { grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); margin-bottom: 16px; }
.gauge { padding: 12px 16px 0; }
.gauge .none { height: 104px; display: flex; align-items: center; justify-content: center; color: var(--sw-text-3); }
.sites { grid-template-columns: repeat(auto-fit, minmax(min(100%, 380px), 1fr)); }
.st { font-size: 12px; font-weight: 400; color: var(--sw-text-3); margin-left: 6px; }
.lv { color: #fff; font-size: 12px; font-weight: 400; padding: 2px 10px; border-radius: 10px; }
.row4 { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; }
.row4 div { display: flex; flex-direction: column; min-width: 0; }
.row4 b { font-size: 20px; }
.row4 span { font-size: 12px; color: var(--sw-text-3); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.yoy { margin: 8px 0; }
em { font-style: normal; }
em.up { color: var(--sw-danger); }
em.down { color: var(--sw-ok); }
.facts { list-style: none; margin: 0; padding: 0; border-top: 1px solid var(--sw-border); }
.facts li { display: flex; justify-content: space-between; gap: 8px; padding: 7px 2px; border-bottom: 1px dashed var(--sw-border); font-size: 13px; cursor: pointer; }
.facts li:hover { background: var(--sw-bg); }
.facts span { color: var(--sw-text-2); }
.facts i { font-style: normal; font-weight: 400; color: var(--sw-text-3); font-size: 12px; }
.facts b.warn { color: #b8740a; }
.facts b.bad { color: var(--sw-danger); }
.queues { display: flex; flex-wrap: wrap; gap: 6px 14px; font-size: 12px; margin-top: 10px; }
.note { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 10px; }
.two { grid-template-columns: minmax(0, 1.5fr) minmax(0, 1fr); }
.alerts { list-style: none; margin: 0; padding: 0; max-height: 280px; overflow: auto; }
.alerts li { display: flex; gap: 10px; align-items: flex-start; padding: 8px 6px; border-left: 3px solid var(--sw-ice); margin-bottom: 6px; background: var(--sw-bg); border-radius: 0 6px 6px 0; font-size: 13px; }
.alerts li.warn { border-left-color: var(--sw-warn); }
.alerts li.critical { border-left-color: var(--sw-danger); }
.ty { flex: none; font-size: 12px; color: var(--sw-text-3); width: 56px; }
.body { flex: 1; min-width: 0; }
.body p { margin: 2px 0 0; color: var(--sw-text-3); font-size: 12px; }
.at { flex: none; font-size: 12px; color: var(--sw-text-3); }
.quick { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 12px; }
.quick a { display: flex; align-items: center; gap: 8px; cursor: pointer; font-size: 14px; color: var(--sw-text-2); padding: 12px 14px; }
.quick a:hover { color: var(--sw-primary); border-color: var(--sw-primary); }
@media (max-width: 900px) { .two { grid-template-columns: 1fr; } }
@media (max-width: 480px) { .row4 { grid-template-columns: repeat(2, 1fr); } }
</style>
