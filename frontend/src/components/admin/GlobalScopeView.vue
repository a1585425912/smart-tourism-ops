<script setup lang="ts">
import { computed, ref } from 'vue'
import { useAppStore } from '@/stores/app'
import { useAsync } from './composables'
import { screenApi } from '@/api/screen'
import { opsApi } from '@/api/ops'
import { scenicApi } from '@/api/core'
import StatePanel from '@/components/common/StatePanel.vue'
import { fmtNum, fmtMoney } from '@/utils/format'

const props = defineProps<{ kind: 'flows' | 'business' | 'profiles' | 'queues' | 'forecasts' | 'sites' | 'groups' }>()
const app = useAppStore()
const days = ref(30)
const page = ref(1)
const total = ref(0)
const month = ref(app.now().subtract(1, 'month').format('YYYY-MM'))
const start = computed(() => days.value === 0 ? '2025-11-01' : app.now().subtract(days.value, 'day').format('YYYY-MM-DD'))
const end = computed(() => app.now().add(1, 'day').format('YYYY-MM-DD'))
const titles = { flows: '客流分析', business: '多业态经营', profiles: '游客画像', queues: '排队时长', forecasts: '客流预测与调度', sites: '景区与电子围栏', groups: '旅行社与团队' }
type Row = { siteId: number; site: string; item: string; date: string; value: string; detail: string }
const data = useAsync(async () => {
  if (props.kind === 'groups') {
    const groups = await opsApi.groups({ page: page.value, size: 100 })
    total.value = groups.total
    return groups.items.map(x => ({ siteId:x.siteId, site:x.siteName, item:x.groupCode, date:x.visitDate,
      value:`${x.enteredCount} / ${x.plannedSize} 人`, detail:`${x.agencyName} · ${x.originRegion ?? '未知客源'} · ${{BOOKED:'已预约',ENTERED:'已入园',LEFT:'已离园',CANCELLED:'已取消'}[x.status]}` }))
  }
  const lists = await Promise.all(app.sites.map(async site => {
    const row = (item: string, date: string, value: string, detail: string): Row => ({ siteId: site.id, site: site.name, item, date, value, detail })
    const span = `${start.value} 至 ${end.value}`
    switch (props.kind) {
      case 'flows': {
        const flow = await screenApi.flowSummary(site.id, start.value+'T00:00:00+08:00', end.value+'T00:00:00+08:00')
        return [row('入园 / 出园人次', span, `${fmtNum(flow.entries)} / ${fmtNum(flow.exits)}`, `在园峰值 ${fmtNum(flow.inParkPeak)} 人；承载量 ${fmtNum(flow.capacity)} 人`)]
      }
      case 'business': {
        const business = await opsApi.businessSummary(site.id, start.value, end.value)
        const names: Record<string,string> = { HOTEL:'酒店', CATERING:'餐饮', RETAIL:'零售', TICKET:'门票', ENTERTAINMENT:'娱乐' }
        return business.byCategory.map(x => row(names[x.category] ?? x.category, span, fmtMoney(x.revenue), `${fmtNum(x.orders)} 笔订单，${fmtNum(x.guests)} 人次；${x.units} 个经营单元`))
      }
      case 'profiles': {
        const profile = await opsApi.profile(site.id, month.value)
        const names: Record<string,string> = {ORIGIN:'客源地',AGE:'年龄',GENDER:'性别',CHANNEL:'渠道',STAY:'停留时长'}
        return Object.entries(profile.dimensions).flatMap(([dimension, buckets]) => (buckets ?? []).map(x => row((names[dimension] ?? dimension)+' · '+x.bucket, profile.month, `${fmtNum(x.visitors)} 人`, `占比 ${(x.share*100).toFixed(1)}%；样本 ${fmtNum(profile.sampleSize)} 人`)))
      }
      case 'queues': {
        const queues = await screenApi.queues(site.id)
        return queues.map(x => row(x.name, x.at?.slice(0,16) ?? '当前', `${fmtNum(x.measuredWaitMin ?? x.estimatedWaitMin,1)} 分钟`, `排队 ${fmtNum(x.queueLength)} 人；${x.measuredWaitMin == null ? '估算' : '实测'}`))
      }
      case 'forecasts': {
        const forecast = await screenApi.dailyForecast(site.id,7)
        return forecast.points.map(x => row('入园预测', x.date, `${fmtNum(x.predicted)} 人次`, `区间 ${fmtNum(x.lower)} 至 ${fmtNum(x.upper)}；${x.drivers.join('、')}`))
      }
      case 'sites': {
        const zones = await scenicApi.zones(site.id)
        const names: Record<string,string> = {AREA:'游览区',ENTRANCE:'出入口',ATTRACTION:'景点与项目',SERVICE:'服务区',PARKING:'停车场',COMMERCIAL:'商业区'}
        return zones.map(x => row(x.name,'当前',`${fmtNum(x.capacity)} 人`,`${names[x.zoneType] ?? x.zoneType}；电子围栏已配置`))
      }
    }
  }))
  return lists.flat()
}, [() => app.currentSiteId, days, month, page])
function open(row: Row) { app.selectSite(row.siteId) }
</script>

<template>
  <div class="sw-page">
    <div class="bar">
      <h2>{{ titles[kind] }}</h2><el-tag>全局 · {{ app.sites.length }} 个授权景区</el-tag>
      <el-date-picker v-if="kind === 'profiles'" v-model="month" type="month" value-format="YYYY-MM" size="small" />
      <el-radio-group v-else-if="['flows','business'].includes(kind)" v-model="days" size="small">
        <el-radio-button :value="7">近 7 天</el-radio-button><el-radio-button :value="30">近 30 天</el-radio-button><el-radio-button :value="0">完整演示范围</el-radio-button>
      </el-radio-group>
    </div>
    <div class="sw-card">
      <p class="sw-muted">各景区数据按相同口径列示，点击记录查看对应景区的详细数据。</p>
      <StatePanel :loading="data.loading.value" :error="data.error.value" :empty="!data.data.value?.length" @retry="data.run">
        <el-table :data="data.data.value ?? []" @row-click="open">
          <el-table-column prop="site" label="景区" min-width="150" /><el-table-column prop="item" label="项目 / 指标" min-width="180" />
          <el-table-column prop="date" label="统计范围" min-width="180" /><el-table-column prop="value" label="数值" min-width="150" />
          <el-table-column prop="detail" label="说明" min-width="260" />
        </el-table>
      </StatePanel>
      <el-pagination v-if="kind === 'groups' && total" v-model:current-page="page" :page-size="100" :total="total" layout="total, prev, pager, next" />
    </div>
  </div>
</template>

<style scoped>
.bar { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; }
.bar h2 { margin: 0; font-size: 20px; }
:deep(.el-table__row) { cursor: pointer; }
</style>
